using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text.Json.Nodes;
using Droplet.Core.Common;
using Droplet.Core.Mesh;
using Droplet.Core.Platform;
using Droplet.Core.Tests.Support;

namespace Droplet.Core.Tests;

// Progress and Cancel, links, rename and nicknames, and sending to several devices
// (docs/mesh.md §9.11), ported from the reference's agent/tests/test_transfers.py and
// test_sending.py, between real .NET peers on loopback.

/// <summary>The transfer list's arithmetic and words.</summary>
public sealed class TransferListTests
{
    sealed class Clock
    {
        public double T = 100;
    }

    [Fact]
    public void The_list_works_out_speed_and_time_left_from_byte_counts()
    {
        var c = new Clock();
        var t = new Transfers(() => c.T);
        var id = new string('a', 32);
        t.Start(id, "out", new string('f', 64), "pixel", "video.mp4", 10_000_000);
        Assert.Equal(0, t.Get(id)!.Percent);
        Assert.Null(t.Get(id)!.Rate);
        for (var i = 1; i < 5; i++)
        {
            c.T += 1;
            t.Progress(id, i * 1_000_000L);
        }
        var v = t.Get(id)!;
        Assert.Equal((40, 1_000_000L, 6L), (v.Percent, v.Rate, v.Eta));
        Assert.Equal("40% · 6 s left · 976.6 KB/s", Transfers.ProgressText(v));
        c.T += 10; // nothing for a while: stalled, not "6 s left" forever
        Assert.Null(t.Get(id)!.Rate);
        Assert.Null(t.Get(id)!.Eta);
        t.Finish(id, TransferState.Cancelled, "pixel cancelled it");
        Assert.Equal("Cancelled: pixel cancelled it", Transfers.ProgressText(t.Get(id)!));
        Assert.Equal("Cancelled", Transfers.ProgressText(t.Get(id)! with { Error = "cancelled here" }));
        c.T += Transfers.Keep.TotalSeconds + 1; // finished ones go after a while
        Assert.Empty(t.List());
        Assert.Equal(("5 s left", "2 min left", "1 h 2 min left"), (Transfers.EtaText(5), Transfers.EtaText(125), Transfers.EtaText(3725)));
        Assert.Equal(("512 bytes", "3 MB", "1.5 KB"), (Transfers.SizeText(512), Transfers.SizeText(3 * 1024 * 1024), Transfers.SizeText(1536)));
    }

    [Fact]
    public void How_each_ending_reads()
    {
        var v = new TransferView { Id = "x", Dir = "out", Fp = "f", Size = 100 };
        Assert.Equal("0% · starting…", Transfers.ProgressText(v));
        Assert.Equal("Sent", Transfers.ProgressText(v with { State = TransferState.Done }));
        Assert.Equal("Received", Transfers.ProgressText(v with { Dir = "in", State = TransferState.Done }));
        Assert.Equal("Failed: no space", Transfers.ProgressText(v with { State = TransferState.Failed, Error = "no space" }));
        Assert.Equal("Stopped at 30%: it carries on when it can", Transfers.ProgressText(v with { State = TransferState.Waiting, Percent = 30 }));
        Assert.Equal("Sending through the hub…", Transfers.ProgressText(v with { Route = "hub" }));
    }
}

/// <summary>Links and names: what's allowed.</summary>
public sealed class LinkAndNameRulesTests
{
    [Theory]
    [InlineData("https://example.com/a?b=c#d", true)]
    [InlineData("http://192.168.1.20:8000/", true)]
    [InlineData("  https://x.org  ", true)]
    [InlineData("HTTPS://X.ORG/", true)]
    [InlineData("javascript:alert(1)", false)]
    [InlineData("file:///etc/passwd", false)]
    [InlineData("data:text/html,hi", false)]
    [InlineData("ftp://x.org", false)]
    [InlineData("intent://x#Intent;end", false)]
    [InlineData("https://", false)]
    [InlineData("https:x.org", false)]
    [InlineData("https://a b.com", false)]
    [InlineData("https://x.org/‮", false)]
    [InlineData("https://x.org/​", false)]
    [InlineData("https://x.org/­", false)]
    [InlineData("https://x.org/﻿", false)]
    [InlineData("https://x.org/\u0007", false)]
    [InlineData("", false)]
    [InlineData(null, false)]
    public void Only_web_links_travel_and_open(string? url, bool ok) => Assert.Equal(ok, Links.IsUrl(url));

