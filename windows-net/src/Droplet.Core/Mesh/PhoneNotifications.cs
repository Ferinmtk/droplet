using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Droplet.Core.Common;
using Droplet.Core.Platform;

namespace Droplet.Core.Mesh;

/// <summary>
/// A phone's notifications, mirrored straight to this PC over the mesh (docs/mesh.md §9.4):
/// <c>{"t":"notify","app","title","text","key"}</c> becomes a toast, and
/// <c>{"t":"notify-removed","key"}</c> takes it away. Pure, so it's tested anywhere.
/// </summary>
public static class PhoneNotifications
{
    /// <summary>The capability this PC announces while it shows them, so phones send them.</summary>
    public const string Cap = "notify";

    /// <summary>Every phone notification's tag starts with this (the settings switch and Pause go by it).</summary>
    public const string TagPrefix = "phone-";

    /// <summary>
    /// The toast tag for a phone's notification: the same for each update of it, so an
    /// update replaces the toast. Windows allows 64 characters, and a phone's key is often
    /// longer (and the fingerprint alone is 64), so both are hashed: "phone-" + 12 + "-" + 24.
    /// </summary>
    /// <param name="fp">The phone's fingerprint.</param>
    /// <param name="key">The phone's key for the notification.</param>
    public static string Tag(string fp, string key)
    {
        ArgumentNullException.ThrowIfNull(fp);
        ArgumentNullException.ThrowIfNull(key);
        var who = fp.Length > 12 ? fp[..12] : fp;
        var what = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(key)))[..24];
        return $"{TagPrefix}{who}-{what}";
    }

    /// <summary>The toast for a <c>notify</c> message from <paramref name="peerName"/>.</summary>
    /// <param name="msg">The message.</param>
    /// <param name="peerName">The phone's name.</param>
    /// <param name="fp">The phone's fingerprint.</param>
    public static Notification Toast(JsonObject msg, string peerName, string fp)
    {
        ArgumentNullException.ThrowIfNull(msg);
        var app = msg.Str("app") is { Length: > 0 } a ? a : peerName;
        app = Clip(app, 40);
        var title = msg.Str("title") is { Length: > 0 } t ? t : app;
        var key = msg.Str("key");
        return new Notification
        {
            Title = Clip($"{title} ({peerName})", 200),
            Body = Clip(msg.Str("text") ?? "", 1000),
            // without a key it can't be replaced or taken away, but it's still from the phone
            Tag = key is { Length: > 0 } ? Tag(fp, key) : TagPrefix + (fp.Length > 12 ? fp[..12] : fp),
            App = app,
        };
    }

    /// <summary>The tag to take away for a <c>notify-removed</c>, or null if it names nothing.</summary>
    /// <param name="msg">The message.</param>
    /// <param name="fp">The phone's fingerprint.</param>
    public static string? RemovedTag(JsonObject msg, string fp)
    {
        ArgumentNullException.ThrowIfNull(msg);
        return msg.Str("key") is { Length: > 0 } key ? Tag(fp, key) : null;
    }

    static string Clip(string s, int n) => s.Length > n ? s[..n] : s;
}
