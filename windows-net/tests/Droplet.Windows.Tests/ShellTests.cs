using System.IO;
using System.Buffers.Binary;
using System.Xml.Linq;
using Droplet.Core.Config;
using Droplet.Core.Hub;
using Droplet.Core.Mesh;
using Droplet.Core.Platform;
using Droplet.Windows.Platform;
using Droplet.Windows.Services;
using Droplet.Windows.Shell;
using Droplet.Windows.Tray;
using Droplet.Windows.ViewModels;

namespace Droplet.Windows.Tests;

public sealed class ActionLinkTests
{
    const string Key = "s3cret key";

    [Fact]
    public void Links_round_trip_with_the_key_and_web_links_stay_as_they_are()
    {
        var url = ActionLinks.ToUrl(new NotificationAction("Open", NotificationActionKind.OpenFile, @"C:\Users\me\Downloads\droplet\a&b.txt"), Key);
        Assert.StartsWith("droplet:open?k=", url, StringComparison.Ordinal);
        var back = ActionLinks.Parse(url, Key);
        Assert.Equal((NotificationActionKind.OpenFile, @"C:\Users\me\Downloads\droplet\a&b.txt"), (back?.Kind, back?.Arg));
        Assert.Equal(NotificationActionKind.AcceptPairing, ActionLinks.Parse(ActionLinks.ToUrl(new NotificationAction("Accept", NotificationActionKind.AcceptPairing, "r1"), Key), Key)?.Kind);
        Assert.Equal(NotificationActionKind.StopRing, ActionLinks.Parse("droplet://stop-ring/?k=s3cret%20key", Key)?.Kind);
        Assert.Equal("https://t15.example/#inbox", ActionLinks.ToUrl(new NotificationAction("Open", NotificationActionKind.OpenUrl, "https://t15.example/#inbox"), Key));
    }

    [Theory]
    [InlineData("droplet:open?k=wrong&p=C%3A%5Cx")]
    [InlineData("droplet:open?p=C%3A%5Cx")]
    [InlineData("droplet:format-disk?k=s3cret%20key")]
    [InlineData("https://example.com/?k=s3cret%20key")]
    [InlineData(null)]
    public void Links_without_the_key_or_of_unknown_kinds_are_refused(string? url) => Assert.Null(ActionLinks.Parse(url, Key));

    [Fact]
    public void No_key_configured_refuses_everything() =>
        Assert.Null(ActionLinks.Parse("droplet:stop-ring?k=", ""));

