using System.Net;
using System.Net.WebSockets;
using System.Text.Json.Nodes;
using Droplet.Core.Common;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Droplet.Core.Remote;

/// <summary>What the live connection needs, read again at every (re)connect and on <see cref="HubLiveSession.Reload"/>.</summary>
/// <param name="HubUrl">The route's base URL.</param>
/// <param name="Handler">How to connect along the route: the pinned LAN handler, or the verified one.</param>
/// <param name="Token">The device token, sent as a bearer.</param>
/// <param name="Session">The hub's session cookie after a PIN login, if any.</param>
/// <param name="Name">This device's name.</param>
/// <param name="Caps">The capabilities to offer; empty still connects (to hear rosters, and route mesh messages).</param>
public sealed record LiveParams(string HubUrl, HttpMessageHandler Handler, string Token, string? Session, string Name, IReadOnlyList<string> Caps);

/// <summary>The live connection's state, for the tray.</summary>
/// <param name="Offered">Capabilities offered.</param>
/// <param name="Live">Connected and welcomed.</param>
/// <param name="Problem">Why not live, in words.</param>
public sealed record LiveStatus(IReadOnlyList<string> Offered, bool Live, string Problem);

/// <summary>
/// The app's live connection to the hub (<c>wss://&lt;route&gt;/ws</c>, docs/remote.md §2):
/// hello with what's switched on, then remote-control messages to the dispatcher,
/// presence (who's online, for the mesh's routing) and roster changes. It pings every
/// 25 s, reconnects after any drop (1, 2, 4… up to 30 s), authenticates with the device
/// token as a bearer (plus the PIN sign-in's cookie), and sends no <c>Origin</c>.
/// </summary>
public sealed class HubLiveSession : IAsyncDisposable
{
    const int MaxFrame = 512 * 1024;   // the hub's limit for one frame
    const int ReadLimit = 1 << 20;
    static readonly TimeSpan PingEvery = TimeSpan.FromSeconds(25);
    static readonly TimeSpan MaxBackoff = TimeSpan.FromSeconds(30);
    static readonly TimeSpan DialTimeout = TimeSpan.FromSeconds(20);

    readonly Func<LiveParams?> parameters;
    readonly RemoteDispatcher dispatcher;
    readonly ILogger log;
    readonly string app;
    readonly SemaphoreSlim reload = new(0, 1);
    readonly SemaphoreSlim sendLock = new(1, 1);
    readonly Lock gate = new();
    readonly CancellationTokenSource stop = new();
    HashSet<string> online = [];
    ClientWebSocket? ws;
    LiveParams? connectedWith;
    LiveStatus status = new([], false, "");
    Task? loop;

    /// <summary>Creates a session; <see cref="Start"/> runs it.</summary>
    /// <param name="parameters">The current parameters, or null when there's nothing to connect to (no route, not let in).</param>
    /// <param name="dispatcher">Acts on what arrives.</param>
    /// <param name="app">e.g. "droplet-windows/2.0.0".</param>
    /// <param name="logger">Where to log.</param>
    public HubLiveSession(Func<LiveParams?> parameters, RemoteDispatcher dispatcher, string app, ILogger? logger = null)
    {
        this.parameters = parameters ?? throw new ArgumentNullException(nameof(parameters));
        this.dispatcher = dispatcher ?? throw new ArgumentNullException(nameof(dispatcher));
        this.app = app;
        log = logger ?? NullLogger.Instance;
    }

    /// <summary>The platform named in hello.</summary>
    public string Platform { get; init; } = "windows";

    /// <summary>Raised when the status changes.</summary>
    public event Action<LiveStatus>? StatusChanged;

    /// <summary>Raised when the hub says its mesh roster changed.</summary>
    public event Action? RosterChanged;

    /// <summary>Raised when the connection is welcomed.</summary>
    public event Action? Connected;

    /// <summary>The current status.</summary>
    public LiveStatus Status
    {
        get
        {
            lock (gate)
            {
                return status;
            }
        }
    }

    /// <summary>Whether the connection is up and welcomed.</summary>
    public bool IsLive => Status.Live;

    /// <summary>Whether a device has a live connection to the hub (from welcome and presence).</summary>
    public bool IsOnline(string deviceId)
    {
        lock (gate)
        {
            return online.Contains(deviceId);
        }
    }

    /// <summary>Starts connecting (and keeps at it).</summary>
    public void Start() => loop ??= Task.Run(RunAsync);

    /// <summary>Re-reads the parameters, reconnecting if they changed (a new route or token, a switch turned off...).</summary>
    public void Reload()
    {
        try
        {
            reload.Release();
        }
        catch (SemaphoreFullException)
        {
        }
    }

