using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using Droplet.Core.Common;
using Droplet.Core.Platform;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Droplet.Core.Remote;

/// <summary>Capability names (docs/remote.md), as in hello and the hub's routing.</summary>
public static class Caps
{
    /// <summary>Mouse and keyboard.</summary>
    public const string Input = "input";

    /// <summary>Media and volume.</summary>
    public const string Media = "media";

    /// <summary>Locking.</summary>
    public const string Lock = "lock";

    /// <summary>Screenshots.</summary>
    public const string Screenshot = "screenshot";

    /// <summary>Clipboard sync.</summary>
    public const string Clipboard = "clipboard";

    /// <summary>Every capability this app can offer, in hello order.</summary>
    public static readonly IReadOnlyList<string> All = [Input, Media, Lock, Screenshot, Clipboard];
}

/// <summary>A device, as the hub names it in "from".</summary>
/// <param name="Id">Its id.</param>
/// <param name="Name">Its name.</param>
public sealed record Sender(string Id, string Name);

/// <summary>
/// Where a message arrived from, and where its answers go: the hub's <c>/ws</c>
/// connection, or a direct link with a peer.
/// </summary>
public interface IRemoteSource
{
    /// <summary>"hub" or "peer".</summary>
    string Kind { get; }

    /// <summary>Sends a reply back the way the message came.</summary>
    Task<bool> ReplyAsync(JsonObject message);

    /// <summary>
    /// Delivers a file to the requester (a screenshot): an upload to the hub for
    /// <paramref name="to"/>, or a mesh offer to the peer that asked, whatever <paramref name="to"/> says.
    /// </summary>
    Task DeliverFileAsync(string name, byte[] data, string mime, string to, CancellationToken ct = default);
}

/// <summary>Remote control activity, for the tray: who, and whether it was pointer or keyboard input.</summary>
/// <param name="From">Who.</param>
/// <param name="Input">Input (as opposed to media, lock...).</param>
public sealed record RemoteActivity(string From, bool Input);

/// <summary>
/// Acts on remote-control messages (<c>input</c>, <c>media</c>, <c>cmd</c>, <c>clip</c>,
/// <c>rpc</c>, docs/remote.md §3) from wherever they came: the hub's <c>/ws</c> connection
/// or a direct mesh link. Both feed the same handlers; the source is where replies and
/// screenshots go back. Only what's switched on (the <c>enabled</c> capabilities) and what the
/// platform can do is acted on; anything else is ignored, as the protocol asks.
/// <para>
/// Input, media and clipboard writes keep their order (one queue); locks and screenshots
/// run beside them.
/// </para>
/// </summary>
public sealed class RemoteDispatcher : IAsyncDisposable
{
    readonly PlatformServices services;
    readonly Func<IReadOnlySet<string>> enabled;
    readonly Func<string> machineName;
    readonly ILogger log;
    readonly Channel<Func<Task>> serial = Channel.CreateBounded<Func<Task>>(new BoundedChannelOptions(2000)
    {
        FullMode = BoundedChannelFullMode.DropWrite, SingleReader = true,
    });
    readonly Task worker;
    string lastError = "";
    DateTimeOffset lastErrorAt;

    /// <summary>Creates a dispatcher.</summary>
    /// <param name="services">What the desktop can do.</param>
    /// <param name="enabled">The capabilities switched on now (the settings, less "paused").</param>
    /// <param name="machineName">This device's name, for screenshot file names.</param>
    /// <param name="logger">Where to log. Typed text and clipboard contents are never logged.</param>
    public RemoteDispatcher(PlatformServices services, Func<IReadOnlySet<string>> enabled, Func<string> machineName, ILogger? logger = null)
    {
        this.services = services ?? throw new ArgumentNullException(nameof(services));
        this.enabled = enabled ?? throw new ArgumentNullException(nameof(enabled));
        this.machineName = machineName ?? throw new ArgumentNullException(nameof(machineName));
        log = logger ?? NullLogger.Instance;
        worker = Task.Run(Work);
    }

    /// <summary>Clipboard sync's state, shared with whoever polls the clipboard.</summary>
    public ClipboardSync Clip { get; } = new();

    /// <summary>Raised on remote control, for the tray.</summary>
    public event Action<RemoteActivity>? Activity;

