using System.Net.Security;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using Droplet.Core.Common;
using Droplet.Core.Hub;

namespace Droplet.Core.LocalFirst;

/// <summary>
/// A certificate that isn't the pinned one: either not our hub, or the hub's
/// certificate was regenerated. It is never retried with the new certificate by
/// itself; the person has to re-pair.
/// </summary>
public sealed class PinMismatchException(string want, string? got)
    : HubException(got is null
        ? "the hub presented no certificate"
        : $"the hub's identity changed: its certificate is {Fingerprint.Short(got)}…, not the {Fingerprint.Short(want)}… it was paired with")
{
    /// <summary>The pinned fingerprint.</summary>
    public string Want { get; } = want;

    /// <summary>What the server presented (null when nothing).</summary>
    public string? Got { get; } = got;
}

/// <summary>
/// Trusts the hub's self-signed LAN certificate by its fingerprint
/// (docs/local-first.md §2): a TLS connection is accepted if and only if the server's
/// leaf certificate hashes to the pin. Hostname and CA checks are skipped for this
/// connection only, which is why nothing outside this class relaxes certificate
/// validation for the hub; the tailnet route keeps .NET's normal verification.
/// </summary>
public sealed class PinnedConnection
{
    readonly Lock gate = new();
    string? lastMismatch;

    PinnedConnection(string pin) => Pin = pin;

    /// <summary>The fingerprint connections are pinned to.</summary>
    public string Pin { get; }

    /// <summary>
    /// A handler for the hub on the LAN, pinned to <paramref name="pin"/>. It never uses a
    /// proxy (the hub is on the local network) and never follows redirects. An empty or
    /// malformed pin is refused: it must never mean "accept anything".
    /// </summary>
    public static (SocketsHttpHandler Handler, PinnedConnection Pin) CreateHandler(string pin)
    {
        var fp = Fingerprint.Normalize(pin) ?? throw new ArgumentException("no valid certificate fingerprint to check the hub against", nameof(pin));
        var p = new PinnedConnection(fp);
        var handler = new SocketsHttpHandler
        {
            AllowAutoRedirect = false,
            UseCookies = false,
            UseProxy = false,
            ConnectTimeout = TimeSpan.FromSeconds(10),
            PooledConnectionIdleTimeout = TimeSpan.FromSeconds(60),
            MaxConnectionsPerServer = 8,
            SslOptions = new SslClientAuthenticationOptions
            {
                EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                // the hub speaks HTTP/1.1; saying so keeps WebSocket upgrades working
                ApplicationProtocols = [SslApplicationProtocol.Http11],
                CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                RemoteCertificateValidationCallback = (_, cert, _, _) => p.Check(cert),
            },
        };
        return (handler, p);
    }

    /// <summary>Whether a presented certificate is the pinned one. Records a mismatch.</summary>
    internal bool Check(X509Certificate? cert)
    {
        var got = cert is null ? null : Fingerprint.Of(cert);
        if (got is not null && Fingerprint.Matches(Pin, got))
        {
            return true;
        }
        lock (gate)
        {
            lastMismatch = got ?? "";
        }
        return false;
    }

    /// <summary>The fingerprint of the last certificate refused ("" for none presented), or null.</summary>
    public string? LastMismatch
    {
        get
        {
            lock (gate)
            {
                return lastMismatch;
            }
        }
    }

    /// <summary>
    /// When <paramref name="error"/> is a connection that failed the pin, the mismatch;
    /// otherwise null.
    /// </summary>
    public PinMismatchException? AsMismatch(Exception error)
    {
        for (var e = error; e is not null; e = e.InnerException)
        {
            if (e is PinMismatchException pm)
            {
                return pm;
            }
            if (e is AuthenticationException && LastMismatch is { } got)
            {
                return new PinMismatchException(Pin, got.Length == 0 ? null : got);
            }
        }
        return null;
    }
}
