using System.Globalization;
using System.Text.RegularExpressions;
using Droplet.Core.Config;
using Droplet.Core.Hub;
using Droplet.Core.LocalFirst;
using Droplet.Core.Mesh;
using Droplet.Core.Platform;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Droplet.Core.Polling;

/// <summary>
/// Decides what's new between two looks at the hub, so each file and message is
/// announced exactly once, even across restarts.
/// </summary>
public static class NewSince
{
    /// <summary>
    /// How old an inbox file without sender info must be before it counts as complete. The
    /// hub labels files with their sender only after the whole upload is saved; this is the
    /// fallback for one whose label went missing.
    /// </summary>
    public static readonly TimeSpan SettleAfter = TimeSpan.FromSeconds(60);

    /// <summary>Identifies one inbox item: a name reused by a later upload is a new item.</summary>
    public static string InboxKey(HubFile f)
    {
        ArgumentNullException.ThrowIfNull(f);
        return f.Name + "|" + f.Mtime.ToString(CultureInfo.InvariantCulture);
    }

    /// <summary>Whether an inbox file has finished uploading.</summary>
    public static bool Complete(HubFile f, DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(f);
        return !string.IsNullOrEmpty(f.From) || now - DateTimeOffset.FromUnixTimeSeconds(f.Mtime) > SettleAfter;
    }

    /// <summary>
    /// The complete inbox files not yet announced (oldest first), and the new seen list,
    /// which drops items no longer in the inbox so it can't grow forever.
    /// </summary>
    public static (List<HubFile> Fresh, List<string> Seen) Inbox(IEnumerable<HubFile> current, IEnumerable<string> seen, DateTimeOffset now)
    {
        var was = seen.ToHashSet();
        var fresh = new List<HubFile>();
        var next = new List<string>();
        foreach (var f in current)
        {
            if (!Complete(f, now))
            {
                continue; // announced once it's complete
            }
            var k = InboxKey(f);
            next.Add(k);
            if (!was.Contains(k))
            {
                fresh.Add(f);
            }
        }
        return (fresh.OrderBy(f => f.Mtime).ToList(), next);
    }

    /// <summary>
    /// The messages from one sender worth announcing: at most the last <paramref name="unread"/>
    /// of them (the hub's count), and only those newer than the last one shown. Oldest first,
    /// with the new high-water mark.
    /// </summary>
    public static (List<ChatMessage> Fresh, double Seen) Messages(IEnumerable<ChatMessage> thread, string from, int unread, double seenTs)
    {
        var next = seenTs;
        var theirs = thread.Where(m => m.From == from).OrderBy(m => m.Ts).ToList();
        foreach (var m in theirs)
        {
            next = Math.Max(next, m.Ts);
        }
        if (unread < theirs.Count)
        {
            theirs = theirs[^Math.Max(0, unread)..];
        }
        return (theirs.Where(m => m.Ts > seenTs).ToList(), next);
    }
}

/// <summary>What the tray shows about the hub.</summary>
public sealed record PollStatus
{
    /// <summary>Let in to a hub.</summary>
    public bool Configured { get; init; }

    /// <summary>Waiting to be let in.</summary>
    public bool Pending { get; init; }

    /// <summary>The code to compare while waiting.</summary>
    public string? PairCode { get; init; }

    /// <summary>The last poll reached the hub.</summary>
    public bool Connected { get; init; }

    /// <summary>Why not, in words.</summary>
    public string Problem { get; init; } = "";

    /// <summary>The route: "on Wi-Fi", "via Tailscale".</summary>
    public string Route { get; init; } = "";

    /// <summary>The hub no longer lets this PC in.</summary>
    public bool NotAllowed { get; init; }

    /// <summary>The hub's LAN certificate changed.</summary>
    public bool IdentityChanged { get; init; }

    /// <summary>Being rung, and by whom.</summary>
    public string? RingingFrom { get; init; }

    /// <summary>The devices on the hub.</summary>
    public IReadOnlyList<Device> Devices { get; init; } = [];
}

/// <summary>
/// The poll loop (the Go app's agent.tick): every 5 s (backing off to a minute while the
/// hub can't be reached, every 2.5 s while waiting to be let in), it fetches
/// <c>/api/files</c>, saves new inbox files to the download folder and removes them from
/// the hub once safely on disk, shows new chat messages, and rings when rung.
/// </summary>
public sealed partial class HubPoller : IAsyncDisposable
{
    static readonly TimeSpan PollEvery = TimeSpan.FromSeconds(5);
    static readonly TimeSpan PollRinging = TimeSpan.FromMilliseconds(1500);
    static readonly TimeSpan MaxBackoff = TimeSpan.FromMinutes(1);
    static readonly TimeSpan RingLimit = TimeSpan.FromSeconds(60);
    static readonly TimeSpan RingRecheck = TimeSpan.FromMinutes(5);

