using System.Text.Json.Serialization;

namespace Droplet.Core.Platform;

/// <summary>
/// One pointer or keyboard event from a controller (docs/remote.md §3.1), as received.
/// The platform (SendInput on Windows) turns these into real input: it carries pixel
/// and wheel fractions between events, maps key names to virtual keys, and remembers
/// held buttons so <see cref="IInput.ReleaseAll"/> can let go of them.
/// </summary>
/// <param name="Kind">move, button, click, scroll, text or key.</param>
/// <param name="Dx">move: pixels right; scroll: lines right.</param>
/// <param name="Dy">move: pixels down; scroll: lines down (content moves up).</param>
/// <param name="Button">button, click: left, right or middle.</param>
/// <param name="Down">button: pressed (true) or released.</param>
/// <param name="Count">click: 1 for a click, 2 for a double click.</param>
/// <param name="Text">text: what to type, as is.</param>
/// <param name="Key">key: a web <c>KeyboardEvent.key</c> name.</param>
/// <param name="Mods">key: any of ctrl, alt, shift, meta.</param>
public sealed record InputEvent(
    string Kind, double Dx = 0, double Dy = 0, string? Button = null, bool Down = false, int Count = 0,
    string? Text = null, string? Key = null, IReadOnlyList<string>? Mods = null);

/// <summary>Mouse and keyboard (capability <c>input</c>).</summary>
public interface IInput
{
    /// <summary>Applies one message's events, in order. Throws when the platform refused them (UIPI, the lock screen).</summary>
    void Apply(IReadOnlyList<InputEvent> events);

    /// <summary>Lets go of every button and key still held, and forgets carried fractions (the controller went away).</summary>
    void ReleaseAll();
}

/// <summary>One player, in the protocol's media state format (docs/remote.md §3.2).</summary>
public sealed record MediaPlayer
{
    /// <summary>Its id.</summary>
    [JsonPropertyName("id")] public required string Id { get; init; }

    /// <summary>Its readable name.</summary>
    [JsonPropertyName("name")] public required string Name { get; init; }

    /// <summary>Playing, Paused or Stopped.</summary>
    [JsonPropertyName("status")] public required string Status { get; init; }

    /// <summary>The title.</summary>
    [JsonPropertyName("title")] public string Title { get; init; } = "";

    /// <summary>The artist.</summary>
    [JsonPropertyName("artist")] public string Artist { get; init; } = "";

    /// <summary>The album.</summary>
    [JsonPropertyName("album")] public string Album { get; init; } = "";

    /// <summary>Album art as a small data: URL, or null.</summary>
    [JsonPropertyName("art")] public string? Art { get; init; }

    /// <summary>Seconds in.</summary>
    [JsonPropertyName("position")] public double? Position { get; init; }

    /// <summary>Seconds long.</summary>
    [JsonPropertyName("length")] public double? Length { get; init; }

    /// <summary>Seeking works.</summary>
    [JsonPropertyName("can_seek")] public bool CanSeek { get; init; }

    /// <summary>Next works.</summary>
    [JsonPropertyName("can_next")] public bool CanNext { get; init; }

    /// <summary>Previous works.</summary>
    [JsonPropertyName("can_previous")] public bool CanPrevious { get; init; }
}

/// <summary>The system volume.</summary>
/// <param name="Level">0..1.</param>
/// <param name="Muted">Muted.</param>
public sealed record Volume([property: JsonPropertyName("level")] double Level, [property: JsonPropertyName("muted")] bool Muted);

/// <summary>The data of a <c>{"t":"state","kind":"media"}</c> message.</summary>
public sealed record MediaState
{
    /// <summary>The players, the current one first; empty when nothing plays.</summary>
    [JsonPropertyName("players")] public IReadOnlyList<MediaPlayer> Players { get; init; } = [];

    /// <summary>The current player's id.</summary>
    [JsonPropertyName("active")] public string? Active { get; init; }

    /// <summary>The volume, or null when unknown.</summary>
    [JsonPropertyName("volume")] public Volume? Volume { get; init; }
}

/// <summary>A media request (docs/remote.md §3.2).</summary>
/// <param name="Action">play-pause, play, pause, next, previous, stop, seek, volume or mute.</param>
/// <param name="Number">seek: seconds; volume: 0..1.</param>
/// <param name="Flag">mute: true or false; null toggles.</param>
/// <param name="Player">The player, or null for the active one.</param>
public sealed record MediaCommand(string Action, double? Number, bool? Flag, string? Player);

