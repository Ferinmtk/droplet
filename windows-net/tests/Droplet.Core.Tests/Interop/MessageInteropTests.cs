using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Droplet.Core.Common;
using Droplet.Core.Mesh;
using Droplet.Core.Platform;
using Droplet.Core.Tests.Support;

namespace Droplet.Core.Tests.Interop;

/// <summary>
/// Everything a link carries, both ways, between the .NET peer and the Linux agent:
/// text, a 20 MB file interrupted and resumed, ring, clip, and remote control reaching
/// the fake platform services.
/// </summary>
[Collection(nameof(InteropCollection))]
public sealed partial class MessageInteropTests : IAsyncLifetime
{
    const int RateLimit = 4 * 1024 * 1024; // bytes a second: a 20 MB file takes about 5 s
    string root = "";

    public ValueTask InitializeAsync()
    {
        Assert.SkipWhen(Reference.Unavailable is not null, Reference.Unavailable ?? "");
        root = TestDirs.Make("messages");
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync()
    {
        TestDirs.Remove(root);
        return ValueTask.CompletedTask;
    }

    static long PartSize(string dir) =>
        Directory.Exists(dir) ? Directory.GetFiles(dir, ".droplet-*.part").Select(f => new FileInfo(f).Length).DefaultIfEmpty(0).Max() : 0;

    [GeneratedRegex(@"resuming \S+ at byte (\d+)")]
    private static partial Regex Resuming();

    [Fact]
    public async Task Text_files_ring_clip_and_remote_control_both_ways()
    {
        await using var a = new LinuxAgent("alpha", new JsonObject { ["max_rate"] = RateLimit });
        await a.StartAsync();
        var fakes = new FakePlatform();
        var nRoot = Path.Combine(root, "n");
        var n = await DotNetPeer.StartAsync(nRoot, "dotnet", fakes, maxRate: RateLimit);
        try
        {
            await MeshInteropTests.PairFromDotNetAsync(n, a);
            var aFp = a.Fingerprint;
            var nId = n.Node.PeerId;

            // --- text, both ways
            var job = n.Node.SendText(aFp, "hello from .NET ✓");
            var done = await n.Node.WaitJobAsync(job.Id, TimeSpan.FromSeconds(30));
            Assert.Equal((JobState.Done, "lan"), (done!.State, done.Route));
            Assert.Contains(a.Chat(), m => m.Str("body") == "hello from .NET ✓" && m.Str("dir") == "in");
            ChatEntry? got = null;
            n.Node.TextReceived += e => got = e;
            var sent = await a.CallAsync(new JsonObject { ["cmd"] = "text", ["peer"] = nId, ["body"] = "and back from Linux", ["wait"] = 20 });
            Assert.Equal(("done", "lan"), (sent.Str("state"), sent.Str("route")));
            await Wait.For(() => got?.Body == "and back from Linux", 5, "the .NET peer to receive the text");
            Assert.Contains(n.Node.Chat.Recent(), m => m.Dir == "in" && m.Body == "and back from Linux");
            // a repeated id is acknowledged, not stored twice (the agent re-sends the same job id on retry)
            Assert.Single(n.Node.Chat.Recent(), m => m.Body == "and back from Linux");

            // --- a 20 MB file from .NET, the receiver killed part-way, resumed
            var big = Files.Random(root, "big-from-dotnet.bin", 20);
            job = n.Node.SendFile(aFp, big);
            await Wait.For(() => PartSize(a.Downloads) > 6 * 1024 * 1024, 30, "the transfer to be under way");
            await a.StopAsync(kill: true);
            var interruptedAt = PartSize(a.Downloads);
            Assert.InRange(interruptedAt, 1, 20 * 1024 * 1024 - 1);
            await a.StartAsync();
            done = await n.Node.Outbox.WaitAsync(job.Id, j => j.State is JobState.Done or JobState.Failed, TimeSpan.FromSeconds(120));
            Assert.Equal(JobState.Done, done!.State);
            var saved = Path.Combine(a.Downloads, "big-from-dotnet.bin");
            Assert.Equal(Files.Sha256(big), Files.Sha256(saved));
            var m = Resuming().Match(a.Log);
            Assert.True(m.Success, "the agent didn't resume:\n" + a.Log[^Math.Min(3000, a.Log.Length)..]);
            Assert.True(long.Parse(m.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture) >= interruptedAt - 1,
                $"resumed at {m.Groups[1].Value}, interrupted at {interruptedAt}");
            Assert.Empty(Directory.GetFiles(a.Downloads, ".droplet-*"));

            // --- a 20 MB file from Linux, the receiver (this peer) stopped part-way and restarted, resumed
            var big2 = Files.Random(root, "big-from-linux.bin", 20);
            var queued = await a.CallAsync(new JsonObject { ["cmd"] = "send-file", ["peer"] = nId, ["path"] = big2, ["wait"] = 0 });
            var jobId = queued.Str("id")!;
            await Wait.For(() => PartSize(n.Downloads) > 6 * 1024 * 1024, 30, "the transfer to this peer to be under way");
            var port = n.Node.Port;
            await n.DisposeAsync();
            var stoppedAt = PartSize(n.Downloads);
            Assert.InRange(stoppedAt, 1, 20 * 1024 * 1024 - 1);
            n = await DotNetPeer.StartAsync(nRoot, "dotnet", fakes, port: port, maxRate: RateLimit);
            await Wait.For(async () => (await a.CallAsync(new JsonObject { ["cmd"] = "job", ["id"] = jobId })).Str("state") == "done", 120,
                "the agent to see the file delivered");
            Assert.Equal(Files.Sha256(big2), Files.Sha256(Path.Combine(n.Downloads, "big-from-linux.bin")));
            m = Resuming().Match(n.Log.Text);
            Assert.True(m.Success, "this peer didn't resume:\n" + n.Log.Text);
            Assert.True(long.Parse(m.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture) >= stoppedAt - 1);
            Assert.Empty(Directory.GetFiles(n.Downloads, ".droplet-*"));

            // --- ring, both ways
            var ring = await a.CallAsync(new JsonObject { ["cmd"] = "ring", ["peer"] = nId });
            Assert.Equal("lan", ring.Str("route"));
            await Wait.For(() => fakes.Sound.Rings == 1, 5, "the fake sound to ring");
            await a.CallAsync(new JsonObject { ["cmd"] = "ring", ["peer"] = nId, ["stop"] = true });
            await Wait.For(() => fakes.Sound.Stops == 1, 5, "the fake sound to stop");
            Assert.Equal("lan", await n.Node.RingAsync(aFp));
            await Wait.For(() => a.Log.Contains("ring (dry run)", StringComparison.Ordinal), 5, "the agent to ring");
            await n.Node.RingAsync(aFp, stopRing: true);
            await Wait.For(() => a.Log.Contains("ring stopped (dry run)", StringComparison.Ordinal), 5, "the agent to stop ringing");

            // --- clip, both ways
            var clip = await a.CallAsync(new JsonObject { ["cmd"] = "clip", ["peer"] = nId, ["text"] = "copied on Linux" });
            Assert.Equal("lan", clip.Str("route"));
            await Wait.For(() => fakes.Clipboard.Written.Contains("copied on Linux"), 5, "the fake clipboard to be written");
            Assert.Equal("lan", await n.Node.ClipAsync(aFp, "copied on .NET"));
            await Wait.For(() => a.Log.Contains("would set 14 characters: 'copied on .NET'", StringComparison.Ordinal), 5, "the agent's clipboard");

            // --- remote control from Linux reaches the fake platform, attributed to the peer
            var input = new JsonObject
            {
                ["t"] = "input",
                ["ev"] = new JsonArray(
                    new JsonObject { ["k"] = "move", ["dx"] = 3, ["dy"] = 4.5 },
                    new JsonObject { ["k"] = "key", ["key"] = "ArrowRight", ["mods"] = new JsonArray("ctrl") },
                    new JsonObject { ["k"] = "click", ["b"] = "left", ["n"] = 2 },
                    new JsonObject { ["k"] = "text", ["s"] = "héllo 👋" }),
                ["from"] = new JsonObject { ["id"] = "forged", ["name"] = "someone else" },
            };
            var route = await a.CallAsync(new JsonObject { ["cmd"] = "send", ["peer"] = nId, ["msg"] = input });
            Assert.Equal("lan", route.Str("route"));
            await Wait.For(() => !fakes.Input.Applied.IsEmpty, 5, "the fake input");
            var events = fakes.Input.Applied.First();
            Assert.Equal(new InputEvent("move", 3, 4.5, Mods: []), events[0] with { Mods = [] });
            Assert.Equal(("key", "ArrowRight", "ctrl"), (events[1].Kind, events[1].Key, events[1].Mods![0]));
            Assert.Equal(("click", "left", 2), (events[2].Kind, events[2].Button, events[2].Count));
            Assert.Equal(("text", "héllo 👋"), (events[3].Kind, events[3].Text));
            await a.CallAsync(new JsonObject
            {
                ["cmd"] = "send", ["peer"] = nId, ["msg"] = new JsonObject { ["t"] = "media", ["action"] = "volume", ["value"] = 0.25 },
            });
            await Wait.For(() => fakes.Media.Commands.Any(c => c is { Action: "volume", Number: 0.25 }), 5, "the fake media");
            await a.CallAsync(new JsonObject { ["cmd"] = "send", ["peer"] = nId, ["msg"] = new JsonObject { ["t"] = "cmd", ["cmd"] = "lock" } });
            await Wait.For(() => fakes.Lock.Locked == 1, 5, "the fake lock");
            // a screenshot goes back to the requester as a mesh file
            await a.CallAsync(new JsonObject { ["cmd"] = "send", ["peer"] = nId, ["msg"] = new JsonObject { ["t"] = "cmd", ["cmd"] = "screenshot" } });
            await Wait.For(() => Directory.Exists(a.Downloads) && Directory.GetFiles(a.Downloads, "screenshot-dotnet-*.png").Length == 1, 20,
                "the screenshot to reach the agent");
            Assert.Equal(FakeScreenshot.Png, File.ReadAllBytes(Directory.GetFiles(a.Downloads, "screenshot-dotnet-*.png")[0]));

            // --- and from .NET to the agent's (dry-run) backends
            Assert.Equal("lan", await n.Node.SendLiveAsync(aFp, new JsonObject
            {
                ["t"] = "input", ["ev"] = new JsonArray(new JsonObject { ["k"] = "key", ["key"] = "F5" }),
            }));
            await Wait.For(() => a.Log.Contains("input (dry run): key F5", StringComparison.Ordinal), 5, "the agent's input");
        }
        finally
        {
            await n.DisposeAsync();
        }
    }
}