    void SetStatus(LiveStatus s)
    {
        lock (gate)
        {
            if (status.Live == s.Live && status.Problem == s.Problem && status.Offered.SequenceEqual(s.Offered))
            {
                return;
            }
            status = s;
        }
        StatusChanged?.Invoke(s);
    }

    async Task RunAsync()
    {
        var backoff = TimeSpan.FromSeconds(1);
        while (!stop.IsCancellationRequested)
        {
            var p = parameters();
            if (p is null)
            {
                SetStatus(new LiveStatus([], false, ""));
                try
                {
                    await reload.WaitAsync(stop.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                continue;
            }
            var (welcomed, error) = await ConnectAsync(p).ConfigureAwait(false);
            if (stop.IsCancellationRequested)
            {
                break;
            }
            if (error is ReloadException)
            {
                backoff = TimeSpan.FromSeconds(1);
                continue;
            }
            if (welcomed)
            {
                backoff = TimeSpan.FromSeconds(1);
            }
            var problem = Describe(error, p);
            log.LogInformation("live connection: {Problem} (retrying in {Seconds} s)", problem, backoff.TotalSeconds);
            SetStatus(new LiveStatus(p.Caps, false, problem));
            try
            {
                if (await reload.WaitAsync(backoff, stop.Token).ConfigureAwait(false))
                {
                    backoff = TimeSpan.FromSeconds(1);
                    continue;
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            backoff = TimeSpan.FromTicks(Math.Min(backoff.Ticks * 2, MaxBackoff.Ticks));
        }
        SetStatus(new LiveStatus([], false, ""));
    }

    sealed class ReloadException : Exception;

    sealed class DialException(HttpStatusCode? status, string? location, Exception inner) : Exception(inner.Message, inner)
    {
        public HttpStatusCode? Status { get; } = status;
        public string? Location { get; } = location;
    }

    static string Describe(Exception? error, LiveParams p)
    {
        var host = Uri.TryCreate(p.HubUrl, UriKind.Absolute, out var u) ? u.Authority : p.HubUrl;
        return error switch
        {
            null => "disconnected",
            DialException { Status: { } s, Location: { } l } when (int)s is >= 300 and < 400 && l.Contains("/login", StringComparison.Ordinal) =>
                "the hub wants a PIN (open Settings)",
            DialException { Status: HttpStatusCode.Forbidden } => "this PC hasn't been let in to the hub (open Settings)",
            DialException { Status: HttpStatusCode.NotFound } => "this hub has no live connections (update the hub)",
            DialException { Status: { } s } => $"the hub refused the connection ({(int)s})",
            DialException => "can't reach " + host,
            WebSocketException { WebSocketErrorCode: WebSocketError.ConnectionClosedPrematurely } => "lost the connection to " + host,
            ClosedException c when c.Status == WebSocketCloseStatus.PolicyViolation && c.Reason.Contains("name this device", StringComparison.Ordinal) =>
                "the hub doesn't know this PC any more (removed?)",
            ClosedException c => $"the hub closed the connection ({(int?)c.Status} {c.Reason})",
            TimeoutException => "no answer from " + host,
            _ => error.Message,
        };
    }

    sealed class ClosedException(WebSocketCloseStatus? status, string reason) : Exception($"closed: {status} {reason}")
    {
        public WebSocketCloseStatus? Status { get; } = status;
        public string Reason { get; } = reason;
    }

    static Uri WsUrl(string hubUrl)
    {
        var b = new UriBuilder(Hub.HubClient.ParseUrl(hubUrl));
        b.Scheme = b.Scheme == Uri.UriSchemeHttps ? "wss" : "ws";
        b.Path = b.Path.TrimEnd('/') + "/ws";
        return b.Uri;
    }

    /// <summary>One connection. Returns whether the hub welcomed it, and why it ended.</summary>
    async Task<(bool Welcomed, Exception? Error)> ConnectAsync(LiveParams p)
    {
        var socket = new ClientWebSocket();
        socket.Options.SetRequestHeader("Authorization", "Bearer " + p.Token);
        if (!string.IsNullOrEmpty(p.Session))
        {
            socket.Options.SetRequestHeader("Cookie", "session=" + p.Session); // a PIN hub's sign-in
        }
        socket.Options.SetRequestHeader("User-Agent", app);
        socket.Options.CollectHttpResponseDetails = true;
        // pings are the app's own ({"t":"ping"}), as the hub expects; no Origin header:
        // the hub refuses handshakes from other sites' pages
        socket.Options.KeepAliveInterval = TimeSpan.Zero;
        using var invoker = new HttpMessageInvoker(p.Handler, disposeHandler: false);
        using var conn = CancellationTokenSource.CreateLinkedTokenSource(stop.Token);
        try
        {
            using (var dial = CancellationTokenSource.CreateLinkedTokenSource(stop.Token))
            {
                dial.CancelAfter(DialTimeout);
                try
                {
                    await socket.ConnectAsync(WsUrl(p.HubUrl), invoker, dial.Token).ConfigureAwait(false);
                }
                catch (Exception e) when (e is WebSocketException or HttpRequestException or OperationCanceledException && !stop.IsCancellationRequested)
                {
                    var location = socket.HttpResponseHeaders?.TryGetValue("Location", out var l) == true ? l.FirstOrDefault() : null;
                    var code = socket.HttpStatusCode == 0 ? (HttpStatusCode?)null : socket.HttpStatusCode;
                    return (false, new DialException(code, location, e));
                }
            }
            lock (gate)
            {
                ws = socket;
                connectedWith = p;
            }
            await SendAsync(new JsonObject { ["t"] = "hello", ["caps"] = Json.Array(p.Caps), ["platform"] = Platform, ["app"] = app }).ConfigureAwait(false);
            JsonObject? welcome;
            using (var w = CancellationTokenSource.CreateLinkedTokenSource(stop.Token))
            {
                w.CancelAfter(DialTimeout);
                try
                {
                    welcome = await ReadAsync(socket, w.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (!stop.IsCancellationRequested)
                {
                    return (false, new TimeoutException("no welcome"));
                }
            }
            if (welcome?.Str("t") != "welcome")
            {
                return (false, new ClosedException(socket.CloseStatus, welcome is null ? socket.CloseStatusDescription ?? "" : $"answered {welcome.Str("t")} instead of welcome"));
            }
            UpdateOnline(welcome["devices"] as JsonObject);
            log.LogInformation("live: connected to {Url} as {Name}, offering {Caps}", p.HubUrl,
                welcome["device"]?["name"]?.GetValue<string>() ?? p.Name, p.Caps.Count == 0 ? "nothing" : string.Join(", ", p.Caps));
            SetStatus(new LiveStatus(p.Caps, true, ""));
            Connected?.Invoke();
            var lastReceived = DateTimeOffset.UtcNow;
            var pinger = PingAsync(socket, p, () => lastReceived, conn);
            try
            {
                while (true)
                {
                    var msg = await ReadAsync(socket, conn.Token).ConfigureAwait(false);
                    if (msg is null)
                    {
                        return (true, new ClosedException(socket.CloseStatus, socket.CloseStatusDescription ?? ""));
                    }
                    lastReceived = DateTimeOffset.UtcNow;
                    await HandleAsync(msg).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) when (!stop.IsCancellationRequested)
            {
                // the pinger ended it: a reload, or no answer to a ping
                return (true, await pinger.ConfigureAwait(false) ?? new TimeoutException("no answer to ping"));
            }
            catch (WebSocketException e)
            {
                return (true, e);
            }
            finally
            {
                await conn.CancelAsync().ConfigureAwait(false);
                await pinger.ConfigureAwait(false);
            }
        }
        catch (WebSocketException e)
        {
            return (false, e);
        }
        finally
        {
            lock (gate)
            {
                ws = null;
                connectedWith = null;
                online = [];
            }
            // a drag or a held key mustn't outlive the controller
            dispatcher.ReleaseInput();
            socket.Abort();
            socket.Dispose();
            SetStatus(Status with { Live = false });
        }
    }

    /// <summary>Pings every 25 s, and ends the connection when nothing at all arrived since the last ping, or the parameters changed.</summary>
    async Task<Exception?> PingAsync(ClientWebSocket socket, LiveParams p, Func<DateTimeOffset> lastReceived, CancellationTokenSource conn)
    {
        DateTimeOffset? lastPing = null;
        while (!conn.IsCancellationRequested)
        {
            bool reloaded;
            try
            {
                reloaded = await reload.WaitAsync(PingEvery, conn.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return null;
            }
            if (reloaded)
            {
                var np = parameters();
                if (np is null || np.HubUrl != p.HubUrl || np.Handler != p.Handler || np.Token != p.Token || np.Session != p.Session ||
                    !np.Caps.SequenceEqual(p.Caps))
                {
                    try
                    {
                        using var t = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                        await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "settings changed", t.Token).ConfigureAwait(false);
                    }
                    catch (Exception e) when (e is WebSocketException or OperationCanceledException)
                    {
                    }
                    await conn.CancelAsync().ConfigureAwait(false);
                    return new ReloadException();
                }
                continue;
            }
            if (lastPing is { } lp && lastReceived() < lp)
            {
                // nothing at all since the last ping, not even the pong
                await conn.CancelAsync().ConfigureAwait(false);
                return new TimeoutException("no answer to ping");
            }
            lastPing = DateTimeOffset.UtcNow;
            if (!await SendAsync(new JsonObject { ["t"] = "ping" }).ConfigureAwait(false))
            {
                await conn.CancelAsync().ConfigureAwait(false);
                return new WebSocketException(WebSocketError.ConnectionClosedPrematurely);
            }
        }
        return null;
    }

    static async Task<JsonObject?> ReadAsync(ClientWebSocket socket, CancellationToken ct)
    {
        var buf = new byte[64 * 1024];
        while (true)
        {
            using var ms = new MemoryStream();
            WebSocketReceiveResult r;
            do
            {
                r = await socket.ReceiveAsync(buf, ct).ConfigureAwait(false);
                if (r.MessageType == WebSocketMessageType.Close)
                {
                    return null;
                }
                if (ms.Length + r.Count > ReadLimit)
                {
                    throw new WebSocketException(WebSocketError.Faulted, "a frame over the limit");
                }
                ms.Write(buf, 0, r.Count);
            }
            while (!r.EndOfMessage);
            if (r.MessageType == WebSocketMessageType.Text && Json.ParseObject(ms.ToArray()) is { } msg)
            {
                return msg;
            }
            // not an object we understand: ignored, as the protocol asks
        }
    }

    async Task HandleAsync(JsonObject msg)
    {
        switch (msg.Str("t"))
        {
            case "input" or "media" or "cmd" or "clip" or "rpc":
                await dispatcher.DispatchAsync(msg, new HubSource(this)).ConfigureAwait(false);
                break;
            case "presence":
                UpdateOnline(msg["devices"] as JsonObject);
                break;
            case "roster":
                RosterChanged?.Invoke();
                break;
            case "error":
                log.LogInformation("live: hub error (re {Re}): {Error}", msg.Str("re"), msg.Str("error"));
                break;
            // pong, state, welcome and anything newer: nothing to do
        }
    }

    void UpdateOnline(JsonObject? devices)
    {
        if (devices is null)
        {
            return;
        }
        lock (gate)
        {
            online = [.. devices.Select(d => d.Key)];
        }
    }

    /// <summary>Sends one message to the hub. False when not connected, or it's too big.</summary>
    public async Task<bool> SendAsync(JsonObject msg)
    {
        ArgumentNullException.ThrowIfNull(msg);
        ClientWebSocket? socket;
        lock (gate)
        {
            socket = ws;
        }
        if (socket is null || socket.State != WebSocketState.Open)
        {
            return false;
        }
        var data = Json.ToUtf8(msg);
        if (data.Length > MaxFrame)
        {
            log.LogWarning("live: a {Type} message of {Bytes} bytes is too large for the hub", msg.Str("t"), data.Length);
            return false;
        }
        await sendLock.WaitAsync().ConfigureAwait(false);
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await socket.SendAsync(data, WebSocketMessageType.Text, true, cts.Token).ConfigureAwait(false);
            return true;
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or ObjectDisposedException)
        {
            return false;
        }
        finally
        {
            sendLock.Release();
        }
    }

    /// <summary>The parameters of the connection that's up, or null.</summary>
    public LiveParams? ConnectedWith
    {
        get
        {
            lock (gate)
            {
                return connectedWith;
            }
        }
    }

    /// <summary>A message that came through the hub: replies go back over it, files are uploaded to it.</summary>
    sealed class HubSource(HubLiveSession session) : IRemoteSource
    {
        public string Kind => "hub";

        public Task<bool> ReplyAsync(JsonObject message) => session.SendAsync(message);

        public async Task DeliverFileAsync(string name, byte[] data, string mime, string to, CancellationToken ct = default)
        {
            // the route of the WebSocket the request came over: the pinned LAN or the tailnet
            var p = session.ConnectedWith ?? session.parameters() ?? throw new InvalidOperationException("not connected to the hub");
            using var c = new Hub.HubClient(p.HubUrl, p.Token, p.Session, p.Handler);
            await c.UploadDataAsync(to, name, data, mime, ct).ConfigureAwait(false);
        }
    }

    /// <inheritdoc/>
    public async ValueTask DisposeAsync()
    {
        await stop.CancelAsync().ConfigureAwait(false);
        if (loop is not null)
        {
            try
            {
                await loop.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }
        stop.Dispose();
    }
}
