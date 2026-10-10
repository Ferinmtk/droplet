using System.Net;
using System.Net.Sockets;
using Droplet.Core.Common;
using Droplet.Core.Mesh;
using Droplet.Core.Tests.Support;

namespace Droplet.Core.Tests;

/// <summary>
/// A phone serving a hotspot doesn't announce itself on it, and the laptop knows it only by
/// an address from another network; but the phone is the hotspot's gateway. Nodes here are
/// on localhost, with mDNS off, and "the gateway" is 127.0.0.1 at the phone's port.
/// </summary>
public sealed class HotspotGatewayTests : IAsyncLifetime
{
    const string Elsewhere = "10.255.255.1"; // an address from another network: nothing answers there

    readonly string root = TestDirs.Make("gateway");
    readonly List<MeshNode> nodes = [];
    readonly Dictionary<MeshNode, TestLog> logs = [];
    IReadOnlyList<string> laptopGateways = [];

    public ValueTask InitializeAsync() => ValueTask.CompletedTask;

    public async ValueTask DisposeAsync()
    {
        foreach (var n in nodes)
        {
            await n.DisposeAsync();
        }
        TestDirs.Remove(root);
    }

    async Task<MeshNode> StartAsync(string name, Func<IReadOnlyList<string>>? gateways = null)
    {
        var dir = Path.Combine(root, name);
        var log = new TestLog();
        var node = new MeshNode(new NoHubHost(name), new MeshOptions
        {
            ConfigDir = Path.Combine(dir, "mesh"),
            DataDir = Path.Combine(dir, "mesh", "data"),
            Downloads = Path.Combine(dir, "Downloads"),
            Port = 0,
            Announce = false,
            LocalAddresses = () => [],
            Gateways = gateways ?? (() => []),
            RetryEvery = TimeSpan.FromSeconds(2),
            LoggerFactory = log,
        });
        await node.StartAsync();
        nodes.Add(node);
        logs[node] = log;
        return node;
    }

    Task<MeshNode> StartLaptopAsync() => StartAsync("laptop", () => laptopGateways);

    /// <summary>The laptop trusts the peer, known only from elsewhere and at <paramref name="port"/>; the peer trusts the laptop.</summary>
    static void Pair(MeshNode laptop, MeshNode peer, int port)
    {
        laptop.Trust.AddPaired(TrustEntry.Make(peer.PeerId, peer.Name, peer.Identity.CertificatePem, TrustSource.Paired, [Elsewhere], port));
        peer.Trust.AddPaired(TrustEntry.Make(laptop.PeerId, laptop.Name, laptop.Identity.CertificatePem, TrustSource.Paired, port: laptop.Port));
    }

    [Fact]
    public void The_gateways_are_ipv4_addresses_of_real_interfaces()
    {
        foreach (var gw in Addresses.Gateways())
        {
            Assert.True(IPAddress.TryParse(gw, out var ip));
            Assert.Equal(AddressFamily.InterNetwork, ip!.AddressFamily);
            Assert.False(IPAddress.IsLoopback(ip) || Addresses.IsUnspecified(ip) || Addresses.IsTailnet(ip));
        }
    }

    [Fact]
    public async Task The_gateway_is_a_candidate_after_the_known_addresses()
    {
        var laptop = await StartLaptopAsync();
        var phone = await StartAsync("phone");
        Pair(laptop, phone, phone.Port);
        laptopGateways = ["127.0.0.1"];
        var candidates = laptop.Candidates(laptop.Trust.Get(phone.Identity.Fingerprint)!);
        Assert.Equal([(Elsewhere, phone.Port, "lan"), ("127.0.0.1", phone.Port, "lan")], candidates);
        // so a message finds it there, the known address having failed
        var link = await laptop.DirectAsync(phone.Identity.Fingerprint);
        Assert.NotNull(link);
        Assert.Equal("127.0.0.1", link!.Address);
    }

    [Fact]
    public async Task A_paired_phone_serving_the_hotspot_is_found_at_the_gateway_and_the_link_kept()
    {
        var laptop = await StartLaptopAsync();
        var phone = await StartAsync("phone");
        Pair(laptop, phone, phone.Port);
        laptopGateways = ["127.0.0.1"];
        var link = await laptop.ProbeGatewaysAsync();
        Assert.NotNull(link);
        Assert.Equal(phone.Identity.Fingerprint, link!.Fp);
        Assert.Equal("127.0.0.1", link.Address);
        Assert.True(link.Keep);
        // the phone sees the laptop
        await Wait.For(() => phone.OpenLink(laptop.Identity.Fingerprint) is not null, 10, "the phone to see the laptop");
        // a gateway already linked isn't probed again
        Assert.Null(await laptop.ProbeGatewaysAsync());
        Assert.Same(link, laptop.OpenLink(phone.Identity.Fingerprint));
    }