    [Fact]
    public void A_link_too_long_is_refused_and_says_why()
    {
        Assert.False(Links.IsUrl("https://x.org/" + new string('a', 3000)));
        Assert.Contains("only web links", Assert.Throws<ArgumentException>(() => Links.CheckUrl("file:///etc/passwd")).Message, StringComparison.Ordinal);
        Assert.Contains("too long", Assert.Throws<ArgumentException>(() => Links.CheckUrl("https://x.org/" + new string('a', 3000))).Message,
            StringComparison.Ordinal);
    }

    [Fact]
    public void A_message_thats_only_a_link_gets_an_open_button()
    {
        Assert.Equal("https://x.org/a", Links.OnlyUrl(" https://x.org/a "));
        Assert.Null(Links.OnlyUrl("look: https://x.org"));
        Assert.Null(Links.OnlyUrl("javascript:alert(1)"));
        Assert.Null(Links.OnlyUrl(null));
    }

    [Theory]
    [InlineData("", "empty")]
    [InlineData("   ", "empty")]
    [InlineData("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "40 characters")]
    [InlineData("bad\u0007name", "control")]
    [InlineData("evil‮gnp.exe", "control")]
    [InlineData("zero​width", "control")]
    [InlineData(null, "text")]
    public void Names_are_checked(string? bad, string why) =>
        Assert.Contains(why, Assert.Throws<ArgumentException>(() => Names.Check(bad)).Message, StringComparison.Ordinal);

    [Fact]
    public void A_name_is_tidied()
    {
        Assert.Equal("Ferrin's laptop", Names.Check("  Ferrin's   laptop "));
        Assert.Equal("", Names.Check(" ", emptyOk: true));
        Assert.Equal("", Names.CleanNickname("bad\u0007"));
        Assert.Equal(new string('a', 40), Names.Check(new string('a', 40)));
    }

    [Fact]
    public void Cancel_and_rename_are_taken_even_while_paused_and_a_link_needs_messages()
    {
        var paused = new TrustEntry { Id = "0123456789abcdef", Name = "x", Fp = new string('a', 64), CertPem = "", Source = TrustSource.Paired, Paused = true };
        Assert.Null(Perms.Check(paused, Perms.Message("cancel")));
        Assert.Null(Perms.Check(paused, Perms.Message("rename")));
        Assert.Equal(Perms.Chat, Perms.Capability(Perms.Message("link")));
        Assert.Equal((Perms.WhyPaused, Perms.Chat), Perms.Check(paused, Perms.Message("link"))!.Value);
    }

    [Fact]
    public void All_my_devices_means_your_own_that_allow_it()
    {
        static TrustEntry E(char c, string relation = Perms.Own, bool paused = false, string? off = null)
        {
            var allow = Perms.Defaults(relation);
            if (off is not null)
            {
                allow[off] = false;
            }
            return new TrustEntry
            {
                Id = "0123456789abcdef", Name = c.ToString(), Fp = new string(c, 64), CertPem = "", Source = TrustSource.Paired, Relation = relation,
                Allow = allow, Paused = paused,
            };
        }
        var peers = new List<TrustEntry> { E('1'), E('2', Perms.Other), E('3', off: Perms.Files), E('4', paused: true) };
        Assert.Equal(["1", "4"], Perms.OwnTargets(peers, Perms.Files).Select(p => p.Name));
        Assert.Equal(["1", "3", "4"], Perms.OwnTargets(peers, Perms.Clipboard).Select(p => p.Name));
    }

    [Fact]
    public void Several_results_read_as_one_line()
    {
        var results = new List<DeviceResult>
        {
            new("1", "pixel", SendResult.Sent, "lan"),
            new("2", "t15", SendResult.Waiting, "t15 is paused: resume it to send"),
            new("3", "slim", SendResult.Refused, "slim doesn't allow files from you"),
        };
        Assert.Equal("Sent to 1 of 3 devices, 1 waiting, 1 refused", Several.Summary(results));
        Assert.Equal("pixel: sent", Several.Line(results[0]));
        Assert.Equal("t15: waiting (t15 is paused: resume it to send)", Several.Line(results[1]));
    }
}

/// <summary>Peers on loopback, for the features.</summary>
public abstract class FeaturePeers : IAsyncLifetime
{
    protected string Root { get; } = TestDirs.Make("features");

    readonly List<DotNetPeer> peers = [];

    public ValueTask InitializeAsync() => ValueTask.CompletedTask;

    public async ValueTask DisposeAsync()
    {
        foreach (var p in peers)
        {
            await p.DisposeAsync();
        }
        TestDirs.Remove(Root);
        GC.SuppressFinalize(this);
    }

    protected async Task<DotNetPeer> StartAsync(string name, long maxRate = 0)
    {
        var p = await DotNetPeer.StartAsync(Path.Combine(Root, name), name, port: 0, maxRate: maxRate, announce: false);
        peers.Add(p);
        return p;
    }

    protected static void TrustEachOther(DotNetPeer a, DotNetPeer b, string source = TrustSource.Paired, string? hub = null)
    {
        foreach (var (x, y) in new List<(DotNetPeer, DotNetPeer)> { (a, b), (b, a) })
        {
            var e = TrustEntry.Make(y.Node.PeerId, y.Node.Name, y.Node.Identity.CertificatePem, source, ["127.0.0.1"], y.Node.Port, hub: hub);
            if (source == TrustSource.Paired)
            {
                x.Node.Trust.AddPaired(e);
            }
            else
            {
                x.Node.Trust.SyncRoster([e], hub!);
            }
        }
    }

    protected async Task<(DotNetPeer A, DotNetPeer B)> PairAsync(long maxRate = 0)
    {
        var a = await StartAsync("a", maxRate);
        var b = await StartAsync("b");
        TrustEachOther(a, b);
        return (a, b);
    }

    protected static string Fp(DotNetPeer p) => p.Node.Identity.Fingerprint;

    protected string Big(int megabytes = 4) => Files.Random(Directory.CreateDirectory(Path.Combine(Root, "src")).FullName, "big.bin", megabytes);

    protected static bool NoPartial(DotNetPeer p) =>
        !Directory.Exists(p.Downloads) || Directory.GetFiles(p.Downloads, ".droplet-*").Length == 0;

    /// <summary>Every message <paramref name="p"/> receives from now on.</summary>
    protected static ConcurrentQueue<(string Fp, JsonObject Msg)> Record(DotNetPeer p)
    {
        var got = new ConcurrentQueue<(string, JsonObject)>();
        p.Node.Received += (fp, msg) => got.Enqueue((fp, (JsonObject)msg.DeepClone()));
        return got;
    }

    protected static async Task CloseLinksAsync(DotNetPeer a, DotNetPeer b)
    {
        while (a.Node.OpenLink(Fp(b)) is { } l)
        {
            await l.CloseAsync(WebSocketCloseStatus.NormalClosure, "test");
        }
        while (b.Node.OpenLink(Fp(a)) is { } l)
        {
            await l.CloseAsync(WebSocketCloseStatus.NormalClosure, "test");
        }
        await Wait.For(() => a.Node.OpenLink(Fp(b)) is null && b.Node.OpenLink(Fp(a)) is null, 10, "the links to close");
    }
}

/// <summary>Progress and Cancel, both ways.</summary>
public sealed class TransferTests : FeaturePeers
{
    [Fact]
    public async Task Progress_comes_from_real_byte_counts_on_both_sides()
    {
        var (a, b) = await PairAsync(maxRate: 2 * 1024 * 1024);
        var job = a.Node.SendFile(Fp(b), Big());
        var seenA = new List<TransferView>();
        var seenB = new List<TransferView>();
        await Wait.For(() =>
        {
            if (a.Node.Transfers.Get(job.Id) is { } ta)
            {
                seenA.Add(ta);
            }
            if (b.Node.Transfers.Get(job.Id) is { } tb)
            {
                seenB.Add(tb);
            }
            return seenB.Count > 0 && seenB[^1].State == TransferState.Done;
        }, 30, "the file to arrive");
        Assert.Equal(JobState.Done, (await a.Node.Outbox.WaitAsync(job.Id, j => j.State == JobState.Done, TimeSpan.FromSeconds(10)))?.State);
        var midA = seenA.Where(t => t.Done > 0 && t.Done < t.Size).ToList();
        var midB = seenB.Where(t => t.Done > 0 && t.Done < t.Size).ToList();
        Assert.NotEmpty(midA);
        Assert.NotEmpty(midB);
        Assert.All(seenA, t => Assert.Equal(("out", "b", "big.bin"), (t.Dir, t.Peer, t.Name)));
        Assert.All(seenB, t => Assert.Equal(("in", "a"), (t.Dir, t.Peer)));
        Assert.Contains(midA.Concat(midB), t => t.Rate > 0);
        Assert.Equal(seenB.Select(t => t.Done).Order(), seenB.Select(t => t.Done));
        Assert.Equal(TransferState.Done, a.Node.Transfers.Get(job.Id)!.State);
        Assert.Contains(a.Node.Status()["transfers"]!.AsArray(), t => t!["id"]!.GetValue<string>() == job.Id);
    }

    [Fact]
    public async Task The_sender_cancels_and_the_receiver_drops_the_partial_file()
    {
        var (a, b) = await PairAsync(maxRate: 1024 * 1024);
        var job = a.Node.SendFile(Fp(b), Big());
        await Wait.For(() => b.Node.Transfers.Get(job.Id)?.Done > 300_000, 15, "some of it to arrive");
        var got = await a.Node.CancelAsync(job.Id[..8]);
        Assert.Equal(("out", "big.bin"), (got.Dir, got.Name));
        var done = await a.Node.Outbox.WaitAsync(job.Id, j => j.State == JobState.Cancelled, TimeSpan.FromSeconds(10));
        Assert.Equal((JobState.Cancelled, "cancelled here"), (done?.State, done?.Error));
        await Wait.For(() => b.Node.Transfers.Get(job.Id)?.State == TransferState.Cancelled, 10, "the receiver to hear");
        Assert.Equal("a cancelled it", b.Node.Transfers.Get(job.Id)!.Error);
        await Wait.For(() => NoPartial(b), 5, "the partial file to go");
        Assert.False(File.Exists(Path.Combine(b.Downloads, "big.bin")));
        Assert.Equal(TransferState.Cancelled, a.Node.Transfers.Get(job.Id)!.State);
        // gone for good: nothing is offered again
        await Task.Delay(1000);
        Assert.Empty(a.Node.Outbox.Queued());
        Assert.False(File.Exists(Path.Combine(b.Downloads, "big.bin")));
    }

    [Fact]
    public async Task The_receiver_cancels_and_the_sender_is_told()
    {
        var (a, b) = await PairAsync(maxRate: 1024 * 1024);
        var job = a.Node.SendFile(Fp(b), Big());
        await Wait.For(() => b.Node.Transfers.Get(job.Id)?.Done > 300_000, 15, "some of it to arrive");
        Assert.Equal("in", (await b.Node.CancelAsync(job.Id)).Dir);
        await Wait.For(() => b.Node.Transfers.Get(job.Id)?.State == TransferState.Cancelled, 10, "the receiver to stop");
        Assert.Equal("Cancelled", Transfers.ProgressText(b.Node.Transfers.Get(job.Id)!));
        await Wait.For(() => NoPartial(b), 5, "the partial file to go");
        var done = await a.Node.Outbox.WaitAsync(job.Id, j => j.State == JobState.Cancelled, TimeSpan.FromSeconds(10));
        Assert.Equal((JobState.Cancelled, "b cancelled it"), (done?.State, done?.Error));
        Assert.Equal(TransferState.Cancelled, a.Node.Transfers.Get(job.Id)!.State);
        Assert.False(File.Exists(Path.Combine(b.Downloads, "big.bin")));
    }

    [Fact]
    public async Task An_older_sender_that_offers_it_again_is_refused()
    {
        var (a, b) = await PairAsync(maxRate: 512 * 1024);
        var src = Big(2);
        var job = a.Node.SendFile(Fp(b), src);
        await Wait.For(() => b.Node.Transfers.Get(job.Id)?.Done > 100_000, 15, "some of it to arrive");
        await b.Node.CancelAsync(job.Id);
        await Wait.For(() => b.Node.Transfers.Get(job.Id)?.State == TransferState.Cancelled, 10, "the receiver to stop");
        var heard = Record(a);
        // a sender that didn't understand cancel offers the same id again
        var link = (await a.Node.DirectAsync(Fp(b)))!;
        await link.SendAsync(new JsonObject { ["t"] = "offer", ["id"] = job.Id, ["name"] = "big.bin", ["size"] = new FileInfo(src).Length, ["mime"] = "application/octet-stream" });
        await Wait.For(() => heard.Any(m => m.Msg.Str("t") == "nack" && m.Msg.Str("id") == job.Id), 10, "the nack");
        var nack = heard.First(m => m.Msg.Str("t") == "nack").Msg;
        Assert.Equal(("cancelled", "b cancelled it"), (nack.Str("why"), nack.Str("error")));
        await Task.Delay(500);
        Assert.False(File.Exists(Path.Combine(b.Downloads, "big.bin")));
        Assert.True(NoPartial(b));
    }

    [Fact]
    public async Task A_job_waiting_in_the_outbox_can_be_cancelled()
    {
        var (a, b) = await PairAsync();
        a.Node.SetPerms(Fp(b), paused: true);
        var src = Path.Combine(Root, "later.txt");
        await File.WriteAllTextAsync(src, "later");
        var job = a.Node.SendFile(Fp(b), src);
        await a.Node.Outbox.WaitAsync(job.Id, j => j.Attempts > 0, TimeSpan.FromSeconds(5));
        Assert.Equal("out", (await a.Node.CancelAsync(job.Id)).Dir);
        Assert.Equal(JobState.Cancelled, a.Node.Outbox.Get(job.Id)!.State);
        Assert.Equal(JobState.Cancelled, (await a.Node.WaitJobAsync(job.Id, TimeSpan.Zero))!.State);
        a.Node.SetPerms(Fp(b), paused: false);
        await Task.Delay(800);
        Assert.False(File.Exists(Path.Combine(b.Downloads, "later.txt")));
        Assert.Contains("no transfer", (await Assert.ThrowsAsync<ArgumentException>(() => a.Node.CancelAsync("ffffffffffff"))).Message, StringComparison.Ordinal);
        Assert.StartsWith("say which", (await Assert.ThrowsAsync<ArgumentException>(() => a.Node.CancelAsync(""))).Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Cancel_is_taken_even_while_paused_and_only_from_the_other_end()
    {
        var (a, b) = await PairAsync();
        var c = await StartAsync("c");
        TrustEachOther(a, c);
        a.Node.SetPerms(Fp(b), paused: true);
        var src = Path.Combine(Root, "x.txt");
        await File.WriteAllTextAsync(src, "x");
        var job = a.Node.SendFile(Fp(b), src);
        await a.Node.Outbox.WaitAsync(job.Id, j => j.Attempts > 0, TimeSpan.FromSeconds(5));
        // c can't cancel what a sends b
        await (await c.Node.DirectAsync(Fp(a)))!.SendAsync(new JsonObject { ["t"] = "cancel", ["id"] = job.Id });
        await Task.Delay(300);
        Assert.NotEqual(JobState.Cancelled, a.Node.Outbox.Get(job.Id)!.State);
        await (await b.Node.DirectAsync(Fp(a)))!.SendAsync(new JsonObject { ["t"] = "cancel", ["id"] = job.Id });
        var done = await a.Node.Outbox.WaitAsync(job.Id, j => j.State == JobState.Cancelled, TimeSpan.FromSeconds(5));
        Assert.Equal("b cancelled it", done?.Error);
    }

    [Fact]
    public void Dropping_a_partial_file_takes_its_metadata_too()
    {
        var dir = Path.Combine(Root, "dl");
        Directory.CreateDirectory(dir);
        var (part, meta) = FileReceiver.PartPaths(dir, new string('f', 64), new string('1', 32));
        File.WriteAllText(part, "xxxxxxxxxx");
        File.WriteAllText(meta, """{"size":100}""");
        FileReceiver.DropPartial(dir, new string('f', 64), new string('1', 32));
        Assert.False(File.Exists(part));
        Assert.False(File.Exists(meta));
    }
}

/// <summary>Links: opened on your own devices, an Open button on someone else's.</summary>
public sealed class LinkTests : FeaturePeers
{
    [Fact]
    public async Task Hello_says_what_this_peer_understands()
    {
        var (a, b) = await PairAsync();
        Assert.Equal(["cancel", "link", "rename"], a.Node.Hello().Strings("features")!);
        Assert.NotNull(await a.Node.DirectAsync(Fp(b)));
        Assert.Equal(["cancel", "link", "rename"], a.Node.FeaturesOf(Fp(b)));
        await Wait.For(() => b.Node.FeaturesOf(Fp(a)).Count == 3, 5, "b to see a's features");
        Assert.Equal(3, a.Node.Status()["peers"]![0]!["features"]!.AsArray().Count);
    }

    [Fact]
    public async Task A_link_from_your_own_device_opens()
    {
        var (a, b) = await PairAsync();
        var sent = await a.Node.SendLinkAsync(Fp(b), "https://example.com/slides");
        Assert.Equal(("link", "lan"), (sent.How, sent.Route));
        await Wait.For(() => b.Fakes.Browser.Opened.Contains("https://example.com/slides"), 5, "b to open it");
        var got = b.Node.Chat.Recent()[^1];
        Assert.Equal(("link", "https://example.com/slides", true, "in"), (got.Kind, got.Body, got.Opened, got.Dir));
        var mine = a.Node.Chat.Recent()[^1];
        Assert.Equal(("link", "out"), (mine.Kind, mine.Dir));
        Assert.Equal("a opened a link", b.Fakes.Notifications.Shown.Last().Title);
    }

    [Fact]
    public async Task A_link_from_someone_elses_device_waits_with_an_open_button()
    {
        var (a, b) = await PairAsync();
        b.Node.SetPerms(Fp(a), relation: Perms.Other);
        Assert.Equal("link", (await a.Node.SendLinkAsync(Fp(b), "https://example.com/x")).How);
        await Wait.For(() => b.Node.Chat.Recent().LastOrDefault()?.Body == "https://example.com/x", 5, "b to store it");
        await Task.Delay(200);
        Assert.Empty(b.Fakes.Browser.Opened);
        Assert.False(b.Node.Chat.Recent()[^1].Opened);
        var shown = b.Fakes.Notifications.Shown.Last();
        Assert.Equal("a sent a link", shown.Title);
        Assert.Equal(new NotificationAction("Open link", NotificationActionKind.OpenUrl, "https://example.com/x"), shown.Buttons[0]);
    }

    [Fact]
    public async Task Bad_links_are_refused_both_ways()
    {
        var (a, b) = await PairAsync();
        Assert.Contains("only web links", (await Assert.ThrowsAsync<ArgumentException>(() => a.Node.SendLinkAsync(Fp(b), "file:///etc/passwd"))).Message,
            StringComparison.Ordinal);
        var heard = Record(a);
        // a peer that sends one anyway: refused, never stored or opened
        await (await a.Node.DirectAsync(Fp(b)))!.SendAsync(new JsonObject { ["t"] = "link", ["id"] = "linkid0001", ["url"] = "javascript:alert(1)", ["ts"] = 1 });
        await Wait.For(() => heard.Any(m => m.Msg.Str("t") == "nack" && m.Msg.Str("id") == "linkid0001"), 5, "the nack");
        Assert.Empty(b.Fakes.Browser.Opened);
        Assert.Empty(b.Node.Chat.Recent());
    }

    [Fact]
    public async Task Links_follow_the_messages_switch()
    {
        var (a, b) = await PairAsync();
        a.Node.SetPerms(Fp(b), allow: new Dictionary<string, bool> { [Perms.Chat] = false });
        Assert.Contains("switched off here", (await Assert.ThrowsAsync<RefusedException>(() => a.Node.SendLinkAsync(Fp(b), "https://example.com"))).Message,
            StringComparison.Ordinal);
        a.Node.SetPerms(Fp(b), allow: new Dictionary<string, bool> { [Perms.Chat] = true });
        Assert.NotNull(await a.Node.DirectAsync(Fp(b)));
        b.Node.SetPerms(Fp(a), allow: new Dictionary<string, bool> { [Perms.Chat] = false });
        // whether a heard b's word yet or not, it's refused, and nothing opens there
        a.Node.SendRegardless = true;
        var no = await Assert.ThrowsAsync<RefusedException>(() => a.Node.SendLinkAsync(Fp(b), "https://example.com"));
        Assert.Contains("doesn't allow messages", no.Message, StringComparison.Ordinal);
        Assert.Empty(b.Fakes.Browser.Opened);
    }

    [Fact]
    public async Task Links_to_a_paused_or_older_peer_go_as_a_message()
    {
        var (a, b) = await PairAsync();
        a.Node.SetPerms(Fp(b), paused: true);
        var sent = await a.Node.SendLinkAsync(Fp(b), "https://example.com/p");
        Assert.Equal("message", sent.How);
        Assert.Equal(JobState.Queued, (await a.Node.Outbox.WaitAsync(sent.Job!.Id, j => j.Attempts > 0, TimeSpan.FromSeconds(5)))?.State);
        a.Node.SetPerms(Fp(b), paused: false);
        await Wait.For(() => b.Node.Chat.Recent().Any(m => m.Body == "https://example.com/p"), 10, "the message after the resume");
        Assert.Empty(b.Fakes.Browser.Opened); // a message is never opened by itself
        // ...but it has an Open button
        Assert.Equal("https://example.com/p", b.Fakes.Notifications.Shown.Last().Buttons.Single().Arg);
        // an older peer, which doesn't say it understands link
        a.Node.OpenLink(Fp(b))!.Hello.Remove("features");
        Assert.Equal("message", (await a.Node.SendLinkAsync(Fp(b), "https://example.com/old")).How);
    }

    [Fact]
    public async Task Opening_is_limited()
    {
        var (a, b) = await PairAsync();
        for (var i = 0; i < 8; i++)
        {
            await a.Node.SendLinkAsync(Fp(b), $"https://example.com/{i}");
        }
        await Wait.For(() => b.Node.Chat.Recent().Count == 8, 10, "all eight");
        Assert.Equal(5, b.Fakes.Browser.Opened.Count);
    }
}

/// <summary>Renaming this device, and nicknames for the others.</summary>
public sealed class NameTests : FeaturePeers
{
    [Fact]
    public async Task Renaming_tells_linked_devices_mdns_and_the_next_hello()
    {
        var (a, b) = await PairAsync();
        Assert.NotNull(await a.Node.DirectAsync(Fp(b)));
        Assert.Equal(1, await a.Node.RenameAsync("Ferrin's  laptop"));
        Assert.Equal("Ferrin's laptop", a.Node.Name);
        Assert.Equal("Ferrin's laptop", a.Node.Hello().Str("name"));
        await Wait.For(() => b.Node.Trust.Get(Fp(a))!.Name == "Ferrin's laptop", 5, "b to hear the new name");
        Assert.Contains("control", (await Assert.ThrowsAsync<ArgumentException>(() => a.Node.RenameAsync("x\0"))).Message, StringComparison.Ordinal);
        // b not linked: it learns from the next hello
        await CloseLinksAsync(a, b);
        Assert.Equal(0, await a.Node.RenameAsync("slim"));
        Assert.Equal("Ferrin's laptop", b.Node.Trust.Get(Fp(a))!.Name);
        Assert.NotNull(await b.Node.DirectAsync(Fp(a)));
        await Wait.For(() => b.Node.Trust.Get(Fp(a))!.Name == "slim", 5, "b to learn it from the welcome");
    }

    [Fact]
    public async Task A_peer_the_hub_names_isnt_renamed_by_itself()
    {
        var a = await StartAsync("a");
        var b = await StartAsync("b");
        TrustEachOther(a, b, TrustSource.Roster, "abababababababab");
        Assert.NotNull(await a.Node.DirectAsync(Fp(b)));
        await b.Node.RenameAsync("not the hub's name");
        await Task.Delay(300);
        Assert.Equal("b", a.Node.Trust.Get(Fp(b))!.Name);
    }

    [Fact]
    public async Task Nicknames_show_here_and_are_never_sent()
    {
        var (a, b) = await PairAsync();
        var heard = Record(b);
        var e = a.Node.SetNickname(Fp(b), "Brian's  laptop");
        Assert.Equal(("b", "Brian's laptop", "Brian's laptop"), (e.Name, e.Nickname, e.Label));
        Assert.Equal(Fp(b), a.Node.Resolve("brian's laptop").Fp);
        var p = a.Node.Status()["peers"]![0]!;
        Assert.Equal(("Brian's laptop", "b"), (p["nickname"]!.GetValue<string>(), p["name"]!.GetValue<string>()));
        await a.Node.WaitJobAsync(a.Node.SendText(Fp(b), "hi").Id, TimeSpan.FromSeconds(10));
        await a.Node.ClipAsync(Fp(b), "clip");
        await a.Node.SendLinkAsync(Fp(b), "https://example.com");
        a.Node.SetPerms(Fp(b), paused: false);
        await a.Node.RenameAsync("a2");
        await (await b.Node.DirectAsync(Fp(a)))!.SendAsync(new JsonObject { ["t"] = "text", ["id"] = "fromb00001", ["body"] = "hello" });
        await Wait.For(() => a.Node.Chat.Recent().Any(m => m.Body == "hello"), 5, "b's message");
        Assert.Equal("Brian's laptop", a.Fakes.Notifications.Shown.Last().Title); // its notifications use the nickname
        // a restart keeps it
        Assert.Equal("Brian's laptop", new TrustList(Path.Combine(a.Root, "mesh", "trust.json"), Fp(a)).Get(Fp(b))!.Nickname);
        await Wait.For(() => heard.Count >= 5, 5, "what a sent");
        var everything = string.Join("\n", heard.Select(m => m.Msg.ToJsonString())) + a.Node.Hello("hello", Fp(b)).ToJsonString() +
                         a.Node.AnnounceBody().ToJsonString();
        Assert.DoesNotContain("Brian", everything, StringComparison.Ordinal);
        Assert.Equal("", b.Node.Trust.Get(Fp(a))!.Nickname);
        Assert.Equal("", a.Node.SetNickname(Fp(b), "").Nickname);
        Assert.Equal("b", a.Node.Trust.Get(Fp(b))!.Label);
        Assert.Contains("40 characters", Assert.Throws<ArgumentException>(() => a.Node.SetNickname(Fp(b), new string('x', 50))).Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_roster_fetch_keeps_the_nickname()
    {
        const string Hub = "abababababababab";
        var a = await StartAsync("a");
        var b = await StartAsync("b");
        TrustEachOther(a, b, TrustSource.Roster, Hub);
        a.Node.SetNickname(Fp(b), "the NAS");
        a.Node.Trust.SyncRoster([TrustEntry.Make(b.Node.PeerId, "b", b.Node.Identity.CertificatePem, TrustSource.Roster, ["127.0.0.1"], b.Node.Port, hub: Hub)], Hub);
        Assert.Equal("the NAS", a.Node.Trust.Get(Fp(b))!.Nickname);
        // and a paired peer keeps it when paired again
        var path = Path.Combine(Root, "t.json");
        var t = new TrustList(path, new string('0', 64));
        var e = TrustEntry.Make(b.Node.PeerId, "b", b.Node.Identity.CertificatePem, TrustSource.Paired);
        t.AddPaired(e);
        t.SetNickname(e.Fp, "box");
        t.AddPaired(e);
        Assert.Equal("box", t.Get(e.Fp)!.Nickname);
        Assert.Equal("box", JsonNode.Parse(File.ReadAllText(path))!["peers"]![e.Fp]!["nickname"]!.GetValue<string>());
        Assert.Equal(e.Fp, Assert.Single(t.Find("BOX")).Fp);
    }
}

/// <summary>Sending to several devices at once: each its own job and result.</summary>
public sealed class SeveralTests : FeaturePeers
{
    [Fact]
    public async Task Each_device_gets_its_own_result()
    {
        var a = await StartAsync("a");
        var b = await StartAsync("b");
        var c = await StartAsync("c");
        var d = await StartAsync("d");
        foreach (var x in new[] { b, c, d })
        {
            TrustEachOther(a, x);
        }
        a.Node.SetPerms(Fp(c), paused: true);
        d.Node.SetPerms(Fp(a), allow: new Dictionary<string, bool> { [Perms.Files] = false });
        Assert.NotNull(await a.Node.DirectAsync(Fp(d)));
        await Wait.For(() => a.Node.RemotePermOf(Fp(d))?.Allow.GetValueOrDefault(Perms.Files, true) == false, 10, "d's word");
        var f = Path.Combine(Root, "notes.txt");
        await File.WriteAllTextAsync(f, "for everyone");

        var results = await Several.SendAsync(a.Node, [Fp(b), Fp(c), Fp(d)], fp => a.Node.SendFile(fp, f), TimeSpan.FromSeconds(30));
        Assert.Equal([SendResult.Sent, SendResult.Waiting, SendResult.Refused], results.Select(r => r.Result));
        Assert.Equal(("b", "lan"), (results[0].Name, results[0].Detail));
        Assert.Equal("c is paused: resume it to send", results[1].Detail);
        Assert.Equal("d doesn't allow files from you", results[2].Detail);
        Assert.Equal("Sent to 1 of 3 devices, 1 waiting, 1 refused", Several.Summary(results));
        Assert.Equal("for everyone", await File.ReadAllTextAsync(Path.Combine(b.Downloads, "notes.txt")));
        Assert.False(File.Exists(Path.Combine(d.Downloads, "notes.txt")));
        // each its own job: c's goes on resume
        a.Node.SetPerms(Fp(c), paused: false);
        await Wait.For(() => File.Exists(Path.Combine(c.Downloads, "notes.txt")), 15, "c's copy after the resume");
    }

    [Fact]
    public async Task Messages_behind_a_waiting_one_say_they_wait_too()
    {
        var (a, b) = await PairAsync();
        a.Node.SetPerms(Fp(b), paused: true);
        var first = a.Node.SendText(Fp(b), "one");
        var second = a.Node.SendText(Fp(b), "two");
        var waited = await a.Node.WaitJobAsync(second.Id, TimeSpan.FromSeconds(10));
        Assert.Equal((JobState.Queued, "waiting: b is paused: resume it to send"), (waited?.State, waited?.Error));
        Assert.Equal(SendResult.Waiting, Several.Of(Fp(b), "b", waited!).Result);
        a.Node.SetPerms(Fp(b), paused: false);
        Assert.Equal(JobState.Done, (await a.Node.Outbox.WaitAsync(first.Id, j => j.State == JobState.Done, TimeSpan.FromSeconds(10)))?.State);
        Assert.Equal(JobState.Done, (await a.Node.Outbox.WaitAsync(second.Id, j => j.State == JobState.Done, TimeSpan.FromSeconds(10)))?.State);
    }
}
