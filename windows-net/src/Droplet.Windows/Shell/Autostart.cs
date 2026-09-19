using Microsoft.Win32;

namespace Droplet.Windows.Shell;

/// <summary>
/// Start with Windows. It's off until turned on in Settings. Unpackaged, it's the per-user
/// Run key, rewritten at each start while on (the exe may have moved); packaged, it's the
/// manifest's StartupTask, which Windows lets the app enable only with the person's say-so,
/// and which they can also turn off in Settings → Apps → Startup.
/// </summary>
internal static class Autostart
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string ValueName = "droplet";

    /// <summary>The StartupTask's id in the package manifest.</summary>
    public const string TaskId = "dropletStartup";

    /// <summary>The argument that says "started at sign-in": stay in the tray.</summary>
    public const string BackgroundArg = "--background";

    /// <summary>Sets or removes the Run key entry (null: remove).</summary>
    public static void SetRunKey(string? exe)
    {
        using var k = Registry.CurrentUser.CreateSubKey(RunKey);
        if (exe is null)
        {
            k.DeleteValue(ValueName, throwOnMissingValue: false);
        }
        else
        {
            k.SetValue(ValueName, $"\"{exe}\" {BackgroundArg}");
        }
    }

    /// <summary>
    /// Turns it on or off. Returns what happened, for Settings: a packaged app's startup task
    /// may be disabled by the person or by policy, and then only they can turn it back on.
    /// </summary>
    public static async Task<string?> SetAsync(bool on, string exe)
    {
        if (!Packaging.IsPackaged)
        {
            SetRunKey(on ? exe : null);
            return null;
        }
        var task = await global::Windows.ApplicationModel.StartupTask.GetAsync(TaskId);
        if (!on)
        {
            task.Disable();
            return null;
        }
        var state = await task.RequestEnableAsync();
        return state switch
        {
            global::Windows.ApplicationModel.StartupTaskState.Enabled or global::Windows.ApplicationModel.StartupTaskState.EnabledByPolicy => null,
            global::Windows.ApplicationModel.StartupTaskState.DisabledByUser =>
                "Windows says you turned droplet off under Settings → Apps → Startup. Turn it on there.",
            global::Windows.ApplicationModel.StartupTaskState.DisabledByPolicy => "Your organisation's policy doesn't allow droplet to start at sign-in.",
            _ => "Windows didn't turn start-at-sign-in on.",
        };
    }

    /// <summary>Whether it's on now, as Windows sees it (packaged), or as the settings say (unpackaged).</summary>
    public static async Task<bool?> IsEnabledAsync()
    {
        if (!Packaging.IsPackaged)
        {
            return null;
        }
        var task = await global::Windows.ApplicationModel.StartupTask.GetAsync(TaskId);
        return task.State is global::Windows.ApplicationModel.StartupTaskState.Enabled or global::Windows.ApplicationModel.StartupTaskState.EnabledByPolicy;
    }
}
