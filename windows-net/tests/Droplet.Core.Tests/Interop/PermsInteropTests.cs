using System.Text.Json.Nodes;
using Droplet.Core.Common;
using Droplet.Core.Mesh;
using Droplet.Core.Tests.Support;

namespace Droplet.Core.Tests.Interop;

/// <summary>
/// Per-device permissions and Pause (docs/mesh.md §9.9) between the .NET peer and the
/// reference, the Linux agent, as real processes: each side asks its own owner at pairing,
/// each tells the other how it treats it, each enforces its own switches, and a pause holds
/// messages until the resume, both ways.
/// </summary>
[Collection(nameof(InteropCollection))]
public sealed class PermsInteropTests : IAsyncLifetime
{
    string root = "";

    public ValueTask InitializeAsync()
    {
        Assert.SkipWhen(Reference.Unavailable is not null, Reference.Unavailable ?? "");
        root = TestDirs.Make("perms-interop");
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync()
    {
        TestDirs.Remove(root);
        return ValueTask.CompletedTask;
    }

    static async Task<JsonObject?> PeerAsync(LinuxAgent a, string fp) =>
        (await a.StatusAsync())["peers"]?.AsArray().OfType<JsonObject>().FirstOrDefault(p => p.Str("fp") == fp);

    [Fact]
    public async Task Someone_elses_device_shares_files_not_the_clipboard_and_a_pause_holds_messages_both_ways()
    {
        await using var a = new LinuxAgent("alpha");
        await a.StartAsync();
        var fakes = new FakePlatform();
        await using var n = await DotNetPeer.StartAsync(Path.Combine(root, "n"), "dotnet", fakes);
        var nFp = n.Node.Identity.Fingerprint;
        var nId = n.Node.PeerId;

        // --- pairing: each owner answers for their own side, and neither answer is sent
        var aId = (await a.StatusAsync()).Str("id")!;
        await Wait.For(() => n.Node.Nearby.Any(s => s.Id == aId), 30, "the agent to be seen over mDNS");
        var start = await n.Node.PairStartAsync(aId);
        JsonObject? request = null;
        await Wait.For(async () => (request = (await a.StatusAsync())["incoming"]?.AsArray().OfType<JsonObject>().FirstOrDefault()) is not null,
            20, "the agent to show the request");
        Assert.Equal(start.Code, request!.Str("code"));
        var answer = await a.CallAsync(new JsonObject { ["cmd"] = "pair-answer", ["request"] = request.Str("request"), ["accept"] = true, ["relation"] = "other" });
        Assert.Equal(("accepted", "other"), (answer.Str("state") ?? "", answer.Str("relation") ?? ""));
        await n.Node.PairConfirmAsync(start.Request, true, Perms.Own);
        await Wait.For(() => n.Node.PairStatus(start.Request) == PairState.Accepted, 20, "the .NET side to see it accepted");
        var aFp = a.Fingerprint;
        Assert.Equal(Perms.Own, n.Node.Trust.Get(aFp)!.Relation);
        var atAgent = (JsonObject)a.Trust()[nFp]!;
        Assert.Equal("other", atAgent.Str("relation"));
        Assert.False(atAgent["allow"]!.AsObject().Bool("clipboard"));
        Assert.True(atAgent["allow"]!.AsObject().Bool("files"));

        // --- the agent says how it treats .NET (its welcome's perm), and .NET doesn't send what it would refuse
        Assert.NotNull(await n.Node.DirectAsync(aFp));
        await Wait.For(() => n.Node.RemotePermOf(aFp)?.Allow.GetValueOrDefault("clipboard", true) == false, 10, "the agent's perm");
        Assert.True(n.Node.RemotePermOf(aFp)!.Takes(Perms.Files));
        var no = await Assert.ThrowsAsync<RefusedException>(() => n.Node.ClipAsync(aFp, "my password"));
        Assert.Equal("alpha doesn't allow the clipboard from you", no.Message);
        Assert.False(no.Local);
        // sent anyway (an older or misbehaving peer): the agent refuses it, and says why
        n.Node.SendRegardless = true;
        Assert.Equal("lan", await n.Node.ClipAsync(aFp, "my password"));
        await Wait.For(() => n.Node.LastRefusal(aFp) is not null, 10, "the agent's refusal");
        var refusal = n.Node.LastRefusal(aFp)!;
        Assert.Equal(("denied", "clipboard", "clip"), (refusal.Why, refusal.Cap ?? "", refusal.Re));
        Assert.Equal("alpha doesn't allow the clipboard from you", refusal.Text);
        Assert.DoesNotContain("would set", a.Log, StringComparison.Ordinal);
        n.Node.SendRegardless = false;

        // --- files and messages are fine
        var photo = Files.Random(root, "holiday.bin", 1);
        var job = n.Node.SendFile(aFp, photo);
        Assert.Equal(JobState.Done, (await n.Node.WaitJobAsync(job.Id, TimeSpan.FromSeconds(60)))?.State);
        Assert.Equal(Files.Sha256(photo), Files.Sha256(Path.Combine(a.Downloads, "holiday.bin")));
        job = n.Node.SendText(aFp, "thanks for lunch");
        Assert.Equal(JobState.Done, (await n.Node.WaitJobAsync(job.Id, TimeSpan.FromSeconds(30)))?.State);

        // --- the agent pauses .NET: .NET hears it, its message waits, and goes on the resume
        Assert.True((await a.CallAsync(new JsonObject { ["cmd"] = "pause", ["peer"] = nId })).Bool("paused"));
        await Wait.For(() => n.Node.RemotePermOf(aFp)?.Paused == true, 10, "the agent's perm saying it paused");
        job = n.Node.SendText(aFp, "are you there");
        var waiting = await n.Node.Outbox.WaitAsync(job.Id, j => j.Attempts > 0 && j.State == JobState.Queued, TimeSpan.FromSeconds(20));
        Assert.Equal("waiting: alpha paused sharing with you", waiting?.Error);
        var ring = await Assert.ThrowsAsync<RefusedException>(() => n.Node.RingAsync(aFp));
        Assert.Equal("alpha paused sharing with you", ring.Message);
        Assert.False((await a.CallAsync(new JsonObject { ["cmd"] = "resume", ["peer"] = nId })).Bool("paused"));
        Assert.Equal(JobState.Done, (await n.Node.Outbox.WaitAsync(job.Id, j => j.State == JobState.Done, TimeSpan.FromSeconds(20)))?.State);
        await Wait.For(() => a.Chat().Any(m => m.Str("body") == "are you there" && m.Str("dir") == "in"), 10, "the agent to get the message");

        // --- the agent's owner changes their mind (your own device after all), and .NET's treats the
        // agent as someone else's: the agent hears it, and won't send its clipboard
        var mine = await a.CallAsync(new JsonObject { ["cmd"] = "perm-set", ["peer"] = nId, ["relation"] = "own" });
        Assert.True(mine["allow"]!.AsObject().Bool("clipboard"));
        n.Node.SetPerms(aFp, relation: Perms.Other);
        await Wait.For(async () => (await PeerAsync(a, nFp))?["remote"]?["allow"]?.AsObject().Bool("clipboard") == false, 10,
            "the agent to hear how .NET treats it");
        var clip = await a.CallAsync(new JsonObject { ["cmd"] = "clip", ["peer"] = nId, ["text"] = "from Linux" });
        Assert.Equal("dotnet doesn't allow the clipboard from you", clip.Str("error"));
        // ring is still allowed for someone else's device
        Assert.Equal("lan", (await a.CallAsync(new JsonObject { ["cmd"] = "ring", ["peer"] = nId })).Str("route"));
        await Wait.For(() => fakes.Sound.Rings == 1, 5, "the fake sound to ring");
        // nor remote control
        var forced = await a.CallAsync(new JsonObject
        {
            ["cmd"] = "send", ["peer"] = nId, ["msg"] = new JsonObject { ["t"] = "input", ["ev"] = new JsonArray(new JsonObject { ["k"] = "key", ["key"] = "F5" }) },
        });
        Assert.Contains("remote control", forced.Str("error") ?? forced.Str("route") ?? "", StringComparison.Ordinal);
        Assert.Empty(fakes.Input.Applied);
        Assert.Empty(fakes.Clipboard.Written);

        // --- .NET pauses the agent: the agent's message waits for .NET's resume
        n.Node.SetPerms(aFp, paused: true);
        await Wait.For(async () => (await PeerAsync(a, nFp))?["remote"]?.AsObject().Bool("paused") == true, 10, "the agent to hear .NET paused it");
        var queued = await a.CallAsync(new JsonObject { ["cmd"] = "text", ["peer"] = nId, ["body"] = "after the meeting", ["wait"] = 5 });
        Assert.Equal("queued", queued.Str("state"));
        Assert.Equal("waiting: dotnet paused sharing with you", queued.Str("error"));
        Assert.DoesNotContain(n.Node.Chat.Recent(), m => m.Body == "after the meeting");
        n.Node.SetPerms(aFp, paused: false);
        await Wait.For(() => n.Node.Chat.Recent().Any(m => m.Dir == "in" && m.Body == "after the meeting"), 20, ".NET to get the message after the resume");
        var done = await a.CallAsync(new JsonObject { ["cmd"] = "job", ["id"] = queued.Str("id"), ["wait"] = 10 });
        Assert.Equal("done", done.Str("state"));
    }

    [Fact]
    public async Task Pause_everything_on_dotnet_holds_the_agents_messages_and_its_clipboard()
    {
        await using var a = new LinuxAgent("alpha");
        await a.StartAsync();
        var fakes = new FakePlatform();
        await using var n = await DotNetPeer.StartAsync(Path.Combine(root, "n"), "dotnet", fakes);
        await MeshInteropTests.PairFromDotNetAsync(n, a);
        var aFp = a.Fingerprint;
        var nFp = n.Node.Identity.Fingerprint;
        Assert.NotNull(await n.Node.DirectAsync(aFp));

        n.Node.PauseEverything(true);
        await Wait.For(async () => (await PeerAsync(a, nFp))?["remote"]?.AsObject().Bool("paused") == true, 10, "the agent to hear it");
        var clip = await a.CallAsync(new JsonObject { ["cmd"] = "clip", ["peer"] = n.Node.PeerId, ["text"] = "from Linux" });
        Assert.Equal("dotnet paused sharing with you", clip.Str("error"));
        // .NET's own broadcasts stop too
        Assert.False(await n.Node.BroadcastAsync(new JsonObject { ["t"] = "clip", ["text"] = "from .NET" }));
        var queued = await a.CallAsync(new JsonObject { ["cmd"] = "text", ["peer"] = n.Node.PeerId, ["body"] = "later", ["wait"] = 5 });
        Assert.Equal("waiting: dotnet paused sharing with you", queued.Str("error"));

        n.Node.PauseEverything(false);
        await Wait.For(() => n.Node.Chat.Recent().Any(m => m.Dir == "in" && m.Body == "later"), 20, ".NET to get the message after the resume");
        Assert.Equal("lan", (await a.CallAsync(new JsonObject { ["cmd"] = "clip", ["peer"] = n.Node.PeerId, ["text"] = "from Linux" })).Str("route"));
        await Wait.For(() => fakes.Clipboard.Written.Contains("from Linux"), 5, "the clipboard after the resume");
    }
}
