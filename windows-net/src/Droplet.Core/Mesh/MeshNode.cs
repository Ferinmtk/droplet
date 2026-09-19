using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Droplet.Core.Common;
using Droplet.Core.Platform;
using Droplet.Core.Remote;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Droplet.Core.Mesh;

/// <summary>No route reaches the peer right now.</summary>
public sealed class NoRouteException(string message) : Exception(message);

/// <summary>
/// What the mesh needs from the rest of the app: who this device is, and the hub.
/// Every hub call may throw.
/// </summary>
public interface IMeshHost
{
    /// <summary>The capabilities to announce (what's switched on and works).</summary>
    IReadOnlyList<string> MeshCaps { get; }

    /// <summary>This device's name.</summary>
    string DeviceName { get; }

    /// <summary>This device's id on its hub, once let in; null otherwise.</summary>
    string? HubDeviceId { get; }

    /// <summary>The hub's id, once let in; null otherwise.</summary>
    string? HubId { get; }

    /// <summary>The latest state published, by kind (media...), for new links.</summary>
    IReadOnlyDictionary<string, JsonNode?> LastStates { get; }

    /// <summary>Whether the hub's live connection is up.</summary>
    bool HubConnected { get; }

    /// <summary>Whether a device has a live connection to the hub now.</summary>
    bool HubOnline(string deviceId);

    /// <summary>Sends a message over the hub's live connection.</summary>
    Task<bool> HubSendAsync(JsonObject message);

    /// <summary>A chat message through the hub (<c>POST /text</c>), held for an offline device.</summary>
    Task HubTextAsync(string deviceId, string body, CancellationToken ct);

    /// <summary>A file through the hub (<c>POST /upload?to=</c>), held for an offline device.</summary>
    Task HubUploadAsync(string deviceId, string path, string name, string mime, CancellationToken ct);

    /// <summary>Rings a device through the hub's ring API (which also reaches a closed app by push).</summary>
    Task HubRingAsync(string deviceId, bool stopRing, CancellationToken ct);
}

/// <summary>A host with no hub: the mesh alone.</summary>
public class NoHubHost(string deviceName, IReadOnlyList<string>? caps = null) : IMeshHost
{
    /// <inheritdoc/>
    public virtual IReadOnlyList<string> MeshCaps => caps ?? [];

    /// <inheritdoc/>
    public virtual string DeviceName => deviceName;

    /// <inheritdoc/>
    public virtual string? HubDeviceId => null;

    /// <inheritdoc/>
    public virtual string? HubId => null;

    /// <inheritdoc/>
    public virtual IReadOnlyDictionary<string, JsonNode?> LastStates => new Dictionary<string, JsonNode?>();

    /// <inheritdoc/>
    public virtual bool HubConnected => false;

    /// <inheritdoc/>
    public virtual bool HubOnline(string deviceId) => false;

    /// <inheritdoc/>
    public virtual Task<bool> HubSendAsync(JsonObject message) => Task.FromResult(false);

    /// <inheritdoc/>
    public virtual Task HubTextAsync(string deviceId, string body, CancellationToken ct) => throw new NoRouteException("no hub");

    /// <inheritdoc/>
    public virtual Task HubUploadAsync(string deviceId, string path, string name, string mime, CancellationToken ct) =>
        throw new NoRouteException("no hub");

    /// <inheritdoc/>
    public virtual Task HubRingAsync(string deviceId, bool stopRing, CancellationToken ct) => throw new NoRouteException("no hub");
}

/// <summary>How a mesh node is set up.</summary>
public sealed record MeshOptions
{
    /// <summary>The trust list (and, with the file store, the identity).</summary>
    public required string ConfigDir { get; init; }

    /// <summary>Outbox, chat, the record of received files, files being sent.</summary>
    public required string DataDir { get; init; }

    /// <summary>Where files sent directly are saved.</summary>
    public required string Downloads { get; init; }

    /// <summary>A fixed port; null for the first free one in 1739–1749.</summary>
    public int? Port { get; init; }

    /// <summary>Bytes a second when serving files; 0 for no limit.</summary>
    public long MaxRate { get; init; }

    /// <summary>Announce and browse over mDNS.</summary>
    public bool Announce { get; init; } = true;

    /// <summary>This machine's LAN addresses, the main one first.</summary>
    public Func<IReadOnlyList<string>> LocalAddresses { get; init; } = Addresses.Lan;

    /// <summary>How often the outbox looks for routes (and at once when a peer or the hub appears).</summary>
    public TimeSpan RetryEvery { get; init; } = TimeSpan.FromSeconds(15);

    /// <summary>Where the identity is kept; default: <see cref="FileIdentityStore"/> in <see cref="ConfigDir"/>.</summary>
    public IIdentityStore? IdentityStore { get; init; }

    /// <summary>Logging.</summary>
    public ILoggerFactory? LoggerFactory { get; init; }
}

/// <summary>A pairing this device started: what to show while the owner compares the codes.</summary>
/// <param name="Request">Its id.</param>
/// <param name="Code">The code.</param>
/// <param name="PeerId">The peer's id.</param>
/// <param name="PeerName">The peer's name.</param>
/// <param name="PeerFp">The peer's fingerprint.</param>
/// <param name="Address">Where it was reached.</param>
public sealed record PairStart(string Request, string Code, string PeerId, string PeerName, string PeerFp, string Address);

/// <summary>
/// The mesh peer (docs/mesh.md): links to other devices, what arrives over them, and how
/// messages leave. For each message this device sends, the first route that works:
/// <list type="number">
/// <item>direct LAN: an open link, or a new one to an address from mDNS or one that worked before;</item>
/// <item>direct tailnet: the peer's tailnet address, from the hub's roster;</item>
/// <item>through the hub, when it's connected and knows the peer;</item>
/// <item>the hub's mailbox, when the hub knows the peer but the peer is offline;</item>
/// <item>the outbox: kept here, and sent when the peer or the hub appears.</item>
/// </list>
/// Live control (<c>input</c>, <c>media</c>, <c>cmd</c>), <c>clip</c> and <c>ring</c> use
/// 1–3 only; chat and files use 1–5. Each peer's queue goes out in order.
/// </summary>
public sealed partial class MeshNode : IMeshServerHandler, IAsyncDisposable
{
    static readonly TimeSpan LanTimeout = TimeSpan.FromSeconds(1.5);
    static readonly TimeSpan TailnetTimeout = TimeSpan.FromSeconds(4);
    static readonly TimeSpan AckTimeout = TimeSpan.FromSeconds(10);
    static readonly TimeSpan Stall = TimeSpan.FromSeconds(60);
    static readonly TimeSpan IdleClose = TimeSpan.FromSeconds(300);
    static readonly string[] Live = ["input", "media", "cmd"];

    readonly IMeshHost host;
    readonly MeshOptions options;
    readonly RemoteDispatcher? dispatcher;
    readonly PlatformServices services;
    readonly ILogger log;
    readonly Lock gate = new();
    readonly Dictionary<string, List<MeshLink>> links = [];
    readonly ConcurrentDictionary<string, SemaphoreSlim> dialLocks = new();
    readonly ConcurrentDictionary<string, (TaskCompletionSource<(bool Ok, string Error)> Waiter, string Fp)> acks = new();
    readonly ConcurrentDictionary<string, Offer> offers = new();
    readonly ConcurrentDictionary<string, OutgoingPairing> outgoing = new();
    readonly ConcurrentDictionary<string, ConcurrentDictionary<string, JsonNode?>> peerState = new();
    readonly HashSet<(string, string)> downloading = [];
    readonly HashSet<string> workers = [];
    readonly Lock workersGate = new();
    readonly SemaphoreSlim kick = new(0, 1);
    readonly CancellationTokenSource stop = new();
    readonly List<Task> background = [];
    readonly Lock backgroundGate = new();
    MeshServer? server;
    PeerDirectory? directory;

