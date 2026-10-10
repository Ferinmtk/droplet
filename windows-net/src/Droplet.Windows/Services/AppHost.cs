using System.IO;
using System.Text.Json.Nodes;
using System.Windows.Threading;
using Droplet.Core;
using Droplet.Core.Config;
using Droplet.Core.Mesh;
using Droplet.Core.Platform;
using Droplet.Core.Remote;
using Droplet.Windows.Platform;
using Droplet.Windows.Shell;
using Droplet.Windows.Tray;
using Microsoft.Extensions.Logging;

namespace Droplet.Windows.Services;

/// <summary>
/// The running app: the platform services (which outlive the engine), the engine (restarted
/// when a mesh setting changes), and what the tray and the windows show, kept up to date on
/// the UI thread. Everything the menus and windows do goes through here.
/// </summary>
internal sealed class AppHost : IAsyncDisposable
{
    static readonly TimeSpan ControlledFor = TimeSpan.FromMinutes(2);
    static readonly TimeSpan InputFor = TimeSpan.FromSeconds(3);

    readonly Dispatcher ui;
    readonly ILoggerFactory logs;
    readonly ILogger log;
    readonly DispatcherTimer tick;
    int refreshQueued;
    string? meshRingFrom;
    string? controlledBy;
    DateTimeOffset controlledAt, inputAt;
    string sendToKey = "";

    public AppHost(Dispatcher ui, ILoggerFactory logs, AppPaths paths, Func<nint> clipboardOwner)
    {
        this.ui = ui;
        this.logs = logs;
        Paths = paths;
        log = logs.CreateLogger("droplet.app");
        Input = new SendInputService();
        Ring = new RingService(logs.CreateLogger("droplet.ring"));
        Toasts = new ToastService(() => Engine?.Store.Get().ActionKey ?? "", Wanted, logs.CreateLogger("droplet.toast"));
        Media = new MediaService(Input.InjectStrokes, logs.CreateLogger("droplet.media"));
        Services = new PlatformServices
        {
            Input = Input,
            Media = Media,
            Clipboard = new ClipboardService(ui, clipboardOwner),
            Notifications = Toasts,
            Screenshot = new ScreenshotService(),
            Lock = new LockService(),
            Sound = Ring,
            Browser = new BrowserService(log),
        };
        Ring.RingingChanged += _ => Refresh();
        tick = new DispatcherTimer(TimeSpan.FromSeconds(2), DispatcherPriority.Background, (_, _) => Refresh(), ui);
        // progress: at most 4 updates a second, and none while nothing moves
        transferTick = new DispatcherTimer(TimeSpan.FromMilliseconds(250), DispatcherPriority.Background, (_, _) => TransferTick(), ui);
    }

    readonly DispatcherTimer transferTick;
    int transfersMoved;

    void OnTransfer()
    {
        if (Interlocked.Exchange(ref transfersMoved, 1) == 0)
        {
            ui.BeginInvoke(() =>
            {
                if (!transferTick.IsEnabled)
                {
                    transferTick.Start();
                }
            });
        }
    }

    void TransferTick()
    {
        if (Interlocked.Exchange(ref transfersMoved, 0) == 1)
        {
            TransfersChanged?.Invoke();
        }
        else
        {
            transferTick.Stop();
        }
    }

    /// <summary>Raised on the UI thread (at most 4 times a second) while files are on their way.</summary>
    public event Action? TransfersChanged;

    /// <summary>Where droplet keeps its files.</summary>
    public AppPaths Paths { get; }

    /// <summary>What the desktop can do.</summary>
    public PlatformServices Services { get; }

    public SendInputService Input { get; }

    public RingService Ring { get; }

    public ToastService Toasts { get; }

    public MediaService Media { get; }

    /// <summary>The engine; null while starting or restarting.</summary>
    public DropletEngine? Engine { get; private set; }

    /// <summary>The path Explorer, Start with Windows and Send To start droplet with.</summary>
    public static string ExePath => Packaging.IsPackaged
        ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Microsoft", "WindowsApps", "droplet.exe")
        : Environment.ProcessPath ?? "droplet.exe";

    /// <summary>The config (a copy).</summary>
    public AppConfig Config => Engine?.Store.Get() ?? ConfigStore.Defaults();

    /// <summary>Everything to send to, the hub first.</summary>
    public IReadOnlyList<Destination> Destinations { get; private set; } = [];

    /// <summary>What the tray shows.</summary>
    public TrayState Tray { get; private set; } = new();

    /// <summary>Raised on the UI thread when anything shown may have changed.</summary>
    public event Action? Changed;

    /// <summary>Raised on the UI thread when the person has to act in Settings (the hub stopped letting this PC in, its identity changed).</summary>
    public event Action? NeedsSettings;

    /// <summary>Raised on the UI thread when a chat message arrives or leaves: (peer fingerprint or hub device id).</summary>
    public event Action<string>? ChatChanged;

    /// <summary>Raised on the UI thread when a device asks to pair.</summary>
    public event Action<PairRequestInfo>? PairingRequested;

