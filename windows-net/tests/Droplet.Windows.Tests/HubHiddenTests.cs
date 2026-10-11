using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Markup;
using System.Windows.Threading;
using System.Xml.Linq;
using Droplet.Core.Config;
using Droplet.Core.Hub;
using Droplet.Core.Mesh;
using Droplet.Windows.Services;
using Droplet.Windows.Shell;
using Droplet.Windows.Tray;
using Droplet.Windows.ViewModels;

namespace Droplet.Windows.Tests;

/// <summary>
/// The hub is optional and hidden: a fresh install never reads the word, while a PC that has a
/// hub keeps all of its hub things (docs/mesh.md).
/// </summary>
public sealed class HubHiddenTests
{
    static bool SaysHub(string? s) => s?.Contains("hub", StringComparison.OrdinalIgnoreCase) == true;

    static AppConfig Linked() => new()
    {
        HubUrl = "https://t15.tailnet.ts.net", DeviceId = "d1", DeviceName = "office", DeviceToken = "tok",
        Hub = new HubIdentity { Id = "hub1", Name = "t15" },
    };

    [Fact]
    public void A_fresh_install_opens_at_pairing_and_has_no_hub()
    {
        var fresh = new AppConfig();
        Assert.False(fresh.UsesHub);
        Assert.Equal(FirstStart.Pairing, HubUi.OnOpen(fresh, trusted: 0));
        Assert.Equal(FirstStart.Devices, HubUi.OnOpen(fresh, trusted: 2));
        Assert.False(SaysHub(HubUi.NameNote(false)));
        Assert.False(SaysHub(HubUi.MeshNote(false)));
        // the tray, with nothing paired and with paired devices
        Assert.Equal("droplet — no devices paired yet (open droplet to pair one)", new TrayState().Tooltip());
        Assert.False(SaysHub(new TrayState().Tooltip()));
        Assert.False(SaysHub(new TrayState { Peers = 2, PeersLinked = 1 }.Tooltip()));
        Assert.False(new TrayState().UsesHub);
        // the devices: no hub to send to
        var list = Destinations.Build([new PeerView(new TrustEntry { Id = "p", Name = "pixel", Fp = new string('a', 64), CertPem = "", Source = TrustSource.Paired }, null, false)],
            [], null, null, hubRegistered: false, hubConnected: false);
        Assert.DoesNotContain(list, d => d.IsHub || SaysHub(d.Label) || SaysHub(d.Route));
    }

    [Fact]
    public void The_usage_keeps_the_hub_in_its_advanced_part()
    {
        var at = Command.Usage.IndexOf("Advanced", StringComparison.Ordinal);
        Assert.True(at > 0);
        Assert.False(SaysHub(Command.Usage[..at]));
        Assert.Contains("droplet hub", Command.Usage[at..], StringComparison.Ordinal);
        Assert.Equal(CommandKind.Hub, Command.Parse(["hub"]).Kind);
        Assert.False(SaysHub(Command.Parse(["send", "a.txt"]).Error));
        // sending to the hub still parses
        Assert.Equal((CommandKind.Send, "hub"), (Command.Parse(["send", "--to", "hub", "a.txt"]).Kind, Command.Parse(["send", "--to", "hub", "a.txt"]).Target));
    }