    [Fact]
    public async Task Another_device_at_the_gateway_is_tried_once_then_left_alone_on_this_network()
    {
        var laptop = await StartLaptopAsync();
        var phone = await StartAsync("phone");
        var other = await StartAsync("other");
        // "other" is paired, but the gateway (at the phone's port) is the phone, which the laptop doesn't trust
        Pair(laptop, other, phone.Port);
        var fp = other.Identity.Fingerprint;
        laptopGateways = ["127.0.0.1"];
        Assert.Null(await laptop.ProbeGatewaysAsync());
        Assert.Null(laptop.OpenLink(fp));
        Assert.DoesNotContain(laptop.Candidates(laptop.Trust.Get(fp)!), c => c.Address == "127.0.0.1");
        var dials = Dials(laptop);
        Assert.True(dials >= 1);
        Assert.Null(await laptop.ProbeGatewaysAsync());
        Assert.Equal(dials, Dials(laptop)); // not dialled again
        // a new network: tried again there
        laptopGateways = ["127.0.0.2"];
        await laptop.ProbeGatewaysAsync();
        laptopGateways = ["127.0.0.1"];
        Assert.Contains(laptop.Candidates(laptop.Trust.Get(fp)!), c => c.Address == "127.0.0.1");
    }

    // --- pairing on a hotspot: nothing announces, the gateway is asked who it is (§9.10) -------------

    [Fact]
    public async Task The_device_serving_the_hotspot_can_be_paired_from_either_side()
    {
        var phone = await StartAsync("phone");               // serves the hotspot
        var laptop = await StartLaptopAsync();               // joined it
        laptopGateways = ["127.0.0.1"];
        laptop.GatewayPort = phone.Port;                     // 1739 in real life
        Assert.Empty(laptop.Nearby);
        Assert.Empty(phone.Nearby);

        // the laptop's Pair screen asks the gateway: the phone, checked against its certificate
        var found = Assert.Single(await laptop.ScanGatewaysAsync());
        Assert.Equal(phone.Identity.Fingerprint, found.Fp);
        Assert.Equal("phone", found.Name);
        Assert.Equal(["127.0.0.1"], found.Addresses);
        Assert.Equal(phone.Port, found.Port);
        Assert.Equal("windows", found.Os);
        Assert.Contains(laptop.Nearby, s => s.Fp == phone.Identity.Fingerprint);
        // the phone lists the laptop, which asked, at the address it asked from and its own port
        var asked = Assert.Single(phone.Nearby);
        Assert.Equal(laptop.Identity.Fingerprint, asked.Fp);
        Assert.Equal("laptop", asked.Name);
        Assert.Equal(laptop.Port, asked.Port);
        Assert.Equal(["127.0.0.1"], asked.Addresses);
        var near = Assert.Single(phone.Status()["nearby"]!.AsArray());
        Assert.Equal(laptop.Identity.Fingerprint, near!["fp"]!.GetValue<string>());
        // nothing is trusted by itself
        Assert.Null(phone.Trust.Get(laptop.Identity.Fingerprint));
        Assert.Null(laptop.Trust.Get(phone.Identity.Fingerprint));

        // the laptop's owner starts it, by the fingerprint listed: both show the same code, as always
        var start = await laptop.PairStartAsync(phone.Identity.Fingerprint);
        var waiting = Assert.Single(phone.Incoming.Waiting());
        Assert.Equal(start.Code, waiting.Code);
        phone.PairAnswer(waiting.Request, true);
        await laptop.PairConfirmAsync(start.Request, true);
        await Wait.For(() => laptop.Trust.Get(phone.Identity.Fingerprint) is not null, 20, "the laptop to trust the phone");
        Assert.NotNull(phone.Trust.Get(laptop.Identity.Fingerprint));
        Assert.Empty(phone.Status()["nearby"]!.AsArray());   // paired: not offered for pairing any more

        // or the phone's owner starts it, from the phone's list
        var other = await StartAsync("other", () => ["127.0.0.1"]);
        other.GatewayPort = phone.Port;
        await other.ScanGatewaysAsync();
        var back = await phone.PairStartAsync("other");
        Assert.Equal(back.Code, Assert.Single(other.Incoming.Waiting()).Code);
    }

