using System.Text.Json.Serialization;

namespace Droplet.Core.Config;

/// <summary>
/// The paired hub's identity (docs/local-first.md): what lets the app find it on the
/// LAN whatever its IP is today, and check it's really it. The same shape as the Go
/// app's <c>hub</c> object, so its config imports as is.
/// </summary>
public sealed class HubIdentity
{
    /// <summary>Permanent, 16 hex characters.</summary>
    [JsonPropertyName("id")] public string Id { get; set; } = "";

    /// <summary>The hub machine's name, e.g. "t15".</summary>
    [JsonPropertyName("name")] public string? Name { get; set; }

    /// <summary>
    /// Pins the hub's self-signed LAN certificate (SHA-256 of its DER, lowercase hex).
    /// Empty: no LAN route, tailnet only.
    /// </summary>
    [JsonPropertyName("fingerprint")] public string? Fingerprint { get; set; }

    /// <summary>Where the fingerprint came from: <see cref="PinSources"/>.</summary>
    [JsonPropertyName("pin_source")] public string? PinSource { get; set; }

    /// <summary>Where the hub last answered on the LAN ("ip:port", newest first). Only a hint.</summary>
    [JsonPropertyName("lan")] public List<string> Lan { get; set; } = [];

    /// <summary>Its plain-HTTP port, for browsers.</summary>
    [JsonPropertyName("http_port")] public int HttpPort { get; set; }

    /// <summary>Its tailnet URL, if it has one.</summary>
    [JsonPropertyName("tailnet")] public string? Tailnet { get; set; }

    /// <summary>A deep copy.</summary>
    public HubIdentity Clone() => new()
    {
        Id = Id, Name = Name, Fingerprint = Fingerprint, PinSource = PinSource, Lan = [.. Lan], HttpPort = HttpPort,
        Tailnet = Tailnet,
    };
}

/// <summary>Where a hub's certificate pin came from.</summary>
public static class PinSources
{
    /// <summary>Read over the tailnet, with normal, verified TLS.</summary>
    public const string Tailnet = "tailnet";

    /// <summary>Trusted on first use on the LAN, confirmed by pairing.</summary>
    public const string Lan = "lan";
}

/// <summary>The mesh peer's settings (docs/mesh.md).</summary>
public sealed class MeshSettings
{
    /// <summary>Whether this device is a mesh peer at all.</summary>
    [JsonPropertyName("enabled")] public bool Enabled { get; set; } = true;

    /// <summary>A fixed mesh port; null picks the first free one in 1739–1749.</summary>
    [JsonPropertyName("port")] public int? Port { get; set; }

    /// <summary>Announce this device over mDNS.</summary>
    [JsonPropertyName("announce")] public bool Announce { get; set; } = true;

    /// <summary>Bytes a second when serving files to peers; 0 for no limit.</summary>
    [JsonPropertyName("max_rate")] public long MaxRate { get; set; }

    /// <summary>Where files sent directly are saved; null means the download folder.</summary>
    [JsonPropertyName("downloads")] public string? Downloads { get; set; }
}

/// <summary>
/// Everything the app keeps between runs. The field names are the Go app's, so its
/// <c>config.json</c> is read as is (<see cref="GoConfigImport"/>); new settings live
/// under <c>mesh</c>.
/// </summary>
public sealed class AppConfig
{
    /// <summary>
    /// The hub's tailnet URL (or any address typed in Settings): the fallback route away
    /// from home, with normal TLS checks. Empty for a hub paired on the LAN with no tailnet.
    /// </summary>
    [JsonPropertyName("hub_url")] public string HubUrl { get; set; } = "";

    /// <summary>The paired hub's identity; null until known.</summary>
    [JsonPropertyName("hub")] public HubIdentity? Hub { get; set; }

    /// <summary>This device's id on the hub.</summary>
    [JsonPropertyName("device_id")] public string? DeviceId { get; set; }

    /// <summary>This device's name on the hub.</summary>
    [JsonPropertyName("device_name")] public string? DeviceName { get; set; }

    /// <summary>The device token (the <c>droplet_device</c> cookie, sent as a bearer).</summary>
    [JsonPropertyName("device_token")] public string? DeviceToken { get; set; }

