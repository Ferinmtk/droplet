using System.IO;
using System.Windows;
using System.Windows.Controls;
using Droplet.Core.Config;
using Droplet.Core.LocalFirst;
using Droplet.Windows.Services;
using Droplet.Windows.Shell;
using Droplet.Windows.ViewModels;

namespace Droplet.Windows.Views;

/// <summary>
/// Settings: this PC's name, the hub and how it's reached (with its pinned identity), what
/// remote control may do (clipboard sync off until asked for), files, notifications, Start
/// with Windows and Send To (both off until asked for), and the mesh.
/// </summary>
public partial class SettingsWindow : Window
{
    readonly App app;
    bool loading;

    internal SettingsWindow(App app)
    {
        this.app = app;
        InitializeComponent();
        app.Host.Changed += ShowStatus;
        Closed += (_, _) => app.Host.Changed -= ShowStatus;
        Load();
    }

    AppHost Host => app.Host;

    ConfigStore Store => Host.Engine?.Store ?? throw new InvalidOperationException("droplet is restarting; try again in a moment");

    static void Show(TextBlock error, string? message)
    {
        error.Text = message ?? "";
        error.Visibility = string.IsNullOrEmpty(message) ? Visibility.Collapsed : Visibility.Visible;
    }

    void Load()
    {
        loading = true;
        var c = Host.Config;
        NameBox.Text = c.DeviceName ?? Environment.MachineName.ToLowerInvariant();
        RemoteInput.IsChecked = c.RemoteInput;
        RemoteMedia.IsChecked = c.RemoteMedia;
        RemoteLock.IsChecked = c.RemoteLock;
        RemoteScreenshot.IsChecked = c.RemoteScreenshot;
        ClipboardSync.IsChecked = c.ClipboardSync;
        RemotePaused.IsChecked = c.RemotePaused;
        FolderBox.Text = c.DownloadDir;
        AutoDownload.IsChecked = c.AutoDownload;
        NotifyFiles.IsChecked = c.NotifyFiles;
        NotifyMessages.IsChecked = c.NotifyMessages;
        RingSound.IsChecked = c.RingSound;
        Paused.IsChecked = c.Paused;
        Autostart.IsChecked = c.Autostart;
        SendTo.IsChecked = c.SendTo;
        SendTo.IsEnabled = AppHost.SendToAvailable;
        if (!AppHost.SendToAvailable)
        {
            SendTo.IsChecked = false;
            SendToNote.Text = "Not available in the Store version: Windows keeps packaged apps out of Explorer's Send To folder. " +
                              "Use Send files in the tray or the Devices window instead.";
        }
        MeshEnabled.IsChecked = c.Mesh.Enabled;
        MeshAnnounce.IsChecked = c.Mesh.Announce;
        PortBox.Text = c.Mesh.Port?.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "";
        var version = typeof(App).Assembly.GetName().Version?.ToString(3);
        AboutText.Text = $"droplet {version} for Windows ({(Packaging.IsPackaged ? "Microsoft Store package" : "standalone")}). " +
                         $"Free software under the GPL-3.0. Settings are in {Host.Paths.Root}.";
        UninstallButton.Visibility = Packaging.IsPackaged ? Visibility.Collapsed : Visibility.Visible;
        loading = false;
        ShowStatus();
    }

