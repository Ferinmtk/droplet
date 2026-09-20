using System.Collections.Concurrent;
using System.Text.Json;
using System.Text.Json.Nodes;
using Droplet.Core.Platform;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Droplet.Core.Remote;

/// <summary>
/// Watches the clipboard while clipboard sync is on, and says when there's new text to
/// send (<see cref="Copied"/>): to the hub's live connection and to linked peers. Text
/// written from another device isn't sent back (<see cref="ClipboardSync"/>).
/// </summary>
public sealed class ClipboardWatcher(IClipboard clipboard, ClipboardSync sync, Func<bool> enabled, ILogger? logger = null) : IAsyncDisposable
{
    readonly ILogger log = logger ?? NullLogger.Instance;
    readonly CancellationTokenSource stop = new();
    Task? loop;

    /// <summary>New clipboard text to send.</summary>
    public event Func<string, Task>? Copied;

    /// <summary>Starts polling.</summary>
    public void Start() => loop ??= Task.Run(RunAsync);

    async Task RunAsync()
    {
        var wasOn = false;
        while (!stop.IsCancellationRequested)
        {
            try
            {
                var on = enabled();
                if (on && !wasOn)
                {
                    sync.Reset(); // what's on the clipboard when syncing starts isn't news
                }
                wasOn = on;
                if (on && sync.Observe(clipboard.Sequence, () => clipboard.TryReadText(out var t) ? (true, t) : (false, ""), DateTimeOffset.UtcNow) is { } text &&
                    Copied is { } handlers)
                {
                    foreach (var h in handlers.GetInvocationList().Cast<Func<string, Task>>())
                    {
                        await h(text).ConfigureAwait(false);
                    }
                }
            }
            catch (Exception e)
            {
                log.LogDebug("clipboard: {Error}", e.Message);
            }
            try
            {
                await Task.Delay(ClipboardSync.Poll, stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    /// <inheritdoc/>
    public async ValueTask DisposeAsync()
    {
        await stop.CancelAsync().ConfigureAwait(false);
        if (loop is not null)
        {
            await loop.ConfigureAwait(false);
        }
        stop.Dispose();
    }
}

/// <summary>
/// Publishes this device's state (docs/remote.md §3.5): media, whenever it changes (at
/// once for a new track, a play/pause or a volume change; every few seconds while
/// something plays, so the position stays roughly right; never more than once a
/// second). The latest of each kind is kept for new links (<see cref="Last"/>).
/// </summary>
public sealed class StatePublisher : IAsyncDisposable
{
    static readonly TimeSpan Tick = TimeSpan.FromSeconds(1);
    static readonly TimeSpan Refresh = TimeSpan.FromSeconds(5);

    readonly IMedia? media;
    readonly Func<bool> mediaEnabled;
    readonly ILogger log;
    readonly ConcurrentDictionary<string, JsonNode?> last = new();
    readonly CancellationTokenSource stop = new();
    readonly SemaphoreSlim changed = new(0, 1);
    Task? loop;

    /// <summary>Creates a publisher of <paramref name="media"/>'s state, while <paramref name="mediaEnabled"/>.</summary>
    public StatePublisher(IMedia? media, Func<bool> mediaEnabled, ILogger? logger = null)
    {
        this.media = media;
        this.mediaEnabled = mediaEnabled;
        log = logger ?? NullLogger.Instance;
        if (media is not null)
        {
            media.Changed += Poke;
        }
    }

    /// <summary>A state to publish: (kind, data).</summary>
    public event Func<string, JsonNode?, Task>? Publish;

    /// <summary>The latest state published, by kind.</summary>
    public IReadOnlyDictionary<string, JsonNode?> Last => new Dictionary<string, JsonNode?>(last);

    /// <summary>Publishes at the next tick (the connection is new, or media changed).</summary>
    public void Poke()
    {
        try
        {
            changed.Release();
        }
        catch (SemaphoreFullException)
        {
        }
        catch (ObjectDisposedException)
        {
        }
    }

    /// <summary>Starts publishing.</summary>
    public void Start() => loop ??= Task.Run(RunAsync);

    /// <summary>A state message.</summary>
    public static JsonObject Message(string kind, JsonNode? data) => new() { ["t"] = "state", ["kind"] = kind, ["data"] = data?.DeepClone() };

    async Task RunAsync()
    {
        string? lastKey = null;
        var lastAt = DateTimeOffset.MinValue;
        while (!stop.IsCancellationRequested)
        {
            var forced = false;
            try
            {
                forced = await changed.WaitAsync(Tick, stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            if (media is null || !mediaEnabled())
            {
                continue;
            }
            try
            {
                var state = media.Current;
                var now = DateTimeOffset.UtcNow;
                if (now - lastAt < Tick)
                {
                    continue;
                }
                var key = Key(state);
                var playing = state.Players.Count > 0 && state.Players[0].Status == "Playing";
                if (key == lastKey && !(playing && now - lastAt >= Refresh) && !forced)
                {
                    continue;
                }
                var data = JsonSerializer.SerializeToNode(state with { Volume = state.Volume is { } v ? v with { Level = Math.Round(v.Level, 3) } : null });
                last["media"] = data;
                if (Publish is { } handlers)
                {
                    foreach (var h in handlers.GetInvocationList().Cast<Func<string, JsonNode?, Task>>())
                    {
                        await h("media", data).ConfigureAwait(false);
                    }
                }
                (lastKey, lastAt) = (key, now);
            }
            catch (Exception e)
            {
                log.LogDebug("media state: {Error}", e.Message);
            }
        }
    }

    /// <summary>A media state's identity, ignoring the position of whatever is playing (it moves every second).</summary>
    static string Key(MediaState st) =>
        JsonSerializer.Serialize(st with { Players = st.Players.Select(p => p.Status == "Playing" ? p with { Position = null } : p).ToList() });

    /// <inheritdoc/>
    public async ValueTask DisposeAsync()
    {
        if (media is not null)
        {
            media.Changed -= Poke;
        }
        await stop.CancelAsync().ConfigureAwait(false);
        if (loop is not null)
        {
            await loop.ConfigureAwait(false);
        }
        stop.Dispose();
    }
}
