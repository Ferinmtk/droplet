using System.Buffers;
using System.Net.WebSockets;
using System.Text.Json.Nodes;
using Droplet.Core.Common;
using Microsoft.Extensions.Logging;

namespace Droplet.Core.Mesh;

/// <summary>
/// One open WebSocket with a peer whose certificate was already checked (docs/mesh.md
/// §9.4), either end: plain RFC 6455, no subprotocol, no compression, text frames of one
/// JSON object each, at most 1 MiB. Liveness is the WebSocket's own ping: every 20 s,
/// and the link is dropped when no pong comes (60 s at most without hearing anything).
/// <para>
/// Messages are handed to <c>onMessage</c> one at a time, in order, on the link's
/// reader. A handler must not wait on something that needs this link's next message.
/// </para>
/// </summary>
[System.Diagnostics.CodeAnalysis.SuppressMessage("Design", "CA1001",
    Justification = "The token source and semaphore hold no unmanaged state here (no timers, no wait handles); the socket is disposed when the link ends.")]
public sealed class MeshLink
{
    readonly WebSocket ws;
    readonly Func<MeshLink, JsonObject, Task> onMessage;
    readonly Action<MeshLink> onClose;
    readonly ILogger log;
    readonly SemaphoreSlim sendLock = new(1, 1);
    readonly TaskCompletionSource ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    readonly CancellationTokenSource closing = new();
    int finished;
    long lastUsed = Environment.TickCount64;

    readonly IDisposable? owned;

    internal MeshLink(WebSocket ws, string fp, string address, bool outbound, Func<MeshLink, JsonObject, Task> onMessage,
        Action<MeshLink> onClose, ILogger log, IDisposable? owned = null)
    {
        this.ws = ws;
        this.owned = owned;
        Fp = fp;
        Address = address;
        Outbound = outbound;
        this.onMessage = onMessage;
        this.onClose = onClose;
        this.log = log;
        Kind = Addresses.IsTailnet(address) ? "tailnet" : "lan";
    }

    /// <summary>The peer's fingerprint, checked by TLS.</summary>
    public string Fp { get; }

    /// <summary>The peer's address.</summary>
    public string Address { get; }

    /// <summary>This device dialled it.</summary>
    public bool Outbound { get; }

    /// <summary>"lan" or "tailnet": the route, for reporting.</summary>
    public string Kind { get; init; }

    /// <summary>The peer's mesh port, when this device dialled it.</summary>
    public int? Port { get; init; }

    /// <summary>What the peer said about itself in hello or welcome.</summary>
    public JsonObject Hello { get; internal set; } = [];

    /// <summary>Input arrived over it: held buttons must be let go when it ends.</summary>
    internal bool SentInput { get; set; }

    /// <summary>When the link opened.</summary>
    public DateTimeOffset Opened { get; } = DateTimeOffset.UtcNow;

    /// <summary>How long since a message went either way.</summary>
    public TimeSpan Idle => TimeSpan.FromMilliseconds(Environment.TickCount64 - Interlocked.Read(ref lastUsed));

    /// <summary>Whether hello has gone both ways.</summary>
    public bool IsReady => ready.Task.IsCompletedSuccessfully;

    /// <summary>Completes once hello has gone both ways.</summary>
    public Task Ready => ready.Task;

    /// <summary>Whether the link has ended.</summary>
    public bool Closed => Volatile.Read(ref finished) != 0;

    internal void MarkReady() => ready.TrySetResult();

