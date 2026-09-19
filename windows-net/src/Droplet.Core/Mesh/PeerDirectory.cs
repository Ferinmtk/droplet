using System.Net;
using System.Text.RegularExpressions;
using Droplet.Core.Common;
using Droplet.Core.Mdns;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Droplet.Core.Mesh;

/// <summary>A peer announcing itself on the LAN. An announcement proves nothing: it's only a hint where a peer is.</summary>
public sealed record SeenPeer
{
    /// <summary>The fingerprint it announces.</summary>
    public required string Fp { get; init; }

    /// <summary>Its peer id.</summary>
    public required string Id { get; init; }

    /// <summary>Its name.</summary>
    public required string Name { get; init; }

    /// <summary>android, windows, linux, or "".</summary>
    public required string Os { get; init; }

    /// <summary>What it can do.</summary>
    public required IReadOnlyList<string> Caps { get; init; }

    /// <summary>The hub it belongs to, or "".</summary>
    public required string Hub { get; init; }

    /// <summary>Its mesh port.</summary>
    public required int Port { get; init; }

    /// <summary>Its addresses, IPv4 first.</summary>
    public required IReadOnlyList<string> Addresses { get; init; }

    /// <summary>The DNS-SD instance.</summary>
    public required string Service { get; init; }

    /// <summary>When it was last heard from.</summary>
    public DateTimeOffset SeenAt { get; init; }
}

/// <summary>
/// Finding peers on the LAN (docs/mesh.md §2): announcing <c>_droplet-peer._tcp</c> with
/// this peer's TXT records, and keeping a live map of the peers announcing themselves,
/// from periodic legacy-unicast browses and from whatever they announce on the group.
/// </summary>
public sealed partial class PeerDirectory : IAsyncDisposable
{
    static readonly DnsName ServiceType = DnsName.Parse(MeshProtocol.ServiceType);
    static readonly TimeSpan BrowseEvery = TimeSpan.FromSeconds(30);
    static readonly TimeSpan ForgetAfter = TimeSpan.FromSeconds(100);
    static readonly string[] Oses = ["android", "windows", "linux"];

    readonly string ownFp;
    readonly Func<IReadOnlyList<string>> localAddresses;
    readonly ILogger log;
    readonly MdnsResponder responder;
    readonly MdnsBrowser browser;
    readonly ServiceCollector passive = new(ServiceType);
    readonly Lock gate = new();
    readonly Dictionary<string, SeenPeer> seen = new(StringComparer.OrdinalIgnoreCase);
    readonly CancellationTokenSource stop = new();
    readonly SemaphoreSlim browseNow = new(0, 1);
    Task? loop;
    int port;
    IReadOnlyList<KeyValuePair<string, string>> txt = [];
    List<string> announced = [];

    /// <summary>Creates a directory; <see cref="StartAsync"/> starts it.</summary>
    public PeerDirectory(string ownFp, Func<IReadOnlyList<string>> localAddresses, ILogger? logger = null)
    {
        this.ownFp = ownFp;
        this.localAddresses = localAddresses;
        log = logger ?? NullLogger.Instance;
        responder = new MdnsResponder(log);
        browser = new MdnsBrowser(log);
        responder.ResponseReceived += OnResponse;
    }

    /// <summary>Raised when a peer appears or changes.</summary>
    public event Action<SeenPeer>? Seen;

    /// <summary>Whether this device is being announced (port 5353 could be bound).</summary>
    public bool Announcing => responder.Listening;

    /// <summary>The TXT records currently announced.</summary>
    public IReadOnlyList<KeyValuePair<string, string>> Txt
    {
        get
        {
            lock (gate)
            {
                return txt;
            }
        }
    }

