using System.Windows;
using System.Windows.Controls;
using Droplet.Core.Mesh;
using Droplet.Windows.Services;

namespace Droplet.Windows.Tray;

/// <summary>
/// The tray's menu and icon: Pause (Resume) everything · Pause a device ▸ · Open droplet ·
/// Send files ▸ · Send clipboard ▸ · Ring ▸ · Devices… · Open downloads folder · Pause
/// notifications · Pause remote control · Settings… · Quit. The icon dims when nothing can be
/// reached, turns amber while another device sends input, and shows a pause sign while
/// everything is paused; the tooltip says how the hub is reached and who's in control.
/// </summary>
internal sealed class TrayController
{
    readonly App app;
    readonly AppHost host;
    readonly NotifyIcon icon;
    readonly MenuItem header = new() { IsEnabled = false };
    readonly MenuItem stopRing = new();
    readonly MenuItem sendFiles = new() { Header = "Send files to" };
    readonly MenuItem sendClip = new() { Header = "Send clipboard to" };
    readonly MenuItem ring = new() { Header = "Ring" };
    readonly MenuItem pauseNotifications = new() { Header = "Pause notifications", IsCheckable = true };
    readonly MenuItem pauseRemote = new() { Header = "Pause remote control", IsCheckable = true };
    readonly MenuItem pauseAll = new();
    readonly MenuItem pauseDevice = new() { Header = "Pause a device" };

    public TrayController(App app, AppHost host, NotifyIcon icon)
    {
        this.app = app;
        this.host = host;
        this.icon = icon;
        stopRing.Click += async (_, _) => await host.StopRingAsync();
        pauseNotifications.Click += (_, _) => Guard(() => host.SetNotificationsPaused(pauseNotifications.IsChecked));
        pauseRemote.Click += (_, _) => Guard(() => host.SetRemotePaused(pauseRemote.IsChecked));
        pauseAll.Click += (_, _) => Guard(() => host.SetPausedAll(!host.Tray.PausedAll));
        var menu = new ContextMenu { MinWidth = 240 };
        menu.Items.Add(header);
        menu.Items.Add(stopRing);
        // sharing first: Pause everything is what you reach for before a presentation
        menu.Items.Add(pauseAll);
        menu.Items.Add(pauseDevice);
        menu.Items.Add(new Separator());
        menu.Items.Add(Item("Open droplet", app.OpenWeb));
        menu.Items.Add(sendFiles);
        menu.Items.Add(sendClip);
        menu.Items.Add(ring);
        menu.Items.Add(Item("Devices…", app.ShowDevices));
        menu.Items.Add(new Separator());
        menu.Items.Add(Item("Open downloads folder", app.OpenDownloads));
        menu.Items.Add(pauseNotifications);
        menu.Items.Add(pauseRemote);
        menu.Items.Add(Item("Settings…", app.ShowSettings));
        menu.Items.Add(new Separator());
        menu.Items.Add(Item("Quit droplet", app.Quit));
        icon.Menu = menu;
        icon.MenuOpening += Rebuild;
        icon.Clicked += app.ShowDevices;
        host.Changed += Update;
    }

    static MenuItem Item(string text, Action click)
    {
        var item = new MenuItem { Header = text };
        item.Click += (_, _) => click();
        return item;
    }

    void Guard(Action action)
    {
        try
        {
            action();
        }
        catch (InvalidOperationException e)
        {
            App.ShowError(e.Message);
        }
        Update();
    }

    /// <summary>Brings the icon and tooltip up to date.</summary>
    public void Update()
    {
        var t = host.Tray;
        var tip = t.Tooltip();
        icon.Show(t.Icon(), tip);
        header.Header = Plain(tip);
        stopRing.Visibility = t.RingingFrom is null ? Visibility.Collapsed : Visibility.Visible;
        stopRing.Header = Plain($"Stop ringing ({t.RingingFrom})");
        pauseNotifications.IsChecked = t.NotificationsPaused;
        pauseRemote.IsChecked = t.RemotePaused;
        pauseAll.Header = t.PausedAll ? "Resume everything" : "Pause everything";
        pauseAll.ToolTip = t.PausedAll ? "Share with your devices again" : "Share nothing with any device, either way, until you resume";
    }

    /// <summary>Fills the device submenus as the menu opens.</summary>
    void Rebuild()
    {
        Update();
        var dests = host.Destinations;
        var pausedAll = host.Tray.PausedAll;
        Fill(sendFiles, dests, d => d.Label, app.PickAndSend, d => Destinations.Blocked(d, Perms.Files, pausedAll));
        Fill(sendClip, dests, d => d.Label, app.SendClipboard, d => Destinations.Blocked(d, Perms.Clipboard, pausedAll));
        Fill(ring, dests, d => d.Label, d => _ = host.RingAsync(d), d => Destinations.Blocked(d, Perms.Ring, pausedAll));
        // each paired or roster device, paused or not
        var peers = dests.Where(d => d.Fp is not null).ToList();
        Fill(pauseDevice, peers, d => d.Paused ? $"Resume {d.Name}" : $"Pause {d.Name}", d => Guard(() => host.SetPeerPaused(d, !d.Paused)));
    }

    /// <summary>Text shown as is: an underscore would otherwise mark an access key.</summary>
    static string Plain(string s) => s.Replace("_", "__", StringComparison.Ordinal);

    static void Fill(MenuItem parent, IReadOnlyList<Destination> dests, Func<Destination, string> label, Action<Destination> click,
        Func<Destination, Block?>? blocked = null)
    {
        parent.Items.Clear();
        foreach (var d in dests)
        {
            // refused (switched off, or the device said no) greys it, with why; a pause only makes it wait
            var block = blocked?.Invoke(d);
            var item = new MenuItem { Header = Plain(label(d)), IsEnabled = block is null || block.Waits, ToolTip = block?.Text };
            ToolTipService.SetShowOnDisabled(item, true);
            item.Click += (_, _) => click(d);
            parent.Items.Add(item);
        }
        if (dests.Count == 0)
        {
            parent.Items.Add(new MenuItem { Header = "No devices yet: pair one under Devices", IsEnabled = false });
        }
    }
}
