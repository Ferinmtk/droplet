using System.Formats.Asn1;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.RegularExpressions;
using Droplet.Core.Common;

namespace Droplet.Core.Mesh;

/// <summary>What an <see cref="IIdentityStore"/> keeps: the random local id, the certificate, and its private key.</summary>
/// <param name="LocalId">16 lowercase hex characters, made once.</param>
/// <param name="CertificateDer">The certificate, DER.</param>
/// <param name="PrivateKeyPkcs8">The EC private key, PKCS#8 DER. Secret.</param>
public sealed record StoredIdentity(string LocalId, byte[] CertificateDer, byte[] PrivateKeyPkcs8);

/// <summary>
/// Keeps this device's mesh identity between runs. The private key must stay private:
/// the Windows app keeps it with DPAPI (<c>ProtectedData</c>, current user) in 3b;
/// <see cref="FileIdentityStore"/> is the owner-only file form the Linux agent uses,
/// for tests and for Linux.
/// </summary>
public interface IIdentityStore
{
    /// <summary>The stored identity, or null when there's none (or it can't be read).</summary>
    StoredIdentity? Load();

    /// <summary>Stores a new identity, replacing any other.</summary>
    void Save(StoredIdentity identity);
}

/// <summary>
/// This device's mesh identity (docs/mesh.md §1, §9.1): an EC P-256 key and a
/// long-lived self-signed certificate. The fingerprint, the SHA-256 of the certificate's
/// DER, *is* the peer. It's made once, and only changes if the stored identity is lost.
/// </summary>
public sealed partial class MeshIdentity : IDisposable
{
    /// <summary>
    /// Validity starts at a fixed date in the past, so a peer whose clock is behind still
    /// accepts the certificate, and runs about 30 years: OpenSSL checks the dates of a
    /// trust anchor too, so it must never lapse in use.
    /// </summary>
    public static readonly DateTimeOffset NotBefore = new(2020, 1, 1, 0, 0, 0, TimeSpan.Zero);

    /// <summary>How long a new certificate is valid.</summary>
    public static readonly TimeSpan Lifetime = TimeSpan.FromDays(365 * 30);

    /// <summary>A peer id: 8 to 64 lowercase hex characters (hub device ids are 12).</summary>
    [GeneratedRegex("^[0-9a-f]{8,64}$")]
    public static partial Regex PeerIdPattern();

    readonly ECDsa key;

    MeshIdentity(string localId, byte[] der, ECDsa key, X509Certificate2 tlsCertificate)
    {
        LocalId = localId;
        Der = der;
        this.key = key;
        Fingerprint = Common.Fingerprint.Of(der);
        CertificatePem = Certificates.ToPem(der);
        TlsCertificate = tlsCertificate;
    }

    /// <summary>The random id used when not joined to a hub.</summary>
    public string LocalId { get; }

    /// <summary>The certificate, DER.</summary>
    public byte[] Der { get; }

    /// <summary>The certificate, PEM (as Python's <c>ssl.DER_cert_to_PEM_cert</c> writes it).</summary>
    public string CertificatePem { get; }

    /// <summary>The fingerprint: this peer's identity.</summary>
    public string Fingerprint { get; }

    /// <summary>The certificate with its private key, loaded so the platform's TLS can use it, as server and as client.</summary>
    public X509Certificate2 TlsCertificate { get; }

    /// <summary>This device's peer id: the hub's device id when it has joined one, else the random local id.</summary>
    public string PeerId(string? hubDeviceId) =>
        hubDeviceId is not null && PeerIdPattern().IsMatch(hubDeviceId) ? hubDeviceId : LocalId;