    /// <summary>The parts that change on their own: the hub's state and the mesh.</summary>
    void ShowStatus()
    {
        var c = Host.Config;
        var t = Host.Tray;
        var hubName = Core.Polling.HubPoller.HubName(c);
        HubDetails.Visibility = c.Hub is null && c.RemoteUrl.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        HubSummary.Text = c switch
        {
            { Pending: true } => $"Waiting to be let in to {hubName}: on one of your devices, allow \"{c.DeviceName}\" and check the code is {c.PairCode}.",
            { Registered: true } => $"This PC is \"{c.DeviceName}\" on {hubName}.",
            _ when t.NotAllowed => $"{hubName} doesn't let this PC in any more. Set it up again.",
            _ => "No hub yet. A hub keeps things for devices that are off, and reaches them from anywhere; you can also pair devices directly.",
        };
        HubRoute.Text = t.Connected ? (t.Route.Length > 0 ? $"{hubName} {t.Route}" : hubName) : (t.Problem.Length > 0 ? t.Problem : "not now");
        HubId.Text = c.Hub?.Id ?? "(unknown)";
        HubPin.Text = c.Hub?.Fingerprint is { Length: > 16 } fp
            ? $"{fp[..16]}… ({(c.Hub.PinSource == PinSources.Tailnet ? "checked over Tailscale" : "trusted when this PC joined on the LAN")})"
            : "not pinned (reached over Tailscale only)";
        HubTailnet.Text = c.RemoteUrl.Length > 0 ? c.RemoteUrl : "none";
        SetupButton.Content = c.Registered || c.Pending ? "Change hub…" : "Set up a hub…";
        RepairButton.Visibility = t.IdentityChanged ? Visibility.Visible : Visibility.Collapsed;

        if (Host.Engine?.Mesh is { } m)
        {
            Fingerprint.Text = SettingsLogic.Grouped(m.Identity.Fingerprint);
            var peers = m.Trust.All();
            MeshStatus.Text = $"Listening on port {m.Port}. {peers.Count} trusted device{(peers.Count == 1 ? "" : "s")}" +
                              (m.Refused > 0 ? $"; {m.Refused} connection{(m.Refused == 1 ? "" : "s")} from untrusted devices refused." : ".");
        }
        else
        {
            Fingerprint.Text = "";
            MeshStatus.Text = c.Mesh.Enabled ? "Not listening: no free port (see the log)." : "Switched off.";
        }
    }

    void Switch_Click(object sender, RoutedEventArgs e)
    {
        if (loading)
        {
            return;
        }
        try
        {
            var pausing = RemotePaused.IsChecked == true && !Host.Config.RemotePaused;
            Store.Update(c =>
            {
                c.RemoteInput = RemoteInput.IsChecked == true;
                c.RemoteMedia = RemoteMedia.IsChecked == true;
                c.RemoteLock = RemoteLock.IsChecked == true;
                c.RemoteScreenshot = RemoteScreenshot.IsChecked == true;
                c.ClipboardSync = ClipboardSync.IsChecked == true;
                c.RemotePaused = RemotePaused.IsChecked == true;
                c.AutoDownload = AutoDownload.IsChecked == true;
                c.NotifyFiles = NotifyFiles.IsChecked == true;
                c.NotifyMessages = NotifyMessages.IsChecked == true;
                c.RingSound = RingSound.IsChecked == true;
                c.Paused = Paused.IsChecked == true;
                c.SendTo = AppHost.SendToAvailable && SendTo.IsChecked == true;
            });
            if (pausing)
            {
                Host.SetRemotePaused(true); // lets go of a drag in progress
            }
            Host.Refresh();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            App.ShowError("Couldn't save the settings: " + ex.Message);
            Load();
        }
    }

    async void Autostart_Click(object sender, RoutedEventArgs e)
    {
        var problem = await Host.SetAutostartAsync(Autostart.IsChecked == true);
        Autostart.IsChecked = Host.Config.Autostart;
        AutostartNote.Text = problem ?? "";
        AutostartNote.Visibility = problem is null ? Visibility.Collapsed : Visibility.Visible;
    }

    async void SaveName_Click(object sender, RoutedEventArgs e)
    {
        Show(NameError, null);
        SaveName.IsEnabled = false;
        try
        {
            var name = HubSetup.CleanDeviceName(NameBox.Text);
            if (Host.Config.Registered || Host.Config.Pending)
            {
                await Host.Engine!.Setup.SetupAsync(null, name);
            }
            else
            {
                Store.Update(c => c.DeviceName = name);
            }
            NameBox.Text = Host.Config.DeviceName;
        }
        catch (FieldException ex)
        {
            Show(NameError, ex.Message);
        }
        catch (InvalidOperationException ex)
        {
            Show(NameError, ex.Message);
        }
        finally
        {
            SaveName.IsEnabled = true;
        }
    }

