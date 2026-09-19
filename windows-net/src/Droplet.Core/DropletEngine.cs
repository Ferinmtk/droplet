using System.Text.Json.Nodes;
using Droplet.Core.Config;
using Droplet.Core.LocalFirst;
using Droplet.Core.Mesh;
using Droplet.Core.Platform;
using Droplet.Core.Polling;
using Droplet.Core.Remote;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Droplet.Core;

/// <summary>How the engine is set up. The Windows shell passes its platform services and a DPAPI identity store.</summary>
public sealed record EngineOptions
{
    /// <summary>Where the app keeps its files.</summary>
    public required AppPaths Paths { get; init; }

    /// <summary>What the desktop can do.</summary>
    public required PlatformServices Services { get; init; }

    /// <summary>Where the mesh identity is kept; default: the file store in <see cref="AppPaths.MeshConfigDir"/>.</summary>
    public IIdentityStore? IdentityStore { get; init; }

    /// <summary>The config; default: <see cref="ConfigStore.Open"/> at <see cref="Paths"/> (importing the Go app's once).</summary>
    public ConfigStore? Store { get; init; }

    /// <summary>How the app names itself to the hub, e.g. "droplet-windows/2.0.0".</summary>
    public string App { get; init; } = "droplet-windows/2";

    /// <summary>The network access route selection uses (tests replace it).</summary>
    public RouteDeps? RouteDeps { get; init; }

    /// <summary>How often the mesh outbox looks for routes.</summary>
    public TimeSpan MeshRetry { get; init; } = TimeSpan.FromSeconds(15);

    /// <summary>This machine's LAN addresses (tests may fix them).</summary>
    public Func<IReadOnlyList<string>>? LocalAddresses { get; init; }

    /// <summary>Logging.</summary>
    public ILoggerFactory? LoggerFactory { get; init; }
}

/// <summary>
/// Everything in Droplet.Core put together, as the app runs it: the config, the route
/// to the hub, the poll loop, the live connection, the mesh peer and its roster, and
/// clipboard and media state going to the hub and to linked peers. The shell creates one,
/// shows its state, and calls into its parts (<see cref="Setup"/> to join,
/// <see cref="Mesh"/> to send directly...).
/// </summary>
public sealed class DropletEngine : IAsyncDisposable
{
    readonly CancellationTokenSource stop = new();
    readonly ILogger log;
    Task? routeWatch;

    DropletEngine(EngineOptions o, ConfigStore store)
    {
        Options = o;
        Store = store;
        var lf = o.LoggerFactory ?? NullLoggerFactory.Instance;
        log = lf.CreateLogger("droplet");
        Routes = new RouteManager(() => HubTarget.Of(Store.Get()), o.RouteDeps, logger: lf.CreateLogger("droplet.route"));
        Setup = new HubSetup(Store, Routes, lf.CreateLogger("droplet.setup"));
        Dispatcher = new RemoteDispatcher(o.Services, EnabledCaps, () => Store.Get().DeviceName ?? Environment.MachineName, lf.CreateLogger("droplet.remote"));
        Live = new HubLiveSession(LiveParams, Dispatcher, o.App, lf.CreateLogger("droplet.live"));
        States = new StatePublisher(o.Services.Media, () => Dispatcher.Offered().Contains(Caps.Media), lf.CreateLogger("droplet.state"));
        Bridge = new HubMeshBridge(Store, Routes, Live, Dispatcher, States, lf.CreateLogger("droplet.mesh.hub"));
        Poller = new HubPoller(Store, Routes, Setup, o.Services, lf.CreateLogger("droplet.poll"));
        if (o.Services.Clipboard is { } clipboard)
        {
            Clipboard = new ClipboardWatcher(clipboard, Dispatcher.Clip, () => Dispatcher.Offered().Contains(Caps.Clipboard), lf.CreateLogger("droplet.clip"));
            Clipboard.Copied += ToEveryoneAsync;
        }
        States.Publish += (kind, data) => ToEveryoneAsync(StatePublisher.Message(kind, data));
        Routes.RouteChanged += _ => Live.Reload();
        Live.Connected += States.Poke;
        Store.Changed += changed =>
        {
            Live.Reload(); // a new token, a switch turned off, paused...
            if (Mesh is { } m)
            {
                _ = m.RefreshAnnouncementAsync();
            }
        };
    }

    /// <summary>How it was set up.</summary>
    public EngineOptions Options { get; }

    /// <summary>The config.</summary>
    public ConfigStore Store { get; }

    /// <summary>The route to the hub.</summary>
    public RouteManager Routes { get; }

    /// <summary>Joining, linking, the PIN, re-pairing.</summary>
    public HubSetup Setup { get; }

    /// <summary>Remote control, from the hub and from peers.</summary>
    public RemoteDispatcher Dispatcher { get; }

    /// <summary>The live connection to the hub.</summary>
    public HubLiveSession Live { get; }

    /// <summary>The hub's side of the mesh: its routes and roster.</summary>
    public HubMeshBridge Bridge { get; }

    /// <summary>The mesh peer; null when the mesh is switched off.</summary>
    public MeshNode? Mesh { get; private set; }