    /// <summary>Creates a node; <see cref="StartAsync"/> starts it.</summary>
    /// <param name="host">The rest of the app: who this is, and the hub.</param>
    /// <param name="options">Where things are kept, and the port.</param>
    /// <param name="dispatcher">Handles remote control arriving over links; null ignores it.</param>
    /// <param name="services">Notifications and the ring sound for what arrives.</param>
    public MeshNode(IMeshHost host, MeshOptions options, RemoteDispatcher? dispatcher = null, PlatformServices? services = null)
    {
        this.host = host ?? throw new ArgumentNullException(nameof(host));
        this.options = options ?? throw new ArgumentNullException(nameof(options));
        this.dispatcher = dispatcher;
        this.services = services ?? new PlatformServices();
        log = options.LoggerFactory?.CreateLogger("droplet.mesh") ?? NullLogger.Instance;
        Identity = MeshIdentity.LoadOrCreate(options.IdentityStore ?? new FileIdentityStore(options.ConfigDir));
        Trust = new TrustList(Path.Combine(options.ConfigDir, "trust.json"), Identity.Fingerprint);
        Trust.CertificatesChanged += OnTrustChanged;
        Incoming = new IncomingPairings(Identity);
        Incoming.Ready += OnPairRequest;
        Completed = new CompletedFiles(Path.Combine(options.DataDir, "received.json"));
        Outbox = new Outbox(Path.Combine(options.DataDir, "outbox.json"));
        Chat = new ChatLog(Path.Combine(options.DataDir, "chat.jsonl"));
    }

    /// <summary>This device's mesh identity.</summary>
    public MeshIdentity Identity { get; }

    /// <summary>The trust list.</summary>
    public TrustList Trust { get; }

    /// <summary>Pairing requests from other devices.</summary>
    public IncomingPairings Incoming { get; }

    /// <summary>Offers already received.</summary>
    public CompletedFiles Completed { get; }

    /// <summary>What's waiting to be delivered.</summary>
    public Outbox Outbox { get; }

    /// <summary>Chat sent and received directly.</summary>
    public ChatLog Chat { get; }

    /// <summary>The mesh port, once started.</summary>
    public int Port { get; private set; }

    /// <summary>TLS handshakes refused.</summary>
    public long Refused => server?.Refused ?? 0;

    /// <summary>The peers announcing themselves on the LAN.</summary>
    public IReadOnlyList<SeenPeer> Nearby => directory?.Peers() ?? [];

    /// <summary>This device's peer id.</summary>
    public string PeerId => Identity.PeerId(host.HubDeviceId);

    /// <summary>This device's name.</summary>
    public string Name => host.DeviceName;

    /// <summary>A chat message arrived directly (a new one, not a repeat).</summary>
    public event Action<ChatEntry>? TextReceived;

    /// <summary>A file arrived directly: (peer, saved path).</summary>
    public event Action<TrustEntry, string>? FileReceived;

    /// <summary>A peer asks to pair: show the code, and answer with <see cref="PairAnswer"/>.</summary>
    public event Action<PairRequestInfo>? PairingRequested;

    /// <summary>A peer is trusted now, by pairing.</summary>
    public event Action<TrustEntry>? Paired;

    /// <summary>An outbox job was delivered or failed.</summary>
    public event Action<OutboxJob>? JobFinished;

    /// <summary>A peer rang this device (its name), or stopped (null).</summary>
    public event Action<string?>? Rung;

    /// <summary>A peer published state: (fp, kind, data).</summary>
    public event Action<string, string, JsonNode?>? PeerStateChanged;

    // --- lifecycle -----------------------------------------------------------------------

    /// <summary>Opens the mesh port, announces this device, and starts delivering.</summary>
    public async Task StartAsync(CancellationToken ct = default)
    {
        server = await MeshServer.StartAsync(this, Identity, options.Port, options.LoggerFactory, ct).ConfigureAwait(false);
        Port = server.Port;
        log.LogInformation("mesh: listening on port {Port} as {Id} (fingerprint {Fp})", Port, PeerId, Identity.Fingerprint);
        if (options.Announce)
        {
            directory = new PeerDirectory(Identity.Fingerprint, options.LocalAddresses, options.LoggerFactory?.CreateLogger("droplet.mesh.discovery"));
            directory.Seen += OnSeen;
            await directory.StartAsync(Port, Txt()).ConfigureAwait(false);
        }
        Track(Task.Run(DeliverLoopAsync, CancellationToken.None));
        Track(Task.Run(HousekeepingAsync, CancellationToken.None));
        Kick();
    }

    List<KeyValuePair<string, string>> Txt() =>
        PeerDirectory.TxtRecords(PeerId, Identity.Fingerprint, Name, host.MeshCaps, host.HubId);

    /// <summary>The hello (or welcome) this device sends on a link.</summary>
    public JsonObject Hello(string t = "hello") => new()
    {
        ["t"] = t, ["id"] = PeerId, ["name"] = Name, ["caps"] = Json.Array(host.MeshCaps), ["os"] = MeshProtocol.Os,
        ["v"] = MeshProtocol.Version, ["port"] = Port,
    };

    /// <summary>What the hub's roster needs from this device (<c>POST /api/mesh/announce</c>).</summary>
    public JsonObject AnnounceBody() => new()
    {
        ["fp"] = Identity.Fingerprint, ["cert_pem"] = Identity.CertificatePem, ["port"] = Port,
        ["lan"] = Json.Array(Addresses.Clean(options.LocalAddresses().Where(a => !Addresses.IsTailnet(a)))),
        ["os"] = MeshProtocol.Os, ["caps"] = Json.Array(host.MeshCaps), ["v"] = MeshProtocol.Version,
    };

    /// <summary>Announces again over mDNS if what this device says about itself changed (a new hub, name or caps).</summary>
    public async Task RefreshAnnouncementAsync()
    {
        var d = directory;
        if (d is null || !d.Announcing)
        {
            return;
        }
        var txt = Txt();
        if (!txt.SequenceEqual(d.Txt))
        {
            await d.UpdateAsync(txt).ConfigureAwait(false);
        }
    }

    async Task HousekeepingAsync()
    {
        while (!stop.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(30), stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            foreach (var link in AllLinks().Where(l => l.Outbound && l.Idle > IdleClose))
            {
                await link.CloseAsync(WebSocketCloseStatus.NormalClosure, "idle").ConfigureAwait(false);
            }
            try
            {
                await RefreshAnnouncementAsync().ConfigureAwait(false);
            }
            catch (Exception e)
            {
                log.LogWarning(e, "mesh: announcing again");
            }
        }
    }

