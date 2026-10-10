using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Droplet.Core.Mesh;
using Droplet.Windows.Services;
using Droplet.Windows.Shell;
using Droplet.Windows.Themes;
using Droplet.Windows.Tray;
using Droplet.Windows.Views;

namespace Droplet.Windows.Tests;

/// <summary>What the windows and the tray show about per-device permissions and Pause (docs/mesh.md §9.9).</summary>
public sealed class SharingTests
{
    internal static TrustEntry Peer(string name, char fp, string relation = Perms.Own, bool paused = false,
        IReadOnlyDictionary<string, bool>? allow = null) => new()
    {
        Id = "0123456789abcdef", Name = name, Fp = new string(fp, 64), CertPem = "", Source = TrustSource.Paired, Os = "windows",
        Relation = relation, Paused = paused, Allow = allow ?? Perms.Defaults(relation),
    };

    static Destination One(PeerView p) => Assert.Single(Destinations.Build([p], [], null, null, hubRegistered: false, hubConnected: false));

    static RemotePerm Said(bool paused = false, params string[] off) => new(paused, off.ToDictionary(c => c, _ => false));

    [Fact]
    public void A_device_carries_its_relation_pause_switches_and_word()
    {
        var d = One(new PeerView(Peer("Brian's laptop", 'a', Perms.Other), "lan", true, Said(false, "clipboard"),
            new Refusal("clip", "clipboard", "denied", "Brian's laptop doesn't allow the clipboard from you", DateTimeOffset.UtcNow)));
        Assert.True(d.Other);
        Assert.False(d.Paused);
        Assert.False(d.PausedByPeer);
        Assert.False(d.Allow!["clipboard"]);
        Assert.Equal("Brian's laptop doesn't allow the clipboard from you", d.Refused);
        Assert.Equal("on Wi-Fi · someone else's", DevicesWindow.RowRoute(d));
        Assert.Equal("Brian's laptop  (on Wi-Fi)", d.Label);
        var paused = One(new PeerView(Peer("t15", 'b', paused: true), "lan", true));
        Assert.Equal("paused", DevicesWindow.RowRoute(paused));
        Assert.Equal("t15  (paused)", paused.Label);
        var theirs = One(new PeerView(Peer("t15", 'c'), "tailnet", false, Said(paused: true)));
        Assert.Equal("via Tailscale · paused by it", DevicesWindow.RowRoute(theirs));
        // an entry from before permissions: your own, everything on
        var old = One(new PeerView(Peer("old", 'd') with { Allow = null }, null, false));
        Assert.All(Perms.Capabilities, c => Assert.Null(Destinations.Blocked(old, c, pausedAll: false)));
    }

    [Fact]
    public void Actions_are_greyed_with_the_reason_or_wait_for_a_resume()
    {
        var other = One(new PeerView(Peer("Brian's laptop", 'a', Perms.Other), "lan", true));
        Assert.Null(Destinations.Blocked(other, Perms.Files, false));
        Assert.Null(Destinations.Blocked(other, Perms.Ring, false));
        Assert.Equal(new Block("The clipboard with Brian's laptop is switched off here", false), Destinations.Blocked(other, Perms.Clipboard, false));
        // what it may do here doesn't stop this PC using it: it decides that
        Assert.Null(Destinations.Blocked(other, Perms.Control, false));

        var paused = One(new PeerView(Peer("t15", 'b', paused: true), "lan", true));
        Assert.Equal(new Block("t15 is paused: resume it to send", true), Destinations.Blocked(paused, Perms.Files, false));
        Assert.Equal(new Block("t15 is paused: resume it to send", false), Destinations.Blocked(paused, Perms.Ring, false));

        var mine = One(new PeerView(Peer("t15", 'c'), "lan", true));
        Assert.Equal(new Block("Everything is paused on this PC: resume it to send", true), Destinations.Blocked(mine, Perms.Chat, true));
        Assert.Equal(new Block("Everything is paused on this PC: resume it to send", false), Destinations.Blocked(mine, Perms.Clipboard, true));

        var refusing = One(new PeerView(Peer("t15", 'd'), "lan", true, Said(false, "clipboard", "ring")));
        Assert.Equal(new Block("t15 doesn't allow the clipboard from you", false), Destinations.Blocked(refusing, Perms.Clipboard, false));
        Assert.Equal(new Block("t15 doesn't allow ringing from you", false), Destinations.Blocked(refusing, Perms.Ring, false));
        Assert.Null(Destinations.Blocked(refusing, Perms.Files, false));
        var pausedUs = One(new PeerView(Peer("t15", 'e'), "lan", true, Said(paused: true)));
        Assert.Equal(new Block("t15 paused sharing with you", true), Destinations.Blocked(pausedUs, Perms.Files, false));

        // the hub and the devices only it knows are your own: nothing is blocked there
        var hub = Destinations.Build([], [], "h", "t15", hubRegistered: true, hubConnected: true)[0];
        Assert.Null(Destinations.Blocked(hub, Perms.Clipboard, true));
    }

