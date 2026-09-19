using System.IO;
using ScaleTransform = System.Windows.Media.ScaleTransform;
using System.Windows.Media.Imaging;
using Droplet.Core.Platform;
using Droplet.Windows.Shell;
using Microsoft.Extensions.Logging;
using Windows.Media.Control;
using Session = Windows.Media.Control.GlobalSystemMediaTransportControlsSession;
using SessionManager = Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager;

namespace Droplet.Windows.Platform;

/// <summary>
/// Media and volume. What's playing comes from Windows' media sessions
/// (<see cref="SessionManager"/>, the source of the volume flyout's media card: Spotify,
/// browsers, Media Player...), read once a second while someone is looking, with album art
/// read once per track. Commands go to the chosen session (or the current one) through the
/// same API, and to the media keys when there's no session. The volume is exact, through
/// Core Audio, with the volume keys as the fallback.
/// </summary>
internal sealed class MediaService : IMedia, IDisposable
{
    const int MaxArt = 64 * 1024; // the protocol's cap on an art data: URL
    static readonly TimeSpan Every = TimeSpan.FromSeconds(1);
    static readonly TimeSpan IdleAfter = TimeSpan.FromSeconds(15);

    readonly Action<List<Stroke>> inject;
    readonly ILogger log;
    readonly CancellationTokenSource stop = new();
    readonly SemaphoreSlim wake = new(0, 1);
    readonly Dictionary<string, string?> art = []; // art key → data: URL (null: none or failed)
    readonly Lock gate = new();
    SessionManager? manager;
    MediaState current = new();
    string lastKey = "";
    long lastReadTicks;
    int failures;

    public MediaService(Action<List<Stroke>> inject, ILogger log)
    {
        this.inject = inject;
        this.log = log;
        _ = Task.Run(RunAsync);
    }

    /// <inheritdoc/>
    public event Action? Changed;

    /// <inheritdoc/>
    public MediaState Current
    {
        get
        {
            var idle = Environment.TickCount64 - Interlocked.Exchange(ref lastReadTicks, Environment.TickCount64) > IdleAfter.TotalMilliseconds;
            if (idle)
            {
                Poke(); // it was asleep: read now, and keep reading while someone looks
            }
            lock (gate)
            {
                return current;
            }
        }
    }

    void Poke()
    {
        try
        {
            wake.Release();
        }
        catch (SemaphoreFullException)
        {
        }
    }

