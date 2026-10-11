using System.Text.Json.Nodes;
using Droplet.Core.Common;
using Droplet.Core.Mesh;
using Droplet.Core.Tests.Support;

namespace Droplet.Core.Tests.Interop;

/// <summary>
/// Progress and Cancel, links and rename (docs/mesh.md §9.11) between the .NET peer and the
/// reference, the Linux agent, as real processes: each says what it understands in its hello,
/// a link from your own device opens on the other, a rename is heard at once, and a file
/// cancelled from either end stops on both, with the partial file gone.
/// </summary>
[Collection(nameof(InteropCollection))]
public sealed class FeaturesInteropTests : IAsyncLifetime
{
    string root = "";

    public ValueTask InitializeAsync()
    {
        Assert.SkipWhen(Reference.Unavailable is not null, Reference.Unavailable ?? "");
        root = TestDirs.Make("features-interop");
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync()
    {
        TestDirs.Remove(root);
        return ValueTask.CompletedTask;
    }

    static async Task<JsonObject?> TransferAsync(LinuxAgent a, string id) =>
        (await a.CallAsync(new JsonObject { ["cmd"] = "transfers" }))["transfers"]?.AsArray().OfType<JsonObject>().FirstOrDefault(t => t.Str("id") == id);

    static bool NoPartial(string dir) => !Directory.Exists(dir) || Directory.GetFiles(dir, ".droplet-*").Length == 0;

    [Fact]
    public async Task Features_links_and_rename_both_ways()
    {
        await using var a = new LinuxAgent("alpha");
        await a.StartAsync();
        var fakes = new FakePlatform();
        await using var n = await DotNetPeer.StartAsync(Path.Combine(root, "n"), "dotnet", fakes);
        await MeshInteropTests.PairFromDotNetAsync(n, a);
        var aFp = a.Fingerprint;
        var nFp = n.Node.Identity.Fingerprint;
        Assert.NotNull(await n.Node.DirectAsync(aFp));

        // each says what it understands
        Assert.Equal(["cancel", "link", "rename"], n.Node.FeaturesOf(aFp));
        await Wait.For(async () => (await a.StatusAsync())["peers"]?.AsArray().OfType<JsonObject>()
            .FirstOrDefault(p => p.Str("fp") == nFp)?.Strings("features")?.Contains("link") == true, 10, "the agent to see .NET's features");

        // a link from .NET opens on the agent (your own device), and is in its chat as a link
        var sent = await n.Node.SendLinkAsync(aFp, "https://example.com/from-dotnet");
        Assert.Equal(("link", "lan"), (sent.How, sent.Route));
        await Wait.For(() => a.Chat().Any(m => m.Str("kind") == "link" && m.Str("body") == "https://example.com/from-dotnet" && m.Bool("opened") == true),
            10, "the agent to open the link");
        Assert.Contains("would open (dry run): https://example.com/from-dotnet", a.Log, StringComparison.Ordinal);
        // a bad one never leaves .NET
        await Assert.ThrowsAsync<ArgumentException>(() => n.Node.SendLinkAsync(aFp, "javascript:alert(1)"));

        // and one from the agent opens here
        var back = await a.CallAsync(new JsonObject { ["cmd"] = "link", ["peer"] = n.Node.PeerId, ["url"] = "https://example.com/from-linux" });
        Assert.Equal("link", back.Str("how"));
        await Wait.For(() => fakes.Browser.Opened.Contains("https://example.com/from-linux"), 10, ".NET to open it");
        Assert.Contains(n.Node.Chat.Recent(), m => m.Kind == "link" && m.Opened && m.Dir == "in");

        // .NET treats the agent as someone else's: its link waits here with an Open button
        n.Node.SetPerms(aFp, relation: Perms.Other);
        await a.CallAsync(new JsonObject { ["cmd"] = "link", ["peer"] = n.Node.PeerId, ["url"] = "https://example.com/not-opened" });
        await Wait.For(() => n.Node.Chat.Recent().Any(m => m.Body == "https://example.com/not-opened"), 10, "the stored link");
        Assert.DoesNotContain("https://example.com/not-opened", fakes.Browser.Opened);
        Assert.Equal("alpha sent a link", fakes.Notifications.Shown.Last(s => s.Title.Contains("link", StringComparison.Ordinal)).Title);

        // rename, both ways, heard at once
        Assert.Equal(1, await n.Node.RenameAsync("Ferrin's PC"));
        await Wait.For(() => (a.Trust()[nFp] as JsonObject)?.Str("name") == "Ferrin's PC", 10, "the agent to hear the new name");
        var renamed = await a.CallAsync(new JsonObject { ["cmd"] = "rename", ["name"] = "alpha two" });
        Assert.Equal("alpha two", renamed.Str("name"));
        await Wait.For(() => n.Node.Trust.Get(aFp)?.Name == "alpha two", 10, ".NET to hear the agent's new name");

        // a nickname here is never sent: the agent still knows .NET by its own name
        n.Node.SetNickname(aFp, "the desktop");
        Assert.Equal(1, await n.Node.RenameAsync("Ferrin's laptop"));
        await Wait.For(() => (a.Trust()[nFp] as JsonObject)?.Str("name") == "Ferrin's laptop", 10, "the agent to hear it");
        Assert.DoesNotContain("the desktop", a.Log, StringComparison.Ordinal);
        Assert.DoesNotContain("the desktop", a.Trust().ToJsonString(), StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_file_cancelled_from_either_end_stops_on_both()
    {
        // both send slowly, so there's time to cancel part-way
        await using var a = new LinuxAgent("alpha", new JsonObject { ["max_rate"] = 1024 * 1024 });
        await a.StartAsync();
        var fakes = new FakePlatform();
        await using var n = await DotNetPeer.StartAsync(Path.Combine(root, "n"), "dotnet", fakes, maxRate: 1024 * 1024);
        await MeshInteropTests.PairFromDotNetAsync(n, a);
        var aFp = a.Fingerprint;
        var nId = n.Node.PeerId;
        Assert.NotNull(await n.Node.DirectAsync(aFp));
        Directory.CreateDirectory(Path.Combine(root, "src"));

        // .NET sends, .NET cancels: the agent drops what came
        var one = Files.Random(Path.Combine(root, "src"), "one.bin", 4);
        var job = n.Node.SendFile(aFp, one);
        await Wait.For(async () => (await TransferAsync(a, job.Id))?.Int("done") > 300_000, 20, "the agent to get some of it");
        Assert.True(n.Node.Transfers.Get(job.Id)!.Done > 0);
        Assert.Equal("out", (await n.Node.CancelAsync(job.Id)).Dir);
        Assert.Equal("cancelled here", (await n.Node.Outbox.WaitAsync(job.Id, j => j.State == JobState.Cancelled, TimeSpan.FromSeconds(10)))?.Error);
        await Wait.For(async () => (await TransferAsync(a, job.Id))?.Str("state") == "cancelled", 10, "the agent to hear");
        Assert.Equal("dotnet cancelled it", (await TransferAsync(a, job.Id))!.Str("error"));
        await Wait.For(() => NoPartial(a.Downloads), 5, "the agent's partial file to go");
        Assert.False(File.Exists(Path.Combine(a.Downloads, "one.bin")));

        // .NET sends, the agent cancels: .NET hears it, and doesn't offer it again
        var two = Files.Random(Path.Combine(root, "src"), "two.bin", 4);
        job = n.Node.SendFile(aFp, two);
        await Wait.For(async () => (await TransferAsync(a, job.Id))?.Int("done") > 300_000, 20, "the agent to get some of it");
        Assert.Equal("in", (await a.CallAsync(new JsonObject { ["cmd"] = "cancel", ["id"] = job.Id })).Str("dir"));
        Assert.Equal("alpha cancelled it", (await n.Node.Outbox.WaitAsync(job.Id, j => j.State == JobState.Cancelled, TimeSpan.FromSeconds(10)))?.Error);
        await Wait.For(() => NoPartial(a.Downloads), 5, "the agent's partial file to go");

        // the agent sends, .NET cancels: the agent's job is cancelled, and .NET's partial file goes
        var three = Files.Random(Path.Combine(root, "src"), "three.bin", 4);
        var theirs = await a.CallAsync(new JsonObject { ["cmd"] = "send-file", ["peer"] = nId, ["path"] = three, ["wait"] = 0 });
        var tid = theirs.Str("id")!;
        await Wait.For(() => n.Node.Transfers.Get(tid)?.Done > 300_000, 20, ".NET to get some of it");
        Assert.Equal("in", (await n.Node.CancelAsync(tid)).Dir);
        await Wait.For(() => NoPartial(n.Downloads), 5, ".NET's partial file to go");
        var answer = await a.CallAsync(new JsonObject { ["cmd"] = "job", ["id"] = tid, ["wait"] = 10 });
        Assert.Equal(("cancelled", "dotnet cancelled it"), (answer.Str("state"), answer.Str("why")));

        // the agent sends, the agent cancels: .NET stops and says who did
        var four = Files.Random(Path.Combine(root, "src"), "four.bin", 4);
        theirs = await a.CallAsync(new JsonObject { ["cmd"] = "send-file", ["peer"] = nId, ["path"] = four, ["wait"] = 0 });
        tid = theirs.Str("id")!;
        await Wait.For(() => n.Node.Transfers.Get(tid)?.Done > 300_000, 20, ".NET to get some of it");
        Assert.Equal("out", (await a.CallAsync(new JsonObject { ["cmd"] = "cancel", ["id"] = tid })).Str("dir"));
        await Wait.For(() => n.Node.Transfers.Get(tid)?.State == TransferState.Cancelled, 10, ".NET to hear");
        Assert.Equal("alpha cancelled it", n.Node.Transfers.Get(tid)!.Error);
        await Wait.For(() => NoPartial(n.Downloads), 5, ".NET's partial file to go");
        Assert.False(File.Exists(Path.Combine(n.Downloads, "four.bin")));
    }
}
