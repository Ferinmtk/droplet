namespace Droplet.Core.Mesh;

/// <summary>The mesh's constants (docs/mesh.md §9).</summary>
public static class MeshProtocol
{
    /// <summary>The protocol version.</summary>
    public const int Version = 1;

    /// <summary>What this peer says it runs on.</summary>
    public const string Os = "windows";

    /// <summary>The mDNS service type.</summary>
    public const string ServiceType = "_droplet-peer._tcp.local.";

    /// <summary>The first mesh port tried.</summary>
    public const int DefaultPort = 1739;

    /// <summary>The last mesh port tried.</summary>
    public const int LastPort = 1749;

    /// <summary>How this peer names itself in HTTP.</summary>
    public const string UserAgent = "droplet-windows-mesh/1";

    /// <summary>A link frame's limit: 1 MiB.</summary>
    public const int MaxFrame = 1024 * 1024;

    /// <summary>A pairing request body's limit.</summary>
    public const int MaxPairBody = 64 * 1024;

    /// <summary>A chat message's limit, in bytes of UTF-8.</summary>
    public const int MaxText = 64 * 1024;

    /// <summary>Clipboard text's limit, in bytes of UTF-8.</summary>
    public const int MaxClip = 256 * 1024;

    /// <summary>A link with no hello within this is closed.</summary>
    public static readonly TimeSpan HelloTimeout = TimeSpan.FromSeconds(15);

    /// <summary>A link pings after this much silence.</summary>
    public static readonly TimeSpan IdlePing = TimeSpan.FromSeconds(20);

    /// <summary>A link that hears nothing for this long is dead.</summary>
    public static readonly TimeSpan DeadAfter = TimeSpan.FromSeconds(60);
}
