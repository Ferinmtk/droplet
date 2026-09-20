using System.Diagnostics;
using System.IO;
using Microsoft.Extensions.Logging;

namespace Droplet.Windows.Shell;

/// <summary>Opening things, and marking what arrives.</summary>
internal static class Files
{
    /// <summary>
    /// Adds the Mark of the Web to a received file, as a browser download would, so Windows
    /// (SmartScreen, Office's Protected View) treats it with the same care as one fetched from
    /// the hub's web page. Best effort.
    /// </summary>
    public static void MarkFromInternet(string path, ILogger log)
    {
        try
        {
            File.WriteAllText(path + ":Zone.Identifier", "[ZoneTransfer]\r\nZoneId=3\r\n");
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or NotSupportedException)
        {
            // FAT32 and some network drives have no alternate data streams
            log.LogDebug("mark of the web on {Path}: {Error}", path, e.Message);
        }
    }

    /// <summary>Opens a link, a file (with its default app) or a folder.</summary>
    public static void Open(string target, ILogger log)
    {
        try
        {
            using var _ = Process.Start(new ProcessStartInfo(target) { UseShellExecute = true });
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException or FileNotFoundException)
        {
            log.LogWarning("open {Target}: {Error}", target, e.Message);
        }
    }

    /// <summary>Opens Explorer with the file selected.</summary>
    public static void ShowInFolder(string path, ILogger log)
    {
        try
        {
            // explorer parses its own command line: it wants exactly /select,"path"
            using var _ = Process.Start(new ProcessStartInfo("explorer.exe") { Arguments = $"/select,\"{path}\"", UseShellExecute = false });
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            log.LogWarning("show {Path}: {Error}", path, e.Message);
        }
    }
}
