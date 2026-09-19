using System.Net;
using System.Net.WebSockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Droplet.Core.Common;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.AspNetCore.Server.Kestrel.Https;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Droplet.Core.Mesh;

/// <summary>What the mesh port needs from the node.</summary>
public interface IMeshServerHandler
{
    /// <summary>The trusted peer with this fingerprint, or null.</summary>
    TrustEntry? Trusted(string fp);

    /// <summary>A trusted peer's WebSocket; completes when the link has ended.</summary>
    Task OnLinkAsync(WebSocket ws, TrustEntry entry, string address);

    /// <summary>The offer with this id, or null.</summary>
    Offer? GetOffer(string id);

    /// <summary>Bytes a second when serving files; 0 for no limit.</summary>
    long MaxRate { get; }

    /// <summary>A pairing call: (method, path, body) → (status, body).</summary>
    (int Status, JsonObject Body) Pair(string method, string path, JsonObject? body);
}

/// <summary>
/// The mesh port (docs/mesh.md §9.2), on embedded Kestrel: one TLS listener on 1739, or
/// the first free port up to 1749, dual-stack.
/// <code>
/// wss://peer:port/mesh                   a trusted peer's link        (client certificate, trusted)
/// GET|HEAD /mesh/files/&lt;id&gt;             a file offered to that peer  (client certificate, trusted)
/// POST /mesh/pair, GET /mesh/pair/&lt;r&gt;, POST /mesh/pair/&lt;r&gt;/confirm|cancel   (no client certificate)
/// </code>
/// Who may do what is decided in the handshake: TLS asks for a client certificate
/// without requiring one (<c>AllowCertificate</c>), and the validation callback accepts
/// only a certificate in the trust list, so any other certificate fails the handshake,
/// as in the reference. A client with no certificate may only use <c>/mesh/pair*</c>;
/// everything else answers 403 before any body is read. Every request checks the
/// presented leaf's fingerprint against the trust list again, which also covers a
/// resumed TLS session of a peer unpaired since.
/// </summary>
public sealed partial class MeshServer : IAsyncDisposable
{
    const int MaxConnections = 64;
    const int FileChunk = 256 * 1024;

    readonly IMeshServerHandler handler;
    readonly MeshIdentity identity;
    readonly ILogger log;
    readonly ILoggerFactory? loggerFactory;
    WebApplication? app;
    long refused;

    MeshServer(IMeshServerHandler handler, MeshIdentity identity, ILoggerFactory? loggerFactory)
    {
        this.handler = handler;
        this.identity = identity;
        this.loggerFactory = loggerFactory;
        log = loggerFactory?.CreateLogger("droplet.mesh.server") ?? NullLogger.Instance;
    }

    /// <summary>The port it listens on.</summary>
    public int Port { get; private set; }

    /// <summary>TLS handshakes refused (untrusted certificates among them).</summary>
    public long Refused => Interlocked.Read(ref refused);

    [GeneratedRegex("^/mesh/pair/([0-9a-f]{32})(/confirm|/cancel)?$")]
    private static partial Regex PairPath();

    /// <summary>
    /// Listens on <paramref name="port"/>, or with null the first free port in 1739–1749.
    /// </summary>
    public static async Task<MeshServer> StartAsync(IMeshServerHandler handler, MeshIdentity identity, int? port,
        ILoggerFactory? loggerFactory = null, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(handler);
        ArgumentNullException.ThrowIfNull(identity);
        var server = new MeshServer(handler, identity, loggerFactory);
        var ports = port is { } p ? [p] : Enumerable.Range(MeshProtocol.DefaultPort, MeshProtocol.LastPort - MeshProtocol.DefaultPort + 1).ToArray();
        Exception? last = null;
        foreach (var candidate in ports)
        {
            foreach (var dualStack in new[] { true, false })
            {
                var app = server.Build(candidate, dualStack);
                try
                {
                    await app.StartAsync(ct).ConfigureAwait(false);
                    server.app = app;
                    server.Port = candidate == 0 ? BoundPort(app) : candidate;
                    return server;
                }
                catch (IOException e)
                {
                    last = e;
                    await app.DisposeAsync().ConfigureAwait(false);
                    if (e.InnerException is Microsoft.AspNetCore.Connections.AddressInUseException)
                    {
                        break; // taken: the other family won't help, try the next port
                    }
                }
                catch (Exception e) when (e is System.Net.Sockets.SocketException or NotSupportedException)
                {
                    // no IPv6 on this machine: IPv4 alone
                    last = e;
                    await app.DisposeAsync().ConfigureAwait(false);
                }
            }
        }
        throw new IOException($"no free mesh port in {ports[0]}–{ports[^1]}: {last?.Message}", last);
    }

