using System.Buffers.Binary;
using System.Globalization;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Droplet.Core.Common;

namespace Droplet.Core.Mesh;

/// <summary>
/// Direct pairing, with no hub (docs/mesh.md §9.3): both screens show the same 4-digit
/// code. The initiator I commits to a random nonce before it sees the responder R's,
/// and R picks its nonce before it sees I's, so a man in the middle has one guess in
/// 10,000 per attempt, and each attempt shows the owner a request. I proves it holds
/// its key by signing the transcript, which names R's fingerprint as I saw it.
/// <code>
/// transcript = "droplet-pair-v1" LF fpI LF fpR LF nA LF nB      (ASCII, lowercase hex)
/// code       = first 8 bytes of SHA-256("droplet-pair-code-v1" LF transcript), big-endian, mod 10000, 4 digits
/// </code>
/// </summary>
public static partial class PairingCrypto
{
    /// <summary>A nonce: 32 bytes as 64 lowercase hex characters.</summary>
    [GeneratedRegex("^[0-9a-f]{64}$")]
    public static partial Regex NoncePattern();

    /// <summary>A request id: 32 lowercase hex characters.</summary>
    [GeneratedRegex("^[0-9a-f]{32}$")]
    public static partial Regex RequestPattern();

    /// <summary>A new nonce.</summary>
    public static string NewNonce() => Hex.Random(32);

    /// <summary>The commitment to a nonce: hex(SHA-256(the nonce's 32 bytes)).</summary>
    public static string Commitment(string nonceHex) => Convert.ToHexStringLower(SHA256.HashData(Convert.FromHexString(nonceHex)));

    /// <summary>The transcript the initiator signs.</summary>
    public static byte[] Transcript(string fpInitiator, string fpResponder, string nonceI, string nonceR) =>
        Certificates.Ascii(string.Join('\n', "droplet-pair-v1", fpInitiator, fpResponder, nonceI, nonceR));

    /// <summary>The 4-digit code both screens show.</summary>
    public static string Code(string fpInitiator, string fpResponder, string nonceI, string nonceR)
    {
        var prefix = Certificates.Ascii("droplet-pair-code-v1\n");
        var t = Transcript(fpInitiator, fpResponder, nonceI, nonceR);
        var h = SHA256.HashData([.. prefix, .. t]);
        var n = BinaryPrimitives.ReadUInt64BigEndian(h) % 10000;
        return n.ToString("D4", CultureInfo.InvariantCulture);
    }
}

/// <summary>Where a pairing request stands.</summary>
public static class PairState
{
    /// <summary>Waiting for the owner.</summary>
    public const string Waiting = "waiting";

    /// <summary>The owner accepted.</summary>
    public const string Accepted = "accepted";

    /// <summary>The owner refused.</summary>
    public const string Denied = "denied";

    /// <summary>Nobody answered in 5 minutes, or it's unknown.</summary>
    public const string Expired = "expired";

    /// <summary>The initiator gave up.</summary>
    public const string Cancelled = "cancelled";

    internal const string New = "new";

    /// <summary>Whether a state ends the request.</summary>
    public static bool IsFinal(string s) => s is Accepted or Denied or Expired or Cancelled;
}

/// <summary>A confirmed incoming request, waiting for the owner, as shown to them.</summary>
/// <param name="Request">Its id.</param>
/// <param name="Id">The asking peer's id.</param>
/// <param name="Name">Its name.</param>
/// <param name="Os">Its OS.</param>
/// <param name="Fp">Its certificate's fingerprint.</param>
/// <param name="Code">The code to compare.</param>
/// <param name="Created">When it was made.</param>
/// <param name="State">Where it stands.</param>
public sealed record PairRequestInfo(string Request, string Id, string Name, string Os, string Fp, string Code, DateTimeOffset Created, string State);

