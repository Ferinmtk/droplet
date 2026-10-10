using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Droplet.Core.Common;
using Droplet.Core.Config;
using Droplet.Core.LocalFirst;
using Droplet.Core.Mesh;
using Droplet.Core.Remote;
using Droplet.Core.Tests.Interop;
using Droplet.Core.Tests.Support;

namespace Droplet.Core.Tests;

// Per-device permissions and Pause (docs/mesh.md §9.9), ported from the reference's
// agent/tests/test_perms.py. Each capability is tried both ways between two real .NET peers,
// in each of four states: allowed, switched off for that device, that device paused, and
// everything paused. The receiver enforces its own settings whatever the sender does, and
// the sender its own.

/// <summary>The model: defaults, what each message needs, what always goes, the trust list.</summary>
public sealed class PermsModelTests : IDisposable
{
    readonly string dir = TestDirs.Make("perms-model");

    public void Dispose() => TestDirs.Remove(dir);

    internal static bool[] Bits(IReadOnlyDictionary<string, bool>? allow) => Perms.Capabilities.Select(c => allow![c]).ToArray();

    static readonly bool[] OtherBits = [true, true, false, false, false, true, false];

    [Fact]
    public void Defaults_for_your_own_device_and_someone_elses()
    {
        Assert.DoesNotContain(false, Bits(Perms.Defaults(Perms.Own)));
        Assert.Equal(OtherBits, Bits(Perms.Defaults(Perms.Other)));
        Assert.Equal(["files", "chat", "clipboard", "notify", "control", "ring", "access"], Perms.Capabilities.ToArray());
        // anything else, or nothing at all, is your own device: what every peer was before
        Assert.Equal(Perms.Own, Perms.CleanRelation(null));
        Assert.Equal(Perms.Own, Perms.CleanRelation("guest"));
        var cleaned = Perms.CleanAllow(Perms.BoolsOf(JsonNode.Parse("""{"clipboard": false, "bogus": false, "files": "yes"}""")), Perms.Own);
        Assert.Equal([true, true, false, true, true, true, true], Bits(cleaned));
    }

    [Theory]
    [InlineData("""{"t":"text"}""", "chat")]
    [InlineData("""{"t":"offer"}""", "files")]
    [InlineData("""{"t":"file"}""", "files")]
    [InlineData("""{"t":"clip"}""", "clipboard")]
    [InlineData("""{"t":"notify"}""", "notify")]
    [InlineData("""{"t":"notify-removed"}""", "notify")]
    [InlineData("""{"t":"input"}""", "control")]
    [InlineData("""{"t":"media"}""", "control")]
    [InlineData("""{"t":"cmd"}""", "control")]
    [InlineData("""{"t":"ring"}""", "ring")]
    [InlineData("""{"t":"ring-stop"}""", "ring")]
    [InlineData("""{"t":"rpc","method":"sms.list"}""", "access")]
    [InlineData("""{"t":"rpc","method":"files.get"}""", "access")]
    [InlineData("""{"t":"rpc","method":"media.play"}""", "control")]
    [InlineData("""{"t":"state","kind":"media"}""", "control")]
    [InlineData("""{"t":"state","kind":"battery"}""", null)]
    [InlineData("""{"t":"unpair"}""", null)]
    [InlineData("""{"t":"something-new"}""", null)]
    public void What_each_message_needs(string msg, string? cap) => Assert.Equal(cap, Perms.Capability(Json.ParseObject(msg)!));

    [Fact]
    public void What_always_goes_even_paused()
    {
        using var id = MeshIdentity.LoadOrCreate(new FileIdentityStore(dir));
        var entry = TrustEntry.Make(id.LocalId, "x", id.CertificatePem, TrustSource.Paired, paused: true);
        foreach (var t in new List<string> { "hello", "welcome", "ping", "pong", "perm", "unpair", "ack", "nack", "refused", "error", "rpc-result" })
        {
            Assert.Null(Perms.Check(entry, Perms.Message(t)));
        }
        Assert.Equal(("paused", ""), Perms.Check(entry, Json.ParseObject("""{"t":"state","kind":"battery"}""")!)!.Value);
        var mine = TrustEntry.Make(id.LocalId, "x", id.CertificatePem, TrustSource.Paired);
        Assert.Equal(("paused", "ring"), Perms.Check(mine, Perms.Message("ring"), pausedAll: true)!.Value);
        Assert.Null(Perms.Check(mine, Perms.Message("ring")));
    }