    void Browse_Click(object sender, RoutedEventArgs e)
    {
        var d = new Microsoft.Win32.OpenFolderDialog { Title = "Save received files in", InitialDirectory = Directory.Exists(FolderBox.Text) ? FolderBox.Text : "" };
        if (d.ShowDialog(this) == true)
        {
            FolderBox.Text = d.FolderName;
            SaveFolder_Click(sender, e);
        }
    }

    async void SaveFolder_Click(object sender, RoutedEventArgs e)
    {
        var (folder, error) = SettingsLogic.DownloadDir(FolderBox.Text, AppPaths.DefaultDownloadDir());
        if (folder is null)
        {
            Show(FolderError, error);
            return;
        }
        try
        {
            Directory.CreateDirectory(folder);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            Show(FolderError, "Can't use that folder: " + ex.Message);
            return;
        }
        Show(FolderError, null);
        FolderBox.Text = folder;
        await SaveAsync(c => c.DownloadDir = folder);
    }

    async void ApplyMesh_Click(object sender, RoutedEventArgs e)
    {
        var (port, error) = SettingsLogic.Port(PortBox.Text);
        if (error is not null)
        {
            Show(MeshError, error);
            return;
        }
        Show(MeshError, null);
        await SaveAsync(c =>
        {
            c.Mesh.Enabled = MeshEnabled.IsChecked == true;
            c.Mesh.Announce = MeshAnnounce.IsChecked == true;
            c.Mesh.Port = port;
        });
    }

    /// <summary>Saves a change, restarting the engine when it's one the engine reads only at start.</summary>
    async Task SaveAsync(Action<AppConfig> change)
    {
        try
        {
            var before = Host.Config;
            var after = Store.Update(change);
            if (SettingsLogic.NeedsRestart(before, after))
            {
                IsEnabled = false;
                await Host.RestartAsync();
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            App.ShowError("Couldn't apply that: " + ex.Message);
        }
        finally
        {
            IsEnabled = true;
            if (IsLoaded)
            {
                Load();
            }
        }
    }

    void Setup_Click(object sender, RoutedEventArgs e) => app.ShowSetup();

    async void Repair_Click(object sender, RoutedEventArgs e)
    {
        Show(HubError, null);
        RepairButton.IsEnabled = false;
        try
        {
            var res = await Host.Engine!.Setup.RepairAsync();
            if (res.Done)
            {
                Host.Engine.Routes.ClearChanged();
                MessageBox.Show(this, "The hub's new certificate is confirmed over Tailscale and pinned. droplet uses Wi-Fi again.", "Re-pair",
                    MessageBoxButton.OK, MessageBoxImage.Information);
            }
            else if (res.Join)
            {
                MessageBox.Show(this, $"{res.Reason}\n\nJoin the hub again on this network, comparing the code, to trust its new certificate.",
                    "Re-pair", MessageBoxButton.OK, MessageBoxImage.Information);
                app.ShowSetup(res.HubId);
            }
        }
        catch (Exception ex) when (ex is InvalidOperationException or FieldException)
        {
            Show(HubError, ex.Message);
        }
        finally
        {
            RepairButton.IsEnabled = true;
        }
    }

    void CopyFingerprint_Click(object sender, RoutedEventArgs e)
    {
        if (Host.Engine?.Mesh is { } m)
        {
            Clipboard.SetText(m.Identity.Fingerprint);
        }
    }

    void OpenLog_Click(object sender, RoutedEventArgs e) => Files.Open(Host.Paths.LogFile, Host.Log);

    void OpenFolder_Click(object sender, RoutedEventArgs e) => Files.Open(Host.Paths.Root, Host.Log);

    async void Uninstall_Click(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show(this, "Remove Start with Windows, the Send To entries, the Start menu entry and the droplet: link handler? " +
                                  "Your settings stay. If you start droplet again, it adds the link handler and the Start menu entry back.", "Remove from Windows",
                MessageBoxButton.OKCancel, MessageBoxImage.Question) != MessageBoxResult.OK)
        {
            return;
        }
        var errors = await Host.UninstallAsync();
        MessageBox.Show(this, errors.Count == 0 ? "Done. You can delete droplet.exe now." : string.Join("\n", errors), "Remove from Windows");
        Load();
    }
}