    readonly ConfigStore store;
    readonly RouteManager routes;
    readonly HubSetup setup;
    readonly PlatformServices services;
    readonly ILogger log;
    readonly SemaphoreSlim wake = new(0, 1);
    readonly CancellationTokenSource stop = new();
    readonly Lock gate = new();
    PollStatus status = new();
    string? ringId;
    DateTimeOffset ringStarted;
    string? stoppedRingId;
    DateTimeOffset ringChecked;
    bool ringSupported = true;
    bool removedWarned;
    bool pairWarned;
    string? changedWarned;
    DateTimeOffset migratedAt;
    Task? loop;

    /// <summary>Creates a poller; <see cref="Start"/> runs it.</summary>
    public HubPoller(ConfigStore store, RouteManager routes, HubSetup setup, PlatformServices services, ILogger? logger = null)
    {
        this.store = store ?? throw new ArgumentNullException(nameof(store));
        this.routes = routes ?? throw new ArgumentNullException(nameof(routes));
        this.setup = setup ?? throw new ArgumentNullException(nameof(setup));
        this.services = services ?? throw new ArgumentNullException(nameof(services));
        log = logger ?? NullLogger.Instance;
        routes.Selected += Remember;
        routes.RouteChanged += r => SetStatus(s => s with { Route = r?.Label ?? "" });
    }

    /// <summary>Raised when the status changes.</summary>
    public event Action<PollStatus>? StatusChanged;

    /// <summary>Raised when the person has to act in Settings: the hub stopped letting this PC in, or its identity changed.</summary>
    public event Action? NeedsPairing;

    /// <summary>A file from the inbox was saved (3b marks it as from the internet).</summary>
    public event Action<string>? FileSaved;

    /// <summary>The current status.</summary>
    public PollStatus Status
    {
        get
        {
            lock (gate)
            {
                return status;
            }
        }
    }

    void SetStatus(Func<PollStatus, PollStatus> change)
    {
        PollStatus before, after;
        lock (gate)
        {
            before = status;
            after = status = change(status);
        }
        if (before != after)
        {
            StatusChanged?.Invoke(after);
        }
    }

    void Remember(SelectResult res)
    {
        store.Update(c =>
        {
            if (c.Hub is not null)
            {
                HubIdentities.Remember(c.Hub, res, c.RemoteUrl);
            }
        });
    }

    /// <summary>Makes the loop run now (after settings change, a send...).</summary>
    public void Poke()
    {
        try
        {
            wake.Release();
        }
        catch (SemaphoreFullException)
        {
        }
    }

    /// <summary>Starts polling.</summary>
    public void Start() => loop ??= Task.Run(RunAsync);