    [Fact]
    public void The_card_says_whose_it_is_whos_paused_and_what_it_said()
    {
        var d = One(new PeerView(Peer("t15", 'a'), "lan", true, Said(false, "clipboard", "control")));
        Assert.Null(SharingText.State(d, pausedAll: false));
        Assert.Equal("Everything is paused", SharingText.State(d, pausedAll: true));
        Assert.Equal("t15 doesn't allow the clipboard or remote control from this PC.", DeviceCard.Note(d));
        Assert.Equal("Paused", SharingText.State(One(new PeerView(Peer("t15", 'b', paused: true), "lan", true)), false));
        var pausedUs = One(new PeerView(Peer("t15", 'c'), "lan", true, Said(paused: true)));
        Assert.Equal("Paused by t15", SharingText.State(pausedUs, false));
        Assert.Equal("t15 paused sharing with this PC.", DeviceCard.Note(pausedUs));
        Assert.Equal("t15 allows everything from this PC.", SharingText.TheirWord("t15", Said()));
        Assert.Null(SharingText.TheirWord("t15", null));
        Assert.Equal("a, b or c", SharingText.Join(["a", "b", "c"]));
        Assert.Equal("Is Brian's laptop your device, or someone else's?", SharingText.Question("Brian's laptop"));
    }

    [Fact]
    public void The_tray_shows_pause_everything()
    {
        var connected = new TrayState { Configured = true, Connected = true, Polled = true, HubName = "t15", Route = "on Wi-Fi" };
        var paused = connected with { PausedAll = true };
        Assert.Equal("droplet — t15 on Wi-Fi · everything paused", paused.Tooltip());
        Assert.Equal("tray-paused", paused.Icon());
        Assert.Equal("tray-paused", (paused with { InputNow = true }).Icon());
        Assert.Equal("tray", connected.Icon());
        using var s = typeof(App).Assembly.GetManifestResourceStream("tray-paused.ico");
        Assert.NotNull(s);
        var bytes = new byte[s.Length];
        s.ReadExactly(bytes);
        Assert.Equal(16, IconFile.Pick(bytes, 16).Size);
        Assert.Equal(32, IconFile.Pick(bytes, 32).Size);
    }
}

/// <summary>
/// The new windows, built and laid out for real (so a XAML mistake fails here, not on someone's
/// PC), and saved as pictures for the pull request: $DROPLET_SHOTS, or a folder in temp.
/// </summary>
public sealed class SharingScreenshotTests
{
    sealed class FakeEditor(PermsView view) : IPermsEditor
    {
        public PermsView Now { get; private set; } = view;

        public PermsView? Current() => Now;

        public void Set(string? relation = null, IReadOnlyDictionary<string, bool>? allow = null, bool? paused = null)
        {
            var rel = relation ?? Now.Relation;
            var a = relation is null ? new Dictionary<string, bool>(Now.Allow) : Perms.Defaults(relation);
            foreach (var (c, on) in allow ?? new Dictionary<string, bool>())
            {
                a[c] = on;
            }
            Now = Now with { Relation = rel, Allow = a, Paused = paused ?? Now.Paused };
        }
    }

    static string Folder()
    {
        var dir = Environment.GetEnvironmentVariable("DROPLET_SHOTS") is { Length: > 0 } d ? d : Path.Combine(Path.GetTempPath(), "droplet-shots");
        Directory.CreateDirectory(dir);
        return dir;
    }

