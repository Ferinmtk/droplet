using System.Windows;
using System.Windows.Controls;
using Droplet.Windows.Services;

namespace Droplet.Windows.Tray;

/// <summary>
/// The tray's menu and icon: Open droplet · Send files ▸ · Send clipboard ▸ · Ring ▸ ·
/// Devices… · Open downloads folder · Pause notifications · Pause remote control ·
/// Settings… · Quit. The icon dims when nothing can be reached and turns amber while
/// another device sends input; the tooltip says how the hub is reached and who's in control.
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

    public TrayController(App app, AppHost host, NotifyIcon icon)
    {
        this.app = app;
        this.host = host;
        this.icon = icon;
        stopRing.Click += async (_, _) => await host.StopRingAsync();
        pauseNotifications.Click += (_, _) => Guard(() => host.SetNotificationsPaused(pauseNotifications.IsChecked));
        pauseRemote.Click += (_, _) => Guard(() => host.SetRemotePaused(pauseRemote.IsChecked));
        var menu = new ContextMenu { MinWidth = 240 };
        menu.Items.Add(header);
        menu.Items.Add(stopRing);
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
    }

    /// <summary>Fills the device submenus as the menu opens.</summary>
    void Rebuild()
    {
        Update();
        var dests = host.Destinations;
        Fill(sendFiles, dests, d => d.Label, app.PickAndSend);
        Fill(sendClip, dests, d => d.Label, app.SendClipboard);
        Fill(ring, dests, d => d.Label, d => _ = host.RingAsync(d));
    }

    /// <summary>Text shown as is: an underscore would otherwise mark an access key.</summary>
    static string Plain(string s) => s.Replace("_", "__", StringComparison.Ordinal);

    static void Fill(MenuItem parent, IReadOnlyList<Destination> dests, Func<Destination, string> label, Action<Destination> click)
    {
        parent.Items.Clear();
        foreach (var d in dests)
        {
            var item = new MenuItem { Header = Plain(label(d)) };
            item.Click += (_, _) => click(d);
            parent.Items.Add(item);
        }
        if (dests.Count == 0)
        {
            parent.Items.Add(new MenuItem { Header = "No devices yet: pair one under Devices", IsEnabled = false });
        }
    }
}
