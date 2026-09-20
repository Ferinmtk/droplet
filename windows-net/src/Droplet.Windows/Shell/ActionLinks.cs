using System.IO;
using System.Web;
using Droplet.Core.Platform;

namespace Droplet.Windows.Shell;

/// <summary>
/// The <c>droplet:</c> links notifications use for their click and buttons. Toasts activate
/// them as protocol links, which start a new droplet.exe that passes the link to the running
/// one, so nothing has to stay registered as a COM server to hear a click. Each link carries
/// the config's secret action key, so a web page can't trigger one through the scheme.
/// </summary>
internal static class ActionLinks
{
    /// <summary>The URL scheme.</summary>
    public const string Scheme = "droplet";

    static readonly Dictionary<NotificationActionKind, string> Names = new()
    {
        [NotificationActionKind.OpenFile] = "open",
        [NotificationActionKind.ShowInFolder] = "show",
        [NotificationActionKind.OpenFolder] = "folder",
        [NotificationActionKind.StopRing] = "stop-ring",
        [NotificationActionKind.AcceptPairing] = "pair-accept",
        [NotificationActionKind.DenyPairing] = "pair-deny",
    };

    /// <summary>What a click does: web links as they are, everything else as a keyed <c>droplet:</c> link.</summary>
    public static string ToUrl(NotificationAction action, string key)
    {
        ArgumentNullException.ThrowIfNull(action);
        if (action.Kind == NotificationActionKind.OpenUrl)
        {
            return action.Arg;
        }
        var q = $"k={Uri.EscapeDataString(key)}";
        if (action.Arg.Length > 0)
        {
            q += "&p=" + Uri.EscapeDataString(action.Arg);
        }
        return $"{Scheme}:{Names[action.Kind]}?{q}";
    }

    /// <summary>Reads a link back, refusing any without the right key (or with no key configured).</summary>
    public static NotificationAction? Parse(string? raw, string key)
    {
        if (raw is null || key.Length == 0 || !raw.StartsWith(Scheme + ":", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }
        var rest = raw[(Scheme.Length + 1)..].TrimStart('/');
        var qm = rest.IndexOf('?', StringComparison.Ordinal);
        var name = (qm < 0 ? rest : rest[..qm]).TrimEnd('/');
        var query = HttpUtility.ParseQueryString(qm < 0 ? "" : rest[(qm + 1)..]);
        var got = query["k"] ?? "";
        if (!System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(
                System.Text.Encoding.UTF8.GetBytes(got), System.Text.Encoding.UTF8.GetBytes(key)))
        {
            return null;
        }
        foreach (var (kind, n) in Names)
        {
            if (string.Equals(n, name, StringComparison.OrdinalIgnoreCase))
            {
                return new NotificationAction("", kind, query["p"] ?? "");
            }
        }
        return null;
    }

    /// <summary>Whether <paramref name="path"/> is <paramref name="dir"/> or somewhere below it: links only open what droplet saved.</summary>
    public static bool Inside(string path, string dir)
    {
        if (string.IsNullOrWhiteSpace(path) || string.IsNullOrWhiteSpace(dir))
        {
            return false;
        }
        try
        {
            var rel = Path.GetRelativePath(Path.GetFullPath(dir), Path.GetFullPath(path));
            return rel == "." || (!Path.IsPathRooted(rel) && rel != ".." &&
                                  !rel.StartsWith(".." + Path.DirectorySeparatorChar, StringComparison.Ordinal) &&
                                  !rel.StartsWith("../", StringComparison.Ordinal));
        }
        catch (Exception e) when (e is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return false;
        }
    }
}