    [Fact]
    public void An_entry_from_before_permissions_is_your_own_device_and_a_bad_switch_is_its_default()
    {
        using var other = MeshIdentity.LoadOrCreate(new FileIdentityStore(Path.Combine(dir, "y")));
        var e = TrustEntry.Make(other.LocalId, "y", other.CertificatePem, TrustSource.Paired);
        var old = (JsonObject)JsonSerializer.SerializeToNode(e)!;
        foreach (var k in new List<string> { "relation", "allow", "paused" })
        {
            old.Remove(k);
        }
        var path = Path.Combine(dir, "trust.json");
        File.WriteAllText(path, Json.ToText(new JsonObject { ["v"] = 1, ["peers"] = new JsonObject { [e.Fp] = old } }));
        var got = new TrustList(path, new string('0', 64)).Get(e.Fp)!;
        Assert.Equal(Perms.Own, got.Relation);
        Assert.DoesNotContain(false, Bits(got.Allow));
        Assert.False(got.Paused);

        // the reference's own layout, with a switch that makes no sense: the peer stays, the switch is its default
        old["relation"] = "other";
        old["allow"] = new JsonObject { ["clipboard"] = true, ["files"] = "yes" };
        old["paused"] = true;
        File.WriteAllText(path, Json.ToText(new JsonObject { ["v"] = 1, ["peers"] = new JsonObject { [e.Fp] = old.DeepClone() } }));
        got = new TrustList(path, new string('0', 64)).Get(e.Fp)!;
        Assert.Equal((Perms.Other, true), (got.Relation, got.Paused));
        Assert.Equal([true, true, true, false, false, true, false], Bits(got.Allow));
    }

    [Fact]
    public void The_roster_keeps_the_owners_switches()
    {
        const string Hub = "abababababababab";
        using var b = MeshIdentity.LoadOrCreate(new FileIdentityStore(Path.Combine(dir, "b")));
        var trust = new TrustList(Path.Combine(dir, "trust.json"), new string('0', 64));
        var e = TrustEntry.Make(b.LocalId, "b", b.CertificatePem, TrustSource.Roster, hub: Hub);
        trust.SyncRoster([e], Hub);
        Assert.Equal(Perms.Own, trust.Get(e.Fp)!.Relation); // the hub's devices are your own
        trust.SetPerms(e.Fp, allow: new Dictionary<string, bool> { ["clipboard"] = false }, paused: true);
        trust.SyncRoster([TrustEntry.Make(b.LocalId, "b renamed", b.CertificatePem, TrustSource.Roster, ["127.0.0.1"], 1739, hub: Hub)], Hub);
        var got = trust.Get(e.Fp)!;
        Assert.Equal(("b renamed", true, false), (got.Name, got.Paused, got.Allow!["clipboard"]));
    }

    [Fact]
    public void Changing_the_relation_starts_from_its_defaults_and_is_kept()
    {
        using var b = MeshIdentity.LoadOrCreate(new FileIdentityStore(Path.Combine(dir, "b")));
        var path = Path.Combine(dir, "trust.json");
        var trust = new TrustList(path, new string('0', 64));
        var e = TrustEntry.Make(b.LocalId, "b", b.CertificatePem, TrustSource.Paired);
        trust.AddPaired(e);
        trust.SetPerms(e.Fp, allow: new Dictionary<string, bool> { ["files"] = false });
        Assert.Equal(OtherBits, Bits(trust.SetPerms(e.Fp, relation: Perms.Other).Allow));
        var own = trust.SetPerms(e.Fp, relation: Perms.Own, allow: new Dictionary<string, bool> { ["ring"] = false });
        Assert.Equal([true, true, true, true, true, false, true], Bits(own.Allow));
        Assert.Contains("no capability", Assert.Throws<ArgumentException>(() => trust.SetPerms(e.Fp, allow: new Dictionary<string, bool> { ["teleport"] = true })).Message,
            StringComparison.Ordinal);
        Assert.Contains("relation", Assert.Throws<ArgumentException>(() => trust.SetPerms(e.Fp, relation: "friend")).Message, StringComparison.Ordinal);
        Assert.Throws<ArgumentException>(() => trust.SetPerms(new string('f', 64), paused: true));
        // kept across a restart, in the reference's layout
        Assert.Equal([true, true, true, true, true, false, true], Bits(new TrustList(path, new string('0', 64)).Get(e.Fp)!.Allow));
        var raw = Json.ParseObject(File.ReadAllText(path))!["peers"]![e.Fp]!.AsObject();
        Assert.Equal("own", raw.Str("relation"));
        Assert.False(raw["allow"]!.AsObject().Bool("ring"));
        Assert.False(raw.Bool("paused"));
    }

