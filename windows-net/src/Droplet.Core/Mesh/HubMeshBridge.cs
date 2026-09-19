using System.Text.Json.Nodes;
using Droplet.Core.Config;
using Droplet.Core.Hub;
using Droplet.Core.LocalFirst;
using Droplet.Core.Remote;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Droplet.Core.Mesh;

/// <summary>
/// Connects the mesh to the hub (docs/mesh.md §3.1, §9.6): it's the <see cref="IMeshHost"/>
/// the node calls for the hub routes, and keeps the roster. On every connection to the
/// hub, when the hub says the roster changed, and every 10 minutes while connected, it
/// announces this device and fetches the roster; the cached one keeps working meanwhile.
/// </summary>
public sealed class HubMeshBridge : IMeshHost, IAsyncDisposable
{
    static readonly TimeSpan RosterEvery = TimeSpan.FromMinutes(10);

    readonly ConfigStore store;
    readonly RouteManager routes;
    readonly HubLiveSession? live;
    readonly RemoteDispatcher? dispatcher;
    readonly StatePublisher? states;
    readonly ILogger log;
    readonly SemaphoreSlim syncing = new(1, 1);
    readonly CancellationTokenSource stop = new();
    Task? loop;
    int again;

    /// <summary>Creates a bridge; set <see cref="Node"/> once the node exists, then <see cref="Start"/>.</summary>
    public HubMeshBridge(ConfigStore store, RouteManager routes, HubLiveSession? live, RemoteDispatcher? dispatcher,
        StatePublisher? states = null, ILogger? logger = null)
    {
        this.store = store ?? throw new ArgumentNullException(nameof(store));
        this.routes = routes ?? throw new ArgumentNullException(nameof(routes));
        this.live = live;
        this.dispatcher = dispatcher;
        this.states = states;
        log = logger ?? NullLogger.Instance;
        if (live is not null)
        {
            live.Connected += () => RosterSoon(announceAgain: true);
            live.RosterChanged += () => RosterSoon(announceAgain: false);
        }
    }

    /// <summary>The mesh node.</summary>
    public MeshNode? Node { get; set; }

    /// <inheritdoc/>
    public IReadOnlyList<string> MeshCaps => dispatcher?.Offered() ?? [];

    /// <inheritdoc/>
    public string DeviceName
    {
        get
        {
            var name = store.Get().DeviceName;
            return string.IsNullOrWhiteSpace(name) ? Environment.MachineName.ToLowerInvariant() : name;
        }
    }

    /// <inheritdoc/>
    public string? HubDeviceId => store.Get() is { Registered: true } c ? c.DeviceId : null;

    /// <inheritdoc/>
    public string? HubId => store.Get() is { Registered: true } c ? c.Hub?.Id : null;

    /// <inheritdoc/>
    public IReadOnlyDictionary<string, JsonNode?> LastStates => states?.Last ?? new Dictionary<string, JsonNode?>();

    /// <inheritdoc/>
    public bool HubConnected => live?.IsLive ?? false;

    /// <inheritdoc/>
    public bool HubOnline(string deviceId) => live?.IsOnline(deviceId) ?? false;

    /// <inheritdoc/>
    public Task<bool> HubSendAsync(JsonObject message) => live?.SendAsync(message) ?? Task.FromResult(false);

    async Task<HubClient> ClientAsync(CancellationToken ct)
    {
        var cfg = store.Get();
        if (!cfg.Registered)
        {
            throw new NoRouteException("this PC isn't let in to a hub");
        }
        var r = await routes.EnsureAsync(ct).ConfigureAwait(false);
        return r.Client(cfg.DeviceToken, cfg.Session);
    }

    /// <inheritdoc/>
    public async Task HubTextAsync(string deviceId, string body, CancellationToken ct)
    {
        using var c = await ClientAsync(ct).ConfigureAwait(false);
        await c.SendTextAsync(deviceId, body, ct).ConfigureAwait(false);
    }

    /// <inheritdoc/>
    public async Task HubUploadAsync(string deviceId, string path, string name, string mime, CancellationToken ct)
    {
        using var c = await ClientAsync(ct).ConfigureAwait(false);
        await c.UploadFileAsync(deviceId, path, name, mime, ct: ct).ConfigureAwait(false);
    }

    /// <inheritdoc/>
    public async Task HubRingAsync(string deviceId, bool stopRing, CancellationToken ct)
    {
        using var c = await ClientAsync(ct).ConfigureAwait(false);
        if (stopRing)
        {
            await c.StopRingDeviceAsync(deviceId, ct).ConfigureAwait(false);
        }
        else
        {
            await c.RingDeviceAsync(deviceId, ct).ConfigureAwait(false);
        }
    }

    /// <summary>Starts the periodic roster fetch.</summary>
    public void Start() => loop ??= Task.Run(RunAsync);

    async Task RunAsync()
    {
        while (!stop.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(RosterEvery, stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            if (HubConnected)
            {
                await SyncRosterAsync(stop.Token).ConfigureAwait(false);
            }
        }
    }

    void RosterSoon(bool announceAgain)
    {
        _ = Task.Run(async () =>
        {
            if (announceAgain && Node is { } n)
            {
                await n.RefreshAnnouncementAsync().ConfigureAwait(false);
                n.Retry(); // the hub is back: whatever waits may go through it
            }
            await SyncRosterAsync(stop.Token).ConfigureAwait(false);
        });
    }

    /// <summary>
    /// Announces this device to the hub and applies its roster. One runs at a time; a
    /// request while one runs makes it run once more after.
    /// </summary>
    public async Task SyncRosterAsync(CancellationToken ct = default)
    {
        if (Node is not { } node || !store.Get().Registered)
        {
            return;
        }
        if (!await syncing.WaitAsync(0, ct).ConfigureAwait(false))
        {
            Interlocked.Exchange(ref again, 1); // one is running: it runs once more after
            return;
        }
        try
        {
            do
            {
                Interlocked.Exchange(ref again, 0);
                try
                {
                    using var c = await ClientAsync(ct).ConfigureAwait(false);
                    await c.MeshAnnounceAsync(node.AnnounceBody(), ct).ConfigureAwait(false);
                    var roster = await c.MeshRosterAsync(ct).ConfigureAwait(false);
                    node.ApplyRoster(roster, HubId ?? roster["hub"]?.GetValue<string>() ?? "");
                }
                catch (Exception e) when (e is HubException or HttpRequestException or UnreachableException or NotPairedException or NoRouteException or FormatException)
                {
                    // the cached roster keeps working
                    log.LogWarning("mesh: couldn't update the roster from the hub: {Error}", e.Message);
                }
            }
            while (Interlocked.Exchange(ref again, 0) == 1 && !ct.IsCancellationRequested);
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            syncing.Release();
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