    /// <summary>
    /// The identity kept in <paramref name="store"/>, made on first use. A stored one that
    /// doesn't hold together (a key that isn't the certificate's, a damaged certificate)
    /// is replaced by a new one, as a new device.
    /// </summary>
    public static MeshIdentity LoadOrCreate(IIdentityStore store)
    {
        ArgumentNullException.ThrowIfNull(store);
        var stored = store.Load();
        if (stored is not null && TryOpen(stored) is { } identity)
        {
            return identity;
        }
        var localId = stored?.LocalId is { } id && LocalIdPattern().IsMatch(id) ? id : Hex.Random(8);
        stored = Generate(localId, DateTimeOffset.UtcNow);
        store.Save(stored);
        return TryOpen(stored) ?? throw new CryptographicException("a newly made mesh identity doesn't hold together");
    }

    [GeneratedRegex("^[0-9a-f]{16}$")]
    private static partial Regex LocalIdPattern();

    static MeshIdentity? TryOpen(StoredIdentity s)
    {
        if (!LocalIdPattern().IsMatch(s.LocalId))
        {
            return null;
        }
        ECDsa? key = null;
        try
        {
            key = ECDsa.Create();
            key.ImportPkcs8PrivateKey(s.PrivateKeyPkcs8, out _);
            using var cert = X509CertificateLoader.LoadCertificate(s.CertificateDer);
            using var pub = cert.GetECDsaPublicKey();
            // the key and the certificate must belong together
            if (pub is null || !pub.ExportSubjectPublicKeyInfo().AsSpan().SequenceEqual(key.ExportSubjectPublicKeyInfo()))
            {
                key.Dispose();
                return null;
            }
            var tls = Certificates.ForTls(cert, key);
            return new MeshIdentity(s.LocalId, s.CertificateDer, key, tls);
        }
        catch (CryptographicException)
        {
            key?.Dispose();
            return null;
        }
    }

    /// <summary>
    /// A new identity, to the §9.1 profile: X.509 v3, self-signed with SHA-256, subject
    /// <c>CN=droplet-peer-&lt;local id&gt;</c>, basicConstraints CA:FALSE (critical),
    /// keyUsage digitalSignature (critical), extendedKeyUsage serverAuth and clientAuth,
    /// a subject key identifier, and a random serial.
    /// </summary>
    public static StoredIdentity Generate(string localId, DateTimeOffset now)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var req = new CertificateRequest($"CN=droplet-peer-{localId}", key, HashAlgorithmName.SHA256);
        // not a CA: it can't be used to vouch for any other certificate
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, critical: true));
        req.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, critical: true));
        req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            [new Oid("1.3.6.1.5.5.7.3.1", "serverAuth"), new Oid("1.3.6.1.5.5.7.3.2", "clientAuth")], critical: false));
        req.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(req.PublicKey, critical: false));
        var notBefore = now < NotBefore ? now : NotBefore;
        var serial = RandomNumberGenerator.GetBytes(16);
        serial[0] &= 0x7f; // positive
        serial[0] |= 0x40; // and full length, as a DER INTEGER
        using var cert = req.Create(req.SubjectName, X509SignatureGenerator.CreateForECDsa(key), notBefore, now + Lifetime, serial);
        return new StoredIdentity(localId, cert.RawData, key.ExportPkcs8PrivateKey());
    }

    /// <summary>ECDSA-SHA256 over <paramref name="data"/>, DER-encoded (X9.62, as OpenSSL and Java give it).</summary>
    public byte[] Sign(ReadOnlySpan<byte> data) =>
        key.SignData(data, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

    /// <inheritdoc/>
    public void Dispose()
    {
        key.Dispose();
        TlsCertificate.Dispose();
    }
}

/// <summary>Certificate helpers for the mesh.</summary>
public static class Certificates
{
    const string Begin = "-----BEGIN CERTIFICATE-----";

    /// <summary>A certificate as PEM: 64-character base64 lines and a trailing newline.</summary>
    public static string ToPem(ReadOnlySpan<byte> der) => new string(PemEncoding.Write("CERTIFICATE", der)) + "\n";