    /// <inheritdoc/>
    public async ValueTask DisposeAsync()
    {
        await stop.CancelAsync().ConfigureAwait(false);
        Kick();
        foreach (var link in AllLinks())
        {
            await link.CloseAsync(WebSocketCloseStatus.EndpointUnavailable, "going away").ConfigureAwait(false);
        }
        if (directory is not null)
        {
            await directory.DisposeAsync().ConfigureAwait(false);
        }
        if (server is not null)
        {
            await server.DisposeAsync().ConfigureAwait(false);
        }
        Task[] pending;
        lock (backgroundGate)
        {
            pending = [.. background];
        }
        try
        {
            await Task.WhenAll(pending).WaitAsync(TimeSpan.FromSeconds(5)).ConfigureAwait(false);
        }
        catch (Exception e) when (e is TimeoutException or OperationCanceledException)
        {
        }
        foreach (var og in outgoing.Values)
        {
            og.Dispose();
        }
        Identity.Dispose();
    }

    void Track(Task task)
    {
        lock (backgroundGate)
        {
            background.RemoveAll(t => t.IsCompleted);
            background.Add(task);
        }
    }

    void Kick()
    {
        try
        {
            kick.Release();
        }
        catch (SemaphoreFullException)
        {
        }
        catch (ObjectDisposedException)
        {
        }
    }

    // --- trust ------------------------------------------------------------------------------

    /// <inheritdoc/>
    public TrustEntry? Trusted(string fp) => Trust.Get(fp);

    void OnTrustChanged()
    {
        List<MeshLink> gone;
        lock (gate)
        {
            gone = links.Where(p => Trust.Get(p.Key) is null).SelectMany(p => p.Value).ToList();
        }
        foreach (var link in gone)
        {
            _ = link.CloseAsync(WebSocketCloseStatus.PolicyViolation, "not trusted any more");
        }
        foreach (var j in Outbox.Queued().Where(j => Trust.Get(j.Fp) is null))
        {
            Outbox.Update(j.Id, x => x with { State = JobState.Failed, Error = "that peer isn't trusted any more" });
        }
    }

    /// <summary>Trusts exactly the hub's roster (and whoever was paired directly). Returns (added, removed).</summary>
    public (int Added, int Removed) ApplyRoster(JsonObject data, string hubId)
    {
        ArgumentNullException.ThrowIfNull(data);
        if (data["peers"] is not JsonArray peers)
        {
            throw new FormatException("the hub's roster wasn't a list of peers");
        }
        var entries = new List<TrustEntry>();
        foreach (var p in peers.OfType<JsonObject>())
        {
            if (p.Str("fp") == Identity.Fingerprint)
            {
                continue;
            }
            try
            {
                entries.Add(TrustEntry.Make(p.Str("id"), p.Str("name"), p.Str("cert_pem"), TrustSource.Roster, p.Strings("lan"), p.Int("port"),
                    p.Str("tailnet_ip"), p.Str("os"), p.Strings("caps"), p.Str("fp"), hubId));
            }
            catch (FormatException e)
            {
                log.LogWarning("mesh: skipping a roster entry for {Who}: {Error}", p.Str("name") ?? p.Str("id"), e.Message);
            }
        }
        var (added, removed) = Trust.SyncRoster(entries, hubId);
        if (added > 0 || removed > 0)
        {
            log.LogInformation("mesh: the hub's roster: {Count} peer(s), {Added} new, {Removed} removed", entries.Count, added, removed);
        }
        Kick();
        return (added, removed);
    }

    // --- links ------------------------------------------------------------------------------

    List<MeshLink> AllLinks()
    {
        lock (gate)
        {
            return links.Values.SelectMany(l => l).ToList();
        }
    }

    void AddLink(MeshLink link)
    {
        lock (gate)
        {
            if (!links.TryGetValue(link.Fp, out var list))
            {
                links[link.Fp] = list = [];
            }
            list.Add(link);
        }
    }

    void OnClose(MeshLink link)
    {
        lock (gate)
        {
            if (links.TryGetValue(link.Fp, out var list))
            {
                list.Remove(link);
                if (list.Count == 0)
                {
                    links.Remove(link.Fp);
                }
            }
        }
        if (link.SentInput)
        {
            dispatcher?.ReleaseInput(); // a drag mustn't outlive the controller
        }
        if (link.IsReady)
        {
            log.LogInformation("mesh: link with {Who} ({Address}) closed", link.Hello.Str("name") ?? Fingerprint.Short(link.Fp), link.Address);
        }
    }

    /// <inheritdoc/>
    public async Task OnLinkAsync(WebSocket ws, TrustEntry entry, string address)
    {
        var link = new MeshLink(ws, entry.Fp, address, outbound: false, OnMessageAsync, OnClose, log);
        AddLink(link);
        var run = link.RunAsync();
        // a link with no hello within 15 s is closed
        var hello = await Task.WhenAny(link.Ready, Task.Delay(MeshProtocol.HelloTimeout, stop.Token)).ConfigureAwait(false);
        if (hello != link.Ready && !link.Closed)
        {
            await link.CloseAsync(WebSocketCloseStatus.PolicyViolation, "expected hello").ConfigureAwait(false);
        }
        await run.ConfigureAwait(false);
    }

