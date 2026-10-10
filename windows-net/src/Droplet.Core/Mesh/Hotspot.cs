using System.Net.Http;
using System.Text.Json.Nodes;
using Droplet.Core.Common;

namespace Droplet.Core.Mesh;

/// <summary>
/// Pairing on a phone's hotspot, where mDNS doesn't reach (docs/mesh.md §9.10). A device
/// serving a hotspot doesn't announce itself to the devices that joined it, and may not hear
/// them either; but it's always their default gateway. So while a Pair screen is open, a
/// device asks its gateway who it is, saying who it is itself:
/// <code>
/// POST https://&lt;gateway&gt;:1739/mesh/pair/hello      (no client certificate)
/// {"v":1, "id", "name", "os", "fp", "port"}  →  200 the same fields, the answerer's
/// </code>
/// The asker checks the answer's <c>fp</c> is the certificate presented in TLS and lists the
/// answerer for pairing; the answerer lists the asker at the address it asked from, for a
/// minute. Both are hints, like mDNS: pairing (§9.3) and its 4-digit code are unchanged, and
/// nothing pairs by itself.
/// </summary>
public static class Hotspot
{
    /// <summary>The hello's path.</summary>
    public const string HelloPath = "/mesh/pair/hello";

    /// <summary>How long a device that asked stays listed.</summary>
    public static readonly TimeSpan KnockTtl = TimeSpan.FromSeconds(60);

    /// <summary>Devices listed that way at once.</summary>
    public const int MaxKnocks = 8;

    /// <summary>Hellos answered a minute.</summary>
    public const int MaxHellos = 60;

    /// <summary>How long a gateway stays listed after its last answer.</summary>
    public static readonly TimeSpan FoundTtl = TimeSpan.FromSeconds(30);

    /// <summary>While a Pair screen is open, a gateway that answered is asked this often.</summary>
    public static readonly TimeSpan HelloEvery = TimeSpan.FromSeconds(10);

    /// <summary>One that didn't: after this, then twice as long each time…</summary>
    public static readonly TimeSpan QuietFirst = TimeSpan.FromSeconds(5);

    /// <summary>…up to this.</summary>
    public static readonly TimeSpan QuietMax = TimeSpan.FromSeconds(60);

    static readonly string[] Oses = ["android", "windows", "linux", "macos"];

    /// <summary>A hello's fields as a peer at <paramref name="address"/>, or null if it isn't a usable one.</summary>
    public static SeenPeer? Parse(JsonObject? body, string address)
    {
        if (body is null)
        {
            return null;
        }
        var id = body.Str("id");
        var fp = body.Str("fp");
        if (id is null || !MeshIdentity.PeerIdPattern().IsMatch(id) || !Fingerprint.IsValid(fp))
        {
            return null;
        }
        var os = body.Str("os") is { } o && Oses.Contains(o) ? o : "";
        var port = body.Int("port") is { } p and > 0 and < 65536 ? (int)p : MeshProtocol.DefaultPort;
        return new SeenPeer
        {
            Fp = fp!, Id = id, Name = Clean.Name(body.Str("name"), id), Os = os, Caps = [], Hub = "", Port = port,
            Addresses = [address], Service = "", SeenAt = DateTimeOffset.UtcNow,
        };
    }

    /// <summary>This device's side of a hello.</summary>
    public static JsonObject Me(string id, string fp, string name, int port)
    {
        var n = Clean.Name(name, id);
        return new JsonObject
        {
            ["v"] = MeshProtocol.Version, ["id"] = id, ["name"] = n.Length > 63 ? n[..63] : n, ["os"] = MeshProtocol.Os,
            ["fp"] = fp, ["port"] = port,
        };
    }

    /// <summary>
    /// Asks the device at <paramref name="host"/>:<paramref name="port"/> who it is (saying who
    /// this one is, <paramref name="mine"/>). Null: no droplet device there, an older one (404),
    /// or an answer that doesn't match the certificate it presented.
    /// </summary>
    public static async Task<SeenPeer?> AskAsync(string host, int port, JsonObject mine, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(mine);
        string? tlsFp = null;
        using var http = new HttpClient(MeshTls.Handler(null, null, fp => tlsFp = fp, TimeSpan.FromSeconds(3)))
        {
            Timeout = TimeSpan.FromSeconds(5),
        };
        using var req = new HttpRequestMessage(HttpMethod.Post, new Uri($"https://{Addresses.HostPort(host, port)}{HelloPath}"))
        {
            Version = System.Net.HttpVersion.Version11,
            VersionPolicy = HttpVersionPolicy.RequestVersionExact,
            Content = Json.Content(mine),
        };
        req.Headers.TryAddWithoutValidation("User-Agent", MeshProtocol.UserAgent);
        using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
        if ((int)resp.StatusCode != 200)
        {
            return null;
        }
        var bytes = await resp.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
        var who = bytes.Length <= MeshProtocol.MaxPairBody ? Parse(Json.ParseObject(bytes), host) : null;
        return who is not null && tlsFp is not null && who.Fp == tlsFp ? who : null;
    }
}

