using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Droplet.Core.Common;

/// <summary>
/// Certificate fingerprints: the SHA-256 of a certificate's DER encoding, as 64
/// lowercase hex characters. Both the hub's LAN certificate (docs/local-first.md §2)
/// and a mesh peer's certificate (docs/mesh.md §9.1) are named this way.
/// </summary>
public static class Fingerprint
{
    /// <summary>The fingerprint of a DER-encoded certificate.</summary>
    public static string Of(ReadOnlySpan<byte> der) => Convert.ToHexStringLower(SHA256.HashData(der));

    /// <summary>The fingerprint of a certificate.</summary>
    public static string Of(X509Certificate certificate)
    {
        ArgumentNullException.ThrowIfNull(certificate);
        return Of(certificate.GetRawCertData());
    }

    /// <summary>
    /// A fingerprint written in upper or lower case, with or without colons, as 64
    /// lowercase hex characters; null for anything else.
    /// </summary>
    public static string? Normalize(string? fp)
    {
        if (fp is null)
        {
            return null;
        }
        var s = fp.Trim().Replace(":", "", StringComparison.Ordinal).ToLowerInvariant();
        return IsValid(s) ? s : null;
    }

    /// <summary>Whether <paramref name="fp"/> is exactly 64 lowercase hex characters.</summary>
    public static bool IsValid(string? fp) => fp is { Length: 64 } && Hex.IsLower(fp);

    /// <summary>Compares two fingerprints in constant time. False when either is invalid.</summary>
    public static bool Matches(string? expected, string? actual)
    {
        if (!IsValid(expected) || !IsValid(actual))
        {
            return false;
        }
        return CryptographicOperations.FixedTimeEquals(
            System.Text.Encoding.ASCII.GetBytes(expected!), System.Text.Encoding.ASCII.GetBytes(actual!));
    }

    /// <summary>The first 12 characters, for messages.</summary>
    public static string Short(string? fp) => fp is { Length: > 12 } ? fp[..12] : fp ?? "";
}

/// <summary>Lowercase hex helpers.</summary>
public static class Hex
{
    /// <summary>Whether every character is 0-9 or a-f (and there is at least one).</summary>
    public static bool IsLower(ReadOnlySpan<char> s)
    {
        if (s.IsEmpty)
        {
            return false;
        }
        foreach (var c in s)
        {
            if (c is not ((>= '0' and <= '9') or (>= 'a' and <= 'f')))
            {
                return false;
            }
        }
        return true;
    }

    /// <summary><paramref name="n"/> random bytes, as lowercase hex.</summary>
    public static string Random(int n) => Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(n));
}
