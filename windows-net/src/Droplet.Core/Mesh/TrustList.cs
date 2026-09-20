using System.Text.Json;
using System.Text.Json.Serialization;
using Droplet.Core.Common;

namespace Droplet.Core.Mesh;

/// <summary>Why a peer is trusted.</summary>
public static class TrustSource
{
    /// <summary>Vouched for by the hub, through its roster. Replaced by each roster fetch.</summary>
    public const string Roster = "roster";

    /// <summary>Paired directly by the owner. Only goes when unpaired.</summary>
    public const string Paired = "paired";
}

/// <summary>One trusted peer (docs/mesh.md §3). The certificate is kept in full.</summary>
public sealed record TrustEntry
{
    /// <summary>Its peer id.</summary>
    [JsonPropertyName("id")] public required string Id { get; init; }

    /// <summary>Its name.</summary>
    [JsonPropertyName("name")] public required string Name { get; init; }

    /// <summary>Its certificate's fingerprint.</summary>
    [JsonPropertyName("fp")] public required string Fp { get; init; }

    /// <summary>Its certificate, PEM.</summary>
    [JsonPropertyName("cert_pem")] public required string CertPem { get; init; }

    /// <summary><see cref="TrustSource"/>.</summary>
    [JsonPropertyName("source")] public required string Source { get; init; }

    /// <summary>LAN addresses it was seen at, newest first.</summary>
    [JsonPropertyName("lan")] public List<string> Lan { get; init; } = [];

    /// <summary>Its mesh port.</summary>
    [JsonPropertyName("port")] public int? Port { get; init; }

    /// <summary>Its tailnet address.</summary>
    [JsonPropertyName("tailnet_ip")] public string? TailnetIp { get; init; }

    /// <summary>android, windows or linux.</summary>
    [JsonPropertyName("os")] public string Os { get; init; } = "";

    /// <summary>What it can do.</summary>
    [JsonPropertyName("caps")] public List<string> Caps { get; init; } = [];

    /// <summary>The hub whose roster lists it: messages may go through that hub.</summary>
    [JsonPropertyName("hub")] public string Hub { get; init; } = "";

    /// <summary>When it was added, Unix seconds.</summary>
    [JsonPropertyName("added")] public long Added { get; init; }

    /// <summary>
    /// A checked entry. Throws <see cref="FormatException"/> when the id, certificate or
    /// fingerprint is wrong (a damaged or forged entry is never trusted).
    /// </summary>
    public static TrustEntry Make(string? peerId, string? name, string? certPem, string source, IEnumerable<string?>? lan = null,
        long? port = null, string? tailnetIp = null, string? os = null, IEnumerable<string>? caps = null, string? fp = null,
        string? hub = null)
    {
        if (source is not (TrustSource.Roster or TrustSource.Paired))
        {
            throw new FormatException($"bad source {source}");
        }
        if (peerId is null || !MeshIdentity.PeerIdPattern().IsMatch(peerId))
        {
            throw new FormatException("bad peer id");
        }
        var der = Certificates.PemToDer(certPem);
        var real = Fingerprint.Of(der);
        if (fp is not null && Fingerprint.Normalize(fp) != real)
        {
            throw new FormatException("the fingerprint doesn't match the certificate");
        }
        var tip = Addresses.Clean(tailnetIp is null ? [] : [tailnetIp], 1);
        return new TrustEntry
        {
            Id = peerId,
            Name = Clean.Name(name, peerId),
            Fp = real,
            CertPem = Certificates.ToPem(der),
            Source = source,
            Lan = Addresses.Clean(lan),
            Port = Clean.Port(port),
            TailnetIp = tip.Count > 0 ? tip[0] : null,
            Os = Truncate(Clean.Name(os), 20),
            Caps = Clean.Caps(caps),
            Hub = hub is not null && MeshIdentity.PeerIdPattern().IsMatch(hub) ? hub : "",
            Added = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
        };
    }

    internal static string Truncate(string s, int n) => s.Length > n ? s[..n] : s;
}

/// <summary>
/// The trust list: the peers this device talks to (docs/mesh.md §3). Kept as JSON
/// (<c>{"v":1,"peers":{"&lt;fp&gt;": entry}}</c>, the reference's layout), keyed by
/// fingerprint. Thread-safe.
/// </summary>
public sealed class TrustList
{
    static readonly JsonSerializerOptions Options = new(Json.Indented)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    readonly string path;
    readonly string ownFp;
    readonly Lock gate = new();
    Dictionary<string, TrustEntry> peers = [];