    /// <summary>
    /// Asks the person "Is &lt;name&gt; your device, or someone else's?" (docs/mesh.md §9.9):
    /// <see cref="Perms.Own"/>, <see cref="Perms.Other"/>, or null when they cancel. Unset,
    /// nobody can be asked, and a device is your own, as before.
    /// </summary>
    public Func<string, string?>? AskRelation { get; set; }

    /// <summary>Starts the engine.</summary>
    public async Task StartAsync()
    {
        var engine = await DropletEngine.StartAsync(new EngineOptions
        {
            Paths = Paths,
            Services = Services,
            IdentityStore = new ProtectedIdentityStore(Paths.MeshConfigDir, new DpapiProtector()),
            App = "droplet-windows/" + (typeof(AppHost).Assembly.GetName().Version?.ToString(3) ?? "2"),
            LoggerFactory = logs,
        });
        Attach(engine);
        Engine = engine;
        await SyncWindowsIntegrationAsync(startup: true);
        tick.Start();
        Refresh();
    }

    /// <summary>Stops the engine and starts it again, for settings it reads only at start (the mesh, the download folder).</summary>
    public async Task RestartAsync()
    {
        if (Engine is not { } old)
        {
            return;
        }
        Engine = null;
        Refresh();
        await old.DisposeAsync();
        await StartAsync();
    }

    void Attach(DropletEngine e)
    {
        e.Poller.StatusChanged += _ => Refresh();
        e.Poller.NeedsPairing += () => ui.BeginInvoke(() => NeedsSettings?.Invoke());
        e.Poller.FileSaved += path => Files.MarkFromInternet(path, log);
        e.Live.StatusChanged += _ => Refresh();
        e.Live.RosterChanged += Refresh;
        e.Routes.RouteChanged += _ => Refresh();
        e.Store.Changed += _ => Refresh();
        e.Dispatcher.Activity += OnActivity;
        if (e.Mesh is { } m)
        {
            m.FileReceived += (_, path) =>
            {
                Files.MarkFromInternet(path, log);
                Refresh();
            };
            m.TextReceived += c => ui.BeginInvoke(() => ChatChanged?.Invoke(c.Fp));
            m.JobFinished += j =>
            {
                if (j.Kind == "text")
                {
                    ui.BeginInvoke(() => ChatChanged?.Invoke(j.Fp));
                }
                Refresh();
            };
            m.PairingRequested += r => ui.BeginInvoke(() =>
            {
                PairingRequested?.Invoke(r);
                Refresh();
            });
            m.Paired += _ => Refresh();
            m.SharingChanged += _ => Refresh();
            m.Rung += from =>
            {
                meshRingFrom = from;
                Refresh();
            };
            m.Trust.CertificatesChanged += Refresh;
            m.Transfers.Changed += _ => OnTransfer();
        }
    }

    void OnActivity(RemoteActivity a)
    {
        var now = DateTimeOffset.UtcNow;
        (controlledBy, controlledAt) = (a.From, now);
        if (a.Input)
        {
            var was = now - inputAt < InputFor;
            inputAt = now;
            if (was)
            {
                return; // the icon is amber already; the timer turns it back
            }
        }
        Refresh();
    }

    /// <summary>Whether to show a notification, by the settings.</summary>
    bool Wanted(Notification n)
    {
        if (Engine is not { } e)
        {
            return true;
        }
        var cfg = e.Store.Get();
        var tag = n.Tag ?? "";
        if (tag.StartsWith(Core.Mesh.PhoneNotifications.TagPrefix, StringComparison.Ordinal))
        {
            return !cfg.Paused && cfg.PhoneNotifications;
        }
        if (tag.StartsWith("chat-", StringComparison.Ordinal) || n.App is not null)
        {
            return !cfg.Paused && cfg.NotifyMessages;
        }
        if (tag.StartsWith("file-", StringComparison.Ordinal))
        {
            return !cfg.Paused && cfg.NotifyFiles;
        }
        return true;
    }

    /// <summary>Asks for the shown state to be rebuilt, on the UI thread, soon (calls are merged).</summary>
    public void Refresh()
    {
        if (Interlocked.Exchange(ref refreshQueued, 1) == 0)
        {
            ui.BeginInvoke(DispatcherPriority.Background, () =>
            {
                Volatile.Write(ref refreshQueued, 0);
                Rebuild();
                Changed?.Invoke();
            });
        }
    }