    /// <summary>The session cookie from a PIN login, when the hub asks for one.</summary>
    [JsonPropertyName("session")] public string? Session { get; set; }

    /// <summary>Asked to join over the LAN and waiting to be let in.</summary>
    [JsonPropertyName("pair_pending")] public bool PairPending { get; set; }

    /// <summary>The four digits to compare while waiting.</summary>
    [JsonPropertyName("pair_code")] public string? PairCode { get; set; }

    /// <summary>Where files from the hub's inbox are saved.</summary>
    [JsonPropertyName("download_dir")] public string DownloadDir { get; set; } = "";

    /// <summary>Download inbox files automatically.</summary>
    [JsonPropertyName("auto_download")] public bool AutoDownload { get; set; } = true;

    /// <summary>Notify about files.</summary>
    [JsonPropertyName("notify_files")] public bool NotifyFiles { get; set; } = true;

    /// <summary>Notify about messages.</summary>
    [JsonPropertyName("notify_messages")] public bool NotifyMessages { get; set; } = true;

    /// <summary>Play a sound when rung.</summary>
    [JsonPropertyName("ring_sound")] public bool RingSound { get; set; } = true;

    /// <summary>File and message notifications paused.</summary>
    [JsonPropertyName("paused")] public bool Paused { get; set; }

    /// <summary>Start at sign-in (only once turned on in Settings).</summary>
    [JsonPropertyName("autostart")] public bool Autostart { get; set; }

    /// <summary>Explorer "Send to" entries (only once turned on in Settings).</summary>
    [JsonPropertyName("send_to")] public bool SendTo { get; set; }

    /// <summary>Remote control: mouse and keyboard.</summary>
    [JsonPropertyName("remote_input")] public bool RemoteInput { get; set; } = true;

    /// <summary>Remote control: media and volume.</summary>
    [JsonPropertyName("remote_media")] public bool RemoteMedia { get; set; } = true;

    /// <summary>Remote control: locking.</summary>
    [JsonPropertyName("remote_lock")] public bool RemoteLock { get; set; } = true;

    /// <summary>Remote control: screenshots.</summary>
    [JsonPropertyName("remote_screenshot")] public bool RemoteScreenshot { get; set; } = true;

    /// <summary>
    /// Clipboard sync. Off until asked for: it sends everything copied, passwords too,
    /// to every device.
    /// </summary>
    [JsonPropertyName("clipboard_sync")] public bool ClipboardSync { get; set; }

    /// <summary>All remote control switched off at once, from the tray.</summary>
    [JsonPropertyName("remote_paused")] public bool RemotePaused { get; set; }

    /// <summary>The newest chat timestamp already shown, per sender device id.</summary>
    [JsonPropertyName("chat_seen")] public Dictionary<string, double> ChatSeen { get; set; } = [];

    /// <summary>Inbox items already announced ("name|mtime").</summary>
    [JsonPropertyName("inbox_seen")] public List<string> InboxSeen { get; set; } = [];

    /// <summary>A secret carried by notification links, so a web page can't trigger them.</summary>
    [JsonPropertyName("action_key")] public string ActionKey { get; set; } = "";

    /// <summary>The mesh peer's settings.</summary>
    [JsonPropertyName("mesh")] public MeshSettings Mesh { get; set; } = new();

    /// <summary>The Go app's config this one was imported from, if it was.</summary>
    [JsonPropertyName("imported_from")] public string? ImportedFrom { get; set; }

    /// <summary>Whether this install has named itself on the hub and been let in.</summary>
    [JsonIgnore] public bool Registered => !string.IsNullOrEmpty(DeviceToken) && !PairPending;

    /// <summary>Whether it's waiting to be let in.</summary>
    [JsonIgnore] public bool Pending => !string.IsNullOrEmpty(DeviceToken) && PairPending;

    /// <summary>The fallback route: the configured URL, else the hub's tailnet URL.</summary>
    [JsonIgnore]
    public string RemoteUrl => !string.IsNullOrEmpty(HubUrl) ? HubUrl : Hub?.Tailnet ?? "";
}