    /// <summary>Loads the list at <paramref name="path"/>; this device's own fingerprint is never trusted.</summary>
    public TrustList(string path, string ownFp)
    {
        this.path = path;
        this.ownFp = ownFp;
        Load();
    }

    /// <summary>
    /// Raised (outside any lock) after the set of trusted certificates changes: links to a
    /// peer no longer trusted must close.
    /// </summary>
    public event Action? CertificatesChanged;

    sealed record FileForm([property: JsonPropertyName("v")] int V, [property: JsonPropertyName("peers")] Dictionary<string, JsonElement>? Peers);

    void Load()
    {
        var text = AtomicFile.TryReadText(path);
        if (text is null)
        {
            return;
        }
        FileForm? raw;
        try
        {
            raw = JsonSerializer.Deserialize<FileForm>(text);
        }
        catch (JsonException)
        {
            return;
        }
        var loaded = new Dictionary<string, TrustEntry>();
        foreach (var (fp, e) in raw?.Peers ?? [])
        {
            try
            {
                var stored = e.Deserialize<TrustEntry>();
                if (stored is null)
                {
                    continue;
                }
                // re-checked, so a damaged entry is dropped, never trusted
                var entry = TrustEntry.Make(stored.Id, stored.Name, stored.CertPem, stored.Source, stored.Lan, stored.Port,
                    stored.TailnetIp, stored.Os, stored.Caps, fp, stored.Hub) with
                { Added = stored.Added > 0 ? stored.Added : DateTimeOffset.UtcNow.ToUnixTimeSeconds() };
                if (entry.Fp != ownFp)
                {
                    loaded[entry.Fp] = entry;
                }
            }
            catch (Exception ex) when (ex is JsonException or FormatException or InvalidOperationException)
            {
            }
        }
        peers = loaded;
    }

    void Save()
    {
        var data = JsonSerializer.SerializeToUtf8Bytes(new { v = 1, peers }, Options);
        AtomicFile.Write(path, data);
    }

    // --- reading ---------------------------------------------------------------------

    /// <summary>The entry for a fingerprint, or null.</summary>
    public TrustEntry? Get(string? fp)
    {
        if (fp is null)
        {
            return null;
        }
        lock (gate)
        {
            return peers.GetValueOrDefault(fp);
        }
    }

    /// <summary>Every trusted peer, by name.</summary>
    public List<TrustEntry> All()
    {
        lock (gate)
        {
            return [.. peers.Values.OrderBy(e => e.Name.ToLowerInvariant(), StringComparer.Ordinal).ThenBy(e => e.Fp, StringComparer.Ordinal)];
        }
    }

    /// <summary>Peers matching a name (case-insensitive), an id, a fingerprint, or a fingerprint prefix of 8+ hex.</summary>
    public List<TrustEntry> Find(string? query)
    {
        var q = (query ?? "").Trim();
        var ql = q.ToLowerInvariant();
        List<TrustEntry> all;
        lock (gate)
        {
            all = [.. peers.Values];
        }
        var exact = all.Where(e => e.Id == ql || e.Fp == ql || string.Equals(e.Name, q, StringComparison.OrdinalIgnoreCase)).ToList();
        if (exact.Count > 0)
        {
            return exact;
        }
        if (ql.Length >= 8)
        {
            var prefix = ql.Replace(":", "", StringComparison.Ordinal);
            return all.Where(e => e.Fp.StartsWith(prefix, StringComparison.Ordinal)).ToList();
        }
        return [];
    }

    // --- changing ----------------------------------------------------------------------

    /// <summary>Trusts a directly paired peer. A re-pair of the same peer id replaces its old certificate.</summary>
    public void AddPaired(TrustEntry entry)
    {
        ArgumentNullException.ThrowIfNull(entry);
        if (entry.Source != TrustSource.Paired)
        {
            throw new ArgumentException("not a paired entry", nameof(entry));
        }
        lock (gate)
        {
            if (entry.Fp == ownFp)
            {
                throw new InvalidOperationException("that's this device");
            }
            foreach (var (fp, e) in peers.ToList())
            {
                if (e.Id == entry.Id && e.Source == TrustSource.Paired && fp != entry.Fp)
                {
                    peers.Remove(fp);
                }
            }
            if (peers.TryGetValue(entry.Fp, out var old))
            {
                entry = entry with
                {
                    Lan = Addresses.Clean(entry.Lan.Concat(old.Lan)),
                    TailnetIp = entry.TailnetIp ?? old.TailnetIp,
                };
            }
            peers[entry.Fp] = entry;
            Save();
        }
        CertificatesChanged?.Invoke();
    }