    [Fact]
    public void Refusals_and_local_reasons_read_as_the_reference_words_them()
    {
        Assert.Equal("Brian's laptop doesn't allow the clipboard from you", Perms.RefusalText("Brian's laptop", "denied", "clipboard"));
        Assert.Equal("Brian's laptop paused sharing with you", Perms.RefusalText("Brian's laptop", "paused", "files"));
        Assert.Equal("Files with b is switched off here", Perms.LocalText("b", "denied", "files"));
        Assert.Equal("b is paused: resume it to send", Perms.LocalText("b", "paused", "chat"));
        Assert.Equal("everything is paused on this device: resume to send", Perms.LocalText("b", "paused", "chat", pausedAll: true));
        Assert.Null(Perms.ParseRemote(null));
        var remote = Perms.ParseRemote(JsonNode.Parse("""{"paused": false, "allow": {"clipboard": false, "files": "x"}}"""))!;
        Assert.Equal("denied", Perms.RemoteRefuses(remote, "clipboard"));
        Assert.Null(Perms.RemoteRefuses(remote, "files"));
        Assert.Equal("paused", Perms.RemoteRefuses(remote with { Paused = true }, "files"));
    }

    [Fact]
    public void Pause_everything_is_kept_in_the_config()
    {
        var paths = new AppPaths(Path.Combine(dir, "cfg"));
        var store = ConfigStore.Open(paths, "");
        var bridge = new HubMeshBridge(store, new RouteManager(() => HubTarget.Of(store.Get())), null, null);
        Assert.False(bridge.PausedEverything);
        bridge.SetPausedEverything(true);
        Assert.True(bridge.PausedEverything);
        Assert.True(ConfigStore.Open(paths, "").Get().Mesh.Paused);
        bridge.SetPausedEverything(false);
        Assert.False(ConfigStore.Open(paths, "").Get().Mesh.Paused);
    }
}

/// <summary>Two (or three) .NET peers on loopback, trusting each other.</summary>
public abstract class PermsPeers : IAsyncLifetime
{
    protected string Root { get; } = TestDirs.Make("perms");

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

    protected async Task<DotNetPeer> StartAsync(string name, IMeshHost? host = null)
    {
        var p = await DotNetPeer.StartAsync(Path.Combine(Root, name), name, port: 0, host: host, announce: false);
        peers.Add(p);
        return p;
    }

    protected async Task StopAsync(DotNetPeer p)
    {
        peers.Remove(p);
        await p.DisposeAsync();
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

    protected async Task<(DotNetPeer A, DotNetPeer B)> PairAsync()
    {
        var a = await StartAsync("a");
        var b = await StartAsync("b");
        TrustEachOther(a, b);
        return (a, b);
    }

    protected static string Fp(DotNetPeer p) => p.Node.Identity.Fingerprint;

    /// <summary><paramref name="node"/>'s settings about <paramref name="peer"/>: "allowed" (as paired), "denied" (the capability off), "paused", "global".</summary>
    protected static void SetState(DotNetPeer node, DotNetPeer peer, string cap, string state)
    {
        switch (state)
        {
            case "denied":
                node.Node.SetPerms(Fp(peer), allow: new Dictionary<string, bool> { [cap] = false });
                break;
            case "paused":
                node.Node.SetPerms(Fp(peer), paused: true);
                break;
            case "global":
                node.Node.PauseEverything(true);
                break;
        }
    }

    public static TheoryData<string, string> Matrix()
    {
        var data = new TheoryData<string, string>();
        foreach (var cap in Perms.Capabilities)
        {
            foreach (var state in new List<string> { "allowed", "denied", "paused", "global" })
            {
                data.Add(cap, state);
            }
        }
        return data;
    }

    public static TheoryData<string> EachCap()
    {
        var data = new TheoryData<string>();
        foreach (var cap in Perms.Capabilities)
        {
            data.Add(cap);
        }
        return data;
    }

    /// <summary>What happened to something sent: "taken", "sent", "waiting", a refusal (why, text), or a failure.</summary>
    protected sealed record Got(string What, string? Text = null, RefusedException? Refused = null);

    protected static JsonObject LiveMessage(string cap) => cap switch
    {
        "clipboard" => new JsonObject { ["t"] = "clip", ["text"] = "secret" },
        "notify" => new JsonObject { ["t"] = "notify", ["key"] = "k1", ["app"] = "Chat", ["title"] = "Mum", ["text"] = "hi" },
        "control" => new JsonObject { ["t"] = "input", ["ev"] = new JsonArray(new JsonObject { ["k"] = "key", ["key"] = "Enter" }) },
        "ring" => new JsonObject { ["t"] = "ring" },
        _ => new JsonObject { ["t"] = "rpc", ["id"] = "r1", ["method"] = "files.list", ["params"] = new JsonObject() },
    };
}

/// <summary>The matrix: what the receiver takes, whatever the sender does.</summary>
public sealed class PermsReceiverTests : PermsPeers
{
    /// <summary>a sends b something needing <paramref name="cap"/>, ignoring any hint. Returns what b did.</summary>
    async Task<Got> ReceiveAsync(DotNetPeer a, DotNetPeer b, string cap, ConcurrentQueue<JsonObject> dispatched)
    {
        var fp = Fp(b);
        a.Node.SendRegardless = true;
        if (cap is "files" or "chat")
        {
            OutboxJob job;
            if (cap == "files")
            {
                var src = Path.Combine(Root, "photo.jpg");
                File.WriteAllBytes(src, RandomNumberGenerator.GetBytes(5000));
                job = a.Node.SendFile(fp, src);
            }
            else
            {
                job = a.Node.SendText(fp, "hello");
            }
            var got = await a.Node.WaitJobAsync(job.Id, TimeSpan.FromSeconds(15));
            Assert.NotNull(got);
            if (got.State == JobState.Done)
            {
                return new Got("taken");
            }
            if (got.State == JobState.Failed)
            {
                return new Got("denied", got.Error);
            }
            Assert.StartsWith("waiting: ", got.Error ?? "", StringComparison.Ordinal);
            return new Got("paused", got.Error);
        }
        var link = await a.Node.DirectAsync(fp);
        Assert.NotNull(link);
        var msg = LiveMessage(cap);
        var t = msg.Str("t")!;
        Assert.True(await link.SendAsync(msg));
        bool Taken() => cap switch
        {
            "notify" => b.Fakes.Notifications.Shown.Any(n => n.Tag is { } tag && tag.StartsWith(PhoneNotifications.TagPrefix, StringComparison.Ordinal)),
            "ring" => b.Fakes.Sound.Rings > 0,
            _ => dispatched.Any(m => m.Str("t") == t),
        };
        await Wait.For(() => Taken() || a.Node.LastRefusal(fp) is not null, 5, $"b to take or refuse {t}");
        if (Taken())
        {
            return new Got("taken");
        }
        var r = a.Node.LastRefusal(fp)!;
        Assert.Equal(cap, r.Cap);
        Assert.Equal(t, r.Re);
        return new Got(r.Why, r.Text);
    }