    /// <summary>
    /// The DER of a single PEM certificate, parsed for real (a PEM wrapper around garbage
    /// must never be trusted). Throws <see cref="FormatException"/> when it isn't one.
    /// </summary>
    public static byte[] PemToDer(string? pem)
    {
        if (pem is null || CountOf(pem, Begin) != 1)
        {
            throw new FormatException("not a single PEM certificate");
        }
        if (!PemEncoding.TryFind(pem, out var fields) || pem[fields.Label] is not "CERTIFICATE")
        {
            throw new FormatException("not a PEM certificate");
        }
        byte[] der;
        try
        {
            der = Convert.FromBase64String(pem[fields.Base64Data].ToString());
        }
        catch (FormatException e)
        {
            throw new FormatException("not a PEM certificate: " + e.Message, e);
        }
        try
        {
            // the whole DER must be one certificate, nothing after it
            var reader = new AsnReader(der, AsnEncodingRules.DER);
            reader.ReadSequence();
            reader.ThrowIfNotEmpty();
            using var cert = X509CertificateLoader.LoadCertificate(der);
        }
        catch (Exception e) when (e is CryptographicException or AsnContentException)
        {
            throw new FormatException("not a valid certificate: " + e.Message, e);
        }
        return der;
    }

    static int CountOf(string s, string what)
    {
        var n = 0;
        for (var i = s.IndexOf(what, StringComparison.Ordinal); i >= 0; i = s.IndexOf(what, i + what.Length, StringComparison.Ordinal))
        {
            n++;
        }
        return n;
    }

    /// <summary>
    /// Whether <paramref name="signature"/> over <paramref name="data"/> was made by the key
    /// in <paramref name="certDer"/>: ECDSA-SHA256 with a DER signature, or (for leniency,
    /// as the reference) RSA PKCS#1 v1.5 with SHA-256.
    /// </summary>
    public static bool Verify(byte[] certDer, byte[] signature, byte[] data)
    {
        try
        {
            using var cert = X509CertificateLoader.LoadCertificate(certDer);
            using (var ec = cert.GetECDsaPublicKey())
            {
                if (ec is not null)
                {
                    return ec.VerifyData(data, signature, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
                }
            }
            using var rsa = cert.GetRSAPublicKey();
            return rsa is not null && rsa.VerifyData(data, signature, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        }
        catch (CryptographicException)
        {
            return false;
        }
    }

    /// <summary>
    /// The certificate joined with its key in a form the platform's TLS stack can use.
    /// On Windows, SChannel can't use an ephemeral (in-memory) key for TLS, so the pair is
    /// round-tripped through PKCS#12 into the user's key store; the key file is deleted
    /// again when the certificate is disposed. Elsewhere (OpenSSL) an ephemeral key works.
    /// </summary>
    public static X509Certificate2 ForTls(X509Certificate2 cert, ECDsa key)
    {
        ArgumentNullException.ThrowIfNull(cert);
        using var withKey = cert.CopyWithPrivateKey(key);
        var password = Hex.Random(16);
        var pfx = withKey.Export(X509ContentType.Pkcs12, password);
        try
        {
            var flags = OperatingSystem.IsWindows()
                ? X509KeyStorageFlags.UserKeySet
                : X509KeyStorageFlags.EphemeralKeySet;
            return X509CertificateLoader.LoadPkcs12(pfx, password, flags);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pfx);
        }
    }

    /// <summary>Encodes text as ASCII, refusing anything outside it (transcripts are ASCII by construction).</summary>
    internal static byte[] Ascii(string s) => Encoding.ASCII.GetBytes(s);
}

/// <summary>
/// The Linux agent's layout, owner-only: <c>key.pem</c> (PKCS#8), <c>cert.pem</c> and
/// <c>identity.json</c> (<c>{"id": "&lt;local id&gt;"}</c>) in one directory. For tests
/// and Linux; the Windows app uses a DPAPI-protected store instead.
/// </summary>
public sealed class FileIdentityStore(string directory) : IIdentityStore
{
    string KeyPath => Path.Combine(directory, "key.pem");
    string CertPath => Path.Combine(directory, "cert.pem");
    string MetaPath => Path.Combine(directory, "identity.json");