    [Fact]
    public void A_pc_with_a_hub_keeps_its_hub_things()
    {
        var cfg = Linked();
        Assert.True(cfg.UsesHub);
        Assert.True(cfg.Registered);
        Assert.Equal(FirstStart.Devices, HubUi.OnOpen(cfg, trusted: 0));
        // a hub given (its address) but not joined yet: its setup window, as before
        Assert.Equal(FirstStart.HubSetup, HubUi.OnOpen(new AppConfig { HubUrl = "https://t15.tailnet.ts.net" }, trusted: 0));
        Assert.True(SaysHub(HubUi.NameNote(true)));
        Assert.True(SaysHub(HubUi.MeshNote(true)));
        var connected = new TrayState { UsesHub = true, Configured = true, Connected = true, Polled = true, HubName = "t15", Route = "on Wi-Fi" };
        Assert.Equal("droplet — t15 on Wi-Fi", connected.Tooltip());
        var list = Destinations.Build([], [new Device { Id = "dev2", Name = "tablet", Online = true }], "hub1", "t15", hubRegistered: true, hubConnected: true);
        Assert.Equal(["hub", "dev:dev2"], list.Select(d => d.Key));
        Assert.Equal("via the hub", list[1].Route);
        // the config round-trips: UsesHub isn't stored, the hub's fields are
        var json = System.Text.Json.JsonSerializer.Serialize(cfg);
        Assert.DoesNotContain("UsesHub", json, StringComparison.Ordinal);
        Assert.True(System.Text.Json.JsonSerializer.Deserialize<AppConfig>(json)!.UsesHub);
    }

    [Fact]
    public void The_windows_say_hub_only_in_parts_shown_with_a_hub()
    {
        // every hub word in Settings and Devices sits in an element that starts hidden and is shown only with a hub
        var hubOnly = new HashSet<string> { "HubCard", "AutoDownload", "WebButton" };
        XNamespace x = "http://schemas.microsoft.com/winfx/2006/xaml";
        foreach (var file in new[] { "SettingsWindow.xaml", "DevicesWindow.xaml" })
        {
            var doc = XDocument.Load(LooseXaml.Source(file));
            foreach (var el in doc.Descendants())
            {
                if (el.AncestorsAndSelf().Any(a => hubOnly.Contains((string?)a.Attribute(x + "Name") ?? "")))
                {
                    continue;
                }
                var said = el.Attributes().Where(a => a.Name.LocalName is "Text" or "Content" or "ToolTip" or "Title").Select(a => a.Value)
                    .Concat(el.Nodes().OfType<XText>().Select(t => t.Value));
                Assert.DoesNotContain(said, SaysHub);
            }
            foreach (var name in hubOnly)
            {
                var el = doc.Descendants().FirstOrDefault(e => (string?)e.Attribute(x + "Name") == name);
                if (el is not null)
                {
                    Assert.Equal("Collapsed", (string?)el.Attribute("Visibility"));
                }
            }
        }
    }
}

/// <summary>A window's XAML, loaded as loose XAML (no code behind), so it's seen as a fresh install sees it.</summary>
internal static class LooseXaml
{
    /// <summary>The window's .xaml in the source tree, found from where the tests run.</summary>
    public static string Source(string name)
    {
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d is not null; d = d.Parent)
        {
            var p = Path.Combine(d.FullName, "src", "Droplet.Windows", "Views", name);
            if (File.Exists(p))
            {
                return p;
            }
        }
        throw new FileNotFoundException("the source XAML wasn't found above " + AppContext.BaseDirectory, name);
    }

    public static Window Load(string name)
    {
        var doc = XDocument.Load(Source(name));
        XNamespace x = "http://schemas.microsoft.com/winfx/2006/xaml";
        doc.Root!.Attribute(x + "Class")?.Remove();
        foreach (var el in doc.Root.DescendantsAndSelf())
        {
            foreach (var a in el.Attributes().ToList())
            {
                if (a.Name.LocalName is "Click" or "SelectionChanged")
                {
                    a.Remove();
                }
                else if (a.Value.StartsWith("/Assets/", StringComparison.Ordinal))
                {
                    a.Value = "pack://application:,,,/droplet;component" + a.Value;
                }
                else if (a.IsNamespaceDeclaration && a.Value == "clr-namespace:Droplet.Windows.Views")
                {
                    a.Value += ";assembly=droplet";
                }
            }
        }
        return (Window)XamlReader.Parse(doc.ToString());
    }

    /// <summary>The words shown now: text, buttons' and switches' labels, and tooltips of what's visible.</summary>
    public static List<string> Shown(object root)
    {
        var found = new List<string>();
        void Walk(object o)
        {
            if (o is UIElement { IsVisible: false })
            {
                return;
            }
            switch (o)
            {
                case TextBlock t:
                    found.Add(t.Text);
                    break;
                case ContentControl { Content: string s }:
                    found.Add(s);
                    break;
            }
            if (o is FrameworkElement { ToolTip: string tip })
            {
                found.Add(tip);
            }
            if (o is DependencyObject d)
            {
                foreach (var c in LogicalTreeHelper.GetChildren(d))
                {
                    Walk(c);
                }
            }
        }
        Walk(root);
        return found;
    }

    public static void ShowOffScreen(Window w)
    {
        w.WindowStartupLocation = WindowStartupLocation.Manual;
        (w.Left, w.Top, w.ShowInTaskbar, w.ShowActivated) = (-20000.0, -20000.0, false, false);
        w.Show();
        w.UpdateLayout();
        Dispatcher.CurrentDispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
    }
}