    [Theory]
    [MemberData(nameof(Matrix), MemberType = typeof(PermsPeers))]
    public async Task The_receiver_enforces_its_own_settings(string cap, string state)
    {
        var (a, b) = await PairAsync();
        var dispatched = new ConcurrentQueue<JsonObject>();
        b.Node.Dispatched += dispatched.Enqueue;
        SetState(b, a, cap, state);
        var got = await ReceiveAsync(a, b, cap, dispatched);
        switch (state)
        {
            case "allowed":
                Assert.Equal("taken", got.What);
                return;
            case "denied":
                Assert.Equal(new Got("denied", $"b doesn't allow {Perms.Nouns[cap]} from you"), got);
                break;
            default:
                // paused: refused for now, and files and messages wait for the resume rather than fail
                Assert.Equal("paused", got.What);
                Assert.Contains("b paused sharing with you", got.Text, StringComparison.Ordinal);
                break;
        }
        // nothing of it reached b
        Assert.Empty(dispatched);
        Assert.Empty(b.Fakes.Notifications.Shown);
        Assert.Equal(0, b.Fakes.Sound.Rings);
        Assert.DoesNotContain(b.Node.Chat.Recent(), m => m.Dir == "in");
        Assert.True(!Directory.Exists(b.Downloads) || Directory.GetFiles(b.Downloads).Length == 0, "a file reached b");
    }
}

/// <summary>The matrix: what the sender sends, by its own settings and by what the receiver said.</summary>
public sealed class PermsSenderTests : PermsPeers
{
    /// <summary>a sends b something needing <paramref name="cap"/> the normal way.</summary>
    async Task<Got> SendAsync(DotNetPeer a, DotNetPeer b, string cap)
    {
        var fp = Fp(b);
        OutboxJob job;
        try
        {
            switch (cap)
            {
                case "files":
                    {
                        var src = Path.Combine(Root, "doc.txt");
                        File.WriteAllText(src, "x");
                        job = a.Node.SendFile(fp, src);
                        break;
                    }
                case "chat":
                    job = a.Node.SendText(fp, "hi");
                    break;
                case "clipboard":
                    await a.Node.ClipAsync(fp, "copied");
                    return new Got("sent");
                case "control":
                    await a.Node.SendLiveAsync(fp, new JsonObject { ["t"] = "input", ["ev"] = new JsonArray() });
                    return new Got("sent");
                case "ring":
                    await a.Node.RingAsync(fp);
                    return new Got("sent");
                default:
                    a.Node.MaySend(fp, cap == "notify"
                        ? new JsonObject { ["t"] = "notify", ["key"] = "k", ["title"] = "t", ["text"] = "x" }
                        : new JsonObject { ["t"] = "rpc", ["id"] = "r", ["method"] = "sms.list" });
                    return new Got("sent");
            }
        }
        catch (RefusedException e)
        {
            return new Got("refused", e.Message, e);
        }
        var got = await a.Node.WaitJobAsync(job.Id, TimeSpan.FromSeconds(15));
        return got?.State switch
        {
            JobState.Done => new Got("sent"),
            JobState.Queued => new Got("waiting", got!.Error),
            _ => new Got("failed", got?.Error),
        };
    }

