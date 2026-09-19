using System.Net;
using System.Text.Json.Serialization;

namespace Droplet.Core.Hub;

/// <summary>A named device, as in <c>/api/files</c>, <c>/api/me</c> and <c>/api/device</c>.</summary>
public sealed record Device
{
    /// <summary>The device id.</summary>
    [JsonPropertyName("id")] public string Id { get; init; } = "";

    /// <summary>Its name.</summary>
    [JsonPropertyName("name")] public string Name { get; init; } = "";

    /// <summary>Recently seen by the hub.</summary>
    [JsonPropertyName("online")] public bool Online { get; init; }

    /// <summary>Has web push.</summary>
    [JsonPropertyName("push")] public bool Push { get; init; }

    /// <summary>The device making the request.</summary>
    [JsonPropertyName("self")] public bool Self { get; init; }

    /// <summary>Asked to join over the LAN and not let in yet.</summary>
    [JsonPropertyName("pending")] public bool Pending { get; init; }

    /// <summary>The four digits both screens show while pending.</summary>
    [JsonPropertyName("code")] public string? Code { get; init; }

    /// <summary>What its live connections can do (docs/remote.md).</summary>
    [JsonPropertyName("caps")] public List<string>? Caps { get; init; }
}

/// <summary>An item in a folder listing.</summary>
public sealed record HubFile
{
    /// <summary>The file name.</summary>
    [JsonPropertyName("name")] public string Name { get; init; } = "";

    /// <summary>Its size in bytes.</summary>
    [JsonPropertyName("size")] public long Size { get; init; }

    /// <summary>Its modification time, Unix seconds.</summary>
    [JsonPropertyName("mtime")] public long Mtime { get; init; }

    /// <summary>Whether it's an image.</summary>
    [JsonPropertyName("image")] public bool Image { get; init; }

    /// <summary>Who sent it, once the upload is complete.</summary>
    [JsonPropertyName("from")] public string? From { get; init; }
}

/// <summary><c>GET /api/files</c>.</summary>
public sealed record FilesListing
{
    /// <summary>The hub's received folder.</summary>
    [JsonPropertyName("received")] public List<HubFile> Received { get; init; } = [];

    /// <summary>The hub's shared folder.</summary>
    [JsonPropertyName("shared")] public List<HubFile> Shared { get; init; } = [];

    /// <summary>This device's inbox.</summary>
    [JsonPropertyName("inbox")] public List<HubFile> Inbox { get; init; } = [];

    /// <summary>Every named device.</summary>
    [JsonPropertyName("devices")] public List<Device> Devices { get; init; } = [];

    /// <summary>Unread chat messages, per sender id.</summary>
    [JsonPropertyName("unread")] public Dictionary<string, int> Unread { get; init; } = [];

    /// <summary>This device, when the hub knows it.</summary>
    public Device? Self => Devices.FirstOrDefault(d => d.Self);

    /// <summary>The devices other than this one.</summary>
    public IEnumerable<Device> Others => Devices.Where(d => !d.Self);
}

/// <summary><c>GET /api/me</c>.</summary>
public sealed record Me
{
    /// <summary>This device; null when the hub doesn't know it (denied, expired or removed).</summary>
    [JsonPropertyName("device")] public Device? Device { get; init; }

    /// <summary>Whether this request is trusted.</summary>
    [JsonPropertyName("trusted")] public bool Trusted { get; init; }

    /// <summary>Whether the hub has a PIN.</summary>
    [JsonPropertyName("pin")] public bool Pin { get; init; }

    /// <summary>A suggested name for a new device.</summary>
    [JsonPropertyName("suggested")] public string? Suggested { get; init; }

    /// <summary>The hub's tailnet URL.</summary>
    [JsonPropertyName("hub_url")] public string? HubUrl { get; init; }
}

/// <summary>One chat message.</summary>
public sealed record ChatMessage
{
    /// <summary>Its id.</summary>
    [JsonPropertyName("id")] public string? Id { get; init; }

    /// <summary>The sender's device id.</summary>
    [JsonPropertyName("from")] public string From { get; init; } = "";

    /// <summary>The receiver's device id.</summary>
    [JsonPropertyName("to")] public string To { get; init; } = "";

    /// <summary>The text.</summary>
    [JsonPropertyName("text")] public string Text { get; init; } = "";

    /// <summary>When, Unix seconds.</summary>
    [JsonPropertyName("ts")] public double Ts { get; init; }
}

/// <summary>An active ring aimed at this device.</summary>
public sealed record Ring
{
    /// <summary>Its id.</summary>
    [JsonPropertyName("id")] public string Id { get; init; } = "";

    /// <summary>Who's ringing.</summary>
    [JsonPropertyName("from")] public string? From { get; init; }

    /// <summary>When it started, Unix seconds.</summary>
    [JsonPropertyName("ts")] public double Ts { get; init; }
}

/// <summary>The LAN part of <c>/api/hub/info</c>.</summary>
public sealed record HubLanInfo
{
    /// <summary>The hub's LAN addresses.</summary>
    [JsonPropertyName("addresses")] public List<string> Addresses { get; init; } = [];

    /// <summary>Its plain-HTTP port, for browsers.</summary>
    [JsonPropertyName("http_port")] public int HttpPort { get; init; }

    /// <summary>Its LAN HTTPS port; null without LAN HTTPS.</summary>
    [JsonPropertyName("https_port")] public int? HttpsPort { get; init; }
}

/// <summary><c>GET /api/hub/info</c>: who the hub is and how to reach it. It answers before a device is let in.</summary>
public sealed record HubInfo
{
    /// <summary>The hub's permanent id.</summary>
    [JsonPropertyName("id")] public string Id { get; init; } = "";

    /// <summary>The hub machine's name.</summary>
    [JsonPropertyName("name")] public string? Name { get; init; }

    /// <summary>The fingerprint of the LAN certificate; null without LAN HTTPS.</summary>
    [JsonPropertyName("fingerprint")] public string? Fingerprint { get; init; }

    /// <summary>How to reach it on the LAN.</summary>
    [JsonPropertyName("lan")] public HubLanInfo Lan { get; init; } = new();

    /// <summary>Its tailnet URL.</summary>
    [JsonPropertyName("tailnet")] public string? Tailnet { get; init; }

    /// <summary>Whether it has a PIN.</summary>
    [JsonPropertyName("pin")] public bool Pin { get; init; }

    /// <summary>The LAN HTTPS endpoints, as "ip:port" (IPv4 only, as the hub announces).</summary>
    public IReadOnlyList<string> LanEndpoints()
    {
        if (Lan.HttpsPort is not > 0)
        {
            return [];
        }
        return Lan.Addresses
            .Select(a => IPAddress.TryParse(a, out var ip) && ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork ? ip : null)
            .Where(ip => ip is not null)
            .Select(ip => $"{ip}:{Lan.HttpsPort}")
            .ToList();
    }
}

/// <summary>The answer to a link code: the device this app now belongs to, and its own token for it.</summary>
public sealed record Linked
{
    /// <summary>The device id.</summary>
    [JsonPropertyName("id")] public string Id { get; init; } = "";

    /// <summary>Its name.</summary>
    [JsonPropertyName("name")] public string Name { get; init; } = "";

    /// <summary>This app's token for it.</summary>
    [JsonPropertyName("token")] public string Token { get; init; } = "";
}

/// <summary>Upload and download progress: bytes so far, and the total.</summary>
public delegate void TransferProgress(long done, long total);