    [Fact]
    public async Task A_plain_router_at_the_gateway_lists_nothing()
    {
        var laptop = await StartLaptopAsync();
        laptopGateways = ["127.0.0.1"];
        using (var l = new TcpListener(IPAddress.Loopback, 0))
        {
            l.Start();
            laptop.GatewayPort = ((IPEndPoint)l.LocalEndpoint).Port;
        }
        Assert.Empty(await laptop.ScanGatewaysAsync(force: true));   // nothing listening
        Assert.Empty(laptop.Nearby);
        // a hello that says nothing is answered, and lists nothing
        var phone = await StartAsync("phone");
        Assert.Equal(200, phone.Pair("POST", Hotspot.HelloPath, [], "127.0.0.1").Status);
        Assert.Equal(405, phone.Pair("GET", Hotspot.HelloPath, null, "127.0.0.1").Status);
        Assert.Empty(phone.Nearby);
    }

    sealed class ManualClock : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = DateTimeOffset.UnixEpoch.AddDays(1);

        public override DateTimeOffset GetUtcNow() => Now;
    }

    [Fact]
    public void Asking_the_gateway_backs_off()
    {
        var clock = new ManualClock();
        var scan = new GatewayScan(clock);
        Assert.Equal(["10.0.0.1"], scan.Due(["10.0.0.1"]));
        var waits = new List<double>();
        for (var i = 0; i < 6; i++)
        {
            scan.Result("10.0.0.1", null);
            var t0 = clock.Now;
            while (scan.Due(["10.0.0.1"]).Count == 0)
            {
                clock.Now += TimeSpan.FromSeconds(1);
            }
            waits.Add((clock.Now - t0).TotalSeconds);
        }
        Assert.Equal([5.0, 10, 20, 40, 60, 60], waits);
        var seen = Hotspot.Parse(new System.Text.Json.Nodes.JsonObject
        {
            ["id"] = "0123456789abcdef", ["fp"] = new string('a', 64), ["name"] = "phone", ["os"] = "android", ["port"] = 1739,
        }, "10.0.0.1")!;
        scan.Result("10.0.0.1", seen);
        Assert.Equal([seen], scan.Peers());
        Assert.Empty(scan.Due(["10.0.0.1"]));
        clock.Now += Hotspot.HelloEvery;
        Assert.Equal(["10.0.0.1"], scan.Due(["10.0.0.1"]));
        clock.Now += Hotspot.FoundTtl;
        Assert.Empty(scan.Peers());   // it stopped answering: gone from the list
        Assert.Equal(["10.0.0.9"], scan.Due(["10.0.0.9"]));   // another network
    }

    [Fact]
    public void Devices_that_asked_are_limited_and_forgotten()
    {
        var clock = new ManualClock();
        var own = new string('c', 64);
        var k = new HotspotKnocks(own, clock);
        static System.Text.Json.Nodes.JsonObject Hello(int i, string? fp = null) => new()
        {
            ["v"] = 1, ["id"] = i.ToString("x16", System.Globalization.CultureInfo.InvariantCulture), ["name"] = $" dev\n {i}\u0007",
            ["os"] = "android", ["fp"] = fp ?? i.ToString("x64", System.Globalization.CultureInfo.InvariantCulture), ["port"] = 1740,
        };
        k.Answer(Hello(1, own), "10.0.0.2", []);
        Assert.Empty(k.Peers());   // itself
        var noId = Hello(1);
        noId["id"] = "NOPE";
        foreach (var bad in new System.Text.Json.Nodes.JsonObject?[] { Hello(1, "xyz"), noId, null })
        {
            Assert.Equal(200, k.Answer(bad, "10.0.0.2", []).Status);
            Assert.Empty(k.Peers());
        }
        var odd = Hello(1);
        odd["port"] = 0;
        odd["os"] = "beos";
        k.Answer(odd, "10.0.0.2", []);
        var one = Assert.Single(k.Peers());
        Assert.Equal(("dev 1", "", 1739), (one.Name, one.Os, one.Port));
        Assert.Equal(["10.0.0.2"], one.Addresses);
        for (var i = 2; i < 20; i++)
        {
            k.Answer(Hello(i), $"10.0.0.{i}", []);
        }
        Assert.Equal(Hotspot.MaxKnocks, k.Peers().Count);
        clock.Now += Hotspot.KnockTtl + TimeSpan.FromSeconds(1);
        Assert.Empty(k.Peers());
        for (var i = 0; i < Hotspot.MaxHellos; i++)
        {
            Assert.Equal(200, k.Answer(null, "10.0.0.2", []).Status);
        }
        Assert.Equal(429, k.Answer(null, "10.0.0.2", []).Status);
    }

    /// <summary>How many links the node failed to open, from its log.</summary>
    int Dials(MeshNode node) => logs[node].Lines.Count(l => l.Contains("couldn't open a link", StringComparison.Ordinal));
}