    [Theory]
    [MemberData(nameof(Matrix), MemberType = typeof(PermsPeers))]
    public async Task The_sender_enforces_its_own_settings(string cap, string state)
    {
        var (a, b) = await PairAsync();
        SetState(a, b, cap, state);
        var got = await SendAsync(a, b, cap);
        if (state == "allowed" || (state == "denied" && Perms.InboundOnly.Contains(cap)))
        {
            // a switch about what b may do *here* doesn't stop this device using b: b decides that
            Assert.Equal(new Got("sent"), got);
        }
        else if (state == "denied")
        {
            Assert.Equal("refused", got.What);
            Assert.True(got.Refused!.Local);
            Assert.Equal("denied", got.Refused.Why);
            Assert.EndsWith("switched off here", got.Text, StringComparison.Ordinal);
        }
        else if (cap is "files" or "chat")
        {
            Assert.Equal("waiting", got.What); // it waits for the resume, it doesn't fail
            var job = Assert.Single(a.Node.Outbox.Queued());
            Assert.StartsWith("waiting: ", job.Error, StringComparison.Ordinal);
            Assert.Contains(state == "global" ? "everything is paused" : "b is paused", job.Error, StringComparison.Ordinal);
        }
        else
        {
            Assert.Equal("refused", got.What);
            Assert.True(got.Refused!.Local);
            Assert.Equal("paused", got.Refused.Why);
        }
    }

    [Theory]
    [MemberData(nameof(EachCap), MemberType = typeof(PermsPeers))]
    public async Task The_sender_respects_what_the_receiver_said(string cap)
    {
        var (a, b) = await PairAsync();
        Assert.NotNull(await a.Node.DirectAsync(Fp(b)));
        b.Node.SetPerms(Fp(a), allow: new Dictionary<string, bool> { [cap] = false });
        await Wait.For(() => a.Node.RemotePermOf(Fp(b))?.Allow.GetValueOrDefault(cap, true) == false, 5, "b's perm");
        var got = await SendAsync(a, b, cap);
        Assert.Equal("refused", got.What);
        Assert.False(got.Refused!.Local);
        Assert.Equal($"b doesn't allow {Perms.Nouns[cap]} from you", got.Text);
    }
}

/// <summary>Pausing, resuming, what each peer is told, and the hub's routes.</summary>
public sealed class PermsPauseTests : PermsPeers
{
    [Fact]
    public async Task Messages_to_a_paused_device_wait_and_go_on_resume()
    {
        var (a, b) = await PairAsync();
        a.Node.SetPerms(Fp(b), paused: true);
        var job = a.Node.SendText(Fp(b), "after the meeting");
        var got = await a.Node.Outbox.WaitAsync(job.Id, j => j.Attempts > 0 && j.State == JobState.Queued, TimeSpan.FromSeconds(10));
        Assert.Equal("waiting: b is paused: resume it to send", got?.Error);
        Assert.False(a.Node.SetPerms(Fp(b), paused: false).Paused);
        Assert.Equal(JobState.Done, (await a.Node.Outbox.WaitAsync(job.Id, j => j.State == JobState.Done, TimeSpan.FromSeconds(10)))?.State);
        Assert.Equal(["after the meeting"], b.Node.Chat.Recent().Where(m => m.Dir == "in").Select(m => m.Body).ToArray());
    }

    [Fact]
    public async Task A_device_that_paused_us_gets_our_messages_when_it_resumes()
    {
        var (a, b) = await PairAsync();
        Assert.NotNull(await a.Node.DirectAsync(Fp(b)));
        b.Node.SetPerms(Fp(a), paused: true);
        await Wait.For(() => a.Node.RemotePermOf(Fp(b))?.Paused == true, 5, "b's perm");
        var job = a.Node.SendText(Fp(b), "are you there");
        var got = await a.Node.Outbox.WaitAsync(job.Id, j => j.Attempts > 0 && j.State == JobState.Queued, TimeSpan.FromSeconds(10));
        Assert.Equal("waiting: b paused sharing with you", got?.Error);
        var peer = a.Node.Status()["peers"]!.AsArray().OfType<JsonObject>().Single(p => p.Str("name") == "b");
        Assert.True(peer["remote"]!.AsObject().Bool("paused"));
        b.Node.SetPerms(Fp(a), paused: false); // its perm says so, and what waited goes
        Assert.Equal(JobState.Done, (await a.Node.Outbox.WaitAsync(job.Id, j => j.State == JobState.Done, TimeSpan.FromSeconds(10)))?.State);
    }