    void Rebuild()
    {
        if (Engine is not { } e)
        {
            Tray = Tray with { Polled = false };
            return;
        }
        var cfg = e.Store.Get();
        var poll = e.Poller.Status;
        var now = DateTimeOffset.UtcNow;
        var hubName = Core.Polling.HubPoller.HubName(cfg);
        var peers = new List<PeerView>();
        var linked = 0;
        if (e.Mesh is { } m)
        {
            var nearby = m.Nearby.Select(s => s.Fp).ToHashSet();
            foreach (var t in m.Trust.All())
            {
                var link = m.OpenLink(t.Fp);
                linked += link is null ? 0 : 1;
                peers.Add(new PeerView(t, link?.Kind, nearby.Contains(t.Fp), m.RemotePermOf(t.Fp), m.LastRefusal(t.Fp), m.FeaturesOf(t.Fp)));
            }
        }
        if (!Ring.Ringing)
        {
            meshRingFrom = null;
        }
        Destinations = global::Droplet.Windows.Services.Destinations.Build(peers, poll.Devices, cfg.Hub?.Id, hubName, cfg.Registered, poll.Connected);
        Tray = new TrayState
        {
            Configured = cfg.Registered,
            PendingCode = cfg.Pending ? cfg.PairCode ?? "…" : null,
            NotAllowed = poll.NotAllowed,
            HubName = hubName,
            Connected = poll.Connected,
            Polled = poll.Connected || poll.Problem.Length > 0 || !cfg.Registered,
            Route = poll.Route,
            Problem = poll.Problem,
            IdentityChanged = poll.IdentityChanged,
            NotificationsPaused = cfg.Paused,
            RemotePaused = cfg.RemotePaused,
            ControlledBy = controlledBy is not null && now - controlledAt < ControlledFor ? controlledBy : null,
            InputNow = now - inputAt < InputFor,
            RingingFrom = poll.RingingFrom ?? meshRingFrom,
            PeersLinked = linked,
            Peers = peers.Count,
            PausedAll = e.Mesh?.PausedAll ?? cfg.Mesh.Paused,
        };
        SyncSendTo(cfg);
    }

    // --- Windows integration: Start with Windows, Send To ------------------------------------------

    async Task SyncWindowsIntegrationAsync(bool startup)
    {
        if (Engine is not { } e)
        {
            return;
        }
        var cfg = e.Store.Get();
        try
        {
            if (Packaging.IsPackaged)
            {
                // the person may have turned it off in Task Manager or Settings → Apps → Startup
                if (await Autostart.IsEnabledAsync() is { } on && on != cfg.Autostart)
                {
                    e.Store.Update(c => c.Autostart = on);
                }
            }
            else if (startup && cfg.Autostart)
            {
                Autostart.SetRunKey(ExePath); // the exe may have moved
            }
        }
        catch (Exception ex)
        {
            log.LogWarning("start with Windows: {Error}", ex.Message);
        }
    }

    /// <summary>Turns Start with Windows on or off. Returns a problem to show, or null.</summary>
    public async Task<string?> SetAutostartAsync(bool on)
    {
        if (Engine is not { } e)
        {
            return "droplet is still starting.";
        }
        string? problem;
        try
        {
            problem = await Autostart.SetAsync(on, ExePath);
        }
        catch (Exception ex)
        {
            problem = "Couldn't change it: " + ex.Message;
        }
        var now = problem is null ? on : on && await Autostart.IsEnabledAsync() == true;
        e.Store.Update(c => c.Autostart = now);
        return problem;
    }

    /// <summary>Whether Send To can be offered: a package can't write Explorer's Send To folder (its writes to AppData are its own).</summary>
    public static bool SendToAvailable => !Packaging.IsPackaged;

    void SyncSendTo(AppConfig cfg)
    {
        if (!SendToAvailable)
        {
            return;
        }
        var targets = cfg.SendTo ? Destinations.Select(d => new SendToTarget(d.Key, d.IsHub ? "Hub" : d.Name)).ToList() : [];
        var key = string.Join("\n", targets.Select(t => t.Key + "=" + t.Name));
        if (key == sendToKey)
        {
            return;
        }
        sendToKey = key;
        var exe = cfg.SendTo ? ExePath : null;
        _ = Task.Run(() =>
        {
            foreach (var err in SendTo.Sync(exe, targets))
            {
                log.LogWarning("send to: {Error}", err);
            }
        });
    }

    // --- sending ------------------------------------------------------------------------------------

    DropletEngine Running => Engine ?? throw new InvalidOperationException("droplet is still starting");

    void Notify(string title, string body = "", string tag = "send") =>
        Toasts.Show(new Notification { Title = title, Body = body, Tag = tag });

