using System.Globalization;
using System.IO;
using System.Text.RegularExpressions;

namespace Droplet.Windows.ViewModels;

/// <summary>What Settings checks before saving: pure, so it's tested anywhere.</summary>
internal static partial class SettingsLogic
{
    [GeneratedRegex("%([A-Za-z0-9_()]+)%")]
    private static partial Regex PercentVar();

    /// <summary>A folder as typed: quotes trimmed, %VARIABLES% and ~ expanded, tidied.</summary>
    public static string ExpandPath(string? typed, Func<string, string?>? env = null, string? home = null)
    {
        env ??= Environment.GetEnvironmentVariable;
        home ??= Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var p = (typed ?? "").Trim().Trim('"').Trim();
        p = PercentVar().Replace(p, m => env(m.Groups[1].Value) ?? m.Value);
        if (p == "~" || p.StartsWith("~/", StringComparison.Ordinal) || p.StartsWith("~\\", StringComparison.Ordinal))
        {
            p = home + p[1..];
        }
        return p;
    }

    /// <summary>The download folder to save, or an error to show.</summary>
    public static (string? Folder, string? Error) DownloadDir(string? typed, string fallback, Func<string, string?>? env = null, string? home = null)
    {
        var p = ExpandPath(typed, env, home);
        if (p.Length == 0)
        {
            return (fallback, null);
        }
        if (!Path.IsPathFullyQualified(p))
        {
            return (null, @"Use a full path, like C:\Users\you\Downloads\droplet.");
        }
        try
        {
            return (Path.GetFullPath(p), null);
        }
        catch (Exception e) when (e is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return (null, "That isn't a folder path: " + e.Message);
        }
    }

    /// <summary>The mesh port as typed: empty (or "auto") picks the first free one in 1739–1749.</summary>
    public static (int? Port, string? Error) Port(string? typed)
    {
        var t = (typed ?? "").Trim();
        if (t.Length == 0 || t.Equals("auto", StringComparison.OrdinalIgnoreCase))
        {
            return (null, null);
        }
        if (!int.TryParse(t, NumberStyles.None, CultureInfo.InvariantCulture, out var port) || port is < 1024 or > 65535)
        {
            return (null, "A port is a number from 1024 to 65535; leave it empty to choose automatically.");
        }
        return (port, null);
    }

    /// <summary>A fingerprint in groups of four, for reading aloud and comparing.</summary>
    public static string Grouped(string? fingerprint)
    {
        var f = fingerprint ?? "";
        return string.Join(' ', Enumerable.Range(0, (f.Length + 3) / 4).Select(i => f.Substring(i * 4, Math.Min(4, f.Length - i * 4))));
    }

    /// <summary>Whether mesh settings changed in a way the engine only reads at start.</summary>
    public static bool NeedsRestart(Core.Config.AppConfig before, Core.Config.AppConfig after)
    {
        ArgumentNullException.ThrowIfNull(before);
        ArgumentNullException.ThrowIfNull(after);
        return before.Mesh.Enabled != after.Mesh.Enabled || before.Mesh.Port != after.Mesh.Port || before.Mesh.Announce != after.Mesh.Announce ||
               (string.IsNullOrWhiteSpace(after.Mesh.Downloads) && before.DownloadDir != after.DownloadDir);
    }
}