    [Fact]
    public async Task A_message_the_paused_device_refuses_waits_instead_of_failing()
    {
        // b paused a without a ever hearing it (its perm lost, or a ignores hints): b's refusal keeps it queued
        var (a, b) = await PairAsync();
        a.Node.SendRegardless = true;
        b.Node.SetPerms(Fp(a), paused: true);
        var job = a.Node.SendText(Fp(b), "hello?");
        var got = await a.Node.Outbox.WaitAsync(job.Id, j => j.Attempts > 0 && j.State == JobState.Queued && !j.Retry, TimeSpan.FromSeconds(10));
        Assert.Equal("waiting: b paused sharing with you", got?.Error);
        Assert.Equal("paused", a.Node.LastRefusal(Fp(b))?.Why);
    }

    [Fact]
    public async Task Pause_everything_stops_the_clipboard_to_everyone_and_says_so()
    {
        var a = await StartAsync("a");
        var b = await StartAsync("b");
        var c = await StartAsync("c");
        TrustEachOther(a, b);
        TrustEachOther(a, c);
        Assert.NotNull(await a.Node.DirectAsync(Fp(b)));
        Assert.NotNull(await a.Node.DirectAsync(Fp(c)));
        Assert.True(await a.Node.BroadcastAsync(new JsonObject { ["t"] = "clip", ["text"] = "one" }));
        await Wait.For(() => b.Fakes.Clipboard.Written.Count == 1 && c.Fakes.Clipboard.Written.Count == 1, 5, "both clipboards");
        a.Node.PauseEverything(true);
        Assert.True(a.Node.Status().Bool("paused_all"));
        Assert.False(await a.Node.BroadcastAsync(new JsonObject { ["t"] = "clip", ["text"] = "two" }));
        // each peer is told, so its window can say "paused by a"
        await Wait.For(() => b.Node.RemotePermOf(Fp(a))?.Paused == true && c.Node.RemotePermOf(Fp(a))?.Paused == true, 5, "a's perm");
        a.Node.PauseEverything(false);
        await Wait.For(() => b.Node.RemotePermOf(Fp(a))?.Paused == false, 5, "a's perm");
        Assert.True(await a.Node.BroadcastAsync(new JsonObject { ["t"] = "clip", ["text"] = "three" }));
        await Wait.For(() => b.Fakes.Clipboard.Written.SequenceEqual(new List<string> { "one", "three" }), 5, "the clipboard after the resume");
    }

    [Fact]
    public async Task The_clipboard_skips_someone_elses_device()
    {
        var a = await StartAsync("a");
        var mine = await StartAsync("mine");
        var guest = await StartAsync("guest");
        TrustEachOther(a, mine);
        TrustEachOther(a, guest);
        a.Node.SetPerms(Fp(guest), relation: Perms.Other);
        Assert.NotNull(await a.Node.DirectAsync(Fp(mine)));
        Assert.NotNull(await a.Node.DirectAsync(Fp(guest)));
        Assert.True(await a.Node.BroadcastAsync(new JsonObject { ["t"] = "clip", ["text"] = "my password" }));
        await Wait.For(() => !mine.Fakes.Clipboard.Written.IsEmpty, 5, "my own device's clipboard");
        await Task.Delay(300);
        Assert.Empty(guest.Fakes.Clipboard.Written);
    }

    [Fact]
    public async Task Hello_tells_each_peer_only_what_it_may_use()
    {
        var (a, b) = await PairAsync();
        a.Node.SetPerms(Fp(b), relation: Perms.Other);
        var hello = a.Node.Hello("welcome", Fp(b));
        Assert.Empty(hello.Strings("caps")!); // input, media, lock, screenshot, clipboard: remote control and the clipboard
        Assert.False(hello["perm"]!.AsObject().Bool("paused"));
        Assert.Equal(PermsModelTests.Bits(Perms.OtherDefaults), PermsModelTests.Bits(Perms.BoolsOf(hello["perm"]!["allow"])));
        a.Node.SetPerms(Fp(b), relation: Perms.Own);
        Assert.Equal(["input", "media", "lock", "screenshot", "clipboard"], a.Node.Hello("welcome", Fp(b)).Strings("caps")!.ToArray());
        a.Node.SetPerms(Fp(b), paused: true);
        Assert.Empty(a.Node.Hello("welcome", Fp(b)).Strings("caps")!);
        // a link opened now carries it: b learns how a treats it
        Assert.NotNull(await b.Node.DirectAsync(Fp(a)));
        await Wait.For(() => b.Node.RemotePermOf(Fp(a))?.Paused == true, 5, "a's word in its welcome");
    }

