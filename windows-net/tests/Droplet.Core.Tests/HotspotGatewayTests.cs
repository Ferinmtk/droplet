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

    /// <summary>How many links the node failed to open, from its log.</summary>
    int Dials(MeshNode node) => logs[node].Lines.Count(l => l.Contains("couldn't open a link", StringComparison.Ordinal));
}