    static void Pump() => Dispatcher.CurrentDispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);

    static SolidColorBrush Background(Application app)
    {
        foreach (var key in new List<string> { "ApplicationBackgroundBrush", "SolidBackgroundFillColorBaseBrush" })
        {
            if (app.TryFindResource(key) is SolidColorBrush { Color.A: 255 } b)
            {
                return b;
            }
        }
        return Brushes.White;
    }

    static string Shoot(Application app, Window w, string name)
    {
        w.WindowStartupLocation = WindowStartupLocation.Manual;
        (w.Left, w.Top, w.ShowInTaskbar, w.ShowActivated) = (-20000.0, -20000.0, false, false);
        w.Show();
        w.UpdateLayout();
        Pump();
        var root = (FrameworkElement)w.Content;
        var size = new Size(root.ActualWidth, root.ActualHeight);
        Assert.True(size.Width > 100 && size.Height > 100, $"{name} laid out at {size}");
        const double Scale = 1.5;
        var rtb = new RenderTargetBitmap((int)Math.Ceiling(size.Width * Scale), (int)Math.Ceiling(size.Height * Scale), 96 * Scale, 96 * Scale, PixelFormats.Pbgra32);
        var dv = new DrawingVisual();
        using (var dc = dv.RenderOpen())
        {
            dc.DrawRectangle(Background(app), null, new Rect(size));
            dc.DrawRectangle(new VisualBrush(root), null, new Rect(size));
        }
        rtb.Render(dv);
        var path = Path.Combine(Folder(), name + ".png");
        var png = new PngBitmapEncoder();
        png.Frames.Add(BitmapFrame.Create(rtb));
        using (var f = File.Create(path))
        {
            png.Save(f);
        }
        w.Close();
        return path;
    }

    static Destination Card(PeerView p, string? hubDevice = null) =>
        Destinations.Build([p], [], null, null, hubRegistered: false, hubConnected: false)[0] with { HubDeviceId = hubDevice };

    [Fact]
    public async Task The_pairing_question_the_permissions_and_the_device_cards_render()
    {
        var shots = await Sta.Run(() =>
        {
            var app = Application.Current ?? new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
            try
            {
                // "/Assets/droplet.ico" in the windows means droplet's own assembly, not this test's
                Application.ResourceAssembly = typeof(App).Assembly;
            }
            catch (InvalidOperationException)
            {
            }
#pragma warning disable WPF0001 // the Fluent theme, as the app sets it
            app.ThemeMode = ThemeMode.Light;
#pragma warning restore WPF0001
            app.Resources.MergedDictionaries.Add(new ResourceDictionary
            {
                Source = new Uri("pack://application:,,,/droplet;component/Themes/Droplet.xaml", UriKind.Absolute),
            });
            Palette.Follow(app);
            var made = new List<string>
            {
                Shoot(app, new RelationWindow("Brian's laptop"), "1-pairing-asks-whose-device"),
            };

            var editor = new FakeEditor(new PermsView("Brian's laptop", Perms.Other, Perms.Defaults(Perms.Other), false,
                new RemotePerm(false, new Dictionary<string, bool> { ["clipboard"] = false, ["control"] = false }), false));
            var perms = new PermissionsWindow(editor) { Height = 1180 };
            made.Add(Shoot(app, perms, "2-permissions-someone-elses"));
            // the switches change what's kept, and choosing a relation again starts from its defaults
            editor.Set(allow: new Dictionary<string, bool> { ["chat"] = false });
            Assert.False(editor.Now.Allow["chat"]);
            editor.Set(relation: Perms.Own);
            Assert.True(editor.Now.Allow["clipboard"]);

            var cards = new StackPanel { Margin = new Thickness(24), Width = 640 };
            var title = new TextBlock { Text = "Devices", Style = (Style)app.FindResource("PageTitle") };
            cards.Children.Add(title);
            var banner = new Border
            {
                Style = (Style)app.FindResource("Banner"),
                Background = (Brush)app.FindResource("DropletWarnSoftBrush"),
                BorderBrush = (Brush)app.FindResource("DropletWarnBrush"),
                Child = new TextBlock { Text = "Everything is paused: nothing is shared with any device, either way. Messages and files you send wait.", TextWrapping = TextWrapping.Wrap },
            };
            cards.Children.Add(banner);
            var refused = new Refusal("clip", "clipboard", "denied", "Brian's laptop doesn't allow the clipboard from you", DateTimeOffset.UtcNow);
            foreach (var (view, pausedAll) in new List<(PeerView, bool)>
            {
                (new PeerView(SharingTests.Peer("Brian's laptop", 'a', Perms.Other), "lan", true, null, refused), false),
                (new PeerView(SharingTests.Peer("t15", 'b', paused: true), "lan", true), false),
                (new PeerView(SharingTests.Peer("pixel", 'c') with { Os = "android" }, "lan", true, new RemotePerm(true, new Dictionary<string, bool>())), false),
                (new PeerView(SharingTests.Peer("slim", 'd'), "tailnet", false), true),
            })
            {
                var card = new DeviceCard();
                card.Show(Card(view), pausedAll);
                cards.Children.Add(new Border { Style = (Style)app.FindResource("Card"), Child = card });
            }
            made.Add(Shoot(app, new Window { Content = cards, SizeToContent = SizeToContent.WidthAndHeight }, "3-device-cards"));
            return made;
        });
        Assert.All(shots, p => Assert.True(new FileInfo(p).Length > 1000, p));
    }
}