/// <summary>
/// The responder's side: pairing requests other devices made to this one. The HTTP
/// endpoints (§9.3) call <see cref="Open"/>, <see cref="Confirm"/>, <see cref="Status"/>
/// and <see cref="Cancel"/>; the owner answers with <see cref="Answer"/>.
/// </summary>
public sealed class IncomingPairings(MeshIdentity identity, TimeProvider? clock = null)
{
    /// <summary>Seconds a request stays open.</summary>
    public static readonly TimeSpan RequestTtl = TimeSpan.FromMinutes(5);

    /// <summary>Open requests at once.</summary>
    public const int MaxOpen = 3;

    /// <summary>Pairing POSTs accepted per minute.</summary>
    public const int MaxPerMinute = 20;

    readonly TimeProvider clock = clock ?? TimeProvider.System;
    readonly Lock gate = new();
    readonly Dictionary<string, Req> requests = [];
    readonly List<DateTimeOffset> posts = [];

    sealed class Req
    {
        public required string Id { get; init; }
        public required string Request { get; init; }
        public required string PeerId { get; init; }
        public required string Name { get; init; }
        public required string Os { get; init; }
        public required string Fp { get; init; }
        public required byte[] Der { get; init; }
        public required string Commit { get; init; }
        public required string NonceR { get; init; }
        public required DateTimeOffset Created { get; init; }
        public string State { get; set; } = PairState.New;
        public string? Code { get; set; }
    }

    /// <summary>Raised (outside the lock) when a request is confirmed and waits for the owner.</summary>
    public event Action<PairRequestInfo>? Ready;

    void Prune()
    {
        var now = clock.GetUtcNow();
        foreach (var (rid, r) in requests.ToList())
        {
            if (r.State is PairState.New or PairState.Waiting && now - r.Created > RequestTtl)
            {
                r.State = PairState.Expired;
            }
            if (now - r.Created > RequestTtl * 2)
            {
                requests.Remove(rid);
            }
        }
    }

    /// <summary>Step 1, <c>POST /mesh/pair</c>. Returns the HTTP status and body.</summary>
    public (int Status, JsonObject Body) Open(JsonObject? body, string myId, string myName)
    {
        if (body is null)
        {
            return (400, Error("expected a JSON object"));
        }
        var peerId = body.Str("id");
        var commit = body.Str("commit");
        if (peerId is null || !MeshIdentity.PeerIdPattern().IsMatch(peerId))
        {
            return (400, Error("bad id"));
        }
        if (commit is null || !PairingCrypto.NoncePattern().IsMatch(commit))
        {
            return (400, Error("bad commit"));
        }
        byte[] der;
        try
        {
            der = Certificates.PemToDer(body.Str("cert"));
        }
        catch (FormatException e)
        {
            return (400, Error("bad certificate: " + e.Message));
        }
        var fp = Fingerprint.Of(der);
        if (fp == identity.Fingerprint)
        {
            return (409, Error("that's this device"));
        }
        string rid, nonceR;
        lock (gate)
        {
            var now = clock.GetUtcNow();
            posts.RemoveAll(t => now - t >= TimeSpan.FromMinutes(1));
            if (posts.Count >= MaxPerMinute)
            {
                return (429, Error("too many pairing requests; wait a minute"));
            }
            posts.Add(now);
            Prune();
            if (requests.Values.Count(r => r.State is PairState.New or PairState.Waiting) >= MaxOpen)
            {
                return (429, Error("too many pairing requests are waiting; answer those first"));
            }
            rid = Hex.Random(16);
            nonceR = PairingCrypto.NewNonce();
            var os = new string((body.Str("os") ?? "").Where(char.IsLetterOrDigit).ToArray());
            requests[rid] = new Req
            {
                Id = rid, Request = rid, PeerId = peerId, Name = Clean.Name(body.Str("name"), peerId),
                Os = TrustEntry.Truncate(os, 20), Fp = fp, Der = der, Commit = commit, NonceR = nonceR, Created = now,
            };
        }
        return (200, new JsonObject
        {
            ["v"] = MeshProtocol.Version, ["request"] = rid, ["nonce"] = nonceR, ["id"] = myId, ["name"] = myName,
            ["os"] = MeshProtocol.Os, ["fp"] = identity.Fingerprint,
        });
    }