/// <summary>Media and volume (capability <c>media</c>).</summary>
public interface IMedia
{
    /// <summary>Performs a request. Throws <see cref="NotSupportedException"/> for what the platform can't do (seek on Windows).</summary>
    Task PerformAsync(MediaCommand command, CancellationToken ct = default);

    /// <summary>What's playing and the volume now.</summary>
    MediaState Current { get; }

    /// <summary>Raised when <see cref="Current"/> changes (a new track, play/pause, the volume).</summary>
    event Action? Changed;
}

/// <summary>The clipboard, text only (capability <c>clipboard</c>).</summary>
public interface IClipboard
{
    /// <summary>Changes whenever the clipboard's contents change (GetClipboardSequenceNumber on Windows).</summary>
    uint Sequence { get; }

    /// <summary>
    /// The clipboard's text. False when there's no text, or when the app that copied it
    /// asked for it to stay private (password managers do).
    /// </summary>
    bool TryReadText(out string text);

    /// <summary>Writes text to the clipboard.</summary>
    void WriteText(string text);
}

/// <summary>What a notification's click or button does.</summary>
public enum NotificationActionKind
{
    /// <summary>Opens a URL in the browser.</summary>
    OpenUrl,

    /// <summary>Opens a file.</summary>
    OpenFile,

    /// <summary>Shows a file in its folder.</summary>
    ShowInFolder,

    /// <summary>Opens a folder.</summary>
    OpenFolder,

    /// <summary>Stops the ring.</summary>
    StopRing,

    /// <summary>Accepts a pairing request (Arg: the request id).</summary>
    AcceptPairing,

    /// <summary>Refuses a pairing request (Arg: the request id).</summary>
    DenyPairing,
}

/// <summary>A button, or the click, on a notification.</summary>
public sealed record NotificationAction(string Label, NotificationActionKind Kind, string Arg = "");

/// <summary>A notification.</summary>
public sealed record Notification
{
    /// <summary>The title.</summary>
    public required string Title { get; init; }

    /// <summary>The text.</summary>
    public string Body { get; init; } = "";

    /// <summary>A notification with the same tag replaces the last one; <see cref="INotifications.Clear"/> takes it away.</summary>
    public string? Tag { get; init; }

    /// <summary>Stays up until answered (a ring).</summary>
    public bool Urgent { get; init; }

    /// <summary>What a click does.</summary>
    public NotificationAction? Click { get; init; }

    /// <summary>Buttons.</summary>
    public IReadOnlyList<NotificationAction> Buttons { get; init; } = [];

    /// <summary>The app the notification is about (a mirrored phone notification).</summary>
    public string? App { get; init; }
}

/// <summary>Notifications (toasts on Windows).</summary>
public interface INotifications
{
    /// <summary>Shows one. Never throws: a failure is logged, and the app carries on.</summary>
    void Show(Notification notification);

    /// <summary>Takes a notification away by its tag.</summary>
    void Clear(string tag);
}

/// <summary>Screenshots (capability <c>screenshot</c>).</summary>
public interface IScreenshot
{
    /// <summary>Every monitor, as one PNG.</summary>
    Task<byte[]> CapturePngAsync(CancellationToken ct = default);
}

/// <summary>Locking the screen (capability <c>lock</c>).</summary>
public interface ILock
{
    /// <summary>Locks the screen, as Win+L does.</summary>
    void Lock();
}

/// <summary>Ringing to find this device.</summary>
public interface ISound
{
    /// <summary>Starts the ring sound, loud, until stopped (or a minute passes).</summary>
    void StartRing();

    /// <summary>Stops it.</summary>
    void StopRing();
}

/// <summary>
/// What the desktop can do. A null member means "can't", and the matching capability
/// is never offered. The Windows app (3b) fills these in; tests use fakes.
/// </summary>
public sealed record PlatformServices
{
    /// <summary>Mouse and keyboard.</summary>
    public IInput? Input { get; init; }

    /// <summary>Media and volume.</summary>
    public IMedia? Media { get; init; }

    /// <summary>The clipboard.</summary>
    public IClipboard? Clipboard { get; init; }

    /// <summary>Notifications.</summary>
    public INotifications? Notifications { get; init; }

    /// <summary>Screenshots.</summary>
    public IScreenshot? Screenshot { get; init; }

    /// <summary>Locking.</summary>
    public ILock? Lock { get; init; }

    /// <summary>The ring.</summary>
    public ISound? Sound { get; init; }
}
