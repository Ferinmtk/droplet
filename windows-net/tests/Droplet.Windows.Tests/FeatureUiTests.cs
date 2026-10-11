using Droplet.Core.Mesh;
using Droplet.Windows.Services;
using Droplet.Windows.Views;

namespace Droplet.Windows.Tests;

/// <summary>What the windows and the tray show of progress and Cancel, links, nicknames and several devices (docs/mesh.md §9.11).</summary>
public sealed class FeatureUiTests
{
    internal static List<TransferRow> SomeTransfers() =>
    [
        TransferRow.Of(new TransferView
        {
            Id = "1111111111111111", Dir = "out", Fp = new string('e', 64), Peer = "DESKTOP-7Q2", Name = "holiday.mp4", Size = 100_000_000,
            Done = 45_000_000, Percent = 45, Rate = 3_355_443, Eta = 16,
        }, "Brian's laptop"),
        TransferRow.Queued(new OutboxJob
        {
            Id = "2222222222222222", Kind = "file", Fp = new string('e', 64), Name = "notes.pdf", Error = "waiting: Brian's laptop is paused: resume it to send",
        }, "Brian's laptop"),
        TransferRow.Of(new TransferView
        {
            Id = "3333333333333333", Dir = "in", Fp = new string('e', 64), Peer = "DESKTOP-7Q2", Name = "scan.png", Size = 2_000_000, Done = 500_000,
            Percent = 25, State = TransferState.Cancelled, Error = "Brian's laptop cancelled it",
        }, "Brian's laptop"),
    ];

    [Fact]
    public void A_transfer_row_says_how_far_and_can_be_cancelled_until_it_ends()
    {
        var rows = SomeTransfers();
        Assert.Equal(("to Brian's laptop", "45% · 16 s left · 3.2 MB/s", true, true), (rows[0].Way, rows[0].Status, rows[0].CanCancel, rows[0].Active));
        Assert.Equal("holiday.mp4 to Brian's laptop · 45% · 16 s left · 3.2 MB/s", rows[0].MenuLine);
        Assert.Equal(("Waiting: Brian's laptop is paused: resume it to send", true, false), (rows[1].Status, rows[1].CanCancel, rows[1].Active));
        Assert.Equal(("from Brian's laptop", "Cancelled: Brian's laptop cancelled it", false), (rows[2].Way, rows[2].Status, rows[2].CanCancel));
        var hub = TransferRow.Of(new TransferView { Id = "4", Dir = "out", Fp = "f", Name = "x", Size = 10, Route = "hub" }, "tv");
        Assert.True(hub.Indeterminate);
        Assert.Equal("Sending through the hub…", hub.Status);
    }

    [Fact]
    public void A_nickname_shows_everywhere_with_its_own_name_small()
    {
        var e = SharingTests.Peer("DESKTOP-7Q2", 'a') with { Nickname = "Brian's laptop" };
        var d = Assert.Single(Destinations.Build([new PeerView(e, "lan", true, Features: ["link"])], [], null, null, false, false));
        Assert.Equal(("Brian's laptop", "DESKTOP-7Q2", "Brian's laptop", true), (d.Name, d.RealName, d.Nickname, d.TakesLinks));
        Assert.Equal("Brian's laptop  (on Wi-Fi)", d.Label);
        Assert.Equal("DESKTOP-7Q2 · on Wi-Fi · windows", new DeviceRow(d.Name, "on Wi-Fi", true, "windows", d, d.RealName).Detail);
        // found by either name
        Assert.Equal(d.Key, Destinations.Resolve("brian's laptop", [d]).Key);
        Assert.Equal(d.Key, Destinations.Resolve("desktop-7q2", [d]).Key);
        var plain = Assert.Single(Destinations.Build([new PeerView(SharingTests.Peer("pixel", 'b'), null, false)], [], null, null, false, false));
        Assert.Equal(("pixel", ""), (plain.Name, plain.RealName));
        Assert.False(plain.TakesLinks);
    }

    [Fact]
    public void All_my_devices_are_your_own_that_take_it()
    {
        var list = Destinations.Build(
        [
            new PeerView(SharingTests.Peer("mine", 'a'), "lan", true),
            new PeerView(SharingTests.Peer("brian", 'b', Perms.Other), "lan", true),
            new PeerView(SharingTests.Peer("nofiles", 'c', allow: new Dictionary<string, bool>(Perms.OwnDefaults) { [Perms.Files] = false }), "lan", true),
            new PeerView(SharingTests.Peer("paused", 'd', paused: true), null, false),
        ], [new Core.Hub.Device { Id = "tv1", Name = "tv", Online = true }], "h", "t15", hubRegistered: true, hubConnected: true);
        Assert.Equal(["mine", "paused", "tv"], Destinations.Mine(list, Perms.Files).Select(d => d.Name).Order());
        Assert.Equal(["mine", "nofiles", "paused", "tv"], Destinations.Mine(list, Perms.Clipboard).Select(d => d.Name).Order());
    }

    [Fact]
    public void A_link_in_messages_has_an_open_button()
    {
        var at = DateTimeOffset.UtcNow;
        var link = ChatWindow.Row(new ChatLine(false, "https://example.com/x", at, "lan", Url: "https://example.com/x", IsLink: true, Opened: true));
        Assert.Equal(("https://example.com/x", System.Windows.Visibility.Visible), (link.Url, link.OpenVisibility));
        Assert.EndsWith("· link, opened here", link.Meta, StringComparison.Ordinal);
        var plain = ChatWindow.Row(new ChatLine(true, "hello", at, "lan"));
        Assert.Equal(System.Windows.Visibility.Collapsed, plain.OpenVisibility);
        var waiting = ChatWindow.Row(new ChatLine(true, "https://x.org", at, null, true, Links.OnlyUrl("https://x.org")));
        Assert.EndsWith("· waiting to be sent", waiting.Meta, StringComparison.Ordinal);
        Assert.Equal("https://x.org", waiting.Url);
    }

    [Fact]
    public void Problems_read_as_sentences()
    {
        Assert.Equal("That link has no address in it.", AskWindow.Sentence("that link has no address in it"));
        Assert.Equal("Done.", AskWindow.Sentence("Done."));
        Assert.Equal("", AskWindow.Sentence(""));
    }
}