    /// <summary>The TXT records for a peer (docs/mesh.md §2).</summary>
    public static List<KeyValuePair<string, string>> TxtRecords(string peerId, string fp, string name, IEnumerable<string> caps, string? hubId) =>
    [
        new("id", peerId), new("fp", fp), new("name", Truncate(Clean.Name(name), 63)), new("os", MeshProtocol.Os),
        new("caps", string.Join(',', Clean.Caps(caps))), new("hub", hubId ?? ""), new("v", MeshProtocol.Version.ToString(System.Globalization.CultureInfo.InvariantCulture)),
    ];

    static string Truncate(string s, int n) => s.Length > n ? s[..n] : s;

    /// <summary>Starts announcing on <paramref name="meshPort"/> and browsing.</summary>
    public async Task StartAsync(int meshPort, IReadOnlyList<KeyValuePair<string, string>> records)
    {
        lock (gate)
        {
            port = meshPort;
            txt = records;
        }
        if (responder.Start())
        {
            await AnnounceAsync().ConfigureAwait(false);
        }
        loop = Task.Run(RunAsync);
    }

    /// <summary>Announces again, with new TXT records (or the current ones, for new addresses).</summary>
    public async Task UpdateAsync(IReadOnlyList<KeyValuePair<string, string>>? records = null)
    {
        if (records is not null)
        {
            lock (gate)
            {
                txt = records;
            }
        }
        responder.JoinGroups();
        await AnnounceAsync().ConfigureAwait(false);
    }

    [GeneratedRegex(@"[^\w\- ]")]
    private static partial Regex LabelJunk();

    async Task AnnounceAsync()
    {
        IReadOnlyList<KeyValuePair<string, string>> records;
        int p;
        lock (gate)
        {
            records = txt;
            p = port;
        }
        var addrs = localAddresses().ToList();
        lock (gate)
        {
            announced = addrs;
        }
        if (addrs.Count == 0 || records.Count == 0)
        {
            return;
        }
        var id = records.First(r => r.Key == "id").Value;
        var name = records.First(r => r.Key == "name").Value;
        var label = LabelJunk().Replace(name, "");
        label = Truncate(label, 40).Trim();
        if (label.Length == 0)
        {
            label = "droplet";
        }
        var svc = new ServiceAnnouncement
        {
            ServiceType = ServiceType,
            Instance = $"{label} {id[..Math.Min(6, id.Length)]}",
            Host = DnsName.Parse($"droplet-{id}.local."),
            Port = p,
            Txt = records,
            Addresses = addrs.Select(IPAddress.Parse).ToList(),
        };
        await responder.AnnounceAsync(svc).ConfigureAwait(false);
        log.LogInformation("mesh: announcing on the LAN as {Instance} (port {Port})", svc.Instance, p);
    }

    /// <summary>Browses now, rather than at the next interval.</summary>
    public void BrowseSoon()
    {
        try
        {
            browseNow.Release();
        }
        catch (SemaphoreFullException)
        {
        }
    }

