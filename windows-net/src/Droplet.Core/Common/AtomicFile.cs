namespace Droplet.Core.Common;

/// <summary>
/// Files written whole, so a crash mid-write never leaves a torn one: write a
/// temporary file beside it, then rename it over. On Unix the file is owner-only
/// (it may hold a token or a private key); on Windows, the per-user profile folder's
/// ACL already keeps other users out.
/// </summary>
public static class AtomicFile
{
    /// <summary>Writes <paramref name="data"/> to <paramref name="path"/>, replacing it whole.</summary>
    public static void Write(string path, ReadOnlySpan<byte> data)
    {
        var dir = Path.GetDirectoryName(Path.GetFullPath(path))!;
        CreatePrivateDirectory(dir);
        var tmp = path + ".tmp";
        var options = new FileStreamOptions { Mode = FileMode.Create, Access = FileAccess.Write, Share = FileShare.None };
        if (!OperatingSystem.IsWindows())
        {
            options.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
        }
        using (var f = new FileStream(tmp, options))
        {
            f.Write(data);
            f.Flush(flushToDisk: true);
        }
        File.Move(tmp, path, overwrite: true);
    }

    /// <summary>Writes text as UTF-8 (no BOM).</summary>
    public static void WriteText(string path, string text) => Write(path, System.Text.Encoding.UTF8.GetBytes(text));

    /// <summary>Creates a directory (and its parents), owner-only on Unix.</summary>
    public static void CreatePrivateDirectory(string dir)
    {
        if (OperatingSystem.IsWindows())
        {
            Directory.CreateDirectory(dir);
            return;
        }
        if (!Directory.Exists(dir))
        {
            Directory.CreateDirectory(dir, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }
    }

    /// <summary>The file's text, or null when it doesn't exist or can't be read.</summary>
    public static string? TryReadText(string path)
    {
        try
        {
            return File.ReadAllText(path);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }
}