    /// <summary>Step 2, <c>POST /mesh/pair/&lt;request&gt;/confirm</c>: the reveal and the signature.</summary>
    public (int Status, JsonObject Body) Confirm(string rid, JsonObject? body)
    {
        PairRequestInfo ready;
        lock (gate)
        {
            Prune();
            if (!requests.TryGetValue(rid, out var r) || r.State != PairState.New)
            {
                return (404, Error("no such request"));
            }
            var nonceI = body?.Str("nonce");
            var sig = body?.Str("sig");
            var ok = nonceI is not null && PairingCrypto.NoncePattern().IsMatch(nonceI) &&
                     CryptographicOperations.FixedTimeEquals(Certificates.Ascii(PairingCrypto.Commitment(nonceI)), Certificates.Ascii(r.Commit));
            if (ok)
            {
                byte[] raw;
                try
                {
                    raw = sig is null ? [] : Convert.FromBase64String(sig);
                }
                catch (FormatException)
                {
                    raw = [];
                }
                ok = raw.Length > 0 && Certificates.Verify(r.Der, raw, PairingCrypto.Transcript(r.Fp, identity.Fingerprint, nonceI!, r.NonceR));
            }
            if (!ok)
            {
                requests.Remove(rid);
                return (403, Error("the pairing proof didn't check out"));
            }
            r.Code = PairingCrypto.Code(r.Fp, identity.Fingerprint, nonceI!, r.NonceR);
            r.State = PairState.Waiting;
            ready = Public(r);
        }
        Ready?.Invoke(ready);
        return (200, new JsonObject { ["state"] = PairState.Waiting });
    }

    /// <summary><c>GET /mesh/pair/&lt;request&gt;</c>.</summary>
    public (int Status, JsonObject Body) Status(string rid)
    {
        lock (gate)
        {
            Prune();
            if (!requests.TryGetValue(rid, out var r))
            {
                return (404, new JsonObject { ["state"] = PairState.Expired });
            }
            return (200, new JsonObject { ["state"] = r.State == PairState.New ? PairState.Waiting : r.State });
        }
    }

    /// <summary><c>POST /mesh/pair/&lt;request&gt;/cancel</c>.</summary>
    public (int Status, JsonObject Body) Cancel(string rid)
    {
        lock (gate)
        {
            if (!requests.TryGetValue(rid, out var r))
            {
                return (404, new JsonObject { ["state"] = PairState.Expired });
            }
            if (r.State is PairState.New or PairState.Waiting)
            {
                r.State = PairState.Cancelled;
            }
            return (200, new JsonObject { ["state"] = r.State });
        }
    }

    /// <summary>The confirmed requests waiting for the owner, oldest first.</summary>
    public List<PairRequestInfo> Waiting()
    {
        lock (gate)
        {
            Prune();
            return requests.Values.Where(r => r.State == PairState.Waiting).OrderBy(r => r.Created).Select(Public).ToList();
        }
    }

    /// <summary>
    /// The owner's answer. Returns the request and the asking peer's certificate (DER) if
    /// it was waiting; null when it wasn't (answered, cancelled or expired meanwhile).
    /// </summary>
    public (PairRequestInfo Info, byte[] Der)? Answer(string rid, bool accept)
    {
        lock (gate)
        {
            Prune();
            if (!requests.TryGetValue(rid, out var r) || r.State != PairState.Waiting)
            {
                return null;
            }
            r.State = accept ? PairState.Accepted : PairState.Denied;
            return (Public(r), r.Der);
        }
    }

    static PairRequestInfo Public(Req r) => new(r.Request, r.PeerId, r.Name, r.Os, r.Fp, r.Code ?? "", r.Created, r.State);