    /// <summary>The poll loop: inbox, chat, rings.</summary>
    public HubPoller Poller { get; }

    /// <summary>Clipboard sync; null when there's no clipboard.</summary>
    public ClipboardWatcher? Clipboard { get; }

    /// <summary>Media state.</summary>
    public StatePublisher States { get; }

    /// <summary>Opens the config and starts everything.</summary>
    public static async Task<DropletEngine> StartAsync(EngineOptions options, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(options);
        var store = options.Store ?? ConfigStore.Open(options.Paths);
        var engine = new DropletEngine(options, store);
        await engine.StartPartsAsync(ct).ConfigureAwait(false);
        return engine;
    }

    async Task StartPartsAsync(CancellationToken ct)
    {
        var cfg = Store.Get();
        if (cfg.Mesh.Enabled)
        {
            var lf = Options.LoggerFactory;
            var node = new MeshNode(Bridge, new MeshOptions
            {
                ConfigDir = Options.Paths.MeshConfigDir,
                DataDir = Options.Paths.MeshDataDir,
                Downloads = string.IsNullOrWhiteSpace(cfg.Mesh.Downloads) ? cfg.DownloadDir : cfg.Mesh.Downloads,
                Port = cfg.Mesh.Port,
                MaxRate = cfg.Mesh.MaxRate,
                Announce = cfg.Mesh.Announce,
                RetryEvery = Options.MeshRetry,
                IdentityStore = Options.IdentityStore,
                LocalAddresses = Options.LocalAddresses ?? Common.Addresses.Lan,
                LoggerFactory = lf,
            }, Dispatcher, Options.Services);
            try
            {
                await node.StartAsync(ct).ConfigureAwait(false);
                Bridge.Node = node;
                Mesh = node;
            }
            catch (IOException e)
            {
                // no free port: everything else still works through the hub
                log.LogWarning("mesh: can't listen: {Error}", e.Message);
                await node.DisposeAsync().ConfigureAwait(false);
            }
        }
        Bridge.Start();
        States.Start();
        Clipboard?.Start();
        Live.Start();
        Poller.Start();
        routeWatch = Task.Run(() => Routes.RunAsync(stop.Token), CancellationToken.None);
    }

    /// <summary>The capabilities switched on in the settings (nothing while paused).</summary>
    HashSet<string> EnabledCaps()
    {
        var c = Store.Get();
        var on = new HashSet<string>();
        if (c.RemotePaused)
        {
            return on;
        }
        if (c.RemoteInput)
        {
            on.Add(Caps.Input);
        }
        if (c.RemoteMedia)
        {
            on.Add(Caps.Media);
        }
        if (c.RemoteLock)
        {
            on.Add(Caps.Lock);
        }
        if (c.RemoteScreenshot)
        {
            on.Add(Caps.Screenshot);
        }
        if (c.ClipboardSync)
        {
            on.Add(Caps.Clipboard);
        }
        return on;
    }

    /// <summary>What the live connection needs now; null when there's nothing to connect to.</summary>
    LiveParams? LiveParams()
    {
        var cfg = Store.Get();
        if (!cfg.Registered || Routes.Current is not { } r)
        {
            if (cfg.Registered)
            {
                // no route yet: choose one, and the route change reloads the connection
                _ = Task.Run(async () =>
                {
                    try
                    {
                        await Routes.EnsureAsync(stop.Token).ConfigureAwait(false);
                    }
                    catch (Exception e) when (e is UnreachableException or NotPairedException or OperationCanceledException)
                    {
                    }
                });
            }
            return null;
        }
        return new LiveParams(r.Base, r.Handler(), cfg.DeviceToken!, cfg.Session, cfg.DeviceName ?? "", Dispatcher.Offered());
    }

    /// <summary>A local clipboard change: to the hub (which passes it to your other devices) and to every linked peer.</summary>
    Task ToEveryoneAsync(string text) => ToEveryoneAsync(new JsonObject { ["t"] = "clip", ["text"] = text });

    async Task ToEveryoneAsync(JsonObject msg)
    {
        await Live.SendAsync((JsonObject)msg.DeepClone()).ConfigureAwait(false);
        if (Mesh is { } m)
        {
            await m.BroadcastAsync(msg).ConfigureAwait(false);
        }
    }

    /// <inheritdoc/>
    public async ValueTask DisposeAsync()
    {
        await stop.CancelAsync().ConfigureAwait(false);
        if (routeWatch is not null)
        {
            await routeWatch.ConfigureAwait(false);
        }
        await Poller.DisposeAsync().ConfigureAwait(false);
        await Live.DisposeAsync().ConfigureAwait(false);
        if (Clipboard is not null)
        {
            await Clipboard.DisposeAsync().ConfigureAwait(false);
        }
        await States.DisposeAsync().ConfigureAwait(false);
        await Bridge.DisposeAsync().ConfigureAwait(false);
        if (Mesh is not null)
        {
            await Mesh.DisposeAsync().ConfigureAwait(false);
        }
        await Dispatcher.DisposeAsync().ConfigureAwait(false);
        stop.Dispose();
    }
}
