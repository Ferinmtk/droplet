using System.Globalization;
using System.Net;
using System.Net.Sockets;
using Droplet.Core.Common;
using Droplet.Core.Mdns;
using Microsoft.Extensions.Logging;

namespace Droplet.Core.LocalFirst;

/// <summary>A hub's announcement, validated (docs/local-first.md §1).</summary>
public sealed record HubAnnouncement
{
    /// <summary>The DNS-SD instance label, e.g. "droplet-9b1617".</summary>
    public required string Instance { get; init; }

    /// <summary>The LAN HTTPS port.</summary>
    public required int Port { get; init; }

    /// <summary>Its IPv4 addresses.</summary>
    public required IReadOnlyList<IPAddress> Addresses { get; init; }

    /// <summary>16 lowercase hex characters.</summary>
    public required string Id { get; init; }

    /// <summary>SHA-256 of its LAN certificate, 64 lowercase hex characters.</summary>
    public required string Fingerprint { get; init; }

    /// <summary>The hub machine's name; may be empty.</summary>
    public string Name { get; init; } = "";

    /// <summary>Its plain-HTTP port for browsers; 0 if absent.</summary>
    public int HttpPort { get; init; }

    /// <summary>Its https tailnet URL, or "".</summary>
    public string Tailnet { get; init; } = "";

    /// <summary>Its LAN HTTPS endpoints, as "ip:port".</summary>
    public IReadOnlyList<string> Endpoints => Addresses.Select(a => $"{a}:{Port}").ToList();

    /// <summary>A name to show: the hub's, else the instance.</summary>
    public string DisplayName => Name.Length > 0 ? Name : Instance;

    /// <summary>
    /// Reads a hub announcement. Keys are case-insensitive and only the first counts. A
    /// record without a valid id and fingerprint is unusable (null); the other keys are
    /// optional, and a malformed optional value is dropped.
    /// </summary>
    public static HubAnnouncement? Parse(ServiceInstance inst)
    {
        ArgumentNullException.ThrowIfNull(inst);
        var t = inst.Txt;
        var id = (t.GetValueOrDefault("id") ?? "").Trim().ToLowerInvariant();
        if (id.Length != 16 || !Hex.IsLower(id))
        {
            return null;
        }
        if (Common.Fingerprint.Normalize(t.GetValueOrDefault("fp")) is not { } fp)
        {
            return null;
        }
        var v4 = inst.Addresses.Where(a => a.AddressFamily == AddressFamily.InterNetwork).ToList();
        if (v4.Count == 0 || inst.Port <= 0)
        {
            return null;
        }
        var http = int.TryParse((t.GetValueOrDefault("http") ?? "").Trim(), NumberStyles.None, CultureInfo.InvariantCulture, out var h) && h is > 0 and < 65536 ? h : 0;
        return new HubAnnouncement
        {
            Instance = inst.Label, Port = inst.Port, Addresses = v4, Id = id, Fingerprint = fp,
            Name = CleanName(t.GetValueOrDefault("name")), HttpPort = http, Tailnet = CleanTailnet(t.GetValueOrDefault("ts")),
        };
    }

    static string CleanName(string? s)
    {
        var n = new string((s ?? "").Trim().Where(c => !char.IsControl(c)).ToArray());
        return n.Length > 63 ? n[..63] : n;
    }

    /// <summary>Only an https URL with a host and nothing else.</summary>
    internal static string CleanTailnet(string? s)
    {
        s = (s ?? "").Trim();
        if (s.Length == 0 || !Uri.TryCreate(s, UriKind.Absolute, out var u) || u.Scheme != Uri.UriSchemeHttps ||
            string.IsNullOrEmpty(u.Host) || !string.IsNullOrEmpty(u.UserInfo) || !string.IsNullOrEmpty(u.Query) || !string.IsNullOrEmpty(u.Fragment))
        {
            return "";
        }
        return u.GetLeftPart(UriPartial.Path).TrimEnd('/');
    }
}

/// <summary>Finds droplet hubs on the LAN: <c>_droplet._tcp</c> over mDNS, with legacy unicast queries.</summary>
public sealed class HubBrowser(ILogger? logger = null)
{
    /// <summary>The service type hubs announce.</summary>
    public static readonly DnsName ServiceType = DnsName.Parse("_droplet._tcp.local.");

    readonly MdnsBrowser browser = new(logger);

    /// <summary>
    /// Looks for hubs for up to <paramref name="wait"/>. <paramref name="found"/>, if set,
    /// hears each one as soon as it's complete; returning true stops early.
    /// </summary>
    public async Task<List<HubAnnouncement>> BrowseAsync(TimeSpan wait, Func<HubAnnouncement, bool>? found = null, CancellationToken ct = default)
    {
        var list = await browser.BrowseAsync(ServiceType, wait,
            found is null ? null : inst => HubAnnouncement.Parse(inst) is { } h && found(h), ct).ConfigureAwait(false);
        return list.Select(HubAnnouncement.Parse).OfType<HubAnnouncement>().ToList();
    }
}