    async Task RunAsync()
    {
        var backoff = PollEvery;
        while (!stop.IsCancellationRequested)
        {
            var wait = PollEvery;
            bool ok;
            try
            {
                ok = await TickAsync(stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (stop.IsCancellationRequested)
            {
                break;
            }
            catch (Exception e)
            {
                log.LogWarning(e, "poll");
                ok = false;
            }
            if (ok)
            {
                backoff = PollEvery;
            }
            else
            {
                wait = backoff;
                backoff = TimeSpan.FromTicks(Math.Min(backoff.Ticks * 2, MaxBackoff.Ticks));
            }
            if (Status.RingingFrom is not null)
            {
                wait = PollRinging;
            }
            if (store.Get().Pending)
            {
                wait = JoinRequest.Every; // docs: ask every 2–3 s while waiting to be let in
            }
            try
            {
                await wake.WaitAsync(wait, stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
        Silence();
    }

    /// <summary>One poll. Returns whether the hub answered.</summary>
    public async Task<bool> TickAsync(CancellationToken ct = default)
    {
        var cfg = store.Get();
        var name = HubName(cfg);
        SetStatus(s => s with { Configured = cfg.Registered, Pending = cfg.Pending, PairCode = cfg.PairCode });
        if (string.IsNullOrEmpty(cfg.DeviceToken))
        {
            return true; // nothing to poll until Settings is saved
        }
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(30));
        if (cfg.Hub is null && cfg.Registered && DateTimeOffset.UtcNow - migratedAt > HubIdentities.MigrateRetry && cfg.RemoteUrl.Length > 0)
        {
            migratedAt = DateTimeOffset.UtcNow;
            await MigrateAsync(cfg, cts.Token).ConfigureAwait(false);
        }
        Route r;
        try
        {
            r = await routes.EnsureAsync(cts.Token).ConfigureAwait(false);
        }
        catch (Exception e) when (e is UnreachableException or NotPairedException)
        {
            WarnChanged();
            SetStatus(s => s with { Connected = false, Route = "", Problem = Describe(e, name) });
            return false;
        }
        WarnChanged();
        if (cfg.Pending)
        {
            var state = await setup.PollJoinAsync(cts.Token).ConfigureAwait(false);
            var now = store.Get();
            SetStatus(s => s with { Pending = now.Pending, PairCode = now.PairCode, Configured = now.Registered, Connected = state != JoinState.Unknown });
            if (state == JoinState.Approved)
            {
                services.Notifications?.Show(new Notification { Title = "This PC is now part of droplet", Body = $"{name} let \"{now.DeviceName}\" in.", Tag = "pair" });
                pairWarned = false;
            }
            else if (state == JoinState.Declined)
            {
                services.Notifications?.Show(new Notification
                {
                    Title = "droplet wasn't let in", Tag = "pair",
                    Body = $"{name} declined this PC's request to join, or it expired. Open Settings to ask again.",
                });
            }
            return state != JoinState.Unknown;
        }
        using var c = r.Client(cfg.DeviceToken, cfg.Session);
        FilesListing files;
        try
        {
            files = await c.FilesAsync(cts.Token).ConfigureAwait(false);
        }
        catch (NotAllowedException)
        {
            await NotAllowedAsync(c, name, cts.Token).ConfigureAwait(false);
            return false;
        }
        catch (Exception e) when (e is HubException or HttpRequestException or TaskCanceledException && !ct.IsCancellationRequested)
        {
            if (e is HttpRequestException or TaskCanceledException)
            {
                routes.Lost(r); // choose the route again next time
            }
            SetStatus(s => s with { Connected = false, Problem = Describe(e, name) });
            return false;
        }
        pairWarned = false;
        if (files.Self is null)
        {
            // the hub no longer knows this device (removed from the Devices list)
            SetStatus(s => s with { Connected = true, Problem = "", Configured = false, NotAllowed = false, Devices = files.Devices });
            if (!removedWarned)
            {
                removedWarned = true;
                services.Notifications?.Show(new Notification { Title = "This PC was removed from droplet", Body = "Open Settings to add it again.", Tag = "removed" });
                NeedsPairing?.Invoke();
            }
            return true;
        }
        removedWarned = false;
        SetStatus(s => s with { Connected = true, Problem = "", Configured = true, NotAllowed = false, Devices = files.Devices });
        await InboxAsync(c, cfg, files, cts.Token).ConfigureAwait(false);
        await ChatAsync(c, cfg, files, cts.Token).ConfigureAwait(false);
        await RingAsync(c, cfg, cts.Token).ConfigureAwait(false);
        return true;
    }

    async Task MigrateAsync(AppConfig cfg, CancellationToken ct)
    {
        try
        {
            var h = await HubIdentities.MigrateAsync(cfg.RemoteUrl, routes.Deps, routes.Options, ct).ConfigureAwait(false);
            if (h is null)
            {
                log.LogInformation("hub identity: this hub predates local-first; using {Url} as before", cfg.RemoteUrl);
                return;
            }
            store.Update(n =>
            {
                if (n.Hub is null && n.RemoteUrl == cfg.RemoteUrl)
                {
                    n.Hub = h;
                }
            });
            log.LogInformation("hub identity: {Name} ({Id}), {How}", h.Name, h.Id,
                string.IsNullOrEmpty(h.Fingerprint) ? "no LAN certificate yet: tailnet only"
                : h.PinSource == PinSources.Lan ? "trusted on first use on the LAN" : "checked over " + cfg.RemoteUrl);
            routes.Reset();
        }
        catch (Exception e) when (e is HubException or HttpRequestException)
        {
            log.LogInformation("hub identity: {Error} (using {Url} as before)", e.Message, cfg.RemoteUrl);
        }
    }

    void WarnChanged()
    {
        var changed = routes.Changed;
        SetStatus(s => s with { IdentityChanged = changed is not null });
        if (changed is null || changedWarned == changed.Got)
        {
            return;
        }
        changedWarned = changed.Got;
        var name = HubName(store.Get());
        log.LogWarning("hub identity: {Name} at {Addr} presents certificate {Got}, pinned {Want} (confirmed over the tailnet: {Verified})",
            name, changed.Addr, changed.Got, changed.Want, changed.Verified);
        services.Notifications?.Show(new Notification
        {
            Title = "The hub's identity changed", Tag = "identity",
            Body = changed.Verified
                ? $"{name} has a new certificate, and Tailscale confirms it's the hub's. Open Settings and choose Re-pair to use it on Wi-Fi again."
                : $"Something on your network says it's {name} but has a different certificate, so droplet won't use it. If you reset the hub, open Settings and choose Re-pair.",
        });
        NeedsPairing?.Invoke();
    }

    async Task NotAllowedAsync(HubClient c, string name, CancellationToken ct)
    {
        Me? me = null;
        try
        {
            me = await c.MeAsync(ct).ConfigureAwait(false);
        }
        catch (Exception e) when (e is HubException or HttpRequestException)
        {
        }
        if (me?.Device is { Pending: true } d)
        {
            store.Update(n => (n.PairPending, n.PairCode) = (true, d.Code));
            SetStatus(s => s with { Connected = true, Problem = "", Configured = false, Pending = true, PairCode = d.Code });
            NeedPairing("Waiting to be let in to " + name, $"On one of your devices, allow \"{d.Name}\" and check the code is {d.Code}.");
            return;
        }
        SetStatus(s => s with { Connected = false, Configured = false, NotAllowed = true, Problem = name + " doesn't let this PC in" });
        NeedPairing("droplet isn't let in to " + name,
            "This PC was removed from the hub, or its request to join was declined. Open Settings to pair it again.");
    }

    void NeedPairing(string title, string body)
    {
        if (pairWarned)
        {
            return;
        }
        pairWarned = true;
        services.Notifications?.Show(new Notification { Title = title, Body = body, Tag = "pair" });
        NeedsPairing?.Invoke();
    }

    /// <summary>The hub's short name, e.g. "t15".</summary>
    public static string HubName(AppConfig cfg)
    {
        ArgumentNullException.ThrowIfNull(cfg);
        if (!string.IsNullOrEmpty(cfg.Hub?.Name))
        {
            return cfg.Hub.Name;
        }
        try
        {
            using var c = new HubClient(cfg.RemoteUrl);
            return c.HubName;
        }
        catch (FormatException)
        {
            return "the hub";
        }
    }

    static string Describe(Exception e, string hub) => e switch
    {
        UnreachableException { Changed: not null } or PinMismatchException => $"the identity of {hub} changed (open Settings)",
        NotPairedException => "not set up yet (open Settings)",
        PinRequiredException => $"{hub} wants a PIN (open Settings)",
        TaskCanceledException or TimeoutException => $"can't reach {hub} (timed out)",
        _ => "can't reach " + hub,
    };

    // --- inbox ------------------------------------------------------------------------------

    async Task InboxAsync(HubClient c, AppConfig cfg, FilesListing files, CancellationToken ct)
    {
        var now = DateTimeOffset.UtcNow;
        var (fresh, seen) = NewSince.Inbox(files.Inbox, cfg.InboxSeen, now);
        var saved = new Dictionary<string, string>();
        if (cfg.AutoDownload)
        {
            foreach (var f in files.Inbox.Where(f => NewSince.Complete(f, now)))
            {
                string path;
                try
                {
                    path = await SaveAsync(c, cfg.DownloadDir, f.Name, ct).ConfigureAwait(false);
                }
                catch (Exception e) when (e is HubException or HttpRequestException or IOException or UnauthorizedAccessException)
                {
                    log.LogWarning("download {Name}: {Error}", f.Name, e.Message);
                    continue;
                }
                saved[NewSince.InboxKey(f)] = path;
                FileSaved?.Invoke(path);
                // only once it's safely on disk
                try
                {
                    await c.DeleteInboxAsync(f.Name, ct).ConfigureAwait(false);
                }
                catch (HubNotFoundException)
                {
                }
                catch (Exception e) when (e is HubException or HttpRequestException)
                {
                    log.LogWarning("delete {Name} from the hub's inbox: {Error}", f.Name, e.Message);
                }
            }
        }
        if (!seen.SequenceEqual(cfg.InboxSeen))
        {
            store.Update(n => n.InboxSeen = seen);
        }
        if (fresh.Count == 0 || cfg.Paused || !cfg.NotifyFiles)
        {
            return;
        }
        foreach (var group in fresh.GroupBy(f => string.IsNullOrEmpty(f.From) ? "Someone" : f.From))
        {
            services.Notifications?.Show(FileNotification(group.Key, group.ToList(), saved, cfg));
        }
    }

    /// <summary>
    /// Downloads an inbox file under a unique, safe name. The data goes to a hidden
    /// temporary file first, so a half-finished download never looks complete.
    /// </summary>
    public static async Task<string> SaveAsync(HubClient c, string dir, string name, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(c);
        Directory.CreateDirectory(dir);
        var tmp = Path.Combine(dir, $".droplet-{Guid.NewGuid():N}.part");
        try
        {
            await using (var f = new FileStream(tmp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 256 * 1024, true))
            {
                await c.DownloadAsync(name, f, ct: ct).ConfigureAwait(false);
            }
            while (true)
            {
                var dest = SafeName.Unique(dir, SafeName.Of(name));
                try
                {
                    File.Move(tmp, dest, overwrite: false);
                    return dest;
                }
                catch (IOException) when (File.Exists(dest))
                {
                    // taken meanwhile: the next name
                }
            }
        }
        finally
        {
            File.Delete(tmp);
        }
    }

    static Notification FileNotification(string from, List<HubFile> fs, Dictionary<string, string> saved, AppConfig cfg)
    {
        string title, body;
        if (fs.Count == 1)
        {
            (title, body) = ($"{from} sent {fs[0].Name}", "");
        }
        else
        {
            var names = fs.Take(3).Select(f => f.Name).ToList();
            if (fs.Count > 3)
            {
                names.Add($"+{fs.Count - 3} more");
            }
            (title, body) = ($"{from} sent {fs.Count} files", string.Join(", ", names));
        }
        var paths = fs.Select(f => saved.GetValueOrDefault(NewSince.InboxKey(f))).OfType<string>().ToList();
        if (paths.Count == 1 && fs.Count == 1)
        {
            return new Notification
            {
                Title = title, Body = $"{HumanSize(fs[0].Size)} · saved to {ShortDir(cfg.DownloadDir)}", Tag = "files-" + Guid.NewGuid().ToString("N"),
                Click = new NotificationAction("Show", NotificationActionKind.ShowInFolder, paths[0]),
                Buttons = [new("Open", NotificationActionKind.OpenFile, paths[0]), new("Show in folder", NotificationActionKind.ShowInFolder, paths[0])],
            };
        }
        if (paths.Count > 0)
        {
            return new Notification
            {
                Title = title, Body = body + "\nSaved to " + ShortDir(cfg.DownloadDir), Tag = "files-" + Guid.NewGuid().ToString("N"),
                Click = new NotificationAction("Open folder", NotificationActionKind.OpenFolder, cfg.DownloadDir),
                Buttons = [new("Open folder", NotificationActionKind.OpenFolder, cfg.DownloadDir)],
            };
        }
        return new Notification
        {
            Title = title, Body = (body.Length == 0 ? HumanSize(fs[0].Size) : body) + " · in your droplet inbox", Tag = "files-" + Guid.NewGuid().ToString("N"),
        };
    }

    static string ShortDir(string dir)
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var rel = string.IsNullOrEmpty(home) ? dir : Path.GetRelativePath(home, dir);
        return rel.StartsWith("..", StringComparison.Ordinal) || Path.IsPathRooted(rel) ? dir : rel;
    }

    /// <summary>A size as people read it: "1.4 MB".</summary>
    public static string HumanSize(long n)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double f = n;
        var i = 0;
        while (f >= 1024 && i < units.Length - 1)
        {
            f /= 1024;
            i++;
        }
        return i == 0 ? $"{n} B" : string.Create(CultureInfo.InvariantCulture, $"{f:0.#} {units[i]}");
    }

    // --- chat -------------------------------------------------------------------------------

    [GeneratedRegex(@"^https?://\S+$")]
    private static partial Regex BareUrl();

    async Task ChatAsync(HubClient c, AppConfig cfg, FilesListing files, CancellationToken ct)
    {
        // reading a thread marks it read on the hub, so leave the unread badges alone for
        // the web app when nothing's going to be shown anyway
        if (cfg.Paused || !cfg.NotifyMessages)
        {
            return;
        }
        var names = files.Devices.ToDictionary(d => d.Id, d => d.Name);
        foreach (var (from, count) in files.Unread)
        {
            if (count <= 0)
            {
                continue;
            }
            List<ChatMessage> thread;
            try
            {
                thread = await c.ChatAsync(from, ct).ConfigureAwait(false);
            }
            catch (Exception e) when (e is HubException or HttpRequestException)
            {
                log.LogWarning("chat with {From}: {Error}", from, e.Message);
                continue;
            }
            var (fresh, seen) = NewSince.Messages(thread, from, count, cfg.ChatSeen.GetValueOrDefault(from));
            if (seen != cfg.ChatSeen.GetValueOrDefault(from))
            {
                store.Update(n => n.ChatSeen[from] = seen);
            }
            if (fresh.Count == 0)
            {
                continue;
            }
            var last = fresh[^1];
            var body = fresh.Count > 1 ? $"{last.Text}\n(+{fresh.Count - 1} earlier)" : last.Text;
            var link = last.Text.Trim();
            services.Notifications?.Show(new Notification
            {
                Title = names.GetValueOrDefault(from) ?? "Someone", Body = body, Tag = "chat-" + from,
                // a bare link opens straight away, as it does on the phone
                Click = BareUrl().IsMatch(link) ? new NotificationAction("Open link", NotificationActionKind.OpenUrl, link) : null,
                Buttons = BareUrl().IsMatch(link) ? [new NotificationAction("Open link", NotificationActionKind.OpenUrl, link)] : [],
            });
        }
    }

    // --- ringing ----------------------------------------------------------------------------

    async Task RingAsync(HubClient c, AppConfig cfg, CancellationToken ct)
    {
        if (!ringSupported && DateTimeOffset.UtcNow - ringChecked < RingRecheck)
        {
            return;
        }
        Ring? ring;
        try
        {
            ring = await c.ActiveRingAsync(ct).ConfigureAwait(false);
            (ringChecked, ringSupported) = (DateTimeOffset.UtcNow, true);
        }
        catch (RingUnsupportedException)
        {
            (ringChecked, ringSupported) = (DateTimeOffset.UtcNow, false);
            return;
        }
        catch (Exception e) when (e is HubException or HttpRequestException)
        {
            log.LogInformation("ring check: {Error}", e.Message);
            return;
        }
        if (ringId is not null && (ring is null || ring.Id != ringId))
        {
            Silence(); // stopped elsewhere (or replaced; picked up next poll)
        }
        else if (ringId is not null && DateTimeOffset.UtcNow - ringStarted > RingLimit)
        {
            await StopRingAsync(ct).ConfigureAwait(false); // rings give up after a minute, like a phone
        }
        else if (ringId is null && ring is not null && ring.Id != stoppedRingId)
        {
            StartRing(ring, cfg);
        }
        if (ring is null)
        {
            stoppedRingId = null;
        }
    }

    void StartRing(Ring ring, AppConfig cfg)
    {
        var from = string.IsNullOrEmpty(ring.From) ? "Someone" : ring.From;
        (ringId, ringStarted) = (ring.Id, DateTimeOffset.UtcNow);
        if (cfg.RingSound)
        {
            services.Sound?.StartRing();
        }
        services.Notifications?.Show(new Notification
        {
            Title = $"{from} is ringing this PC", Body = "Found it? Press Stop.", Tag = "ring", Urgent = true,
            Click = new NotificationAction("Stop", NotificationActionKind.StopRing),
            Buttons = [new NotificationAction("Stop", NotificationActionKind.StopRing)],
        });
        SetStatus(s => s with { RingingFrom = from });
    }

    /// <summary>Stops the sound and the ringing state here.</summary>
    void Silence()
    {
        if (ringId is null)
        {
            return;
        }
        ringId = null;
        services.Sound?.StopRing();
        services.Notifications?.Clear("ring");
        SetStatus(s => s with { RingingFrom = null });
    }

    /// <summary>Silences this PC and tells the hub the ring is answered.</summary>
    public async Task StopRingAsync(CancellationToken ct = default)
    {
        if (ringId is not null)
        {
            stoppedRingId = ringId;
        }
        Silence();
        var cfg = store.Get();
        try
        {
            var r = await routes.EnsureAsync(ct).ConfigureAwait(false);
            using var c = r.Client(cfg.DeviceToken, cfg.Session);
            await c.StopRingAsync(ct).ConfigureAwait(false);
        }
        catch (Exception e) when (e is HubException or HttpRequestException or UnreachableException or NotPairedException)
        {
            if (e is not RingUnsupportedException)
            {
                log.LogInformation("stop ring: {Error}", e.Message);
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