    async Task RunAsync()
    {
        var lastAddrCheck = DateTimeOffset.UtcNow;
        while (!stop.IsCancellationRequested)
        {
            try
            {
                var found = await browser.BrowseAsync(ServiceType, TimeSpan.FromSeconds(2), ct: stop.Token).ConfigureAwait(false);
                foreach (var inst in found)
                {
                    Record(inst);
                }
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception e)
            {
                log.LogDebug("mesh: browsing the LAN: {Error}", e.Message);
            }
            Expire();
            if (DateTimeOffset.UtcNow - lastAddrCheck > TimeSpan.FromSeconds(15))
            {
                lastAddrCheck = DateTimeOffset.UtcNow;
                List<string> was;
                lock (gate)
                {
                    was = announced;
                }
                if (!localAddresses().SequenceEqual(was) && responder.Listening)
                {
                    log.LogInformation("mesh: this computer's addresses changed; announcing again");
                    try
                    {
                        await UpdateAsync().ConfigureAwait(false);
                    }
                    catch (Exception e)
                    {
                        log.LogDebug("mesh: announcing again: {Error}", e.Message);
                    }
                }
            }
            try
            {
                await browseNow.WaitAsync(BrowseEvery, stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    void OnResponse(DnsMessage msg, IPEndPoint from)
    {
        passive.Add(msg, from.Address);
        foreach (var gone in passive.TakeGone())
        {
            lock (gate)
            {
                seen.Remove(gone.ToString());
            }
        }
        foreach (var inst in passive.Complete())
        {
            Record(inst);
        }
    }

    void Record(ServiceInstance inst)
    {
        if (Parse(inst) is not { } peer || peer.Fp == ownFp)
        {
            return;
        }
        bool changed;
        lock (gate)
        {
            changed = !seen.TryGetValue(peer.Service, out var old) || old.Fp != peer.Fp || old.Id != peer.Id || old.Name != peer.Name ||
                      old.Port != peer.Port || old.Hub != peer.Hub || !old.Addresses.SequenceEqual(peer.Addresses) ||
                      !old.Caps.SequenceEqual(peer.Caps);
            seen[peer.Service] = peer;
        }
        if (changed)
        {
            try
            {
                Seen?.Invoke(peer);
            }
            catch (Exception e)
            {
                log.LogWarning(e, "mesh: handling a peer seen on the LAN");
            }
        }
    }

    void Expire()
    {
        var now = DateTimeOffset.UtcNow;
        lock (gate)
        {
            foreach (var (k, v) in seen.ToList())
            {
                if (now - v.SeenAt > ForgetAfter)
                {
                    seen.Remove(k);
                }
            }
        }
    }

    /// <summary>A service instance as a peer; null when its TXT records don't make a usable one.</summary>
    public static SeenPeer? Parse(ServiceInstance inst)
    {
        ArgumentNullException.ThrowIfNull(inst);
        var t = inst.Txt;
        var id = (t.GetValueOrDefault("id") ?? "").Trim().ToLowerInvariant();
        var fp = Fingerprint.Normalize(t.GetValueOrDefault("fp"));
        var v = (t.GetValueOrDefault("v") ?? "").Trim();
        if (!MeshIdentity.PeerIdPattern().IsMatch(id) || fp is null || v.Length == 0 || !v.All(char.IsAsciiDigit) || inst.Port <= 0)
        {
            return null;
        }
        var os = (t.GetValueOrDefault("os") ?? "").Trim().ToLowerInvariant();
        var hub = (t.GetValueOrDefault("hub") ?? "").Trim().ToLowerInvariant();
        var addrs = inst.Addresses
            .Where(a => !Common.Addresses.IsUnspecified(a) && !Common.Addresses.IsMulticast(a) &&
                        !(a.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6 && a.IsIPv6LinkLocal))
            .OrderBy(a => a.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork)
            .Select(a => a.ToString())
            .Distinct()
            .ToList();
        if (addrs.Count == 0)
        {
            return null;
        }
        return new SeenPeer
        {
            Fp = fp, Id = id, Name = Clean.Name(t.GetValueOrDefault("name"), id), Os = Oses.Contains(os) ? os : "",
            Caps = Clean.Caps(t.GetValueOrDefault("caps")), Hub = MeshIdentity.PeerIdPattern().IsMatch(hub) ? hub : "",
            Port = inst.Port, Addresses = addrs, Service = inst.Name.ToString(), SeenAt = DateTimeOffset.UtcNow,
        };
    }

    /// <summary>The peers seen lately.</summary>
    public List<SeenPeer> Peers()
    {
        lock (gate)
        {
            return [.. seen.Values];
        }
    }

    /// <summary>The announcements of one fingerprint.</summary>
    public List<SeenPeer> ByFp(string fp) => Peers().Where(s => s.Fp == fp).ToList();

    /// <inheritdoc/>
    public async ValueTask DisposeAsync()
    {
        await stop.CancelAsync().ConfigureAwait(false);
        if (loop is not null)
        {
            try
            {
                await loop.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }
        await responder.DisposeAsync().ConfigureAwait(false);
        stop.Dispose();
        browseNow.Dispose();
    }
}
