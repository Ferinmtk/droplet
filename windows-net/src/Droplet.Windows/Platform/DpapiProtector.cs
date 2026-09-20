using System.Security.Cryptography;
using Droplet.Core.Mesh;

namespace Droplet.Windows.Platform;

/// <summary>
/// Seals the mesh key with DPAPI for the current Windows user: another user, or a copy of
/// the file on another PC, can't unseal it. Windows keeps the master key, protected by the
/// user's sign-in.
/// </summary>
internal sealed class DpapiProtector : ISecretProtector
{
    // mixed in, so another app's DPAPI blob can't be passed off as droplet's
    static readonly byte[] Entropy = "droplet mesh identity v1"u8.ToArray();

    public byte[] Protect(byte[] secret) => ProtectedData.Protect(secret, Entropy, DataProtectionScope.CurrentUser);

    public byte[] Unprotect(byte[] sealedSecret) => ProtectedData.Unprotect(sealedSecret, Entropy, DataProtectionScope.CurrentUser);
}