    async Task RunAsync()
    {
        while (!stop.IsCancellationRequested)
        {
            if (Environment.TickCount64 - Interlocked.Read(ref lastReadTicks) < IdleAfter.TotalMilliseconds)
            {
                await ReadAsync().ConfigureAwait(false);
            }
            try
            {
                await wake.WaitAsync(Every, stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    async Task ReadAsync()
    {
        MediaState next;
        try
        {
            next = new MediaState
            {
                Players = failures < 3 ? await PlayersAsync().ConfigureAwait(false) : [],
                Volume = TryVolume(),
            };
            next = next with { Active = next.Players.Count > 0 ? next.Players[0].Id : null };
            failures = 0;
        }
        catch (Exception e)
        {
            // an old or stripped-down Windows: the volume alone, as the Go app did
            if (++failures == 3)
            {
                log.LogWarning("media sessions: giving up after repeated failures ({Error}); sending the volume only", e.Message);
            }
            manager = null;
            next = new MediaState { Volume = TryVolume() };
        }
        var key = System.Text.Json.JsonSerializer.Serialize(next with
        {
            Players = next.Players.Select(p => p.Status == "Playing" ? p with { Position = null } : p).ToList(),
        });
        lock (gate)
        {
            current = next;
        }
        if (key != lastKey)
        {
            lastKey = key;
            Changed?.Invoke();
        }
    }

    static Volume? TryVolume()
    {
        try
        {
            var v = CoreAudio.Get();
            return v with { Level = Math.Round(v.Level, 3) };
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or InvalidCastException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    async Task<SessionManager> ManagerAsync() => manager ??= await SessionManager.RequestAsync();

    async Task<List<MediaPlayer>> PlayersAsync()
    {
        var mgr = await ManagerAsync().ConfigureAwait(false);
        var currentId = mgr.GetCurrentSession()?.SourceAppUserModelId;
        var players = new List<MediaPlayer>();
        foreach (var s in mgr.GetSessions())
        {
            var p = await PlayerAsync(s).ConfigureAwait(false);
            if (p is null)
            {
                continue;
            }
            if (p.Id == currentId)
            {
                players.Insert(0, p);
            }
            else
            {
                players.Add(p);
            }
        }
        return players;
    }

    async Task<MediaPlayer?> PlayerAsync(Session s)
    {
        var id = s.SourceAppUserModelId;
        if (string.IsNullOrEmpty(id))
        {
            return null;
        }
        var pb = s.GetPlaybackInfo();
        var status = MediaNames.Status((int)pb.PlaybackStatus);
        string title = "", artist = "", album = "";
        string? artUrl = null;
        try
        {
            var props = await s.TryGetMediaPropertiesAsync();
            if (props is not null)
            {
                (title, artist, album) = (props.Title ?? "", props.Artist ?? "", props.AlbumTitle ?? "");
                if (props.Thumbnail is { } thumb)
                {
                    artUrl = await ArtAsync($"{id}|{title}|{artist}|{album}", thumb).ConfigureAwait(false);
                }
            }
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or InvalidOperationException)
        {
            // some apps refuse while switching tracks: the rest still counts
        }
        var tl = s.GetTimelineProperties();
        double? length = null, position = null;
        if (tl is not null)
        {
            var (l, pos) = MediaNames.Timeline(tl.StartTime, tl.EndTime, tl.Position, tl.LastUpdatedTime, status == "Playing", DateTimeOffset.Now);
            if (l > 0)
            {
                (length, position) = (l, pos);
            }
        }
        var controls = pb.Controls;
        return new MediaPlayer
        {
            Id = id, Name = MediaNames.PlayerName(id), Status = status, Title = title, Artist = artist, Album = album, Art = artUrl,
            Length = length, Position = position,
            CanSeek = controls?.IsPlaybackPositionEnabled == true && length is > 0,
            CanNext = controls?.IsNextEnabled == true, CanPrevious = controls?.IsPreviousEnabled == true,
        };
    }

    async Task<string?> ArtAsync(string key, global::Windows.Storage.Streams.IRandomAccessStreamReference thumb)
    {
        if (art.TryGetValue(key, out var known))
        {
            return known;
        }
        if (art.Count > 50)
        {
            art.Clear();
        }
        string? url = null;
        try
        {
            using var stream = await thumb.OpenReadAsync();
            using var input = stream.AsStreamForRead();
            using var buffer = new MemoryStream();
            await input.CopyToAsync(buffer).ConfigureAwait(false);
            var raw = buffer.ToArray();
            url = await Sta.Run(() => ArtDataUrl(raw)).ConfigureAwait(false);
        }
        catch (Exception e) when (e is IOException or NotSupportedException or FileFormatException or System.Runtime.InteropServices.COMException or InvalidOperationException or ArgumentException)
        {
            log.LogDebug("album art: {Error}", e.Message);
        }
        art[key] = url;
        return url;
    }

    /// <summary>Album art (JPEG, PNG, BMP...) shrunk to a JPEG data: URL of at most 64 KB, or null.</summary>
    static string? ArtDataUrl(byte[] raw)
    {
        using var ms = new MemoryStream(raw);
        var frame = BitmapDecoder.Create(ms, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad).Frames[0];
        foreach (var side in (ReadOnlySpan<int>)[300, 200, 128])
        {
            var scale = Math.Min(1.0, side / (double)Math.Max(frame.PixelWidth, frame.PixelHeight));
            BitmapSource img = scale < 1 ? new TransformedBitmap(frame, new ScaleTransform(scale, scale)) : frame;
            foreach (var q in (ReadOnlySpan<int>)[80, 60])
            {
                var enc = new JpegBitmapEncoder { QualityLevel = q };
                enc.Frames.Add(BitmapFrame.Create(img));
                using var outMs = new MemoryStream();
                enc.Save(outMs);
                var url = "data:image/jpeg;base64," + Convert.ToBase64String(outMs.GetBuffer(), 0, (int)outMs.Length);
                if (url.Length <= MaxArt)
                {
                    return url;
                }
            }
        }
        return null;
    }

    async Task<Session?> SessionAsync(string? player)
    {
        try
        {
            var mgr = await ManagerAsync().ConfigureAwait(false);
            if (!string.IsNullOrEmpty(player))
            {
                foreach (var s in mgr.GetSessions())
                {
                    if (s.SourceAppUserModelId == player)
                    {
                        return s;
                    }
                }
            }
            return mgr.GetCurrentSession();
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or InvalidOperationException)
        {
            return null;
        }
    }

    void Key(ushort vk)
    {
        var strokes = new List<Stroke>();
        InputTranslator.PressKey(strokes, new VirtualKey(vk, true), []);
        inject(strokes);
    }

    /// <inheritdoc/>
    public async Task PerformAsync(MediaCommand command, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(command);
        switch (command.Action)
        {
            case "volume":
                {
                    var level = command.Number is { } n && !double.IsNaN(n) ? Math.Clamp(n, 0, 1)
                        : throw new ArgumentException("volume needs a number from 0 to 1");
                    try
                    {
                        CoreAudio.SetLevel(level);
                    }
                    catch (Exception e) when (e is System.Runtime.InteropServices.COMException or InvalidCastException)
                    {
                        inject(MediaNames.VolumeKeys(level)); // the keys still work
                        log.LogInformation("core audio: {Error} (used the volume keys)", e.Message);
                    }
                    break;
                }
            case "mute":
                try
                {
                    var v = CoreAudio.Get();
                    var muted = command.Flag ?? !v.Muted;
                    if (muted != v.Muted)
                    {
                        CoreAudio.SetMuted(muted);
                    }
                }
                catch (Exception e) when (e is System.Runtime.InteropServices.COMException or InvalidCastException)
                {
                    Key(Keys.VolumeMute); // it can only toggle: the best left to do
                    log.LogInformation("core audio: {Error} (toggled with the mute key)", e.Message);
                }
                break;
            case "seek":
                {
                    var s = await SessionAsync(command.Player).ConfigureAwait(false);
                    if (s is null || s.GetPlaybackInfo().Controls?.IsPlaybackPositionEnabled != true || command.Number is not { } secs || double.IsNaN(secs))
                    {
                        throw new NotSupportedException("seeking isn't possible in this player");
                    }
                    var start = s.GetTimelineProperties()?.StartTime ?? TimeSpan.Zero;
                    await s.TryChangePlaybackPositionAsync((start + TimeSpan.FromSeconds(Math.Max(0, secs))).Ticks);
                    break;
                }
            case "play-pause" or "play" or "pause" or "next" or "previous" or "stop":
                await TransportAsync(command).ConfigureAwait(false);
                break;
            default:
                throw new NotSupportedException($"unknown media action \"{command.Action}\"");
        }
        await Task.Delay(250, ct).ConfigureAwait(false); // let the app catch up, then read again
        Poke();
    }

    async Task TransportAsync(MediaCommand command)
    {
        var s = await SessionAsync(command.Player).ConfigureAwait(false);
        var playing = s?.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
        if (s is null)
        {
            // no session: the media keys reach whatever Windows thinks is current
            switch (command.Action)
            {
                case "play-pause" or "play" or "pause":
                    Key(Keys.MediaPlayPause);
                    break;
                case "next":
                    Key(Keys.MediaNextTrack);
                    break;
                case "previous":
                    Key(Keys.MediaPrevTrack);
                    break;
                case "stop":
                    Key(Keys.MediaStop);
                    break;
            }
            return;
        }
        var done = command.Action switch
        {
            "play-pause" => await s.TryTogglePlayPauseAsync(),
            "play" => playing || await s.TryPlayAsync(),
            "pause" => !playing || await s.TryPauseAsync(),
            "next" => await s.TrySkipNextAsync(),
            "previous" => await s.TrySkipPreviousAsync(),
            _ => await s.TryStopAsync(),
        };
        if (!done)
        {
            log.LogInformation("media: {Player} didn't do {Action}", MediaNames.PlayerName(s.SourceAppUserModelId), command.Action);
        }
    }

    public void Dispose()
    {
        stop.Cancel();
        stop.Dispose();
        wake.Dispose();
    }
}