    /// <summary>Sends files. Mesh peers get them directly (or through the hub, or later); hub devices through the hub.</summary>
    public async Task SendFilesAsync(Destination d, IReadOnlyList<string> paths)
    {
        ArgumentNullException.ThrowIfNull(d);
        var files = paths.Where(File.Exists).Select(Path.GetFullPath).ToList();
        var skipped = paths.Count - files.Count;
        if (files.Count == 0)
        {
            Notify("Nothing sent", "Only files can be sent, not folders.");
            return;
        }
        var what = files.Count == 1 ? Path.GetFileName(files[0]) : $"{files.Count} files";
        var e = Running;
        try
        {
            if (d.Fp is { } fp && e.Mesh is { } m)
            {
                var jobs = files.Select(f => m.SendFile(fp, f)).ToList();
                var results = await Task.WhenAll(jobs.Select(j => m.WaitJobAsync(j.Id, TimeSpan.FromMinutes(30))));
                var failed = results.FirstOrDefault(r => r?.State == JobState.Failed);
                var held = results.FirstOrDefault(r => r?.Error?.StartsWith(Waiting, StringComparison.Ordinal) == true);
                if (failed is not null)
                {
                    Notify($"Couldn't send {what} to {d.Name}", failed.Error ?? "");
                }
                else if (held is not null)
                {
                    // paused, here or there: it goes on the resume
                    Notify($"{what} will go to {d.Name}", $"{Sentence(held.Error![Waiting.Length..])}. It goes when sharing resumes.");
                }
                else if (results.All(r => r?.State == JobState.Done))
                {
                    Notify($"Sent {what} to {d.Name}", results[0]?.Route is { } r ? $"({RouteWords(r)})" : "");
                }
                else
                {
                    Notify($"{what} will go to {d.Name}", $"{d.Name} isn't reachable now; droplet sends it when it is.");
                }
            }
            else
            {
                var to = d.IsHub ? "hub" : d.HubDeviceId ?? throw new InvalidOperationException($"{d.Name} can't be reached");
                Notify($"Sending {what} to {d.Name}…");
                foreach (var f in files)
                {
                    await e.Bridge.HubUploadAsync(to, f, Path.GetFileName(f), MimeTypes.Of(f), CancellationToken.None);
                }
                Notify($"Sent {what} to {d.Name}", skipped > 0 ? $"{skipped} folder(s) left out." : "");
            }
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            log.LogWarning("send to {Who}: {Error}", d.Name, ex.Message);
            Notify($"Couldn't send {what} to {d.Name}", ex.Message);
        }
    }

    const string Waiting = "waiting: ";

    static string Sentence(string s) => s.Length == 0 ? s : char.ToUpperInvariant(s[0]) + s[1..];

    static string RouteWords(string route) => route switch
    {
        "lan" => "on Wi-Fi",
        "tailnet" => "over Tailscale",
        "hub" => "through the hub",
        "mailbox" => "left with the hub",
        _ => route,
    };

    /// <summary>Sends a chat message.</summary>
    public async Task SendTextAsync(Destination d, string text)
    {
        ArgumentNullException.ThrowIfNull(d);
        var e = Running;
        if (d.Fp is { } fp && e.Mesh is { } m)
        {
            var job = m.SendText(fp, text);
            ChatChanged?.Invoke(fp);
            var done = await m.WaitJobAsync(job.Id, TimeSpan.FromMinutes(1));
            if (done?.State == JobState.Failed)
            {
                throw new InvalidOperationException(done.Error ?? "not delivered");
            }
            return;
        }
        var to = d.IsHub ? "hub" : d.HubDeviceId ?? throw new InvalidOperationException($"{d.Name} can't be reached");
        await e.Bridge.HubTextAsync(to, text, CancellationToken.None);
        ChatChanged?.Invoke(to);
    }

    /// <summary>Makes a device (or the hub) ring.</summary>
    public async Task RingAsync(Destination d)
    {
        ArgumentNullException.ThrowIfNull(d);
        var e = Running;
        try
        {
            if (d.Fp is { } fp && e.Mesh is { } m)
            {
                await m.RingAsync(fp);
            }
            else
            {
                await e.Bridge.HubRingAsync(d.IsHub ? "hub" : d.HubDeviceId!, false, CancellationToken.None);
            }
            Notify($"Ringing {d.Name}", "It stops after a minute, or when someone answers.", "ring-out");
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            Notify($"Couldn't ring {d.Name}", ex.Message, "ring-out");
        }
    }

    /// <summary>What's on the clipboard, read on the UI thread.</summary>
    public sealed record Clip(string? Text, IReadOnlyList<string> Files, byte[]? Png);