    async Task<MeshLink?> DialAsync(TrustEntry entry, string address, int port, string kind)
    {
        var timeout = kind == "tailnet" ? TailnetTimeout : LanTimeout;
        var handler = MeshTls.Handler(Identity, entry.Fp, connectTimeout: timeout);
        var invoker = new HttpMessageInvoker(handler, disposeHandler: true);
        var ws = new ClientWebSocket();
        ws.Options.KeepAliveInterval = MeshProtocol.IdlePing;
        ws.Options.KeepAliveTimeout = MeshProtocol.DeadAfter - MeshProtocol.IdlePing;
        ws.Options.SetRequestHeader("User-Agent", MeshProtocol.UserAgent);
        var uri = new Uri($"wss://{Addresses.HostPort(address, port)}/mesh");
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(stop.Token);
            cts.CancelAfter(timeout * 3 + TimeSpan.FromSeconds(10));
            await ws.ConnectAsync(uri, invoker, cts.Token).ConfigureAwait(false);
        }
        catch (Exception e) when (e is WebSocketException or HttpRequestException or OperationCanceledException or IOException)
        {
            log.LogDebug("mesh: couldn't open a link to {Who} at {Address}:{Port}: {Error}", entry.Name, address, port, e.Message);
            ws.Dispose();
            invoker.Dispose();
            return null;
        }
        var link = new MeshLink(ws, entry.Fp, address, outbound: true, OnMessageAsync, OnClose, log, invoker) { Kind = kind, Port = port };
        AddLink(link);
        Track(link.RunAsync());
        await link.SendAsync(Hello()).ConfigureAwait(false);
        var ok = await Task.WhenAny(link.Ready, Task.Delay(MeshProtocol.HelloTimeout, stop.Token)).ConfigureAwait(false) == link.Ready &&
                 link.Ready.IsCompletedSuccessfully;
        if (!ok)
        {
            await link.CloseAsync(WebSocketCloseStatus.PolicyViolation, "no welcome").ConfigureAwait(false);
            return null;
        }
        Trust.Learn(entry.Fp, address, port, tailnet: kind == "tailnet");
        return link;
    }

    /// <summary>Where a peer may be reached: mDNS first, then its known LAN addresses, then its tailnet address.</summary>
    List<(string Address, int Port, string Kind)> Candidates(TrustEntry entry)
    {
        var output = new List<(string, int, string)>();
        var port = entry.Port ?? MeshProtocol.DefaultPort;
        if (directory is not null)
        {
            foreach (var s in directory.ByFp(entry.Fp))
            {
                output.AddRange(s.Addresses.Select(a => (a, s.Port, Addresses.IsTailnet(a) ? "tailnet" : "lan")));
            }
        }
        output.AddRange(entry.Lan.Select(a => (a, port, Addresses.IsTailnet(a) ? "tailnet" : "lan")));
        if (entry.TailnetIp is { } t)
        {
            output.Add((t, port, "tailnet"));
        }
        // LAN first, then the tailnet (a stable sort), each address and port once
        return output.OrderBy(c => c.Item3 == "tailnet").DistinctBy(c => (c.Item1, c.Item2)).ToList();
    }

    /// <summary>The newest ready link with a peer, or null.</summary>
    public MeshLink? OpenLink(string fp)
    {
        lock (gate)
        {
            return links.TryGetValue(fp, out var list)
                ? list.Where(l => l.IsReady && !l.Closed).MaxBy(l => l.Opened)
                : null;
        }
    }

    /// <summary>An open link with the peer (routes 1–2), dialling one if need be.</summary>
    public async Task<MeshLink?> DirectAsync(string fp, bool dial = true)
    {
        var link = OpenLink(fp);
        if (link is not null || !dial)
        {
            return link;
        }
        if (Trust.Get(fp) is not { } entry)
        {
            return null;
        }
        var gateFp = dialLocks.GetOrAdd(fp, _ => new SemaphoreSlim(1, 1));
        await gateFp.WaitAsync(stop.Token).ConfigureAwait(false);
        try
        {
            link = OpenLink(fp);
            if (link is not null)
            {
                return link;
            }
            foreach (var (address, port, kind) in Candidates(entry))
            {
                link = await DialAsync(entry, address, port, kind).ConfigureAwait(false);
                if (link is not null)
                {
                    log.LogInformation("mesh: link open with {Who} over {Kind} ({Address}:{Port})", entry.Name, kind, address, port);
                    return link;
                }
            }
            return null;
        }
        finally
        {
            gateFp.Release();
        }
    }

    /// <summary>Sends to every peer with an open link (state, clipboard). Returns whether any got it.</summary>
    public async Task<bool> BroadcastAsync(JsonObject msg)
    {
        List<string> fps;
        lock (gate)
        {
            fps = [.. links.Keys];
        }
        var sent = false;
        foreach (var fp in fps)
        {
            if (OpenLink(fp) is { } link)
            {
                sent |= await link.SendAsync((JsonObject)msg.DeepClone()).ConfigureAwait(false);
            }
        }
        return sent;
    }

    // --- what arrives -----------------------------------------------------------------------------

    [GeneratedRegex("^[0-9A-Za-z_-]{8,64}$")]
    private static partial Regex TextIdPattern();

    async Task OnMessageAsync(MeshLink link, JsonObject msg)
    {
        if (Trust.Get(link.Fp) is not { } entry)
        {
            await link.CloseAsync(WebSocketCloseStatus.PolicyViolation, "not trusted").ConfigureAwait(false);
            return;
        }
        var t = msg.Str("t");
        if (!link.IsReady)
        {
            if ((t == "hello" && !link.Outbound) || (t == "welcome" && link.Outbound))
            {
                link.Hello = msg;
                Trust.Learn(link.Fp, link.Outbound ? null : link.Address, msg.Int("port"), msg.Str("name"), msg.Str("id"), msg.Str("os"),
                    msg["caps"] is JsonArray ? msg.Strings("caps") : null, link.Kind == "tailnet");
                if (t == "hello")
                {
                    await link.SendAsync(Hello("welcome")).ConfigureAwait(false);
                    log.LogInformation("mesh: {Who} connected from {Address}", entry.Name, link.Address);
                }
                link.MarkReady();
                foreach (var (kind, data) in host.LastStates)
                {
                    await link.SendAsync(new JsonObject { ["t"] = "state", ["kind"] = kind, ["data"] = data?.DeepClone() }).ConfigureAwait(false);
                }
                Kick();
            }
            return; // nothing else counts before the hello
        }
        switch (t)
        {
            case "ping":
                await link.SendAsync(new JsonObject { ["t"] = "pong" }).ConfigureAwait(false);
                break;
            case "text":
                await ReceiveTextAsync(link, entry, msg).ConfigureAwait(false);
                break;
            case "ack" or "nack":
                if (msg.Str("id") is { } id)
                {
                    var error = msg.Str("error") ?? "";
                    error = error.Length > 200 ? error[..200] : error;
                    if (acks.TryGetValue(id, out var w) && w.Fp == link.Fp)
                    {
                        w.Waiter.TrySetResult((t == "ack", error));
                    }
                    if (offers.TryGetValue(id, out var o) && o.Fp == link.Fp)
                    {
                        o.Finish(t == "ack", error);
                    }
                }
                break;
            case "offer":
                await ReceiveOfferAsync(link, entry, msg).ConfigureAwait(false);
                break;
            case "ring":
                log.LogInformation("mesh: {Who} is ringing this device", entry.Name);
                services.Notifications?.Show(new Notification
                {
                    Title = $"{entry.Name} is looking for this PC", Body = "droplet is ringing it.", Tag = "droplet-ring", Urgent = true,
                    Click = new NotificationAction("Stop", NotificationActionKind.StopRing),
                    Buttons = [new NotificationAction("Stop", NotificationActionKind.StopRing)],
                });
                services.Sound?.StartRing();
                Rung?.Invoke(entry.Name);
                break;
            case "ring-stop":
                services.Sound?.StopRing();
                services.Notifications?.Clear("droplet-ring");
                Rung?.Invoke(null);
                break;
            case "notify":
                {
                    var app = msg.Str("app") ?? entry.Name;
                    app = app.Length > 40 ? app[..40] : app;
                    var title = msg.Str("title") ?? app;
                    var key = msg.Str("key");
                    services.Notifications?.Show(new Notification
                    {
                        Title = Clip($"{title} ({entry.Name})", 200), Body = Clip(msg.Str("text") ?? "", 1000),
                        Tag = key is null ? null : $"{link.Fp}:{key}", App = app,
                    });
                    break;
                }
            case "notify-removed":
                if (msg.Str("key") is { } removed)
                {
                    services.Notifications?.Clear($"{link.Fp}:{removed}");
                }
                break;
            case "unpair":
                if (entry.Source == TrustSource.Paired)
                {
                    log.LogInformation("mesh: {Who} unpaired from this device", entry.Name);
                    Trust.Remove(link.Fp);
                }
                else
                {
                    log.LogInformation("mesh: {Who} asked to unpair, but the hub vouches for it; remove it on the hub", entry.Name);
                }
                break;
            case "state":
                {
                    var kind = msg.Str("kind") ?? "";
                    kind = kind.Length > 20 ? kind[..20] : kind;
                    if (kind.Length > 0)
                    {
                        var data = msg["data"]?.DeepClone();
                        peerState.GetOrAdd(link.Fp, _ => new())[kind] = data;
                        PeerStateChanged?.Invoke(link.Fp, kind, data);
                    }
                    break;
                }
            case "input" or "media" or "cmd" or "clip" or "rpc":
                {
                    if (dispatcher is null)
                    {
                        break;
                    }
                    var output = (JsonObject)msg.DeepClone();
                    output.Remove("to");
                    // who sent it is the authenticated peer, whatever the message says
                    output["from"] = new JsonObject { ["id"] = entry.Id, ["name"] = entry.Name };
                    if (t == "input")
                    {
                        link.SentInput = true;
                    }
                    await dispatcher.DispatchAsync(output, new PeerSource(this, link.Fp)).ConfigureAwait(false);
                    break;
                }
            // hello, welcome, pong, rpc-result and anything newer: nothing to do
        }
    }

    static string Clip(string s, int n) => s.Length > n ? s[..n] : s;

    async Task ReceiveTextAsync(MeshLink link, TrustEntry entry, JsonObject msg)
    {
        var mid = msg.Str("id");
        var body = msg.Str("body");
        if (mid is null || !TextIdPattern().IsMatch(mid))
        {
            return;
        }
        if (string.IsNullOrEmpty(body) || Encoding.UTF8.GetByteCount(body) > MeshProtocol.MaxText)
        {
            await link.SendAsync(new JsonObject { ["t"] = "nack", ["id"] = mid, ["error"] = "empty or too long" }).ConfigureAwait(false);
            return;
        }
        var ts = msg.Num("ts") ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() / 1000.0;
        var chat = new ChatEntry { Id = $"{link.Fp[..16]}:{mid}", Dir = "in", Fp = link.Fp, Peer = entry.Id, Name = entry.Name, Body = body, Ts = ts };
        var fresh = Chat.Add(chat);
        await link.SendAsync(new JsonObject { ["t"] = "ack", ["id"] = mid }).ConfigureAwait(false);
        if (fresh)
        {
            log.LogInformation("mesh: message from {Who}", entry.Name);
            services.Notifications?.Show(new Notification { Title = entry.Name, Body = body, Tag = $"chat-{link.Fp[..16]}" });
            TextReceived?.Invoke(chat);
        }
    }

    async Task ReceiveOfferAsync(MeshLink link, TrustEntry entry, JsonObject msg)
    {
        OfferInfo offer;
        try
        {
            offer = FileReceiver.Check(msg);
        }
        catch (DownloadException e)
        {
            await link.SendAsync(new JsonObject { ["t"] = "nack", ["id"] = msg.Str("id") ?? "", ["error"] = e.Message }).ConfigureAwait(false);
            return;
        }
        if (Completed.Has(link.Fp, offer.Id) is not null)
        {
            // already here: the last ack must have been lost
            await link.SendAsync(new JsonObject { ["t"] = "ack", ["id"] = offer.Id }).ConfigureAwait(false);
            return;
        }
        lock (gate)
        {
            if (!downloading.Add((link.Fp, offer.Id)))
            {
                return;
            }
        }
        var hosts = new List<(string, int)>
        {
            (link.Address, link.Outbound ? link.Port ?? MeshProtocol.DefaultPort : Clean.Port(link.Hello.Int("port")) ?? MeshProtocol.DefaultPort),
        };
        hosts.AddRange(Candidates(entry).Select(c => (c.Address, c.Port)).Where(h => !hosts.Contains(h)));
        Track(Task.Run(() => DownloadAsync(entry, offer, hosts)));
    }

    async Task DownloadAsync(TrustEntry entry, OfferInfo offer, List<(string, int)> hosts)
    {
        JsonObject? reply = null;
        try
        {
            log.LogInformation("mesh: receiving {Name} ({Size} bytes) from {Who}", offer.Name, offer.Size, entry.Name);
            var path = await FileReceiver.DownloadAsync(Identity, entry.Fp, hosts, offer, options.Downloads,
                m => log.LogInformation("mesh: {Message}", m), ct: stop.Token).ConfigureAwait(false);
            Completed.Add(entry.Fp, offer.Id, path);
            log.LogInformation("mesh: saved {Path} from {Who}", path, entry.Name);
            services.Notifications?.Show(new Notification
            {
                Title = $"{entry.Name} sent a file", Body = Path.GetFileName(path), Tag = $"file-{offer.Id}",
                Click = new NotificationAction("Open", NotificationActionKind.OpenFile, path),
                Buttons = [new NotificationAction("Show in folder", NotificationActionKind.ShowInFolder, path)],
            });
            FileReceived?.Invoke(entry, path);
            reply = new JsonObject { ["t"] = "ack", ["id"] = offer.Id };
        }
        catch (DownloadException e)
        {
            log.LogWarning("mesh: receiving {Name} from {Who} failed: {Error}", offer.Name, entry.Name, e.Message);
            reply = e.Permanent ? new JsonObject { ["t"] = "nack", ["id"] = offer.Id, ["error"] = e.Message } : null;
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception e)
        {
            log.LogWarning(e, "mesh: receiving {Name} failed", offer.Name);
        }
        finally
        {
            lock (gate)
            {
                downloading.Remove((entry.Fp, offer.Id));
            }
        }
        if (reply is not null && !stop.IsCancellationRequested)
        {
            var link = await DirectAsync(entry.Fp).ConfigureAwait(false);
            if (link is null || !await link.SendAsync(reply).ConfigureAwait(false))
            {
                log.LogInformation("mesh: couldn't tell {Who} about {Name}; it will offer it again", entry.Name, offer.Name);
            }
        }
    }

    // --- sending: live messages (routes 1–3) -----------------------------------------------------

    TrustEntry EntryOf(string fp) => Trust.Get(fp) ?? throw new NoRouteException("that peer isn't trusted");

    bool HubKnows(TrustEntry entry) => host.HubId is { Length: > 0 } hubId && entry.Hub == hubId && host.HubConnected;

    /// <summary>The hub knows the peer and the peer is connected to it now: live messages can go that way.</summary>
    bool HubHasItLive(TrustEntry entry) => HubKnows(entry) && host.HubOnline(entry.Id);

    /// <summary>Sends <c>input</c>, <c>media</c> or <c>cmd</c>: directly, else through the hub. Returns the route.</summary>
    public async Task<string> SendLiveAsync(string fp, JsonObject msg)
    {
        ArgumentNullException.ThrowIfNull(msg);
        if (!Live.Contains(msg.Str("t")))
        {
            throw new ArgumentException($"only {string.Join(", ", Live)} messages can be sent this way", nameof(msg));
        }
        var entry = EntryOf(fp);
        if (await DirectAsync(fp).ConfigureAwait(false) is { } link && await link.SendAsync(msg).ConfigureAwait(false))
        {
            return link.Kind;
        }
        if (HubHasItLive(entry))
        {
            var viaHub = (JsonObject)msg.DeepClone();
            viaHub["to"] = entry.Id;
            if (await host.HubSendAsync(viaHub).ConfigureAwait(false))
            {
                return "hub";
            }
        }
        throw new NoRouteException($"{entry.Name} isn't reachable directly, and not through the hub either");
    }

    /// <summary>Rings a peer (or stops): directly, else through the hub's ring API. Returns the route.</summary>
    public async Task<string> RingAsync(string fp, bool stopRing = false)
    {
        var entry = EntryOf(fp);
        if (await DirectAsync(fp).ConfigureAwait(false) is { } link &&
            await link.SendAsync(new JsonObject { ["t"] = stopRing ? "ring-stop" : "ring" }).ConfigureAwait(false))
        {
            return link.Kind;
        }
        if (HubKnows(entry))
        {
            await host.HubRingAsync(entry.Id, stopRing, stop.Token).ConfigureAwait(false);
            return "hub";
        }
        throw new NoRouteException($"{entry.Name} isn't reachable directly, and not through the hub either");
    }

    /// <summary>
    /// Sends clipboard text to a peer: directly, else through the hub (which has no
    /// addressed clipboard message: it reaches all your devices' clipboards). Returns the route.
    /// </summary>
    public async Task<string> ClipAsync(string fp, string text)
    {
        var entry = EntryOf(fp);
        if (string.IsNullOrEmpty(text) || Encoding.UTF8.GetByteCount(text) > MeshProtocol.MaxClip)
        {
            throw new ArgumentException("clipboard text must be 1 byte to 256 KB", nameof(text));
        }
        if (await DirectAsync(fp).ConfigureAwait(false) is { } link &&
            await link.SendAsync(new JsonObject { ["t"] = "clip", ["text"] = text }).ConfigureAwait(false))
        {
            return link.Kind;
        }
        if (HubHasItLive(entry) && await host.HubSendAsync(new JsonObject { ["t"] = "clip", ["text"] = text }).ConfigureAwait(false))
        {
            return "hub";
        }
        throw new NoRouteException($"{entry.Name} isn't reachable directly, and not through the hub either");
    }

    // --- sending: chat and files (routes 1–5) ------------------------------------------------------

    /// <summary>Queues a chat message. Follow it with <see cref="WaitJobAsync"/>.</summary>
    public OutboxJob SendText(string fp, string body)
    {
        var entry = EntryOf(fp);
        if (string.IsNullOrWhiteSpace(body) || Encoding.UTF8.GetByteCount(body) > MeshProtocol.MaxText)
        {
            throw new ArgumentException("a message must be 1 byte to 64 KB of text", nameof(body));
        }
        var job = Outbox.AddText(fp, entry.Name, body);
        Kick();
        return job;
    }

    /// <summary>Queues a file (sent from where it is, not copied).</summary>
    public OutboxJob SendFile(string fp, string path)
    {
        var entry = EntryOf(fp);
        var full = Path.GetFullPath(path);
        if (!File.Exists(full))
        {
            throw new ArgumentException($"{full} isn't a file", nameof(path));
        }
        var job = Outbox.AddFile(fp, entry.Name, full, SafeName.Of(Path.GetFileName(full)), MimeTypes.Of(full));
        Kick();
        return job;
    }

    /// <summary>Sends data as a file (a screenshot): written under the data folder, removed once delivered.</summary>
    public OutboxJob SendBytes(string fp, string name, byte[] data, string mime)
    {
        var entry = EntryOf(fp);
        var dir = Path.Combine(options.DataDir, "sending");
        AtomicFile.CreatePrivateDirectory(dir);
        var p = Path.Combine(dir, $"{Hex.Random(4)}-{SafeName.Of(name)}");
        File.WriteAllBytes(p, data);
        var job = Outbox.AddFile(fp, entry.Name, p, SafeName.Of(name), mime, cleanup: true);
        Kick();
        return job;
    }

    /// <summary>
    /// The job once it's delivered, failed, or found no route (or the time is up). A
    /// transfer that stopped part-way and is being retried isn't an answer yet.
    /// </summary>
    public Task<OutboxJob?> WaitJobAsync(string id, TimeSpan timeout, CancellationToken ct = default) =>
        Outbox.WaitAsync(id, j => j.State is JobState.Done or JobState.Failed || (j.State == JobState.Queued && j.Attempts > 0 && !j.Retry),
            timeout, ct);

    async Task DeliverLoopAsync()
    {
        while (!stop.IsCancellationRequested)
        {
            try
            {
                await kick.WaitAsync(options.RetryEvery, stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            foreach (var fp in Outbox.Queued().Select(j => j.Fp).Distinct())
            {
                lock (workersGate)
                {
                    if (!workers.Add(fp))
                    {
                        continue;
                    }
                }
                _ = Task.Run(() => WorkPeerAsync(fp));
            }
        }
    }

    /// <summary>Delivers one peer's jobs in order, until one has to wait for a route.</summary>
    async Task WorkPeerAsync(string fp)
    {
        try
        {
            while (!stop.IsCancellationRequested)
            {
                List<OutboxJob> jobs;
                lock (workersGate)
                {
                    jobs = Outbox.ForPeer(fp);
                    if (jobs.Count == 0)
                    {
                        workers.Remove(fp);
                        return;
                    }
                }
                if (await AttemptAsync(jobs[0]).ConfigureAwait(false) == "wait")
                {
                    break;
                }
            }
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            log.LogWarning(e, "mesh: delivering to {Fp}", Fingerprint.Short(fp));
        }
        catch (OperationCanceledException)
        {
        }
        lock (workersGate)
        {
            workers.Remove(fp);
        }
    }

    void Finish(OutboxJob job, string state, string? route = null, string? error = null)
    {
        var done = Outbox.Update(job.Id, j => j with { State = state, Route = route, Error = error }) ?? job;
        if (state is JobState.Done or JobState.Failed && job.Cleanup && job.Path is not null)
        {
            try
            {
                File.Delete(job.Path);
            }
            catch (IOException)
            {
            }
        }
        if (state == JobState.Done && job.Kind == "text")
        {
            var entry = Trust.Get(job.Fp);
            Chat.Add(new ChatEntry
            {
                Id = job.Id, Dir = "out", Fp = job.Fp, Peer = entry?.Id, Name = entry?.Name, Body = job.Body ?? "", Ts = job.Created, Route = route,
            });
        }
        if (state == JobState.Done)
        {
            log.LogInformation("mesh: {What} to {Peer} delivered ({Route})", job.Kind == "text" ? "message" : job.Name, job.Peer, route);
        }
        else if (state == JobState.Failed)
        {
            log.LogWarning("mesh: {Kind} to {Peer} failed: {Error}", job.Kind, job.Peer, error);
        }
        JobFinished?.Invoke(done);
    }

    static string? FileChanged(OutboxJob job)
    {
        var fi = new FileInfo(job.Path ?? "");
        if (!fi.Exists)
        {
            return $"{job.Path} is gone";
        }
        if (fi.Length != job.Size || fi.LastWriteTimeUtc.Ticks != job.Mtime)
        {
            return $"{job.Path} changed after it was sent";
        }
        return null;
    }

    async Task<string> AttemptAsync(OutboxJob job)
    {
        if (Trust.Get(job.Fp) is not { } entry)
        {
            Finish(job, JobState.Failed, error: "that peer isn't trusted any more");
            return "done";
        }
        if (job.Kind == "file" && FileChanged(job) is { } why)
        {
            Finish(job, JobState.Failed, error: why);
            return "done";
        }
        Outbox.Update(job.Id, j => j with { State = JobState.Sending, Attempts = j.Attempts + 1 });
        if (await DirectAsync(job.Fp).ConfigureAwait(false) is { } link)
        {
            var got = job.Kind == "text" ? await DirectTextAsync(link, job).ConfigureAwait(false) : await DirectFileAsync(link, job).ConfigureAwait(false);
            if (got == "ok")
            {
                Finish(job, JobState.Done, route: link.Kind);
                return "done";
            }
            if (got.StartsWith("refused:", StringComparison.Ordinal))
            {
                var reason = got["refused:".Length..].Trim();
                Finish(job, JobState.Failed, error: reason.Length > 0 ? reason : "the peer refused it");
                return "done";
            }
            // the peer is there but it didn't finish: try again soon, directly
            Outbox.Update(job.Id, j => j with { State = JobState.Queued, Error = got, Retry = true });
            _ = Task.Delay(TimeSpan.FromSeconds(3), stop.Token).ContinueWith(_ => Kick(), TaskScheduler.Default);
            return "wait";
        }
        string err;
        if (HubKnows(entry))
        {
            try
            {
                if (job.Kind == "text")
                {
                    await host.HubTextAsync(entry.Id, job.Body!, stop.Token).ConfigureAwait(false);
                }
                else
                {
                    await host.HubUploadAsync(entry.Id, job.Path!, job.Name!, job.Mime!, stop.Token).ConfigureAwait(false);
                }
                Finish(job, JobState.Done, route: host.HubOnline(entry.Id) ? "hub" : "hub-mailbox");
                return "done";
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                log.LogInformation("mesh: through the hub failed: {Error}", e.Message);
                err = $"the hub: {e.Message}";
            }
        }
        else
        {
            err = "not reachable directly, and no hub knows it right now";
        }
        Outbox.Update(job.Id, j => j with { State = JobState.Queued, Error = err, Retry = false });
        return "wait";
    }

    async Task<string> DirectTextAsync(MeshLink link, OutboxJob job)
    {
        var waiter = new TaskCompletionSource<(bool Ok, string Error)>(TaskCreationOptions.RunContinuationsAsynchronously);
        acks[job.Id] = (waiter, link.Fp);
        try
        {
            if (!await link.SendAsync(new JsonObject { ["t"] = "text", ["id"] = job.Id, ["body"] = job.Body, ["ts"] = job.Created }).ConfigureAwait(false))
            {
                return "the link dropped";
            }
            try
            {
                var (ok, error) = await waiter.Task.WaitAsync(AckTimeout, stop.Token).ConfigureAwait(false);
                return ok ? "ok" : $"refused: {error}";
            }
            catch (TimeoutException)
            {
                return "no answer";
            }
        }
        finally
        {
            acks.TryRemove(job.Id, out _);
        }
    }

    async Task<string> DirectFileAsync(MeshLink link, OutboxJob job)
    {
        var offer = new Offer(job.Id, link.Fp, job.Name!, job.Size, job.Mime!,
            () => new FileStream(job.Path!, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 1, true),
            () => FileChanged(job));
        offers[job.Id] = offer;
        try
        {
            if (!await link.SendAsync(offer.Message()).ConfigureAwait(false))
            {
                return "the link dropped";
            }
            while (true)
            {
                try
                {
                    var (ok, error) = await offer.Done.WaitAsync(TimeSpan.FromSeconds(1), stop.Token).ConfigureAwait(false);
                    return ok ? "ok" : $"refused: {error}";
                }
                catch (TimeoutException)
                {
                }
                var idle = offer.Idle;
                if (idle > Stall || (link.Closed && idle > TimeSpan.FromSeconds(5)))
                {
                    // gone quiet, or the peer went away: offer it again later, and it resumes
                    return $"stopped at {offer.Sent} bytes sent";
                }
                if (Trust.Get(link.Fp) is null)
                {
                    return "refused: not trusted any more";
                }
            }
        }
        finally
        {
            offers.TryRemove(job.Id, out _);
        }
    }

    /// <inheritdoc/>
    public Offer? GetOffer(string id) => offers.GetValueOrDefault(id);

    /// <inheritdoc/>
    public long MaxRate => options.MaxRate;

    // --- pairing ---------------------------------------------------------------------------------

    /// <inheritdoc/>
    public (int Status, JsonObject Body) Pair(string method, string path, JsonObject? body)
    {
        var (action, rid) = MeshServer.PairRoute(path);
        return (action, method) switch
        {
            ("open", "POST") => Incoming.Open(body, PeerId, Name),
            ("open", _) => (405, new JsonObject { ["error"] = "POST" }),
            ("status", "GET") => Incoming.Status(rid!),
            ("confirm", "POST") => Incoming.Confirm(rid!, body),
            ("cancel", "POST") => Incoming.Cancel(rid!),
            ("", _) => (404, new JsonObject { ["error"] = "not found" }),
            _ => (405, new JsonObject { ["error"] = "method not allowed" }),
        };
    }

    void OnPairRequest(PairRequestInfo req)
    {
        log.LogWarning("mesh: {Who} ({Id}) wants to pair, code {Code}", req.Name, req.Id, req.Code);
        services.Notifications?.Show(new Notification
        {
            Title = $"{req.Name} wants to pair", Body = $"Code {req.Code}. Accept only if {req.Name} shows the same code.",
            Tag = $"pair-{req.Request}", Urgent = true,
            Buttons =
            [
                new NotificationAction("Accept", NotificationActionKind.AcceptPairing, req.Request),
                new NotificationAction("Deny", NotificationActionKind.DenyPairing, req.Request),
            ],
        });
        PairingRequested?.Invoke(req);
    }

    [GeneratedRegex(@"^\[?([0-9A-Za-z.:\-]+?)\]?(?::(\d{1,5}))?$")]
    private static partial Regex AddressPattern();

    [GeneratedRegex(@"^([0-9.]+|[0-9a-fA-F:]+:[0-9a-fA-F:]*|[A-Za-z0-9.\-]+\.[A-Za-z]+)$")]
    private static partial Regex HostPattern();

    (string Host, int Port, string? Expect) PairTarget(string target)
    {
        var t = (target ?? "").Trim();
        var q = t.ToLowerInvariant();
        var seen = directory?.Peers() ?? [];
        var matches = seen.Where(s => string.Equals(s.Name, t, StringComparison.OrdinalIgnoreCase) || s.Id == q ||
                                      (q.Length >= 8 && s.Fp.StartsWith(q, StringComparison.Ordinal))).ToList();
        var distinct = matches.Select(s => s.Fp).Distinct().Count();
        if (distinct == 1)
        {
            var s = matches[0];
            return (s.Addresses[0], s.Port, s.Fp);
        }
        if (distinct > 1)
        {
            throw new ArgumentException($"more than one peer on this network is called {t}; use its id");
        }
        var m = AddressPattern().Match(t);
        if (m.Success && (m.Groups[2].Success || HostPattern().IsMatch(m.Groups[1].Value)))
        {
            var port = m.Groups[2].Success ? int.Parse(m.Groups[2].Value, System.Globalization.CultureInfo.InvariantCulture) : MeshProtocol.DefaultPort;
            if (port is <= 0 or >= 65536)
            {
                throw new ArgumentException("bad port");
            }
            return (m.Groups[1].Value, port, null);
        }
        throw new ArgumentException($"no peer called \"{t}\" is announcing itself on this network; give its address instead");
    }

    /// <summary>
    /// Asks a peer (by name or id on the LAN, or by address[:port]) to pair. Show the code;
    /// then <see cref="PairConfirmAsync"/> once the owner has compared it.
    /// </summary>
    public async Task<PairStart> PairStartAsync(string target, CancellationToken ct = default)
    {
        var (h, port, expect) = PairTarget(target);
        var og = new OutgoingPairing(Identity, PeerId, Name, h, port, expect);
        try
        {
            await og.StartAsync(ct).ConfigureAwait(false);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            og.Dispose();
            throw new PairException($"couldn't reach {h}:{port}: {e.Message}", e);
        }
        catch
        {
            og.Dispose();
            throw;
        }
        if (og.PeerFp == Identity.Fingerprint)
        {
            og.Dispose();
            throw new PairException("that's this device");
        }
        outgoing[og.Request!] = og;
        return new PairStart(og.Request!, og.Code!, og.PeerId, og.PeerName, og.PeerFp, Addresses.HostPort(h, port));
    }

    /// <summary>
    /// The owner here says whether the codes match. Yes: wait (in the background) for the
    /// peer's answer, and trust it only when it accepts too. No: cancel.
    /// </summary>
    public async Task<string> PairConfirmAsync(string request, bool yes)
    {
        if (!outgoing.TryGetValue(request, out var og))
        {
            throw new ArgumentException("no such pairing request");
        }
        if (!yes)
        {
            await og.CancelAsync().ConfigureAwait(false);
            og.State = PairState.Cancelled;
            return og.State;
        }
        og.LocalOk = true;
        Track(Task.Run(() => PairWaitAsync(og)));
        return og.State;
    }

    /// <summary>Where a pairing this device started stands.</summary>
    public string PairStatus(string request) => outgoing.TryGetValue(request, out var og) ? og.State : PairState.Expired;

    async Task PairWaitAsync(OutgoingPairing og)
    {
        var end = DateTimeOffset.UtcNow + IncomingPairings.RequestTtl;
        while (DateTimeOffset.UtcNow < end && !stop.IsCancellationRequested)
        {
            string state;
            try
            {
                state = await og.PollAsync(stop.Token).ConfigureAwait(false);
            }
            catch (Exception e) when (e is HttpRequestException or TaskCanceledException or PairException)
            {
                log.LogDebug("mesh: polling the pairing request: {Error}", e.Message);
                state = PairState.Waiting;
            }
            if (state == PairState.Accepted && og.LocalOk)
            {
                try
                {
                    var tailnet = Addresses.IsTailnet(og.Host);
                    var entry = TrustEntry.Make(og.PeerId, og.PeerName, og.PeerCertPem, TrustSource.Paired, tailnet ? [] : [og.Host], og.Port,
                        tailnet ? og.Host : null, og.PeerOs);
                    Trust.AddPaired(entry);
                    log.LogInformation("mesh: paired with {Who} ({Fp})", entry.Name, entry.Fp);
                    og.State = PairState.Accepted;
                    Paired?.Invoke(entry);
                }
                catch (Exception e) when (e is FormatException or InvalidOperationException)
                {
                    log.LogWarning("mesh: pairing failed: {Error}", e.Message);
                    og.State = PairState.Expired;
                }
                return;
            }
            if (state is PairState.Denied or PairState.Expired or PairState.Cancelled)
            {
                og.State = state;
                return;
            }
            try
            {
                await Task.Delay(1500, stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
        og.State = PairState.Expired;
    }

    /// <summary>The owner's answer to a request another device made. Returns the peer when accepted.</summary>
    public TrustEntry? PairAnswer(string request, bool accept)
    {
        var r = Incoming.Answer(request, accept) ?? throw new ArgumentException("no such pairing request waiting (it may have expired)");
        services.Notifications?.Clear($"pair-{request}");
        if (!accept)
        {
            log.LogInformation("mesh: refused to pair with {Who}", r.Info.Name);
            return null;
        }
        var entry = TrustEntry.Make(r.Info.Id, r.Info.Name, Certificates.ToPem(r.Der), TrustSource.Paired, os: r.Info.Os);
        Trust.AddPaired(entry);
        log.LogInformation("mesh: paired with {Who} ({Fp})", entry.Name, entry.Fp);
        Paired?.Invoke(entry);
        return entry;
    }

    /// <summary>
    /// Stops trusting a directly paired peer, and tells it if it's reachable. A peer the
    /// hub vouches for can't be unpaired here: the next roster would bring it back.
    /// Returns whether the peer was told.
    /// </summary>
    public async Task<bool> UnpairAsync(string fp)
    {
        var entry = EntryOf(fp);
        if (entry.Source != TrustSource.Paired)
        {
            throw new InvalidOperationException($"{entry.Name} is trusted because your hub lists it. Remove it on the hub, and every device stops trusting it.");
        }
        var told = false;
        if (await DirectAsync(fp).ConfigureAwait(false) is { } link)
        {
            told = await link.SendAsync(new JsonObject { ["t"] = "unpair" }).ConfigureAwait(false);
        }
        Trust.Remove(fp);
        log.LogInformation("mesh: unpaired {Who}", entry.Name);
        return told;
    }

    // --- status -----------------------------------------------------------------------------------

    void OnSeen(SeenPeer s)
    {
        if (Trust.Get(s.Fp) is not null && Outbox.ForPeer(s.Fp).Count > 0)
        {
            Kick();
        }
    }

    /// <summary>Asks the outbox to try again now (the hub came back, say).</summary>
    public void Retry() => Kick();

    /// <summary>A trusted peer by name, id or fingerprint (prefix). Throws when none or several match.</summary>
    public TrustEntry Resolve(string query)
    {
        var found = Trust.Find(query);
        return found.Count switch
        {
            0 => throw new ArgumentException($"no trusted peer called \"{query}\""),
            1 => found[0],
            _ => throw new ArgumentException($"\"{query}\" matches more than one peer: " + string.Join(", ", found.Select(e => $"{e.Name} ({e.Id})"))),
        };
    }

    /// <summary>The latest state a peer published, by kind.</summary>
    public IReadOnlyDictionary<string, JsonNode?> StateOf(string fp) =>
        peerState.TryGetValue(fp, out var d) ? new Dictionary<string, JsonNode?>(d) : new Dictionary<string, JsonNode?>();

    /// <summary>A summary for the UI (and the tests): who this is, the peers, who's nearby, what waits.</summary>
    public JsonObject Status()
    {
        var seen = directory?.Peers() ?? [];
        var peers = new JsonArray();
        foreach (var e in Trust.All())
        {
            var link = OpenLink(e.Fp);
            var o = JsonSerializer.SerializeToNode(e) as JsonObject ?? [];
            o.Remove("cert_pem");
            o["link"] = link is null ? null : $"{link.Kind} {link.Address}";
            o["on_lan"] = seen.Any(s => s.Fp == e.Fp);
            peers.Add(o);
        }
        var trusted = Trust.All().Select(e => e.Fp).ToHashSet();
        return new JsonObject
        {
            ["id"] = PeerId, ["name"] = Name, ["fp"] = Identity.Fingerprint, ["port"] = Port, ["peers"] = peers,
            ["nearby"] = new JsonArray(seen.Where(s => !trusted.Contains(s.Fp)).Select(s => (JsonNode)new JsonObject
            {
                ["id"] = s.Id, ["name"] = s.Name, ["fp"] = s.Fp, ["os"] = s.Os, ["addresses"] = Json.Array(s.Addresses), ["port"] = s.Port,
            }).ToArray()),
            ["incoming"] = JsonSerializer.SerializeToNode(Incoming.Waiting()),
            ["outbox"] = JsonSerializer.SerializeToNode(Outbox.Queued()),
            ["refused"] = Refused,
        };
    }

    /// <summary>A message's source when it came over a direct link: replies and files go back to that peer.</summary>
    sealed class PeerSource(MeshNode node, string fp) : IRemoteSource
    {
        public string Kind => "peer";

        public async Task<bool> ReplyAsync(JsonObject message)
        {
            var link = await node.DirectAsync(fp).ConfigureAwait(false);
            return link is not null && await link.SendAsync(message).ConfigureAwait(false);
        }

        // to the peer that asked, whatever `to` says
        public Task DeliverFileAsync(string name, byte[] data, string mime, string to, CancellationToken ct = default)
        {
            node.SendBytes(fp, name, data, mime);
            return Task.CompletedTask;
        }
    }
}

/// <summary>A file's type from its extension, for offers.</summary>
public static class MimeTypes
{
    static readonly Microsoft.AspNetCore.StaticFiles.FileExtensionContentTypeProvider Provider = new();

    /// <summary>The type, or application/octet-stream.</summary>
    public static string Of(string path) => Provider.TryGetContentType(path, out var t) ? t : "application/octet-stream";
}