    [Fact]
    public async Task An_older_peer_with_no_perm_is_treated_as_before()
    {
        var (a, b) = await PairAsync();
        b.Node.SendsPerm = false;
        Assert.NotNull(await a.Node.DirectAsync(Fp(b)));
        Assert.Null(a.Node.RemotePermOf(Fp(b)));
        Assert.Equal("lan", await a.Node.ClipAsync(Fp(b), "x"));
    }

    [Fact]
    public async Task Refusals_of_a_stream_are_said_once_in_a_while()
    {
        var (a, b) = await PairAsync();
        b.Node.SetPerms(Fp(a), allow: new Dictionary<string, bool> { ["control"] = false });
        var link = await a.Node.DirectAsync(Fp(b));
        Assert.NotNull(link);
        for (var i = 0; i < 30; i++)
        {
            await link.SendAsync(new JsonObject { ["t"] = "input", ["ev"] = new JsonArray(new JsonObject { ["k"] = "move", ["dx"] = 1, ["dy"] = 1 }) });
        }
        await Wait.For(() => a.Node.LastRefusal(Fp(b)) is not null, 5, "b's refusal");
        await Task.Delay(500);
        Assert.Single(a.Log.Lines, l => l.Contains("b refused:", StringComparison.Ordinal));
        Assert.Equal("denied", a.Node.LastRefusal(Fp(b))!.Why);
        Assert.Equal("control", a.Node.LastRefusal(Fp(b))!.Cap);
        Assert.Empty(b.Fakes.Input.Applied);
    }

    [Fact]
    public async Task A_paused_device_cant_fetch_a_file_offered_before()
    {
        var (a, b) = await PairAsync();
        var path = "/mesh/files/" + new string('0', 32);
        Assert.Equal(404, await Tls.StatusAsync("127.0.0.1", a.Node.Port, b.Node.Identity.TlsCertificate, "GET", path));
        a.Node.SetPerms(Fp(b), paused: true);
        Assert.Equal(403, await Tls.StatusAsync("127.0.0.1", a.Node.Port, b.Node.Identity.TlsCertificate, "GET", path));
        a.Node.SetPerms(Fp(b), paused: false, allow: new Dictionary<string, bool> { ["files"] = false });
        Assert.Equal(403, await Tls.StatusAsync("127.0.0.1", a.Node.Port, b.Node.Identity.TlsCertificate, "GET", path));
    }

    [Fact]
    public async Task Pairing_as_someone_elses_device_on_both_sides()
    {
        var a = await StartAsync("a");
        var b = await StartAsync("b");
        var start = await a.Node.PairStartAsync($"127.0.0.1:{b.Node.Port}");
        var req = Assert.Single(b.Node.Incoming.Waiting());
        Assert.Throws<ArgumentException>(() => b.Node.PairAnswer(req.Request, true, "deskmate"));
        // each side chooses for itself, and says nothing of it to the other
        Assert.Equal(Perms.Other, b.Node.PairAnswer(req.Request, true, Perms.Other)!.Relation);
        await Assert.ThrowsAsync<ArgumentException>(() => a.Node.PairConfirmAsync(start.Request, true, "deskmate"));
        await a.Node.PairConfirmAsync(start.Request, true, Perms.Own);
        await Wait.For(() => a.Node.Trust.Get(Fp(b)) is not null, 15, "a to trust b");
        var atB = b.Node.Trust.Get(Fp(a))!;
        Assert.Equal(Perms.Other, atB.Relation);
        Assert.Equal(PermsModelTests.Bits(Perms.OtherDefaults), PermsModelTests.Bits(atB.Allow));
        Assert.Equal(Perms.Own, a.Node.Trust.Get(Fp(b))!.Relation);
        // a caller that doesn't say: your own device, as before
        Assert.Equal(Perms.Own, TrustEntry.Make(a.Node.PeerId, "a", a.Node.Identity.CertificatePem, TrustSource.Paired).Relation);
    }

    [Fact]
    public async Task Status_says_who_is_paused_and_how_each_is_treated()
    {
        var (a, b) = await PairAsync();
        Assert.False(a.Node.SetPerms(Fp(b), allow: new Dictionary<string, bool> { ["clipboard"] = false }).Allow!["clipboard"]);
        a.Node.SetPerms(Fp(b), relation: Perms.Other, paused: true);
        var st = a.Node.Status();
        var p = Assert.Single(st["peers"]!.AsArray().OfType<JsonObject>());
        Assert.Equal("other", p.Str("relation"));
        Assert.True(p.Bool("paused"));
        Assert.Equal(PermsModelTests.Bits(Perms.OtherDefaults), PermsModelTests.Bits(Perms.BoolsOf(p["allow"])));
        Assert.False(st.Bool("paused_all"));
        Assert.Equal(Perms.Capabilities.ToArray(), st.Strings("capabilities")!.ToArray());
    }

