using System.IO;

namespace Droplet.Windows.Shell;

/// <summary>A place files can be sent to from Explorer's Send To menu.</summary>
/// <param name="Key">What <c>droplet send --to</c> takes (see <see cref="Services.Destination.Key"/>).</param>
/// <param name="Name">What the menu shows.</param>
internal sealed record SendToTarget(string Key, string Name);

/// <summary>
/// Explorer's "Send to" menu: one "droplet → name" shortcut per destination, only while
/// switched on in Settings. Each runs <c>droplet send --to &lt;key&gt; &lt;files&gt;</c>, which
/// hands the files to the running app.
/// </summary>
internal static class SendTo
{
    /// <summary>Marks droplet's own shortcuts, so stale ones can be removed.</summary>
    public const string Prefix = "droplet → ";

    static string Folder => Environment.GetFolderPath(Environment.SpecialFolder.SendTo);

    /// <summary>
    /// Makes the menu hold exactly one entry per target, removing any left from devices that
    /// are gone. With no exe (or no targets), removes them all. Returns what went wrong.
    /// </summary>
    public static List<string> Sync(string? exe, IReadOnlyList<SendToTarget> targets)
    {
        var errors = new List<string>();
        var dir = Folder;
        if (string.IsNullOrEmpty(dir))
        {
            return errors;
        }
        var want = new Dictionary<string, SendToTarget>(StringComparer.OrdinalIgnoreCase);
        if (exe is not null)
        {
            foreach (var t in targets)
            {
                want.TryAdd(Prefix + FileSafe(t.Name) + ".lnk", t);
            }
        }
        if (Directory.Exists(dir))
        {
            foreach (var p in Directory.EnumerateFiles(dir, Prefix + "*.lnk"))
            {
                if (want.ContainsKey(Path.GetFileName(p)))
                {
                    continue;
                }
                try
                {
                    File.Delete(p);
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                    errors.Add(e.Message);
                }
            }
        }
        // existing ones are rewritten too: the exe may have moved
        foreach (var (file, t) in want)
        {
            try
            {
                ShellLink.Save(new Shortcut(Path.Combine(dir, file), exe!, $"send --to {t.Key}", $"Send to {t.Name} with droplet", exe!));
            }
            catch (Exception e) when (e is System.Runtime.InteropServices.COMException or IOException or UnauthorizedAccessException)
            {
                errors.Add($"{file}: {e.Message}");
            }
        }
        return errors;
    }

    /// <summary>A name with the characters Windows won't allow in a file name replaced.</summary>
    public static string FileSafe(string s)
    {
        var chars = (s ?? "").Select(c => c < 32 || "<>:\"/\\|?*".Contains(c, StringComparison.Ordinal) ? '_' : c).ToArray();
        var name = new string(chars).TrimEnd('.', ' ');
        return name.Length == 0 ? "device" : name;
    }
}