/// <summary>The answering side of a hello: devices that asked who this one is, listed for a minute.</summary>
/// <param name="ownFp">This device's fingerprint (a hello from itself isn't listed).</param>
/// <param name="clock">The time; the system's by default.</param>
public sealed class HotspotKnocks(string ownFp, TimeProvider? clock = null)
{
    readonly TimeProvider clock = clock ?? TimeProvider.System;
    readonly Dictionary<string, (SeenPeer Peer, DateTimeOffset At)> seen = [];
    readonly List<DateTimeOffset> answered = [];
    readonly Lock gate = new();

    /// <summary>A device not listed before asked.</summary>
    public event Action? Knocked;

    /// <summary>Answers a hello from <paramref name="address"/> with <paramref name="mine"/>, and lists the asker.</summary>
    public (int Status, JsonObject Body) Answer(JsonObject? body, string? address, JsonObject mine)
    {
        var fresh = false;
        lock (gate)
        {
            var now = clock.GetUtcNow();
            answered.RemoveAll(t => now - t >= TimeSpan.FromMinutes(1));
            if (answered.Count >= Hotspot.MaxHellos)
            {
                return (429, new JsonObject { ["error"] = "too many requests; wait a minute" });
            }
            answered.Add(now);
            Prune(now);
            var who = string.IsNullOrEmpty(address) ? null : Hotspot.Parse(body, address);
            if (who is not null && who.Fp != ownFp && (seen.ContainsKey(who.Fp) || seen.Count < Hotspot.MaxKnocks))
            {
                fresh = !seen.ContainsKey(who.Fp);
                seen[who.Fp] = (who, now);
            }
        }
        if (fresh)
        {
            Knocked?.Invoke();
        }
        return (200, mine);
    }

    void Prune(DateTimeOffset now)
    {
        foreach (var fp in seen.Where(kv => now - kv.Value.At > Hotspot.KnockTtl).Select(kv => kv.Key).ToList())
        {
            seen.Remove(fp);
        }
    }

    /// <summary>The devices that asked in the last minute.</summary>
    public IReadOnlyList<SeenPeer> Peers()
    {
        lock (gate)
        {
            Prune(clock.GetUtcNow());
            return seen.Values.Select(v => v.Peer).ToList();
        }
    }
}

/// <summary>The asking side of a hello: which gateways answered, and when to ask each again.</summary>
/// <param name="clock">The time; the system's by default.</param>
public sealed class GatewayScan(TimeProvider? clock = null)
{
    readonly TimeProvider clock = clock ?? TimeProvider.System;
    readonly Dictionary<string, (SeenPeer Peer, DateTimeOffset Until)> found = [];
    readonly Dictionary<string, (DateTimeOffset Next, TimeSpan Wait)> next = [];
    readonly Lock gate = new();

    /// <summary>The gateways to ask now (all of them with <paramref name="force"/>); forgets the others.</summary>
    public IReadOnlyList<string> Due(IReadOnlyList<string> gateways, bool force = false)
    {
        lock (gate)
        {
            var now = clock.GetUtcNow();
            foreach (var gw in found.Keys.Where(g => !gateways.Contains(g)).ToList())
            {
                found.Remove(gw);
            }
            foreach (var gw in next.Keys.Where(g => !gateways.Contains(g)).ToList())
            {
                next.Remove(gw);
            }
            return gateways.Where(gw => force || !next.TryGetValue(gw, out var n) || n.Next <= now).ToList();
        }
    }

    /// <summary>What the gateway said: who it is, or null for nothing (asked again later and later).</summary>
    public void Result(string gateway, SeenPeer? peer)
    {
        lock (gate)
        {
            var now = clock.GetUtcNow();
            if (peer is not null)
            {
                found[gateway] = (peer, now + Hotspot.FoundTtl);
                next[gateway] = (now + Hotspot.HelloEvery, TimeSpan.Zero);
                return;
            }
            found.Remove(gateway);
            var last = next.TryGetValue(gateway, out var n) ? n.Wait : TimeSpan.Zero;
            var wait = TimeSpan.FromTicks(Math.Clamp(last.Ticks * 2, Hotspot.QuietFirst.Ticks, Hotspot.QuietMax.Ticks));
            next[gateway] = (now + wait, wait);
        }
    }

    /// <summary>The gateways that said who they are, lately.</summary>
    public IReadOnlyList<SeenPeer> Peers()
    {
        lock (gate)
        {
            var now = clock.GetUtcNow();
            return found.Values.Where(v => v.Until > now).Select(v => v.Peer).ToList();
        }
    }
}