    /// <summary>Sends the clipboard: copied files, an image, or text (onto their clipboard when that works, else as a message).</summary>
    public async Task SendClipboardAsync(Destination d, Clip clip)
    {
        ArgumentNullException.ThrowIfNull(d);
        ArgumentNullException.ThrowIfNull(clip);
        var e = Running;
        if (clip.Files.Count > 0)
        {
            await SendFilesAsync(d, clip.Files);
            return;
        }
        try
        {
            if (clip.Png is { } png)
            {
                var name = $"clipboard-{DateTime.Now:yyyyMMdd-HHmmss}.png";
                if (d.Fp is { } fp && e.Mesh is { } m)
                {
                    m.SendBytes(fp, name, png, "image/png");
                }
                else
                {
                    var tmp = Path.Combine(Path.GetTempPath(), "droplet-" + Guid.NewGuid().ToString("N"));
                    Directory.CreateDirectory(tmp);
                    try
                    {
                        var p = Path.Combine(tmp, name);
                        await File.WriteAllBytesAsync(p, png);
                        await e.Bridge.HubUploadAsync(d.IsHub ? "hub" : d.HubDeviceId!, p, name, "image/png", CancellationToken.None);
                    }
                    finally
                    {
                        Directory.Delete(tmp, true);
                    }
                }
                Notify($"Sent the copied image to {d.Name}", "", "clip");
                return;
            }
            if (string.IsNullOrEmpty(clip.Text))
            {
                Notify("Nothing sent", "The clipboard has no text, image or files.", "clip");
                return;
            }
            var preview = string.Join(' ', clip.Text.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
            if (d.Fp is { } to && e.Mesh is { } guard)
            {
                // text from the clipboard is the clipboard, even when it would go as a message:
                // not to a device it's switched off for, or that doesn't take it from this PC
                guard.MaySend(to, new System.Text.Json.Nodes.JsonObject { ["t"] = "clip" });
            }
            if (d.Fp is { } peer && e.Mesh is { } mesh && d.Caps.Contains(Caps.Clipboard))
            {
                try
                {
                    await mesh.ClipAsync(peer, clip.Text);
                    Notify($"Copied to {d.Name}'s clipboard", preview, "clip");
                    return;
                }
                catch (NoRouteException)
                {
                    // not reachable live: a message waits for it instead
                }
            }
            await SendTextAsync(d, clip.Text);
            Notify($"Sent the clipboard to {d.Name}", preview, "clip");
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            Notify($"Couldn't send the clipboard to {d.Name}", ex.Message, "clip");
        }
    }

    /// <summary>Silences this PC, whoever rang it.</summary>
    public async Task StopRingAsync()
    {
        Ring.StopRing();
        Toasts.Clear("droplet-ring");
        Toasts.Clear("ring");
        meshRingFrom = null;
        if (Engine is { } e)
        {
            try
            {
                await e.Poller.StopRingAsync();
            }
            catch (Exception ex) when (ex is not OutOfMemoryException)
            {
                log.LogInformation("stop ring: {Error}", ex.Message);
            }
        }
        Refresh();
    }

    /// <summary>Pauses (or resumes) all remote control; a drag in progress is let go.</summary>
    public void SetRemotePaused(bool paused)
    {
        var e = Running;
        e.Store.Update(c => c.RemotePaused = paused);
        if (paused)
        {
            e.Dispatcher.ReleaseInput();
            controlledBy = null;
        }
    }

    /// <summary>Pauses (or resumes) file and message notifications.</summary>
    public void SetNotificationsPaused(bool paused) => Running.Store.Update(c => c.Paused = paused);

    // --- sharing: per-device permissions and Pause (docs/mesh.md §9.9) ----------------------------

    /// <summary>Pause everything is on: nothing is shared with any device.</summary>
    public bool PausedAll => Engine is { } e ? e.Mesh?.PausedAll ?? e.Bridge.PausedEverything : false;

    /// <summary>Pauses (or resumes) sharing with every device at once; linked devices are told.</summary>
    public void SetPausedAll(bool on)
    {
        var e = Running;
        if (e.Mesh is { } m)
        {
            m.PauseEverything(on);
        }
        else
        {
            e.Bridge.SetPausedEverything(on);
        }
        Notify(on ? "Everything is paused" : "Everything is resumed",
            on ? "Nothing is shared with any device until you resume." : "Sharing again.", "pause");
        Refresh();
    }

    /// <summary>Pauses (or resumes) sharing with one device; it's told if it's linked.</summary>
    public void SetPeerPaused(Destination d, bool on)
    {
        ArgumentNullException.ThrowIfNull(d);
        if (d.Fp is not { } fp || Running.Mesh is not { } m)
        {
            return;
        }
        try
        {
            m.SetPerms(fp, paused: on);
            Notify(on ? $"Paused {d.Name}" : $"Resumed {d.Name}",
                on ? "Nothing goes to it or comes from it until you resume it." : "Sharing with it again.", "pause");
        }
        catch (ArgumentException ex)
        {
            Notify($"Couldn't {(on ? "pause" : "resume")} {d.Name}", ex.Message, "pause");
        }
        Refresh();
    }

    /// <summary>Changes and reads one device's permissions, for the Permissions window.</summary>
    public IPermsEditor PermsOf(string fp) => new PeerPerms(this, fp);

    sealed class PeerPerms(AppHost host, string fp) : IPermsEditor
    {
        public PermsView? Current()
        {
            if (host.Engine?.Mesh is not { } m || m.Trust.Get(fp) is not { } e)
            {
                return null;
            }
            return new PermsView(e.Name, e.Relation, Perms.CleanAllow(e.Allow, e.Relation), e.Paused, m.RemotePermOf(fp), m.PausedAll);
        }

        public void Set(string? relation = null, IReadOnlyDictionary<string, bool>? allow = null, bool? paused = null)
        {
            var m = host.Running.Mesh ?? throw new InvalidOperationException("Direct connections are switched off in Settings.");
            m.SetPerms(fp, relation, allow, paused);
            host.Refresh();
        }
    }

    // --- progress and Cancel, links, names, several devices (docs/mesh.md §9.10) ---------------------

    /// <summary>
    /// Files on their way to or from a device (every device with null), the moving ones first,
    /// then files still waiting in the outbox, then the ones that ended in the last two minutes.
    /// </summary>
    public List<TransferRow> TransferRows(string? fp = null)
    {
        if (Engine?.Mesh is not { } m)
        {
            return [];
        }
        var list = m.Transfers.List().Where(t => fp is null || t.Fp == fp).ToList();
        var known = list.Select(t => t.Id).ToHashSet();
        var rows = list.Select(t => TransferRow.Of(t, Label(m, t.Fp, t.Peer))).ToList();
        // queued and not started: it waits for the device (or a resume)
        var waiting = m.Outbox.Queued().Where(j => j.Kind == "file" && (fp is null || j.Fp == fp) && !known.Contains(j.Id))
            .Select(j => TransferRow.Queued(j, Label(m, j.Fp, j.Peer)));
        var active = rows.Where(r => r.Active).ToList();
        return [.. active, .. waiting, .. rows.Where(r => !r.Active)];
    }

    static string Label(MeshNode m, string fp, string fallback) => m.Trust.Get(fp)?.Label ?? fallback;

    /// <summary>Cancels a transfer (or a file still waiting): the other device is told.</summary>
    public async Task CancelTransferAsync(string id)
    {
        if (Engine?.Mesh is not { } m)
        {
            return;
        }
        try
        {
            await m.CancelAsync(id);
        }
        catch (ArgumentException ex)
        {
            Notify("Couldn't cancel it", AskSentence(ex.Message), "cancel");
        }
        Refresh();
        TransfersChanged?.Invoke();
    }

    static string AskSentence(string s) => Views.AskWindow.Sentence(s);

    /// <summary>Sends a web link: opened there if it's your own device, an Open button if not; as a message to an older device.</summary>
    public async Task SendLinkAsync(Destination d, string url)
    {
        ArgumentNullException.ThrowIfNull(d);
        var r = await LinkToAsync(d, url);
        Notify(r.Result switch
        {
            SendResult.Sent => $"Sent the link to {d.Name}",
            SendResult.Waiting => $"The link will go to {d.Name}",
            _ => $"Couldn't send the link to {d.Name}",
        }, r.Result == SendResult.Sent ? url : AskSentence(r.Detail ?? ""), "link");
    }

    /// <summary>One device's result for a link.</summary>
    async Task<DeviceResult> LinkToAsync(Destination d, string url)
    {
        var e = Running;
        try
        {
            url = Links.CheckUrl(url);
            if (d.Fp is { } fp && e.Mesh is { } m)
            {
                var sent = await m.SendLinkAsync(fp, url);
                if (sent.How == "link")
                {
                    ChatChanged?.Invoke(fp);
                    return new DeviceResult(fp, d.Name, SendResult.Sent, sent.Route);
                }
                ChatChanged?.Invoke(fp);
                return await JobResultAsync(m, d, sent.Job!, TimeSpan.FromMinutes(1));
            }
            // a device only the hub knows: a message with the link, which gets an Open button there
            await e.Bridge.HubTextAsync(d.IsHub ? "hub" : d.HubDeviceId ?? throw new InvalidOperationException($"{d.Name} can't be reached"), url,
                CancellationToken.None);
            return new DeviceResult(d.Key, d.Name, SendResult.Sent, "hub");
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            return new DeviceResult(d.Fp ?? d.Key, d.Name, SendResult.Refused, ex.Message);
        }
    }

    static async Task<DeviceResult> JobResultAsync(MeshNode m, Destination d, OutboxJob job, TimeSpan wait) =>
        Several.Of(d.Fp ?? d.Key, d.Name, await m.WaitJobAsync(job.Id, wait) ?? job);

    /// <summary>Opens a web link from a message (its Open button). Only http and https.</summary>
    public void OpenLink(string url)
    {
        if (!Services.Browser!.Open(url))
        {
            Notify("Couldn't open the link", url, "link");
        }
    }

    /// <summary>
    /// After this PC was renamed (in Settings: through the hub when it has one): tells the
    /// devices linked now, and mDNS; the others hear it in the next hello.
    /// </summary>
    public async Task AnnounceNameAsync()
    {
        if (Engine?.Mesh is { } m)
        {
            await m.AnnounceNameAsync();
        }
        Refresh();
    }

    /// <summary>What this PC calls a device (empty clears it). Only here: never sent.</summary>
    public void SetNickname(Destination d, string nickname)
    {
        ArgumentNullException.ThrowIfNull(d);
        if (d.Fp is { } fp && Running.Mesh is { } m)
        {
            m.SetNickname(fp, nickname);
            Refresh();
        }
    }

    /// <summary>
    /// Sends to several devices at once: each gets its own send and its own result, exactly as
    /// if it were sent to it alone. One notification sums it up; the results come back for the window.
    /// </summary>
    public async Task<List<DeviceResult>> SendToSeveralAsync(IReadOnlyList<Destination> dests, string what, Func<Destination, Task<DeviceResult>> one)
    {
        ArgumentNullException.ThrowIfNull(dests);
        ArgumentNullException.ThrowIfNull(one);
        var results = (await Task.WhenAll(dests.Select(one))).ToList();
        Notify($"{what}: {Several.Summary(results)}", string.Join("\n", results.Select(Several.Line)), "several");
        Refresh();
        return results;
    }

    /// <summary>Files to one of several devices.</summary>
    public async Task<DeviceResult> FilesToAsync(Destination d, IReadOnlyList<string> paths)
    {
        var e = Running;
        var files = paths.Where(File.Exists).Select(Path.GetFullPath).ToList();
        try
        {
            if (files.Count == 0)
            {
                throw new InvalidOperationException("only files can be sent, not folders");
            }
            if (d.Fp is { } fp && e.Mesh is { } m)
            {
                // every file queued at once (they go in order), then each one's result
                var jobs = new List<OutboxJob>();
                foreach (var f in files)
                {
                    jobs.Add(m.SendFile(fp, f));
                }
                DeviceResult? worst = null;
                foreach (var job in jobs)
                {
                    var r = await JobResultAsync(m, d, job, TimeSpan.FromMinutes(30));
                    worst = worst is null ? r : Worse(worst, r);
                }
                return worst!;
            }
            foreach (var f in files)
            {
                await e.Bridge.HubUploadAsync(d.IsHub ? "hub" : d.HubDeviceId ?? throw new InvalidOperationException($"{d.Name} can't be reached"), f,
                    Path.GetFileName(f), MimeTypes.Of(f), CancellationToken.None);
            }
            return new DeviceResult(d.Key, d.Name, SendResult.Sent, "hub");
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            return new DeviceResult(d.Fp ?? d.Key, d.Name, SendResult.Refused, ex.Message);
        }
    }

    static DeviceResult Worse(DeviceResult a, DeviceResult b) =>
        a.Result == SendResult.Refused || (a.Result == SendResult.Waiting && b.Result == SendResult.Sent) ? a : b;

    /// <summary>A message to one of several devices.</summary>
    public async Task<DeviceResult> TextToAsync(Destination d, string text)
    {
        var e = Running;
        try
        {
            if (d.Fp is { } fp && e.Mesh is { } m)
            {
                var job = m.SendText(fp, text);
                ChatChanged?.Invoke(fp);
                return await JobResultAsync(m, d, job, TimeSpan.FromMinutes(1));
            }
            await e.Bridge.HubTextAsync(d.IsHub ? "hub" : d.HubDeviceId ?? throw new InvalidOperationException($"{d.Name} can't be reached"), text,
                CancellationToken.None);
            return new DeviceResult(d.Key, d.Name, SendResult.Sent, "hub");
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            return new DeviceResult(d.Fp ?? d.Key, d.Name, SendResult.Refused, ex.Message);
        }
    }

    /// <summary>A link to one of several devices.</summary>
    public Task<DeviceResult> LinkToOneAsync(Destination d, string url) => LinkToAsync(d, url);

    /// <summary>The clipboard to one of several devices: copied files, an image, or text (onto their clipboard when that works, else as a message).</summary>
    public async Task<DeviceResult> ClipToAsync(Destination d, Clip clip)
    {
        ArgumentNullException.ThrowIfNull(clip);
        if (clip.Files.Count > 0)
        {
            return await FilesToAsync(d, clip.Files);
        }
        var e = Running;
        try
        {
            if (clip.Png is { } png)
            {
                if (d.Fp is { } fp && e.Mesh is { } m)
                {
                    return await JobResultAsync(m, d, m.SendBytes(fp, $"clipboard-{DateTime.Now:yyyyMMdd-HHmmss}.png", png, "image/png"),
                        TimeSpan.FromMinutes(5));
                }
                await SendClipboardAsync(d, clip);
                return new DeviceResult(d.Key, d.Name, SendResult.Sent, "hub");
            }
            if (string.IsNullOrEmpty(clip.Text))
            {
                throw new InvalidOperationException("the clipboard has no text, image or files");
            }
            if (d.Fp is { } to && e.Mesh is { } mesh)
            {
                // text from the clipboard is the clipboard: not to a device it's switched off for
                mesh.MaySend(to, new System.Text.Json.Nodes.JsonObject { ["t"] = "clip" });
                if (d.Caps.Contains(Caps.Clipboard))
                {
                    try
                    {
                        return new DeviceResult(to, d.Name, SendResult.Sent, await mesh.ClipAsync(to, clip.Text));
                    }
                    catch (NoRouteException)
                    {
                        // not reachable live: a message waits for it instead
                    }
                }
            }
            return await TextToAsync(d, clip.Text);
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            return new DeviceResult(d.Fp ?? d.Key, d.Name, SendResult.Refused, ex.Message);
        }
    }

    /// <summary>A chat thread, oldest first: the mesh's log for a peer, or the hub's thread for a hub device.</summary>
    public async Task<List<ChatLine>> ChatAsync(Destination d)
    {
        ArgumentNullException.ThrowIfNull(d);
        var e = Running;
        if (d.Fp is { } fp && e.Mesh is { } m)
        {
            var lines = m.Chat.Recent(200).Where(c => c.Fp == fp)
                .Select(c => new ChatLine(c.Dir == "out", c.Body, DateTimeOffset.FromUnixTimeMilliseconds((long)(c.Ts * 1000)), c.Route,
                    Url: c.Kind == "link" && Links.IsUrl(c.Body) ? c.Body.Trim() : Links.OnlyUrl(c.Body), IsLink: c.Kind == "link", Opened: c.Opened)).ToList();
            lines.AddRange(m.Outbox.ForPeer(fp).Where(j => j.Kind == "text")
                .Select(j => new ChatLine(true, j.Body ?? "", DateTimeOffset.FromUnixTimeMilliseconds((long)(j.Created * 1000)), null, true,
                    Links.OnlyUrl(j.Body))));
            return lines.OrderBy(l => l.At).ToList();
        }
        if (d.HubDeviceId is { } id && e.Store.Get() is { Registered: true } cfg)
        {
            var r = await e.Routes.EnsureAsync();
            using var c = r.Client(cfg.DeviceToken, cfg.Session);
            var thread = await c.ChatAsync(id);
            return thread.Select(msg => new ChatLine(msg.From == cfg.DeviceId, msg.Text, DateTimeOffset.FromUnixTimeMilliseconds((long)(msg.Ts * 1000)), "hub",
                    Url: Links.OnlyUrl(msg.Text)))
                .OrderBy(l => l.At).ToList();
        }
        return [];
    }

    /// <summary>Handles a notification's droplet: link.</summary>
    public async Task HandleActionAsync(string url)
    {
        if (Engine is not { } e || ActionLinks.Parse(url, e.Store.Get().ActionKey) is not { } act)
        {
            return; // not ours, or a web page trying the scheme: ignored
        }
        var cfg = e.Store.Get();
        var dirs = new[] { cfg.DownloadDir, cfg.Mesh.Downloads ?? "" };
        switch (act.Kind)
        {
            case NotificationActionKind.StopRing:
                await StopRingAsync();
                break;
            case NotificationActionKind.OpenFile or NotificationActionKind.OpenFolder when dirs.Any(d => ActionLinks.Inside(act.Arg, d)):
                Files.Open(act.Arg, log);
                break;
            case NotificationActionKind.ShowInFolder when dirs.Any(d => ActionLinks.Inside(act.Arg, d)):
                Files.ShowInFolder(act.Arg, log);
                break;
            case NotificationActionKind.AcceptPairing:
                {
                    // whose device it is: asked here, never sent to it
                    var name = e.Mesh?.Incoming.Waiting().FirstOrDefault(r => r.Request == act.Arg)?.Name ?? "that device";
                    if (AskRelation is not { } ask)
                    {
                        AnswerPairing(act.Arg, true);
                    }
                    else if (ask(name) is { } relation)
                    {
                        AnswerPairing(act.Arg, true, relation);
                    }
                    break;
                }
            case NotificationActionKind.DenyPairing:
                AnswerPairing(act.Arg, false);
                break;
        }
    }

    /// <summary>
    /// Answers a device's request to pair: accepting, as your own device (<see cref="Perms.Own"/>)
    /// or someone else's (<see cref="Perms.Other"/>).
    /// </summary>
    public void AnswerPairing(string request, bool accept, string relation = Perms.Own)
    {
        if (Running.Mesh is not { } m)
        {
            return;
        }
        try
        {
            if (m.PairAnswer(request, accept, relation) is { } entry)
            {
                Notify($"Paired with {entry.Name}", entry.IsOther
                    ? "As someone else's device: files, messages and ring only. Change that under Permissions in Devices."
                    : "It can now reach this PC directly.", "pair");
            }
        }
        catch (ArgumentException ex)
        {
            Notify("That pairing request has gone", ex.Message, "pair");
        }
        Refresh();
    }

    /// <summary>Removes what droplet added to Windows, and switches Start with Windows and Send To off in the settings.</summary>
    public async Task<List<string>> UninstallAsync()
    {
        var errors = Packaging.IsPackaged ? [] : Registration.Unregister();
        if (Engine is { } e)
        {
            if (Packaging.IsPackaged)
            {
                await SetAutostartAsync(false);
            }
            e.Store.Update(c => (c.Autostart, c.SendTo) = (false, false));
            sendToKey = "";
        }
        return errors;
    }

    public async ValueTask DisposeAsync()
    {
        tick.Stop();
        if (Engine is { } e)
        {
            Engine = null;
            await e.DisposeAsync();
        }
        Ring.Dispose();
        Media.Dispose();
        await Toasts.FlushAsync(TimeSpan.FromSeconds(2));
        Toasts.Dispose();
    }

    /// <summary>For the engine's own logs of what the UI did.</summary>
    public ILogger Log => log;

    /// <summary>The mesh status summary, for Settings.</summary>
    public JsonObject? MeshStatus => Engine?.Mesh?.Status();
}

/// <summary>One chat message, for the chat window.</summary>
/// <param name="Mine">Sent from this PC.</param>
/// <param name="Text">The message.</param>
/// <param name="At">When.</param>
/// <param name="Route">How it went ("lan", "hub"...), when known.</param>
/// <param name="Waiting">Still in the outbox.</param>
/// <param name="Url">A web link it is (or is nothing but), for an Open button; never opened by itself.</param>
/// <param name="IsLink">Sent as a link (docs/mesh.md §9.10), not a message.</param>
/// <param name="Opened">A link from your own device that opened here when it came.</param>
internal sealed record ChatLine(bool Mine, string Text, DateTimeOffset At, string? Route, bool Waiting = false, string? Url = null,
    bool IsLink = false, bool Opened = false);