    /// <summary>Raised after a screenshot was taken for someone: (who, file name).</summary>
    public event Action<string, string>? ScreenshotTaken;

    /// <summary>Whether the platform can provide a capability.</summary>
    public bool Can(string capability) => capability switch
    {
        Caps.Input => services.Input is not null,
        Caps.Media => services.Media is not null,
        Caps.Lock => services.Lock is not null,
        Caps.Screenshot => services.Screenshot is not null,
        Caps.Clipboard => services.Clipboard is not null,
        _ => false,
    };

    /// <summary>The capabilities to offer now: switched on, and working, in hello order.</summary>
    public List<string> Offered()
    {
        var on = enabled();
        return Caps.All.Where(c => on.Contains(c) && Can(c)).ToList();
    }

    bool Has(string capability) => enabled().Contains(capability) && Can(capability);

    async Task Work()
    {
        await foreach (var job in serial.Reader.ReadAllAsync().ConfigureAwait(false))
        {
            try
            {
                await job().ConfigureAwait(false);
            }
            catch (Exception e)
            {
                LogQuietly($"remote: {e.Message}");
            }
        }
    }

    /// <summary>
    /// Handles one message. Returns once it's queued or done; the caller's own loop is
    /// never blocked by a slow action.
    /// </summary>
    public async Task DispatchAsync(JsonObject msg, IRemoteSource source)
    {
        ArgumentNullException.ThrowIfNull(msg);
        ArgumentNullException.ThrowIfNull(source);
        var from = msg["from"] is JsonObject f ? new Sender(f.Str("id") ?? "", f.Str("name") ?? "") : null;
        var who = string.IsNullOrEmpty(from?.Name) ? "Someone" : from.Name;
        switch (msg.Str("t"))
        {
            case "input" when Has(Caps.Input):
                {
                    var events = ParseInput(msg);
                    Activity?.Invoke(new RemoteActivity(who, true));
                    if (events.Count > 0)
                    {
                        serial.Writer.TryWrite(() =>
                        {
                            try
                            {
                                services.Input!.Apply(events);
                            }
                            catch (Exception e)
                            {
                                // while the lock screen or an elevated window is up, every frame fails the same way
                                LogQuietly($"remote: input from {who}: {e.Message}");
                            }
                            return Task.CompletedTask;
                        });
                    }
                    break;
                }
            case "media" when Has(Caps.Media):
                {
                    Activity?.Invoke(new RemoteActivity(who, false));
                    var cmd = ParseMedia(msg);
                    serial.Writer.TryWrite(async () =>
                    {
                        try
                        {
                            await services.Media!.PerformAsync(cmd).ConfigureAwait(false);
                        }
                        catch (Exception e)
                        {
                            log.LogInformation("remote: media {Action} from {Who}: {Error}", cmd.Action, who, e.Message);
                        }
                    });
                    break;
                }
            case "cmd":
                {
                    var cmd = msg.Str("cmd");
                    if (cmd == "lock" && Has(Caps.Lock))
                    {
                        Activity?.Invoke(new RemoteActivity(who, false));
                        log.LogInformation("remote: {Who} locked this device", who);
                        _ = Task.Run(() =>
                        {
                            try
                            {
                                services.Lock!.Lock();
                            }
                            catch (Exception e)
                            {
                                log.LogWarning("remote: lock: {Error}", e.Message);
                            }
                        });
                    }
                    else if (cmd == "screenshot" && Has(Caps.Screenshot) && !string.IsNullOrEmpty(from?.Id))
                    {
                        Activity?.Invoke(new RemoteActivity(who, false));
                        _ = Task.Run(() => ScreenshotAsync(from, source));
                    }
                    break;
                }
            case "clip" when Has(Caps.Clipboard):
                {
                    var text = msg.Str("text");
                    if (string.IsNullOrEmpty(text) || System.Text.Encoding.UTF8.GetByteCount(text) > ClipboardSync.MaxBytes)
                    {
                        break;
                    }
                    serial.Writer.TryWrite(() =>
                    {
                        Clip.Applying(text);
                        try
                        {
                            services.Clipboard!.WriteText(text);
                            log.LogInformation("remote: clipboard set from {Who} ({Chars} characters)", who, text.Length);
                        }
                        catch (Exception e)
                        {
                            log.LogWarning("remote: clipboard from {Who}: {Error}", who, e.Message);
                        }
                        return Task.CompletedTask;
                    });
                    break;
                }
            case "rpc":
                {
                    // no rpc methods here (files and SMS are the phone's): answer, so the
                    // controller isn't left waiting 30 s
                    var reply = new JsonObject
                    {
                        ["t"] = "rpc-result", ["id"] = msg["id"]?.DeepClone(),
                        ["error"] = $"droplet for Windows doesn't support {msg.Str("method") ?? "that"}",
                    };
                    await source.ReplyAsync(reply).ConfigureAwait(false);
                    break;
                }
        }
    }