    /// <summary>Reads until the link ends. Completes when it has, after <c>onClose</c>.</summary>
    public async Task RunAsync()
    {
        var buffer = ArrayPool<byte>.Shared.Rent(64 * 1024);
        try
        {
            while (!closing.IsCancellationRequested && ws.State == WebSocketState.Open)
            {
                var (type, data) = await ReceiveMessageAsync(buffer).ConfigureAwait(false);
                if (type == WebSocketMessageType.Close)
                {
                    // answer the close, completing the handshake, then drop the link
                    await CloseAsync().ConfigureAwait(false);
                    break;
                }
                if (type != WebSocketMessageType.Text || data is null)
                {
                    continue; // binary: not used by the mesh
                }
                if (Json.ParseObject(data) is not { } msg)
                {
                    continue;
                }
                Touch();
                try
                {
                    await onMessage(this, msg).ConfigureAwait(false);
                }
                catch (Exception e)
                {
                    log.LogWarning(e, "mesh: handling {Type} from {Address}", msg.Str("t"), Address);
                }
            }
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or IOException or ObjectDisposedException)
        {
            if (!Closed)
            {
                log.LogDebug("mesh: link with {Address} ended: {Error}", Address, e.Message);
            }
        }
        catch (FrameTooLargeException)
        {
            await CloseAsync(WebSocketCloseStatus.MessageTooBig, "frame too large").ConfigureAwait(false);
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
            Finish();
        }
    }

    sealed class FrameTooLargeException : Exception;

    async Task<(WebSocketMessageType, byte[]?)> ReceiveMessageAsync(byte[] buffer)
    {
        using var assembled = new MemoryStream();
        while (true)
        {
            var r = await ws.ReceiveAsync(buffer, closing.Token).ConfigureAwait(false);
            if (r.MessageType == WebSocketMessageType.Close)
            {
                return (WebSocketMessageType.Close, null);
            }
            if (assembled.Length + r.Count > MeshProtocol.MaxFrame)
            {
                throw new FrameTooLargeException();
            }
            assembled.Write(buffer, 0, r.Count);
            if (r.EndOfMessage)
            {
                return (r.MessageType, assembled.ToArray());
            }
        }
    }

    void Touch() => Interlocked.Exchange(ref lastUsed, Environment.TickCount64);

    /// <summary>Sends one message. False when it's too big or the link is gone (which closes it).</summary>
    public async Task<bool> SendAsync(JsonObject msg)
    {
        ArgumentNullException.ThrowIfNull(msg);
        if (Closed)
        {
            return false;
        }
        var data = Json.ToUtf8(msg);
        if (data.Length > MeshProtocol.MaxFrame)
        {
            log.LogWarning("mesh: not sending a {Type} message of {Bytes} bytes: over the frame limit", msg.Str("t"), data.Length);
            return false;
        }
        try
        {
            await sendLock.WaitAsync(closing.Token).ConfigureAwait(false);
            try
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(closing.Token);
                timeout.CancelAfter(TimeSpan.FromSeconds(30));
                await ws.SendAsync(data, WebSocketMessageType.Text, true, timeout.Token).ConfigureAwait(false);
            }
            finally
            {
                sendLock.Release();
            }
            Touch();
            return true;
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or IOException or ObjectDisposedException)
        {
            log.LogDebug("mesh: sending to {Address} failed: {Error}", Address, e.Message);
            Abort();
            return false;
        }
    }

    /// <summary>Closes the link politely (a close frame), then drops it.</summary>
    public async Task CloseAsync(WebSocketCloseStatus status = WebSocketCloseStatus.NormalClosure, string reason = "")
    {
        if (Closed)
        {
            return;
        }
        try
        {
            if (ws.State is WebSocketState.Open or WebSocketState.CloseReceived)
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                await sendLock.WaitAsync(cts.Token).ConfigureAwait(false);
                try
                {
                    await ws.CloseOutputAsync(status, reason, cts.Token).ConfigureAwait(false);
                }
                finally
                {
                    sendLock.Release();
                }
            }
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or IOException or ObjectDisposedException)
        {
        }
        Abort();
    }

    /// <summary>Drops the link at once.</summary>
    public void Abort()
    {
        try
        {
            closing.Cancel();
        }
        catch (ObjectDisposedException)
        {
        }
        ws.Abort();
        Finish();
    }

    void Finish()
    {
        if (Interlocked.Exchange(ref finished, 1) != 0)
        {
            return;
        }
        ready.TrySetException(new WebSocketException("the link closed"));
        _ = ready.Task.Exception; // observed: nobody may be waiting
        try
        {
            ws.Abort();
            ws.Dispose();
            owned?.Dispose();
        }
        catch (Exception)
        {
        }
        try
        {
            onClose(this);
        }
        catch (Exception e)
        {
            log.LogWarning(e, "mesh: closing a link");
        }
    }
}