    static JsonObject Error(string message) => new() { ["error"] = message };
}

/// <summary>Pairing failed: the peer refused, or answered something that doesn't check out.</summary>
public sealed class PairException(string message, Exception? inner = null) : Exception(message, inner);

/// <summary>
/// The initiator's side: this device asking another to pair. Steps 1 and 2 happen in
/// <see cref="StartAsync"/>, over one connection that presents no client certificate
/// (the responder's TLS would refuse one it doesn't trust yet).
/// </summary>
public sealed class OutgoingPairing : IDisposable
{
    readonly MeshIdentity identity;
    readonly string myId;
    readonly string myName;
    readonly HttpClient http;
    readonly Uri baseUri;
    string? expectFp;
    string? lastServerFp;
    byte[]? lastServerDer;

    /// <summary>Prepares a request to the peer at <paramref name="host"/>:<paramref name="port"/>.</summary>
    /// <param name="identity">This device.</param>
    /// <param name="myId">This device's peer id.</param>
    /// <param name="myName">This device's name.</param>
    /// <param name="host">The peer's address.</param>
    /// <param name="port">The peer's mesh port.</param>
    /// <param name="expectFp">The fingerprint expected (from mDNS or the roster), or null (by address).</param>
    /// <param name="timeout">Per request.</param>
    public OutgoingPairing(MeshIdentity identity, string myId, string myName, string host, int port, string? expectFp,
        TimeSpan? timeout = null)
    {
        this.identity = identity;
        this.myId = myId;
        this.myName = myName;
        this.expectFp = expectFp;
        Host = host;
        Port = port;
        baseUri = new Uri($"https://{Addresses.HostPort(host, port)}");
        // one handler: the fingerprint check reads the expected value at each handshake,
        // and the server's certificate is kept as it's checked (to trust it later)
        var handler = MeshTls.Handler(null, null);
        handler.SslOptions.RemoteCertificateValidationCallback = (_, cert, _, _) =>
        {
            lastServerDer = cert?.GetRawCertData();
            return MeshTls.CheckServer(cert, this.expectFp, fp => lastServerFp = fp);
        };
        http = new HttpClient(handler) { Timeout = timeout ?? TimeSpan.FromSeconds(8) };
    }

    /// <summary>The peer's address.</summary>
    public string Host { get; }

    /// <summary>The peer's port.</summary>
    public int Port { get; }

    /// <summary>The request id, once started.</summary>
    public string? Request { get; private set; }

    /// <summary>The code to compare, once started.</summary>
    public string? Code { get; private set; }

    /// <summary>The peer's id, once started.</summary>
    public string PeerId { get; private set; } = "";

    /// <summary>The peer's name, once started.</summary>
    public string PeerName { get; private set; } = "";

    /// <summary>The peer's OS, once started.</summary>
    public string PeerOs { get; private set; } = "";

    /// <summary>The fingerprint of the certificate the peer presented, once started.</summary>
    public string PeerFp { get; private set; } = "";

    /// <summary>The certificate the peer presented in TLS, PEM, once started.</summary>
    public string? PeerCertPem { get; private set; }

    /// <summary>Whether the owner here confirmed the codes match.</summary>
    public bool LocalOk { get; set; }

    /// <summary>Where it stands, from this side.</summary>
    public string State { get; set; } = PairState.New;