    [Fact]
    public async Task The_hub_route_follows_the_same_switches()
    {
        const string Hub = "abababababababab";
        var host = new FakeHubHost("a", Hub);
        var a = await StartAsync("a", host);
        var b = await StartAsync("b");
        TrustEachOther(a, b, TrustSource.Roster, Hub);
        var fp = Fp(b);
        var bId = b.Node.PeerId;
        await StopAsync(b);
        host.Up = true;
        host.Online[bId] = true;
        var input = new JsonObject { ["t"] = "input", ["ev"] = new JsonArray() };
        Assert.Equal("hub", await a.Node.SendLiveAsync(fp, input));
        a.Node.SetPerms(fp, paused: true);
        await Assert.ThrowsAsync<RefusedException>(() => a.Node.SendLiveAsync(fp, new JsonObject { ["t"] = "input", ["ev"] = new JsonArray() }));
        await Assert.ThrowsAsync<RefusedException>(() => a.Node.RingAsync(fp));
        var job = a.Node.SendText(fp, "later");
        Assert.NotNull(await a.Node.Outbox.WaitAsync(job.Id, j => j.Attempts > 0 && j.State == JobState.Queued, TimeSpan.FromSeconds(10)));
        Assert.DoesNotContain("text", host.Calls);
        a.Node.SetPerms(fp, paused: false);
        Assert.Equal("hub", (await a.Node.Outbox.WaitAsync(job.Id, j => j.State == JobState.Done, TimeSpan.FromSeconds(10)))?.Route);
        // a clipboard through the hub reaches every device it has: not while one shouldn't have it
        var clip = new JsonObject { ["t"] = "clip" };
        var battery = new JsonObject { ["t"] = "state", ["kind"] = "battery" };
        Assert.True(a.Node.HubMayShare(clip));
        a.Node.SetPerms(fp, allow: new Dictionary<string, bool> { ["clipboard"] = false });
        Assert.False(a.Node.HubMayShare(clip));
        Assert.True(a.Node.HubMayShare(battery));
        await Assert.ThrowsAsync<RefusedException>(() => a.Node.ClipAsync(fp, "x"));
        a.Node.PauseEverything(true);
        Assert.False(a.Node.HubMayShare(battery));
    }

    [Fact]
    public async Task Messages_through_the_hub_are_checked_against_the_sender()
    {
        const string Hub = "abababababababab";
        var a = await StartAsync("a");
        var b = await StartAsync("b");
        TrustEachOther(a, b, TrustSource.Roster, Hub);
        var from = new JsonObject { ["id"] = b.Node.PeerId, ["name"] = "b" };
        JsonObject Msg(string t, string? sender = null) =>
            new() { ["t"] = t, ["from"] = sender is null ? from.DeepClone() : new JsonObject { ["id"] = sender } };
        Assert.Null(a.Node.CheckHubMessage(Msg("input")));
        a.Node.SetPerms(Fp(b), relation: Perms.Other);
        Assert.Equal("denied", a.Node.CheckHubMessage(Msg("input")));
        Assert.Equal("denied", a.Node.CheckHubMessage(Msg("clip")));
        // a device of the hub's this one doesn't know (the hub's web page): the same user's
        Assert.Null(a.Node.CheckHubMessage(Msg("input", "0123456789ab")));
        a.Node.PauseEverything(true);
        Assert.Equal("paused", a.Node.CheckHubMessage(Msg("input", "0123456789ab")));
        Assert.Null(a.Node.CheckHubMessage(Msg("rpc-result", "0123456789ab")));
    }

    /// <summary>A hub that's always there and takes whatever it's given.</summary>
    sealed class FakeHubHost(string name, string hubId) : NoHubHost(name, Remote.Caps.All)
    {
        public bool Up { get; set; }

        public ConcurrentDictionary<string, bool> Online { get; } = new();

        public ConcurrentQueue<string> Calls { get; } = new();

        public override string? HubId => hubId;

        public override bool HubConnected => Up;

        public override bool HubOnline(string deviceId) => Online.ContainsKey(deviceId);

        public override Task<bool> HubSendAsync(JsonObject message)
        {
            Calls.Enqueue("send " + message.Str("t"));
            return Task.FromResult(true);
        }

        public override Task HubTextAsync(string deviceId, string body, CancellationToken ct)
        {
            Calls.Enqueue("text");
            return Task.CompletedTask;
        }

        public override Task HubRingAsync(string deviceId, bool stopRing, CancellationToken ct)
        {
            Calls.Enqueue("ring");
            return Task.CompletedTask;
        }
    }
}