    [Theory]
    [InlineData(@"C:\dl\a.txt", @"C:\dl", true)]
    [InlineData(@"C:\dl", @"C:\dl", true)]
    [InlineData(@"C:\dl\sub\a.txt", @"C:\dl\", true)]
    [InlineData(@"C:\dl\..\secret.txt", @"C:\dl", false)]
    [InlineData(@"C:\dl-other\a.txt", @"C:\dl", false)]
    [InlineData(@"D:\a.txt", @"C:\dl", false)]
    [InlineData("", @"C:\dl", false)]
    [InlineData(@"C:\dl\a.txt", "", false)]
    public void Only_files_inside_the_download_folder_open(string path, string dir, bool inside) =>
        Assert.Equal(inside, ActionLinks.Inside(path, dir));
}

public sealed class ToastXmlTests
{
    [Fact]
    public void A_toast_is_valid_xml_with_protocol_buttons_and_escaped_text()
    {
        var xml = ToastXml.Build(new Notification
        {
            Title = "Mum <phone> & \"co\"", Body = "hello\u0001 there", Tag = "t",
            Click = new NotificationAction("Open", NotificationActionKind.OpenFile, @"C:\dl\a.txt"),
            Buttons = [new NotificationAction("Show in folder", NotificationActionKind.ShowInFolder, @"C:\dl\a.txt")],
        }, "k");
        var doc = XDocument.Parse(xml);
        var toast = doc.Root!;
        Assert.Equal("protocol", toast.Attribute("activationType")?.Value);
        Assert.StartsWith("droplet:open?k=k&p=", toast.Attribute("launch")?.Value, StringComparison.Ordinal);
        var texts = toast.Descendants("text").Select(t => t.Value).ToList();
        Assert.Equal(["Mum <phone> & \"co\"", "hello there"], texts);
        var action = Assert.Single(toast.Descendants("action"));
        Assert.Equal("protocol", action.Attribute("activationType")?.Value);
        Assert.StartsWith("droplet:show?", action.Attribute("arguments")?.Value, StringComparison.Ordinal);
        Assert.Null(toast.Attribute("scenario"));
    }

    [Fact]
    public void An_urgent_toast_stays_up_silently_and_always_has_a_button()
    {
        var doc = XDocument.Parse(ToastXml.Build(new Notification { Title = "Ring", Urgent = true }, "k"));
        Assert.Equal("incomingCall", doc.Root!.Attribute("scenario")?.Value);
        Assert.Equal("true", doc.Root.Element("audio")?.Attribute("silent")?.Value);
        Assert.Equal("system", Assert.Single(doc.Descendants("action")).Attribute("activationType")?.Value);
    }

    [Fact]
    public void Tags_are_cut_to_sixty_units_between_characters()
    {
        Assert.Equal(60, ToastXml.Tag(new string('a', 100)).Length);
        var emoji = string.Concat(Enumerable.Repeat("\U0001F600", 40));
        Assert.Equal(60, ToastXml.Tag(emoji).Length);
        Assert.False(char.IsHighSurrogate(ToastXml.Tag("a" + emoji)[^1]));
        Assert.Equal("", ToastXml.Tag(null));
    }
}

public sealed class DestinationTests
{
    static TrustEntry Peer(string id, string name, string fp, string hub = "", string source = TrustSource.Paired) => new()
    {
        Id = id, Name = name, Fp = fp, CertPem = "", Source = source, Hub = hub, Os = "android",
    };

    [Fact]
    public void A_device_known_both_ways_is_listed_once_and_the_hub_comes_first()
    {
        var fpA = new string('a', 64);
        var peers = new[]
        {
            new PeerView(Peer("dev1", "phone", fpA, "hub1", TrustSource.Roster), "lan", true),
            new PeerView(Peer("0123456789abcdef", "laptop", new string('b', 64)), null, false),
        };
        var devices = new[]
        {
            new Device { Id = "dev1", Name = "phone", Online = true },
            new Device { Id = "dev2", Name = "tv", Online = true },
            new Device { Id = "me", Name = "maryanne", Self = true },
            new Device { Id = "dev3", Name = "new", Pending = true },
        };
        var list = Destinations.Build(peers, devices, "hub1", "t15", hubRegistered: true, hubConnected: true);
        Assert.Equal(["hub", "peer:" + fpA, "dev:dev2", "peer:" + new string('b', 64)], list.Select(d => d.Key));
        Assert.Equal("The hub (t15)", list[0].Name);
        Assert.Equal(("on Wi-Fi", "dev1", false), (list[1].Route, list[1].HubDeviceId, list[1].Paired));
        Assert.Equal("via the hub", list[2].Route);
        Assert.Equal(("offline", true), (list[3].Route, list[3].Paired));
    }

    [Fact]
    public void Without_a_hub_only_peers_are_listed()
    {
        var list = Destinations.Build([new PeerView(Peer("0123456789abcdef", "laptop", new string('c', 64)), "tailnet", false)],
            [new Device { Id = "x", Name = "x" }], null, null, hubRegistered: false, hubConnected: false);
        var d = Assert.Single(list);
        Assert.Equal(("via Tailscale", true), (d.Route, d.Online));
    }

    [Fact]
    public void Destinations_resolve_by_key_id_fingerprint_or_unique_name()
    {
        var list = Destinations.Build(
            [new PeerView(Peer("0123456789abcdef", "Laptop", new string('d', 64)), null, false)],
            [new Device { Id = "dev2", Name = "tv", Online = true }, new Device { Id = "dev3", Name = "twin" }, new Device { Id = "dev4", Name = "twin" }],
            "h", "t15", true, true);
        Assert.True(Destinations.Resolve("hub", list).IsHub);
        Assert.Equal("dev:dev2", Destinations.Resolve("dev:dev2", list).Key);
        Assert.Equal("dev:dev2", Destinations.Resolve("dev2", list).Key);
        Assert.Equal("Laptop", Destinations.Resolve("dddddddd", list).Name);
        Assert.Equal("Laptop", Destinations.Resolve("laptop", list).Name);
        Assert.Throws<ArgumentException>(() => Destinations.Resolve("twin", list));
        Assert.Throws<ArgumentException>(() => Destinations.Resolve("nobody", list));
        Assert.Throws<ArgumentException>(() => Destinations.Resolve(" ", list));
    }
}

public sealed class TrayTests
{
    [Fact]
    public void The_tooltip_says_how_the_hub_is_reached_and_who_is_in_control()
    {
        var connected = new TrayState { Configured = true, Connected = true, Polled = true, HubName = "t15", Route = "on Wi-Fi" };
        Assert.Equal("droplet — t15 on Wi-Fi", connected.Tooltip());
        Assert.Equal("tray", connected.Icon());
        Assert.Equal("droplet — t15 via Tailscale (notifications paused)", (connected with { Route = "via Tailscale", NotificationsPaused = true }).Tooltip());
        var controlled = connected with { ControlledBy = "Home", InputNow = true };
        Assert.Equal("droplet — being controlled by Home", controlled.Tooltip());
        Assert.Equal("tray-live", controlled.Icon());
        Assert.Equal("droplet — t15 on Wi-Fi · remote control paused", (controlled with { RemotePaused = true }).Tooltip());
        Assert.Equal("tray", (controlled with { RemotePaused = true }).Icon());
        Assert.Equal("droplet — phone is ringing this PC", (controlled with { RingingFrom = "phone" }).Tooltip());
    }

    [Fact]
    public void Without_a_hub_the_tooltip_counts_linked_devices_and_dims_when_none()
    {
        Assert.Equal("droplet — not set up yet (open Settings)", new TrayState().Tooltip());
        Assert.Equal("tray-dim", new TrayState().Icon());
        var mesh = new TrayState { Peers = 3, PeersLinked = 2 };
        Assert.Equal("droplet — 2 of 3 devices linked", mesh.Tooltip());
        Assert.Equal("tray", mesh.Icon());
        Assert.Equal("droplet — 1 device, none reachable now", new TrayState { Peers = 1 }.Tooltip());
        Assert.Equal("droplet — waiting to be let in to t15 (code 1234)", new TrayState { HubName = "t15", PendingCode = "1234" }.Tooltip());
        Assert.Equal("droplet — can't reach t15", new TrayState { Configured = true, Polled = true, HubName = "t15" }.Tooltip());
        Assert.Equal("droplet — connecting to t15…", new TrayState { Configured = true, HubName = "t15" }.Tooltip());
    }
}

public sealed class SettingsLogicTests
{
    static string? Env(string name) => name switch
    {
        "USERPROFILE" => @"C:\Users\me",
        _ => null,
    };

    [Fact]
    public void Folders_expand_variables_and_home_and_must_be_full_paths()
    {
        Assert.Equal(@"C:\Users\me\Downloads", SettingsLogic.ExpandPath("\"%USERPROFILE%\\Downloads\"", Env, @"C:\Users\me"));
        Assert.Equal(@"C:\Users\me\x", SettingsLogic.ExpandPath(@"~\x", Env, @"C:\Users\me"));
        Assert.Equal("%NOPE%\\x", SettingsLogic.ExpandPath("%NOPE%\\x", Env, @"C:\Users\me"));
        Assert.Equal((@"C:\fallback", null), SettingsLogic.DownloadDir("  ", @"C:\fallback", Env));
        Assert.Null(SettingsLogic.DownloadDir(@"relative\dir", @"C:\fallback", Env).Folder);
        Assert.Equal(@"D:\files", SettingsLogic.DownloadDir(@"D:\files\.\", @"C:\fallback", Env).Folder?.TrimEnd('\\'));
    }

    [Theory]
    [InlineData("", null, false)]
    [InlineData("auto", null, false)]
    [InlineData(" 1739 ", 1739, false)]
    [InlineData("65535", 65535, false)]
    [InlineData("80", null, true)]
    [InlineData("70000", null, true)]
    [InlineData("-5", null, true)]
    [InlineData("12ab", null, true)]
    public void Ports_are_empty_or_a_number_from_1024(string typed, int? port, bool error)
    {
        var (p, e) = SettingsLogic.Port(typed);
        Assert.Equal(port, p);
        Assert.Equal(error, e is not null);
    }

    [Fact]
    public void Fingerprints_read_in_groups_of_four() =>
        Assert.Equal("abcd ef01 23", SettingsLogic.Grouped("abcdef0123"));

    [Fact]
    public void Only_mesh_and_folder_changes_restart_the_engine()
    {
        var a = ConfigStore.Defaults();
        var b = ConfigStore.Defaults();
        b.RemoteInput = !a.RemoteInput;
        Assert.False(SettingsLogic.NeedsRestart(a, b));
        b.Mesh.Port = 1745;
        Assert.True(SettingsLogic.NeedsRestart(a, b));
        var c = ConfigStore.Defaults();
        c.DownloadDir = @"D:\elsewhere";
        Assert.True(SettingsLogic.NeedsRestart(a, c));
        c.Mesh.Downloads = @"D:\mesh";
        a.Mesh.Downloads = @"D:\mesh";
        Assert.False(SettingsLogic.NeedsRestart(a, c));
    }
}

public sealed class MiscTests
{
    [Fact]
    public void The_command_line_is_read_as_the_go_app_did()
    {
        Assert.Equal(CommandKind.Open, Command.Parse([]).Kind);
        Assert.Equal(CommandKind.Background, Command.Parse(["--background"]).Kind);
        Assert.Equal(CommandKind.Action, Command.Parse(["droplet:stop-ring?k=x"]).Kind);
        var send = Command.Parse(["send", "--to", "dev:abc", @"C:\a.txt", "b.txt"]);
        Assert.Equal((CommandKind.Send, "dev:abc"), (send.Kind, send.Target));
        Assert.Equal([@"C:\a.txt", "b.txt"], send.Files);
        Assert.Equal(CommandKind.Invalid, Command.Parse(["send", "a.txt"]).Kind);
        Assert.Equal(CommandKind.Invalid, Command.Parse(["frobnicate"]).Kind);
        Assert.Equal(CommandKind.Settings, Command.Parse(["Settings"]).Kind);
    }

    [Fact]
    public void Media_players_get_readable_names_and_a_moving_position()
    {
        Assert.Equal("Spotify", MediaNames.PlayerName("Spotify.exe"));
        Assert.Equal("Media Player", MediaNames.PlayerName("Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic"));
        Assert.Equal("Firefox", MediaNames.PlayerName("308046B0AF4A39CB"));
        Assert.Equal("MyApp", MediaNames.PlayerName(@"C:\Program Files\MyApp.exe"));
        Assert.Equal("Media", MediaNames.PlayerName(""));
        Assert.Equal(("Playing", "Paused", "Stopped"), (MediaNames.Status(4), MediaNames.Status(5), MediaNames.Status(1)));
        var now = DateTimeOffset.UtcNow;
        var (len, pos) = MediaNames.Timeline(TimeSpan.Zero, TimeSpan.FromMinutes(3), TimeSpan.FromSeconds(10), now.AddSeconds(-5), true, now);
        Assert.Equal(180, len);
        Assert.Equal(15, pos, 3);
        Assert.Equal(10, MediaNames.Timeline(TimeSpan.Zero, TimeSpan.FromMinutes(3), TimeSpan.FromSeconds(10), now.AddSeconds(-5), false, now).Position, 3);
        Assert.Equal((50 + 25) * 2, MediaNames.VolumeKeys(0.5).Count);
    }

    [Fact]
    public void The_ring_tone_is_a_well_formed_wav()
    {
        var wav = RingTone.Wav();
        Assert.Equal("RIFF", System.Text.Encoding.ASCII.GetString(wav, 0, 4));
        Assert.Equal((uint)wav.Length - 8, BinaryPrimitives.ReadUInt32LittleEndian(wav.AsSpan(4)));
        Assert.Equal(22050u, BinaryPrimitives.ReadUInt32LittleEndian(wav.AsSpan(24)));
        Assert.Equal((uint)(22050 * 1.6) * 2, BinaryPrimitives.ReadUInt32LittleEndian(wav.AsSpan(40)));
    }

    [Fact]
    public void The_tray_picks_the_smallest_icon_image_at_least_as_big_as_asked()
    {
        using var s = typeof(App).Assembly.GetManifestResourceStream("tray.ico")!;
        using var ms = new MemoryStream();
        s.CopyTo(ms);
        var ico = ms.ToArray();
        Assert.Equal(16, IconFile.Pick(ico, 16).Size);
        Assert.Equal(24, IconFile.Pick(ico, 21).Size);
        Assert.Equal(64, IconFile.Pick(ico, 200).Size);
        var png = IconFile.Pick(ico, 32).Image.Span;
        Assert.Equal(0x89, png[0]); // PNG images inside
        Assert.Throws<FormatException>(() => IconFile.Pick(new byte[] { 0, 0, 2, 0, 0, 0 }, 16));
    }
}