    /// <summary>Steps 1 and 2: open the request and prove this device holds its key.</summary>
    public async Task StartAsync(CancellationToken ct = default)
    {
        var nonceI = PairingCrypto.NewNonce();
        var (status, body) = await CallAsync(HttpMethod.Post, "/mesh/pair", new JsonObject
        {
            ["v"] = MeshProtocol.Version, ["id"] = myId, ["name"] = myName, ["os"] = MeshProtocol.Os,
            ["cert"] = identity.CertificatePem, ["commit"] = PairingCrypto.Commitment(nonceI),
        }, ct).ConfigureAwait(false);
        if (status != 200)
        {
            throw new PairException(body.Str("error") ?? $"the peer answered {status}");
        }
        var tlsFp = lastServerFp;
        var der = lastServerDer;
        if (tlsFp is null || der is null || body.Str("fp") != tlsFp)
        {
            throw new PairException("the peer's answer doesn't match the certificate it presented");
        }
        var nonceR = body.Str("nonce");
        var rid = body.Str("request");
        if (nonceR is null || !PairingCrypto.NoncePattern().IsMatch(nonceR) || rid is null || !PairingCrypto.RequestPattern().IsMatch(rid))
        {
            throw new PairException("the peer's answer was malformed");
        }
        var peerId = body.Str("id");
        if (peerId is null || !MeshIdentity.PeerIdPattern().IsMatch(peerId))
        {
            throw new PairException("the peer sent a bad id");
        }
        // every later call must reach the same certificate
        expectFp = tlsFp;
        var sig = identity.Sign(PairingCrypto.Transcript(identity.Fingerprint, tlsFp, nonceI, nonceR));
        var (s2, b2) = await CallAsync(HttpMethod.Post, $"/mesh/pair/{rid}/confirm",
            new JsonObject { ["nonce"] = nonceI, ["sig"] = Convert.ToBase64String(sig) }, ct).ConfigureAwait(false);
        if (s2 != 200)
        {
            throw new PairException(b2.Str("error") ?? $"the peer answered {s2}");
        }
        Request = rid;
        PeerId = peerId;
        var name = body.Str("name") ?? peerId;
        PeerName = name.Length > 64 ? name[..64] : name;
        var os = body.Str("os") ?? "";
        PeerOs = os.Length > 20 ? os[..20] : os;
        PeerFp = tlsFp;
        PeerCertPem = Certificates.ToPem(der);
        Code = PairingCrypto.Code(identity.Fingerprint, tlsFp, nonceI, nonceR);
        State = PairState.Waiting;
    }

    /// <summary>Asks the peer where the request stands.</summary>
    public async Task<string> PollAsync(CancellationToken ct = default)
    {
        var (_, body) = await CallAsync(HttpMethod.Get, $"/mesh/pair/{Request}", null, ct).ConfigureAwait(false);
        var state = body.Str("state");
        return state is PairState.Waiting or PairState.Accepted or PairState.Denied or PairState.Expired or PairState.Cancelled
            ? state
            : PairState.Expired;
    }

    /// <summary>Gives up (best effort).</summary>
    public async Task CancelAsync(CancellationToken ct = default)
    {
        if (Request is null)
        {
            return;
        }
        try
        {
            await CallAsync(HttpMethod.Post, $"/mesh/pair/{Request}/cancel", new JsonObject(), ct).ConfigureAwait(false);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or PairException)
        {
        }
    }

    async Task<(int Status, JsonObject Body)> CallAsync(HttpMethod method, string path, JsonObject? body, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(method, new Uri(baseUri, path))
        {
            Version = System.Net.HttpVersion.Version11,
            VersionPolicy = HttpVersionPolicy.RequestVersionExact,
            Content = body is null ? null : JsonContent.Create(body, options: Json.Compact),
        };
        req.Headers.TryAddWithoutValidation("User-Agent", MeshProtocol.UserAgent);
        HttpResponseMessage resp;
        try
        {
            resp = await http.SendAsync(req, ct).ConfigureAwait(false);
        }
        catch (HttpRequestException e) when (e.InnerException is System.Security.Authentication.AuthenticationException && expectFp is not null)
        {
            throw new PairException($"{Host}:{Port} presented certificate {lastServerFp ?? "none"}, not the expected {expectFp}", e);
        }
        using (resp)
        {
            var bytes = await resp.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
            var o = bytes.Length <= 65536 ? Json.ParseObject(bytes) : null;
            return ((int)resp.StatusCode, o ?? []);
        }
    }

    /// <inheritdoc/>
    public void Dispose() => http.Dispose();
}
