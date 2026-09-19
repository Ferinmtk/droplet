using Droplet.Core.Hub;
using Droplet.Core.Mesh;

namespace Droplet.Windows.Services;

/// <summary>
/// Somewhere to send to: the hub itself, a mesh peer (reached directly, or through the hub),
/// or a device only the hub knows. One device is listed once, whichever way it's known.
/// </summary>
internal sealed record Destination
{
    /// <summary>"hub", "peer:&lt;fingerprint&gt;" or "dev:&lt;hub device id&gt;": what Send To's shortcuts carry.</summary>
    public required string Key { get; init; }

    /// <summary>Its name.</summary>
    public required string Name { get; init; }

    /// <summary>The mesh peer's fingerprint, when it's one.</summary>
    public string? Fp { get; init; }

    /// <summary>Its device id on the hub, when the hub knows it.</summary>
    public string? HubDeviceId { get; init; }

    /// <summary>The hub itself (files go to its shared storage).</summary>
    public bool IsHub { get; init; }

    /// <summary>How it's reached now, for people: "on Wi-Fi", "via Tailscale", "via the hub", "offline".</summary>
    public string Route { get; init; } = "";

    /// <summary>Reachable now.</summary>
    public bool Online { get; init; }

    /// <summary>Paired directly (so it can be unpaired here; the hub's own are removed on the hub).</summary>
    public bool Paired { get; init; }

    /// <summary>android, windows, linux, or "".</summary>
    public string Os { get; init; } = "";

    /// <summary>What it can be asked to do: ring, clipboard...</summary>
    public IReadOnlyList<string> Caps { get; init; } = [];

    /// <summary>A line for lists: the name and the route.</summary>
    public string Label => IsHub ? Name : $"{Name}  ({Route})";
}

/// <summary>A trusted mesh peer as the destination list needs it.</summary>
/// <param name="Entry">The trust entry.</param>
/// <param name="Link">The open link's kind ("lan" or "tailnet"), or null.</param>
/// <param name="Nearby">Announcing itself on this network.</param>
internal sealed record PeerView(TrustEntry Entry, string? Link, bool Nearby);

/// <summary>Builds and searches the destination list: pure, so it's tested anywhere.</summary>
internal static class Destinations
{
    /// <summary>
    /// The hub (when this PC is let in), then every device, reachable ones first. A mesh
    /// peer the hub lists too (the same device id on the same hub) appears once, as the peer.
    /// </summary>
    public static List<Destination> Build(IEnumerable<PeerView> peers, IEnumerable<Device> devices, string? hubId, string? hubName,
        bool hubRegistered, bool hubConnected)
    {
        ArgumentNullException.ThrowIfNull(peers);
        ArgumentNullException.ThrowIfNull(devices);
        var hubDevices = hubRegistered
            ? devices.Where(d => !d.Self && !d.Pending && d.Id.Length > 0).GroupBy(d => d.Id).ToDictionary(g => g.Key, g => g.First())
            : [];
        var list = new List<Destination>();
        var claimed = new HashSet<string>();
        foreach (var p in peers)
        {
            var e = p.Entry;
            Device? dev = null;
            if (hubRegistered && !string.IsNullOrEmpty(hubId) && e.Hub == hubId && hubDevices.TryGetValue(e.Id, out var d))
            {
                dev = d;
                claimed.Add(d.Id);
            }
            var viaHub = dev is { Online: true } && hubConnected;
            var route = p.Link switch
            {
                "lan" => "on Wi-Fi",
                "tailnet" => "via Tailscale",
                _ when viaHub => "via the hub",
                _ when p.Nearby => "nearby",
                _ => "offline",
            };
            list.Add(new Destination
            {
                Key = "peer:" + e.Fp, Name = e.Name, Fp = e.Fp, HubDeviceId = dev?.Id, Route = route,
                Online = p.Link is not null || viaHub || p.Nearby, Paired = e.Source == TrustSource.Paired, Os = e.Os, Caps = e.Caps,
            });
        }
        foreach (var d in hubDevices.Values.Where(d => !claimed.Contains(d.Id)))
        {
            var online = d.Online && hubConnected;
            list.Add(new Destination
            {
                Key = "dev:" + d.Id, Name = d.Name, HubDeviceId = d.Id, Route = online ? "via the hub" : "offline", Online = online,
                Caps = d.Caps ?? [],
            });
        }
        var sorted = list.OrderByDescending(x => x.Online).ThenBy(x => x.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
        if (hubRegistered)
        {
            sorted.Insert(0, new Destination
            {
                Key = "hub", Name = string.IsNullOrEmpty(hubName) ? "The hub" : $"The hub ({hubName})", IsHub = true,
                Route = hubConnected ? "" : "offline", Online = hubConnected,
            });
        }
        return sorted;
    }

    /// <summary>
    /// A destination by what someone typed or a shortcut carries: its key, "hub", a device id
    /// or fingerprint, or its name (when only one has it). Throws, with a sentence to show,
    /// when none or several match.
    /// </summary>
    public static Destination Resolve(string query, IReadOnlyList<Destination> all)
    {
        ArgumentNullException.ThrowIfNull(all);
        var q = (query ?? "").Trim();
        if (q.Length == 0)
        {
            throw new ArgumentException("say where to send to");
        }
        if (all.FirstOrDefault(d => d.Key.Equals(q, StringComparison.OrdinalIgnoreCase) || (d.IsHub && q.Equals("hub", StringComparison.OrdinalIgnoreCase))) is { } exact)
        {
            return exact;
        }
        if (all.FirstOrDefault(d => d.HubDeviceId == q || (d.Fp is not null && q.Length >= 8 && d.Fp.StartsWith(q, StringComparison.OrdinalIgnoreCase))) is { } byId)
        {
            return byId;
        }
        var named = all.Where(d => d.Name.Equals(q, StringComparison.OrdinalIgnoreCase)).ToList();
        return named.Count switch
        {
            1 => named[0],
            0 => throw new ArgumentException($"no device called \"{q}\""),
            _ => throw new ArgumentException($"more than one device is called \"{q}\""),
        };
    }
}