    /// <summary>Stops trusting a peer. Returns what was removed.</summary>
    public TrustEntry? Remove(string fp)
    {
        TrustEntry? e;
        lock (gate)
        {
            if (!peers.Remove(fp, out e))
            {
                return null;
            }
            Save();
        }
        CertificatesChanged?.Invoke();
        return e;
    }

    /// <summary>
    /// Makes the roster peers exactly <paramref name="entries"/> (from hub
    /// <paramref name="hubId"/>). Paired peers stay, and learn fresher addresses from it.
    /// Returns (added, removed).
    /// </summary>
    public (int Added, int Removed) SyncRoster(IReadOnlyList<TrustEntry> entries, string hubId)
    {
        ArgumentNullException.ThrowIfNull(entries);
        int added = 0, removed = 0;
        bool changed;
        lock (gate)
        {
            var incoming = new Dictionary<string, TrustEntry>();
            foreach (var e in entries)
            {
                if (e.Fp != ownFp)
                {
                    incoming[e.Fp] = e;
                }
            }
            var before = peers.ToDictionary(p => p.Key, p => p.Value.CertPem);
            foreach (var (fp, e) in peers.ToList())
            {
                if (e.Source == TrustSource.Roster && !incoming.ContainsKey(fp))
                {
                    peers.Remove(fp);
                    removed++;
                }
                else if (e.Source == TrustSource.Paired && !incoming.ContainsKey(fp) && e.Hub == hubId)
                {
                    peers[fp] = e with { Hub = "" }; // still paired, but that hub no longer knows it
                }
            }
            foreach (var (fp, e) in incoming)
            {
                if (!peers.TryGetValue(fp, out var old))
                {
                    peers[fp] = e;
                    added++;
                }
                else if (old.Source == TrustSource.Paired)
                {
                    // direct pairing is the owner's own decision: it stays "paired", and
                    // learns where the peer is from the hub
                    peers[fp] = old with
                    {
                        Lan = Addresses.Clean(e.Lan.Concat(old.Lan)),
                        Port = e.Port ?? old.Port,
                        TailnetIp = e.TailnetIp ?? old.TailnetIp,
                        Os = e.Os.Length > 0 ? e.Os : old.Os,
                        Caps = e.Caps.Count > 0 ? e.Caps : old.Caps,
                        Hub = e.Hub,
                    };
                }
                else
                {
                    peers[fp] = e with { Added = old.Added, Lan = Addresses.Clean(e.Lan.Concat(old.Lan)) };
                }
            }
            var after = peers.ToDictionary(p => p.Key, p => p.Value.CertPem);
            changed = before.Count != after.Count || before.Any(b => !after.TryGetValue(b.Key, out var pem) || pem != b.Value);
            Save();
        }
        if (changed)
        {
            CertificatesChanged?.Invoke();
        }
        return (added, removed);
    }

    /// <summary>Records what an authenticated peer said about itself, or where it answered.</summary>
    public void Learn(string fp, string? address = null, long? port = null, string? name = null, string? peerId = null,
        string? os = null, IEnumerable<string>? caps = null, bool tailnet = false)
    {
        lock (gate)
        {
            if (!peers.TryGetValue(fp, out var e))
            {
                return;
            }
            var before = JsonSerializer.Serialize(e);
            if (!string.IsNullOrEmpty(address))
            {
                if (tailnet)
                {
                    var t = Addresses.Clean([address], 1);
                    e = e with { TailnetIp = t.Count > 0 ? t[0] : e.TailnetIp };
                }
                else
                {
                    e = e with { Lan = Addresses.Clean(new[] { address }.Concat(e.Lan)) };
                }
            }
            if (Clean.Port(port) is { } p)
            {
                e = e with { Port = p };
            }
            if (e.Source == TrustSource.Paired)
            {
                // the hub names roster peers; a paired peer names itself
                if (!string.IsNullOrEmpty(name))
                {
                    e = e with { Name = Clean.Name(name, e.Name) };
                }
                if (peerId is not null && MeshIdentity.PeerIdPattern().IsMatch(peerId))
                {
                    e = e with { Id = peerId };
                }
            }
            if (!string.IsNullOrEmpty(os))
            {
                e = e with { Os = TrustEntry.Truncate(Clean.Name(os), 20) };
            }
            if (caps is not null)
            {
                e = e with { Caps = Clean.Caps(caps) };
            }
            if (JsonSerializer.Serialize(e) != before)
            {
                peers[fp] = e;
                Save();
            }
        }
    }
}