/// <summary>
/// Settings and Devices as a fresh install shows them (no hub anywhere), and Settings on a PC
/// with a hub: run inside the screenshot test, which owns the WPF application.
/// </summary>
internal static class HubHiddenScreens
{
    static bool SaysHub(string s) => s.Contains("hub", StringComparison.OrdinalIgnoreCase);

    public static List<string> Make(Func<Window, string, Func<Window, FrameworkElement>?, string> shoot)
    {
        var made = new List<string>();

        // Settings, fresh: no Hub card, no hub switch, hub-free notes
        var settings = LooseXaml.Load("SettingsWindow.xaml");
        ((TextBlock)settings.FindName("NameNote")).Text = HubUi.NameNote(false);
        ((TextBlock)settings.FindName("MeshNote")).Text = HubUi.MeshNote(false);
        LooseXaml.ShowOffScreen(settings);
        var said = LooseXaml.Shown(settings);
        Assert.Contains("Sharing", said);
        Assert.DoesNotContain(said, SaysHub);
        made.Add(shoot(settings, "7-settings-fresh-install-no-hub", w => (FrameworkElement)((ScrollViewer)w.Content).Content));

        // Settings with a hub: the Hub card is back, as before
        var linked = LooseXaml.Load("SettingsWindow.xaml");
        ((Border)linked.FindName("HubCard")).Visibility = Visibility.Visible;
        ((CheckBox)linked.FindName("AutoDownload")).Visibility = Visibility.Visible;
        ((TextBlock)linked.FindName("HubSummary")).Text = "This PC is \"office\" on t15.";
        ((TextBlock)linked.FindName("NameNote")).Text = HubUi.NameNote(true);
        ((TextBlock)linked.FindName("MeshNote")).Text = HubUi.MeshNote(true);
        ((Button)linked.FindName("SetupButton")).Content = "Change hub…";
        LooseXaml.ShowOffScreen(linked);
        var withHub = LooseXaml.Shown(linked);
        Assert.Contains("Hub", withHub);
        Assert.Contains("Change hub…", withHub);
        made.Add(shoot(linked, "8-settings-with-a-hub", w => (FrameworkElement)((ScrollViewer)w.Content).Content));

        // Devices, fresh: it opens at pairing; no "Open droplet" (the hub's web app)
        var devices = LooseXaml.Load("DevicesWindow.xaml");
        ((TextBlock)devices.FindName("StatusText")).Text = new TrayState().Tooltip().Replace("droplet — ", "", StringComparison.Ordinal);
        ((Border)devices.FindName("PairCard")).Visibility = Visibility.Visible;
        LooseXaml.ShowOffScreen(devices);
        var shown = LooseXaml.Shown(devices);
        Assert.Contains("Pair a device", shown);
        Assert.DoesNotContain("Open droplet", shown);
        Assert.DoesNotContain(shown, SaysHub);
        made.Add(shoot(devices, "9-devices-fresh-install-pairing", null));
        return made;
    }
}
