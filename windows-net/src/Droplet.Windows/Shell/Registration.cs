using System.IO;
using Microsoft.Extensions.Logging;
using Microsoft.Win32;

namespace Droplet.Windows.Shell;

/// <summary>
/// What an unpackaged droplet.exe registers for itself, all per user (HKCU, nothing needs
/// admin): an identity (name and icon) for its notifications, the <c>droplet:</c> links
/// their buttons call, and a Start menu entry carrying that identity. Cheap to redo at
/// every start, which also follows the exe if it moved. A package gets all of this from its
/// manifest instead. <see cref="Unregister"/> undoes it, with Start with Windows and Send To.
/// </summary>
internal static class Registration
{
    /// <summary>The AppUserModelID notifications are shown under (the Go app's, which this replaces).</summary>
    public const string AppId = "Ferinmtk.droplet";

    const string ProtocolKey = @"Software\Classes\" + ActionLinks.Scheme;
    const string AppIdKey = @"Software\Classes\AppUserModelId\" + AppId;

    /// <summary>The Start menu entry.</summary>
    public static string StartMenuShortcut =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "droplet.lnk");

    /// <summary>Registers the notification identity, the droplet: link handler and the Start menu entry.</summary>
    public static void Register(string exe, string? iconPng, ILogger log)
    {
        try
        {
            using (var k = Registry.CurrentUser.CreateSubKey(AppIdKey))
            {
                k.SetValue("DisplayName", "droplet");
                if (!string.IsNullOrEmpty(iconPng))
                {
                    k.SetValue("IconUri", iconPng);
                }
                k.SetValue("IconBackgroundColor", "FF061419");
            }
            using (var k = Registry.CurrentUser.CreateSubKey(ProtocolKey))
            {
                k.SetValue("", "URL:droplet");
                k.SetValue("URL Protocol", "");
            }
            using (var k = Registry.CurrentUser.CreateSubKey(ProtocolKey + @"\DefaultIcon"))
            {
                k.SetValue("", $"\"{exe}\",0");
            }
            using (var k = Registry.CurrentUser.CreateSubKey(ProtocolKey + @"\shell\open\command"))
            {
                k.SetValue("", $"\"{exe}\" \"%1\"");
            }
        }
        catch (Exception e) when (e is UnauthorizedAccessException or IOException or System.Security.SecurityException)
        {
            log.LogWarning("register: {Error}", e.Message);
        }
        try
        {
            // the documented way for a desktop app to own its toasts, and a way to find droplet again
            ShellLink.Save(new Shortcut(StartMenuShortcut, exe, Description: "droplet: your devices, together", Icon: exe, AppId: AppId));
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or UnauthorizedAccessException or IOException)
        {
            log.LogWarning("start menu: {Error}", e.Message);
        }
    }

    /// <summary>Removes everything <see cref="Register"/>, Start with Windows and Send To added. Returns what went wrong.</summary>
    public static List<string> Unregister()
    {
        var errors = new List<string>();
        foreach (var key in (ReadOnlySpan<string>)[ProtocolKey, AppIdKey])
        {
            try
            {
                Registry.CurrentUser.DeleteSubKeyTree(key, throwOnMissingSubKey: false);
            }
            catch (Exception e) when (e is UnauthorizedAccessException or IOException or System.Security.SecurityException)
            {
                errors.Add(e.Message);
            }
        }
        try
        {
            Autostart.SetRunKey(null);
        }
        catch (Exception e) when (e is UnauthorizedAccessException or IOException or System.Security.SecurityException)
        {
            errors.Add(e.Message);
        }
        errors.AddRange(SendTo.Sync(null, []));
        try
        {
            File.Delete(StartMenuShortcut);
        }
        catch (Exception e) when (e is UnauthorizedAccessException or IOException)
        {
            errors.Add(e.Message);
        }
        return errors;
    }
}
