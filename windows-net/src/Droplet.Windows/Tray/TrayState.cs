namespace Droplet.Windows.Tray;

/// <summary>Everything the tray shows, gathered from the engine.</summary>
internal sealed record TrayState
{
    /// <summary>Let in to a hub.</summary>
    public bool Configured { get; init; }

    /// <summary>Waiting to be let in, with this code.</summary>
    public string? PendingCode { get; init; }

    /// <summary>The hub stopped letting this PC in.</summary>
    public bool NotAllowed { get; init; }

    /// <summary>The hub's name ("t15").</summary>
    public string HubName { get; init; } = "the hub";

    /// <summary>The hub answered the last poll.</summary>
    public bool Connected { get; init; }

    /// <summary>At least one poll has finished since starting.</summary>
    public bool Polled { get; init; }

    /// <summary>How the hub is reached: "on Wi-Fi", "via Tailscale", or "".</summary>
    public string Route { get; init; } = "";

    /// <summary>What's wrong, when something is.</summary>
    public string Problem { get; init; } = "";

    /// <summary>The hub's certificate on the LAN changed.</summary>
    public bool IdentityChanged { get; init; }

    /// <summary>File and message notifications are paused.</summary>
    public bool NotificationsPaused { get; init; }

    /// <summary>Remote control is paused.</summary>
    public bool RemotePaused { get; init; }

    /// <summary>Who controlled this PC in the last two minutes, or null.</summary>
    public string? ControlledBy { get; init; }

    /// <summary>Someone sent input in the last few seconds: the icon turns amber.</summary>
    public bool InputNow { get; init; }

    /// <summary>Who is ringing this PC, or null.</summary>
    public string? RingingFrom { get; init; }

    /// <summary>Mesh peers with an open link now.</summary>
    public int PeersLinked { get; init; }

    /// <summary>Mesh peers trusted (paired, or listed by the hub).</summary>
    public int Peers { get; init; }

    /// <summary>The tooltip, as the Go app worded it, plus the mesh.</summary>
    public string Tooltip()
    {
        var where = Route.Length > 0 ? $"{HubName} {Route}" : HubName;
        var text = this switch
        {
            { RingingFrom: { } from } => $"{from} is ringing this PC",
            { ControlledBy: { } who } when !RemotePaused => $"being controlled by {who}",
            { PendingCode: { } code } => $"waiting to be let in to {HubName} (code {code})",
            { NotAllowed: true } => $"{HubName} doesn't let this PC in (open Settings)",
            { Configured: false, Peers: > 0 } => PeersText(),
            { Configured: false } => "not set up yet (open Settings)",
            { Polled: false } => $"connecting to {HubName}…",
            { Connected: true, IdentityChanged: true } => $"{where}; the hub's identity on Wi-Fi changed (open Settings)",
            { Connected: true } => where + (NotificationsPaused ? " (notifications paused)" : ""),
            { Problem.Length: > 0 } => Problem,
            _ => $"can't reach {HubName}",
        };
        if (RemotePaused && RingingFrom is null)
        {
            text += " · remote control paused";
        }
        return "droplet — " + text;
    }

    string PeersText() => PeersLinked switch
    {
        0 => Peers == 1 ? "1 device, none reachable now" : $"{Peers} devices, none reachable now",
        _ => $"{PeersLinked} of {Peers} devices linked",
    };

    /// <summary>Which icon: amber while someone sends input, grey when nothing can be reached, else the drop.</summary>
    public string Icon() => this switch
    {
        { InputNow: true, RemotePaused: false } => "tray-live",
        { Configured: true, Connected: true } => "tray",
        { PeersLinked: > 0 } => "tray",
        _ => "tray-dim",
    };
}