    /// <inheritdoc/>
    public StoredIdentity? Load()
    {
        var meta = AtomicFile.TryReadText(MetaPath);
        var id = meta is null ? null : Json.ParseObject(meta)?.Str("id");
        var keyPem = AtomicFile.TryReadText(KeyPath);
        var certPem = AtomicFile.TryReadText(CertPath);
        if (id is null)
        {
            return null;
        }
        if (keyPem is null || certPem is null)
        {
            return new StoredIdentity(id, [], []);
        }
        try
        {
            using var key = ECDsa.Create();
            key.ImportFromPem(keyPem);
            return new StoredIdentity(id, Certificates.PemToDer(certPem), key.ExportPkcs8PrivateKey());
        }
        catch (Exception e) when (e is FormatException or ArgumentException or CryptographicException)
        {
            return new StoredIdentity(id, [], []);
        }
    }

    /// <inheritdoc/>
    public void Save(StoredIdentity identity)
    {
        ArgumentNullException.ThrowIfNull(identity);
        AtomicFile.CreatePrivateDirectory(directory);
        AtomicFile.WriteText(MetaPath, $"{{\"id\": \"{identity.LocalId}\"}}\n");
        // the key first, then the certificate: a certificate with no key would be useless
        AtomicFile.WriteText(KeyPath, new string(PemEncoding.Write("PRIVATE KEY", identity.PrivateKeyPkcs8)) + "\n");
        AtomicFile.WriteText(CertPath, Certificates.ToPem(identity.CertificateDer));
    }
}

/// <summary>Encrypts a secret for this user only (DPAPI's <c>ProtectedData</c> on Windows).</summary>
public interface ISecretProtector
{
    /// <summary>Encrypts <paramref name="secret"/>.</summary>
    byte[] Protect(byte[] secret);

    /// <summary>Decrypts what <see cref="Protect"/> made. Throws <see cref="CryptographicException"/> when it can't.</summary>
    byte[] Unprotect(byte[] sealedSecret);
}

/// <summary>
/// The identity in one file whose private key is sealed by an <see cref="ISecretProtector"/>:
/// on Windows, DPAPI for the current user (3b passes <c>ProtectedData.Protect</c> and
/// <c>Unprotect</c> with <c>DataProtectionScope.CurrentUser</c>). The certificate and
/// the local id are public and kept as they are. A key that can't be unsealed (another
/// user, a restored profile) reads as no identity, so a new one is made.
/// </summary>
public sealed class ProtectedIdentityStore(string directory, ISecretProtector protector) : IIdentityStore
{
    string FilePath => Path.Combine(directory, "identity-sealed.json");

    /// <inheritdoc/>
    public StoredIdentity? Load()
    {
        var o = Json.ParseObject(AtomicFile.TryReadText(FilePath));
        if (o?.Str("id") is not { } id)
        {
            return null;
        }
        try
        {
            var cert = Convert.FromBase64String(o.Str("cert") ?? "");
            var key = protector.Unprotect(Convert.FromBase64String(o.Str("key") ?? ""));
            return new StoredIdentity(id, cert, key);
        }
        catch (Exception e) when (e is FormatException or CryptographicException)
        {
            return new StoredIdentity(id, [], []);
        }
    }

    /// <inheritdoc/>
    public void Save(StoredIdentity identity)
    {
        ArgumentNullException.ThrowIfNull(identity);
        var o = new System.Text.Json.Nodes.JsonObject
        {
            ["v"] = 1, ["id"] = identity.LocalId, ["cert"] = Convert.ToBase64String(identity.CertificateDer),
            ["key"] = Convert.ToBase64String(protector.Protect(identity.PrivateKeyPkcs8)),
        };
        AtomicFile.WriteText(FilePath, Json.ToText(o) + "\n");
    }
}
