using System.Net.Security;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using Droplet.Core.Common;

namespace Droplet.Core.Mesh;

/// <summary>
/// The client side of mutual TLS between peers (docs/mesh.md §9.2): no CA, no host
/// names, no SNI; the identity is the fingerprint. The client presents its certificate
/// whatever CAs the server names (<c>LocalCertificateSelectionCallback</c>), accepts
/// any server certificate in the TLS layer and checks its fingerprint against the
/// expected one in <c>RemoteCertificateValidationCallback</c>, which runs inside the
/// handshake: a wrong server never sees a byte of the request.
/// </summary>
public static class MeshTls
{
    /// <summary>
    /// TLS options for a client. <paramref name="identity"/> null presents no certificate
    /// (pairing). <paramref name="expectFp"/> null accepts any server certificate (first
    /// contact when pairing by address; the pairing code confirms it), but one must be
    /// presented. <paramref name="observe"/> hears the server's fingerprint.
    /// </summary>
    public static SslClientAuthenticationOptions ClientOptions(MeshIdentity? identity, string? expectFp, Action<string?>? observe = null) =>
        new()
        {
            // no names: an empty target host sends no SNI
            TargetHost = "",
            EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
            ApplicationProtocols = [SslApplicationProtocol.Http11],
            LocalCertificateSelectionCallback = identity is null ? null : (_, _, _, _, _) => identity.TlsCertificate,
            ClientCertificates = identity is null ? null : new X509CertificateCollection { identity.TlsCertificate },
            RemoteCertificateValidationCallback = (_, cert, _, _) => CheckServer(cert, expectFp, observe),
        };

    /// <summary>Whether a server certificate is the expected one (any, when none is expected).</summary>
    internal static bool CheckServer(X509Certificate? cert, string? expectFp, Action<string?>? observe)
    {
        var got = cert is null ? null : Fingerprint.Of(cert);
        observe?.Invoke(got);
        if (got is null)
        {
            return false;
        }
        return expectFp is null || Fingerprint.Matches(expectFp, got);
    }

    /// <summary>
    /// An HTTP handler for mesh requests (pairing, files) with the TLS options above. It
    /// never uses a proxy, follows redirects or keeps cookies.
    /// </summary>
    public static SocketsHttpHandler Handler(MeshIdentity? identity, string? expectFp, Action<string?>? observe = null,
        TimeSpan? connectTimeout = null) =>
        new()
        {
            UseProxy = false,
            AllowAutoRedirect = false,
            UseCookies = false,
            AutomaticDecompression = System.Net.DecompressionMethods.None,
            ConnectTimeout = connectTimeout ?? TimeSpan.FromSeconds(8),
            PooledConnectionIdleTimeout = TimeSpan.FromSeconds(30),
            MaxConnectionsPerServer = 1,
            SslOptions = ClientOptions(identity, expectFp, observe),
        };
}