    /// <summary>Lets go of held buttons and keys (a controller's connection ended mid-drag).</summary>
    public void ReleaseInput()
    {
        if (services.Input is { } input)
        {
            serial.Writer.TryWrite(() =>
            {
                input.ReleaseAll();
                return Task.CompletedTask;
            });
        }
    }

    async Task ScreenshotAsync(Sender to, IRemoteSource source)
    {
        try
        {
            var png = await services.Screenshot!.CapturePngAsync().ConfigureAwait(false);
            var name = ScreenshotName(machineName(), DateTime.Now);
            using var cts = new CancellationTokenSource(TimeSpan.FromMinutes(10));
            await source.DeliverFileAsync(name, png, "image/png", to.Id, cts.Token).ConfigureAwait(false);
            log.LogInformation("remote: sent {Name} ({Kb} KB) to {Who}", name, png.Length / 1024, to.Name);
            ScreenshotTaken?.Invoke(to.Name, name);
            services.Notifications?.Show(new Notification
            {
                Title = $"{to.Name} took a screenshot of this PC", Body = $"It was sent to {to.Name}.", Tag = "screenshot",
            });
        }
        catch (Exception e)
        {
            log.LogWarning("remote: screenshot for {Who}: {Error}", to.Name, e.Message);
        }
    }

    /// <summary>The file name for a screenshot: <c>screenshot-&lt;machine&gt;-YYYYMMDD-HHMMSS.png</c>.</summary>
    public static string ScreenshotName(string machine, DateTime when)
    {
        var safe = new string((machine ?? "").Trim().Select(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_' or '.' ? c : '-').ToArray()).Trim('-', '.');
        return $"screenshot-{(safe.Length == 0 ? "pc" : safe)}-{when.ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture)}.png";
    }

    /// <summary>The events of an input message; malformed ones are skipped.</summary>
    public static List<InputEvent> ParseInput(JsonObject msg)
    {
        ArgumentNullException.ThrowIfNull(msg);
        var list = new List<InputEvent>();
        if (msg["ev"] is not JsonArray ev)
        {
            return list;
        }
        foreach (var node in ev)
        {
            if (node is not JsonObject e || e.Str("k") is not { } k)
            {
                continue;
            }
            list.Add(new InputEvent(k, e.Num("dx") ?? 0, e.Num("dy") ?? 0, e.Str("b"), e.Bool("down") ?? false,
                (int)Math.Clamp(e.Int("n") ?? 0, 0, 3), e.Str("s"), e.Str("key"), e.Strings("mods") ?? []));
        }
        return list;
    }

    /// <summary>A media message as a command.</summary>
    public static MediaCommand ParseMedia(JsonObject msg)
    {
        ArgumentNullException.ThrowIfNull(msg);
        double? number = null;
        bool? flag = null;
        if (msg["value"] is JsonValue v)
        {
            switch (v.GetValueKind())
            {
                case JsonValueKind.Number:
                    number = msg.Num("value");
                    break;
                case JsonValueKind.True:
                case JsonValueKind.False:
                    flag = v.GetValue<bool>();
                    break;
            }
        }
        return new MediaCommand(msg.Str("action") ?? "", number, flag, msg.Str("player"));
    }

    void LogQuietly(string message)
    {
        var now = DateTimeOffset.UtcNow;
        if (message == lastError && now - lastErrorAt < TimeSpan.FromSeconds(30))
        {
            return;
        }
        (lastError, lastErrorAt) = (message, now);
        log.LogWarning("{Message}", message);
    }

    /// <inheritdoc/>
    public async ValueTask DisposeAsync()
    {
        serial.Writer.TryComplete();
        await worker.ConfigureAwait(false);
    }
}