    static int BoundPort(WebApplication a)
    {
        var addresses = a.Services.GetRequiredService<Microsoft.AspNetCore.Hosting.Server.IServer>()
            .Features.Get<Microsoft.AspNetCore.Hosting.Server.Features.IServerAddressesFeature>()?.Addresses ?? [];
        foreach (var address in addresses)
        {
            if (Uri.TryCreate(address.Replace("[::]", "localhost", StringComparison.Ordinal).Replace("0.0.0.0", "localhost", StringComparison.Ordinal), UriKind.Absolute, out var u))
            {
                return u.Port;
            }
        }
        return 0;
    }

    WebApplication Build(int port, bool dualStack)
    {
        var builder = WebApplication.CreateEmptyBuilder(new WebApplicationOptions { ApplicationName = "droplet-mesh" });
        builder.Services.AddLogging(l =>
        {
            l.ClearProviders();
            if (loggerFactory is not null)
            {
                l.AddProvider(new ForwardingLoggerProvider(loggerFactory));
                // Kestrel reports every refused handshake; the mesh counts those itself
                l.AddFilter("Microsoft", LogLevel.Warning);
                l.AddFilter("Microsoft.AspNetCore.Server.Kestrel", LogLevel.Error);
            }
        });
        builder.WebHost.UseKestrelCore().ConfigureKestrel(k =>
        {
            k.AddServerHeader = false;
            k.Limits.MaxConcurrentConnections = MaxConnections;
            k.Limits.MaxConcurrentUpgradedConnections = MaxConnections;
            k.Limits.MaxRequestHeadersTotalSize = 16 * 1024;
            k.Limits.MaxRequestBodySize = MeshProtocol.MaxPairBody;
            k.Limits.KeepAliveTimeout = TimeSpan.FromSeconds(30);
            k.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(10);
            var address = dualStack ? IPAddress.IPv6Any : IPAddress.Any;
            k.Listen(address, port, listen =>
            {
                listen.Protocols = HttpProtocols.Http1;
                listen.UseHttps(new HttpsConnectionAdapterOptions
                {
                    ServerCertificate = identity.TlsCertificate,
                    SslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                    ClientCertificateMode = ClientCertificateMode.AllowCertificate,
                    CheckCertificateRevocation = false,
                    HandshakeTimeout = TimeSpan.FromSeconds(10),
                    // called in the handshake for a presented certificate: only a trusted
                    // one gets through; any other fails the handshake with an alert
                    ClientCertificateValidation = (cert, _, _) => ValidateClient(cert),
                    OnAuthenticate = (_, ssl) => ssl.CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                });
            });
        });
        var app = builder.Build();
        app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = MeshProtocol.IdlePing });
        app.Run(HandleAsync);
        return app;
    }

    bool ValidateClient(X509Certificate2? cert)
    {
        if (cert is null)
        {
            return true; // no certificate: only pairing, enforced per request
        }
        if (handler.Trusted(Fingerprint.Of(cert)) is not null)
        {
            return true;
        }
        Interlocked.Increment(ref refused);
        log.LogInformation("mesh: refused a TLS client presenting certificate {Fp}: not in the trust list", Fingerprint.Short(Fingerprint.Of(cert)));
        return false;
    }

    async Task HandleAsync(HttpContext ctx)
    {
        ctx.Response.Headers.Server = "droplet-windows";
        var address = ctx.Connection.RemoteIpAddress is { } ip ? (ip.IsIPv4MappedToIPv6 ? ip.MapToIPv4() : ip).ToString() : "";
        // AllowCertificate: the certificate (if any) came with the handshake; asking again
        // would renegotiate, which the mesh never does
        var cert = ctx.Connection.ClientCertificate;
        var fp = cert is null ? null : Fingerprint.Of(cert);
        var entry = fp is null ? null : handler.Trusted(fp);
        if (fp is not null && entry is null)
        {
            // TLS let it through (a session resumed from before it was unpaired), but its
            // fingerprint isn't trusted now
            log.LogWarning("mesh: refused {Address}: certificate {Fp} isn't in the trust list", address, Fingerprint.Short(fp));
            await RespondAsync(ctx, 403, new JsonObject { ["error"] = "not trusted" }, close: true).ConfigureAwait(false);
            return;
        }
        var path = ctx.Request.Path.Value ?? "";
        var method = ctx.Request.Method;
        if (path == "/mesh/pair" || path.StartsWith("/mesh/pair/", StringComparison.Ordinal))
        {
            await PairAsync(ctx, method, path).ConfigureAwait(false);
            return;
        }
        if (entry is null)
        {
            // an untrusted client (no certificate) gets nothing but pairing
            await RespondAsync(ctx, 403, new JsonObject { ["error"] = "pair with this device first" }, close: true).ConfigureAwait(false);
            return;
        }
        if (path == "/mesh" && method == "GET" && ctx.WebSockets.IsWebSocketRequest)
        {
            WebSocket ws;
            try
            {
                ws = await ctx.WebSockets.AcceptWebSocketAsync(new WebSocketAcceptContext
                {
                    // ping every 20 s; no pong within 40 s after that ends the link
                    KeepAliveInterval = MeshProtocol.IdlePing,
                    KeepAliveTimeout = MeshProtocol.DeadAfter - MeshProtocol.IdlePing,
                    DangerousEnableCompression = false,
                }).ConfigureAwait(false);
            }
            catch (Exception e) when (e is WebSocketException or IOException)
            {
                log.LogDebug("mesh: WebSocket from {Address} failed: {Error}", address, e.Message);
                return;
            }
            await handler.OnLinkAsync(ws, entry, address).ConfigureAwait(false);
            return;
        }
        if (path.StartsWith("/mesh/files/", StringComparison.Ordinal) && method is "GET" or "HEAD")
        {
            await ServeFileAsync(ctx, path["/mesh/files/".Length..], entry.Fp, method == "HEAD").ConfigureAwait(false);
            return;
        }
        await RespondAsync(ctx, 404, new JsonObject { ["error"] = "not found" }, close: true).ConfigureAwait(false);
    }

    async Task PairAsync(HttpContext ctx, string method, string path)
    {
        JsonObject? body = null;
        if (method == "POST")
        {
            // a Content-Length of at most 64 KB, and no chunked bodies (as the reference)
            if (ctx.Request.Headers.ContainsKey("Transfer-Encoding") || ctx.Request.ContentLength is < 0 or > MeshProtocol.MaxPairBody)
            {
                await RespondAsync(ctx, 413, new JsonObject { ["error"] = "body too large" }, close: true).ConfigureAwait(false);
                return;
            }
            byte[] raw;
            try
            {
                using var ms = new MemoryStream();
                await ctx.Request.Body.CopyToAsync(ms, ctx.RequestAborted).ConfigureAwait(false);
                raw = ms.ToArray();
            }
            catch (Microsoft.AspNetCore.Http.BadHttpRequestException)
            {
                await RespondAsync(ctx, 413, new JsonObject { ["error"] = "body too large" }, close: true).ConfigureAwait(false);
                return;
            }
            if (raw.Length == 0)
            {
                body = [];
            }
            else
            {
                body = Json.ParseObject(raw);
                if (body is null && !IsJson(raw))
                {
                    await RespondAsync(ctx, 400, new JsonObject { ["error"] = "bad JSON" }, close: true).ConfigureAwait(false);
                    return;
                }
            }
        }
        else if (method != "GET")
        {
            await RespondAsync(ctx, 405, new JsonObject { ["error"] = "method not allowed" }, close: true).ConfigureAwait(false);
            return;
        }
        var (status, output) = handler.Pair(method, path, body);
        var close = ctx.Request.Headers.Connection.ToString().Equals("close", StringComparison.OrdinalIgnoreCase);
        await RespondAsync(ctx, status, output, close).ConfigureAwait(false);
    }

    static bool IsJson(byte[] raw)
    {
        try
        {
            using var _ = System.Text.Json.JsonDocument.Parse(raw);
            return true;
        }
        catch (System.Text.Json.JsonException)
        {
            return false;
        }
    }

    /// <summary>The pairing routes, for the node: (method, path) → which call.</summary>
    public static (string Action, string? Request) PairRoute(string path)
    {
        if (path == "/mesh/pair")
        {
            return ("open", null);
        }
        var m = PairPath().Match(path);
        if (!m.Success)
        {
            return ("", null);
        }
        return (m.Groups[2].Value switch { "/confirm" => "confirm", "/cancel" => "cancel", _ => "status" }, m.Groups[1].Value);
    }

    async Task ServeFileAsync(HttpContext ctx, string id, string fp, bool head)
    {
        var resp = ctx.Response;
        // one file per connection, as the reference
        resp.Headers.Connection = "close";
        var offer = FileReceiver.OfferIdPattern().IsMatch(id) ? handler.GetOffer(id) : null;
        if (offer is null || offer.Fp != fp)
        {
            // the same answer whether it doesn't exist or isn't for you
            resp.StatusCode = 404;
            resp.ContentLength = 0;
            return;
        }
        if (offer.Check?.Invoke() is { } why)
        {
            resp.StatusCode = 410;
            resp.ContentLength = 0;
            offer.Finish(false, why);
            return;
        }
        var (kind, first, last) = RangeHeader.Parse(ctx.Request.Headers.Range.ToString(), offer.Size);
        if (kind == RangeHeader.Kind.Bad)
        {
            resp.StatusCode = 416;
            resp.Headers.ContentRange = $"bytes */{offer.Size}";
            resp.ContentLength = 0;
            return;
        }
        var length = Math.Max(0, last - first + 1);
        resp.StatusCode = kind == RangeHeader.Kind.Part ? 206 : 200;
        resp.ContentType = string.IsNullOrEmpty(offer.Mime) ? "application/octet-stream" : offer.Mime;
        resp.ContentLength = length;
        resp.Headers.AcceptRanges = "bytes";
        if (kind == RangeHeader.Kind.Part)
        {
            resp.Headers.ContentRange = $"bytes {first}-{last}/{offer.Size}";
        }
        if (head || length == 0)
        {
            return;
        }
        offer.Touch();
        var started = Environment.TickCount64;
        long done = 0;
        var buf = new byte[FileChunk];
        var rate = handler.MaxRate;
        await using var f = offer.Open();
        f.Seek(first, SeekOrigin.Begin);
        while (done < length)
        {
            var n = await f.ReadAsync(buf.AsMemory(0, (int)Math.Min(FileChunk, length - done)), ctx.RequestAborted).ConfigureAwait(false);
            if (n == 0)
            {
                throw new IOException("the file got shorter while it was being sent");
            }
            await resp.Body.WriteAsync(buf.AsMemory(0, n), ctx.RequestAborted).ConfigureAwait(false);
            done += n;
            offer.Progress(n);
            if (rate > 0)
            {
                var ahead = done * 1000.0 / rate - (Environment.TickCount64 - started);
                if (ahead > 0)
                {
                    await Task.Delay(TimeSpan.FromMilliseconds(ahead), ctx.RequestAborted).ConfigureAwait(false);
                }
            }
        }
    }

    static async Task RespondAsync(HttpContext ctx, int status, JsonObject body, bool close)
    {
        var data = Json.ToUtf8(body);
        ctx.Response.StatusCode = status;
        ctx.Response.ContentType = "application/json";
        ctx.Response.ContentLength = data.Length;
        if (close)
        {
            ctx.Response.Headers.Connection = "close";
        }
        await ctx.Response.Body.WriteAsync(data, ctx.RequestAborted).ConfigureAwait(false);
    }

    /// <inheritdoc/>
    public async ValueTask DisposeAsync()
    {
        if (app is not null)
        {
            try
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
                await app.StopAsync(cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
            await app.DisposeAsync().ConfigureAwait(false);
            app = null;
        }
    }

    /// <summary>Sends ASP.NET Core's logs to the app's logger factory.</summary>
    sealed class ForwardingLoggerProvider(ILoggerFactory factory) : ILoggerProvider
    {
        public ILogger CreateLogger(string categoryName) => factory.CreateLogger(categoryName);

        public void Dispose() { }
    }
}
