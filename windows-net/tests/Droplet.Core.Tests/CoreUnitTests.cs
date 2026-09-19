using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using Droplet.Core.Common;
using Droplet.Core.Config;
using Droplet.Core.Hub;
using Droplet.Core.LocalFirst;
using Droplet.Core.Mdns;
using Droplet.Core.Mesh;
using Droplet.Core.Platform;
using Droplet.Core.Polling;
using Droplet.Core.Remote;
using Droplet.Core.Tests.Support;

namespace Droplet.Core.Tests;

public sealed class DnsTests
{
    static readonly DnsName Type = DnsName.Parse("_droplet-peer._tcp.local.");

    [Fact]
    public void Messages_round_trip_with_compression_and_awkward_labels()
    {
        var inst = DnsName.Child("slim. laptop 9b1617", Type);
        var host = DnsName.Parse("droplet-9b16173d305cd15a.local.");
        var m = new DnsMessage { IsResponse = true, Authoritative = true };
        m.Answers.Add(new PtrRecord(Type, 4500, inst));
        m.Additionals.Add(new SrvRecord(inst, 120, true, 0, 0, 1739, host));
        m.Additionals.Add(TxtRecord.FromPairs(inst, 4500, [new("id", "9b16173d305cd15a"), new("name", "slim ✓"), new("empty", "")]));
        m.Additionals.Add(new AddressRecord(host, 120, true, IPAddress.Parse("192.168.1.20")));
        m.Additionals.Add(new AddressRecord(host, 120, true, IPAddress.Parse("fd00::1")));
        var bytes = m.ToBytes();
        var back = DnsMessage.Parse(bytes)!;
        Assert.True(back.IsResponse);
        Assert.Equal(inst, Assert.IsType<PtrRecord>(back.Answers[0]).Target);
        Assert.Equal("slim. laptop 9b1617", inst.Labels[0]);
        Assert.Equal(@"slim\. laptop 9b1617._droplet-peer._tcp.local.", inst.ToString());
        Assert.Equal(inst, DnsName.Parse(inst.ToString()));
        var srv = Assert.IsType<SrvRecord>(back.Additionals[0]);
        Assert.Equal((1739, host, true), (srv.Port, srv.Target, srv.CacheFlush));
        var txt = Assert.IsType<TxtRecord>(back.Additionals[1]).Pairs();
        Assert.Equal(("9b16173d305cd15a", "slim ✓", ""), (txt["id"], txt["NAME"], txt["empty"]));
        Assert.Equal(IPAddress.Parse("fd00::1"), Assert.IsType<AddressRecord>(back.Additionals[3]).Address);
        // the instance's suffix was written once and pointed to afterwards
        Assert.True(bytes.Length < 250, $"{bytes.Length} bytes: names weren't compressed");
    }

    [Fact]
    public void Malformed_and_looping_messages_are_refused()
    {
        Assert.Null(DnsMessage.Parse([1, 2, 3]));
        // one answer whose name is a pointer to itself
        byte[] loop = [0, 0, 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 10, 0, 4, 1, 2, 3, 4];
        Assert.Null(DnsMessage.Parse(loop));
    }

    [Fact]
    public void The_collector_needs_every_record_and_hears_goodbyes()
    {
        var col = new ServiceCollector(Type);
        var inst = DnsName.Child("beta 0123ab", Type);
        var host = DnsName.Parse("droplet-0123abcd.local.");
        var a = new DnsMessage { IsResponse = true };
        a.Answers.Add(new PtrRecord(Type, 4500, inst));
        col.Add(a, IPAddress.Parse("10.0.0.7"));
        Assert.Empty(col.Complete());
        Assert.Contains(col.NextQuery().Questions, q => q.Name.Equals(inst) && q.Type == DnsType.Srv);
        var b = new DnsMessage { IsResponse = true };
        b.Answers.Add(new SrvRecord(inst, 120, true, 0, 0, 1740, host));
        b.Answers.Add(TxtRecord.FromPairs(inst, 4500, [new("id", "0123abcd")]));
        col.Add(b, IPAddress.Parse("10.0.0.7"));
        // no A record: the address the answers came from
        var found = Assert.Single(col.Complete());
        Assert.Equal([IPAddress.Parse("10.0.0.7")], found.Addresses);
        var bye = new DnsMessage { IsResponse = true };
        bye.Answers.Add(new PtrRecord(Type, 0, inst));
        col.Add(bye, null);
        Assert.Empty(col.Complete());
        Assert.Single(col.TakeGone());
    }

    [Fact]
    public void Peer_announcements_parse_as_the_reference()
    {
        var fp = new string('a', 64);
        ServiceInstance Inst(Dictionary<string, string> txt) => new()
        {
            Name = DnsName.Child("x 012345", Type), Port = 1739, Host = DnsName.Parse("h.local."),
            Addresses = [IPAddress.Parse("fe80::1"), IPAddress.Parse("192.168.1.9")], Txt = txt,
        };
        var p = PeerDirectory.Parse(Inst(new() { ["id"] = "0123ABCD", ["fp"] = fp.ToUpperInvariant(), ["v"] = "1", ["os"] = "plan9", ["caps"] = "media,input,bad cap", ["hub"] = "zz" }))!;
        Assert.Equal(("0123abcd", fp, "", "0123abcd", ""), (p.Id, p.Fp, p.Os, p.Name, p.Hub));
        Assert.Equal(["input", "media"], p.Caps);
        Assert.Equal(["192.168.1.9"], p.Addresses); // no IPv6 link-local
        Assert.Null(PeerDirectory.Parse(Inst(new() { ["id"] = "0123abcd", ["fp"] = "short", ["v"] = "1" })));
        Assert.Null(PeerDirectory.Parse(Inst(new() { ["id"] = "0123abcd", ["fp"] = fp })));
        Assert.Null(PeerDirectory.Parse(Inst(new() { ["id"] = "xyz", ["fp"] = fp, ["v"] = "1" })));
    }

    [Fact]
    public void Hub_announcements_parse_as_the_go_app()
    {
        var fp = new string('c', 64);
        ServiceInstance Inst(Dictionary<string, string> txt) => new()
        {
            Name = DnsName.Child("droplet-9b1617", HubBrowser.ServiceType), Port = 8443, Host = DnsName.Parse("t15.local."),
            Addresses = [IPAddress.Parse("192.168.100.20")], Txt = txt,
        };
        var h = HubAnnouncement.Parse(Inst(new() { ["id"] = "9B16173D305CD15A", ["fp"] = fp, ["name"] = "t15", ["http"] = "8000", ["ts"] = "https://t15.tail7375fe.ts.net/" }))!;
        Assert.Equal(("9b16173d305cd15a", "t15", 8000, "https://t15.tail7375fe.ts.net"), (h.Id, h.Name, h.HttpPort, h.Tailnet));
        Assert.Equal(["192.168.100.20:8443"], h.Endpoints);
        var loose = HubAnnouncement.Parse(Inst(new() { ["id"] = "9b16173d305cd15a", ["fp"] = fp, ["http"] = "x", ["ts"] = "http://insecure" }))!;
        Assert.Equal((0, ""), (loose.HttpPort, loose.Tailnet));
        Assert.Null(HubAnnouncement.Parse(Inst(new() { ["id"] = "9b16", ["fp"] = fp })));
        Assert.Null(HubAnnouncement.Parse(Inst(new() { ["id"] = "9b16173d305cd15a" })));
    }
}

public sealed class ConfigTests : IDisposable
{
    readonly string dir = TestDirs.Make("config");

    public void Dispose() => TestDirs.Remove(dir);

    [Fact]
    public void The_go_apps_config_is_imported_once_and_never_changed()
    {
        var go = Path.Combine(dir, "go", "config.json");
        Directory.CreateDirectory(Path.GetDirectoryName(go)!);
        const string text = """
            {"hub_url":"https://t15.tail7375fe.ts.net","hub":{"id":"9b16173d305cd15a","name":"t15","fingerprint":"3c10","pin_source":"tailnet","lan":["192.168.100.20:8443"],"http_port":8000,"tailnet":"https://t15.tail7375fe.ts.net"},
             "device_id":"8a0f12345678","device_name":"maryanne","device_token":"tok","download_dir":"D:\\dl","auto_download":false,"notify_files":true,
             "notify_messages":false,"ring_sound":true,"paused":false,"autostart":true,"remote_input":true,"remote_media":false,"remote_lock":true,
             "remote_screenshot":true,"clipboard_sync":true,"remote_paused":false,"chat_seen":{"abc":12.5},"inbox_seen":["a.txt|1"],"action_key":"k"}
            """;
        File.WriteAllText(go, text);
        var store = ConfigStore.Open(new AppPaths(Path.Combine(dir, "new")), go);
        var c = store.Get();
        Assert.Equal(("https://t15.tail7375fe.ts.net", "9b16173d305cd15a", "tailnet", "tok", "maryanne"), (c.HubUrl, c.Hub!.Id, c.Hub.PinSource, c.DeviceToken, c.DeviceName));
        Assert.Equal(["192.168.100.20:8443"], c.Hub.Lan);
        Assert.Equal((false, false, true, "k", 12.5), (c.AutoDownload, c.RemoteMedia, c.ClipboardSync, c.ActionKey, c.ChatSeen["abc"]));
        Assert.True(c.SendTo); // from before Send To was a setting, and let in: it keeps its entries
        Assert.True(c.Registered);
        Assert.Equal(go, c.ImportedFrom);
        Assert.True(c.Mesh.Enabled);
        Assert.Equal(text, File.ReadAllText(go));
        // later opens read the new file, not the Go one
        File.WriteAllText(go, "{}");
        Assert.Equal("tok", ConfigStore.Open(new AppPaths(Path.Combine(dir, "new")), go).Get().DeviceToken);
    }

    [Fact]
    public void A_fresh_config_has_safe_defaults_and_updates_are_saved_whole()
    {
        var store = ConfigStore.Open(new AppPaths(Path.Combine(dir, "fresh")), "");
        var c = store.Get();
        Assert.False(c.ClipboardSync); // sends everything copied: off until asked for
        Assert.True(c.RemoteInput && c.RemoteMedia && c.RemoteLock && c.RemoteScreenshot);
        Assert.False(c.Autostart || c.SendTo);
        Assert.Equal("", c.HubUrl); // no built-in hub
        Assert.Matches("^[0-9a-f]{32}$", c.ActionKey);
        Assert.False(store.Exists);
        store.Update(n => n.DeviceName = "pc");
        Assert.True(store.Exists);
        Assert.Equal("pc", ConfigStore.OpenFile(store.FilePath).Get().DeviceName);
        // a copy can't change the stored config
        store.Get().DeviceName = "changed";
        Assert.Equal("pc", store.Get().DeviceName);
        if (!OperatingSystem.IsWindows())
        {
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(store.FilePath));
        }
    }
}

public sealed class HubClientTests
{
    [Theory]
    [InlineData("t15.tail7375fe.ts.net", "https://t15.tail7375fe.ts.net/")]
    [InlineData(" http://192.168.1.2:8000/ ", "http://192.168.1.2:8000/")]
    [InlineData("https://hub.example/droplet/?x=1#y", "https://hub.example/droplet")]
    public void Hub_urls_are_normalised(string typed, string url) => Assert.Equal(url, HubClient.ParseUrl(typed).AbsoluteUri);

    [Theory]
    [InlineData("")]
    [InlineData("ftp://hub")]
    [InlineData("https://user:pw@hub")]
    public void Bad_hub_urls_are_refused(string typed) => Assert.Throws<FormatException>(() => HubClient.ParseUrl(typed));

    [Fact]
    public void Destinations_resolve_by_id_name_or_hub()
    {
        List<Device> devices = [new() { Id = "a1", Name = "Phone" }, new() { Id = "b2", Name = "slim", Self = true }];
        Assert.Equal(("hub", "the hub"), HubClient.Resolve("HUB", devices));
        Assert.Equal(("a1", "Phone"), HubClient.Resolve("phone", devices));
        Assert.Equal(("a1", "Phone"), HubClient.Resolve("a1", devices));
        Assert.Contains("hub, Phone", Assert.Throws<KeyNotFoundException>(() => HubClient.Resolve("tv", devices)).Message, StringComparison.Ordinal);
    }

    [Fact]
    public void Short_hub_names()
    {
        using var a = new HubClient("https://t15.tail7375fe.ts.net");
        using var b = new HubClient("http://192.168.1.2:8000");
        Assert.Equal(("t15", "192.168.1.2"), (a.HubName, b.HubName));
    }
}

public sealed class RouteSelectionTests
{
    const string Id = "9b16173d305cd15a";
    static readonly string Fp = new('f', 64);
    static readonly string Other = new('e', 64);

    sealed class FakeNet
    {
        public Dictionary<string, (string Id, string Fp)> Lan { get; } = [];
        public HubInfo? Remote { get; set; }
        public List<HubAnnouncement> Announced { get; } = [];
        public List<string> Calls { get; } = [];

        public RouteDeps Deps => new()
        {
            Browse = async (_, found, _) =>
            {
                await Task.Yield();
                foreach (var h in Announced)
                {
                    if (found?.Invoke(h) == true)
                    {
                        break;
                    }
                }
                return Announced;
            },
            Info = async (url, pin, ct) =>
            {
                await Task.Yield();
                lock (Calls)
                {
                    Calls.Add(url);
                }
                if (pin is null)
                {
                    return Remote ?? throw new HttpRequestException("no route to host");
                }
                var addr = url["https://".Length..];
                if (!Lan.TryGetValue(addr, out var hub))
                {
                    await Task.Delay(Timeout.Infinite, ct);
                }
                if (hub.Fp != pin)
                {
                    throw new PinMismatchException(pin, hub.Fp);
                }
                return new HubInfo { Id = hub.Id, Fingerprint = hub.Fp };
            },
        };
    }

    static readonly RouteOptions Quick = new() { LanTimeout = TimeSpan.FromMilliseconds(300), BrowseWait = TimeSpan.FromMilliseconds(200) };

    static HubAnnouncement Announce(string addr, string fp, string id = Id) => new()
    {
        Instance = "droplet", Port = int.Parse(addr.Split(':')[1], System.Globalization.CultureInfo.InvariantCulture),
        Addresses = [IPAddress.Parse(addr.Split(':')[0])], Id = id, Fingerprint = fp,
    };

    [Fact]
    public async Task The_lan_wins_from_a_remembered_address_or_mdns()
    {
        var net = new FakeNet { Remote = new HubInfo { Id = Id, Fingerprint = Fp } };
        net.Lan["10.0.0.9:8443"] = (Id, Fp);
        var res = await RouteSelector.SelectAsync(new HubTarget(Id, Fp, ["10.0.0.9:8443"], "https://t15.ts.net"), net.Deps, Quick);
        Assert.Equal((RouteKind.Lan, "https://10.0.0.9:8443", Fp), (res.Route.Kind, res.Route.Base, res.Route.Pin));
        // the address moved (DHCP): mDNS finds it by the hub's id
        net.Lan.Clear();
        net.Lan["10.0.0.23:8443"] = (Id, Fp);
        net.Announced.Add(Announce("10.0.0.50:8443", Fp, "0000000000000000")); // someone else's hub
        net.Announced.Add(Announce("10.0.0.23:8443", Fp));
        res = await RouteSelector.SelectAsync(new HubTarget(Id, Fp, ["10.0.0.9:8443"], "https://t15.ts.net"), net.Deps, Quick);
        Assert.Equal("10.0.0.23:8443", res.Route.Addr);
        Assert.DoesNotContain("https://10.0.0.50:8443", net.Calls);
    }

    [Fact]
    public async Task No_lan_falls_back_to_the_remote_route()
    {
        var net = new FakeNet { Remote = new HubInfo { Id = Id, Fingerprint = Fp } };
        var res = await RouteSelector.SelectAsync(new HubTarget(Id, Fp, [], "https://t15.ts.net"), net.Deps, Quick);
        Assert.Equal((RouteKind.Remote, "https://t15.ts.net", "via Tailscale"), (res.Route.Kind, res.Route.Base, res.Route.Label));
        Assert.Null(res.Changed);
        // a remote URL that's another hub is no route
        net.Remote = new HubInfo { Id = "1111111111111111" };
        var e = await Assert.ThrowsAsync<UnreachableException>(() => RouteSelector.SelectAsync(new HubTarget(Id, Fp, [], "https://t15.ts.net"), net.Deps, Quick));
        Assert.Contains("different hub", e.Detail, StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_changed_certificate_is_reported_never_used_and_verified_over_the_tailnet()
    {
        var net = new FakeNet { Remote = new HubInfo { Id = Id, Fingerprint = Other } };
        net.Lan["10.0.0.9:8443"] = (Id, Other);
        var res = await RouteSelector.SelectAsync(new HubTarget(Id, Fp, ["10.0.0.9:8443"], "https://t15.ts.net"), net.Deps, Quick);
        Assert.Equal(RouteKind.Remote, res.Route.Kind);
        Assert.Equal(new IdentityChange(Fp, Other, "10.0.0.9:8443", Verified: true), res.Changed);
        net.Remote = null;
        var e = await Assert.ThrowsAsync<UnreachableException>(() => RouteSelector.SelectAsync(new HubTarget(Id, Fp, ["10.0.0.9:8443"], "https://t15.ts.net"), net.Deps, Quick));
        Assert.Equal(Other, e.Changed!.Got);
        Assert.False(e.Changed.Verified);
    }

    [Fact]
    public async Task The_manager_keeps_a_route_and_chooses_again_when_it_is_lost()
    {
        var net = new FakeNet();
        net.Lan["10.0.0.9:8443"] = (Id, Fp);
        var target = new HubTarget(Id, Fp, ["10.0.0.9:8443"], "");
        var routes = new RouteManager(() => target, net.Deps, Quick);
        var changes = new List<Route?>();
        routes.RouteChanged += changes.Add;
        var r = await routes.EnsureAsync();
        Assert.Same(r, await routes.EnsureAsync());
        routes.Lost(r);
        Assert.Null(routes.Current);
        Assert.Equal(r, await routes.EnsureAsync());
        Assert.Equal([r, null, r], changes);
        // no hub at all
        var none = new RouteManager(() => new HubTarget("", "", [], ""), net.Deps, Quick);
        await Assert.ThrowsAsync<NotPairedException>(() => none.EnsureAsync());
        // a hub from before local-first: its URL as it is
        var old = new RouteManager(() => new HubTarget("", "", [], "https://old.example"), net.Deps, Quick);
        Assert.Equal(new Route(RouteKind.Remote, "https://old.example"), await old.EnsureAsync());
    }

    [Fact]
    public void What_a_selection_learns_is_remembered_but_a_new_pin_never_is()
    {
        var h = new HubIdentity { Id = Id, Fingerprint = Fp, Lan = ["10.0.0.1:8443", "junk", "10.0.0.2:8443"] };
        var info = new HubInfo { Id = Id, Name = "t15", Fingerprint = Other, Tailnet = "https://t15.ts.net", Lan = new HubLanInfo { Addresses = ["10.0.0.3"], HttpPort = 8000, HttpsPort = 8443 } };
        Assert.True(HubIdentities.Remember(h, new SelectResult(new Route(RouteKind.Lan, "https://10.0.0.9:8443", "10.0.0.9:8443", Fp), info, null), ""));
        Assert.Equal(["10.0.0.9:8443", "10.0.0.3:8443", "10.0.0.1:8443", "10.0.0.2:8443"], h.Lan);
        Assert.Equal((Fp, "t15", 8000, "https://t15.ts.net"), (h.Fingerprint, h.Name, h.HttpPort, h.Tailnet));
        // a first pin comes only from verified TLS (an https remote route)
        var fresh = new HubIdentity { Id = Id };
        HubIdentities.Remember(fresh, new SelectResult(new Route(RouteKind.Remote, "http://10.0.0.3:8000"), info, null), "http://10.0.0.3:8000");
        Assert.Null(fresh.Fingerprint);
        HubIdentities.Remember(fresh, new SelectResult(new Route(RouteKind.Remote, "https://t15.ts.net"), info, null), "https://t15.ts.net");
        Assert.Equal((Other, PinSources.Tailnet), (fresh.Fingerprint, fresh.PinSource));
    }

    [Fact]
    public void An_announcement_is_tied_to_a_url_by_tailnet_or_address()
    {
        var h = Announce("192.168.1.20:8443", Fp) with { HttpPort = 8000, Tailnet = "https://t15.ts.net" };
        Assert.True(HubIdentities.TiedTo(h, new Uri("https://t15.ts.net/")));
        Assert.True(HubIdentities.TiedTo(h, new Uri("http://192.168.1.20:8000")));
        Assert.False(HubIdentities.TiedTo(h, new Uri("http://192.168.1.20:9999")));
        Assert.False(HubIdentities.TiedTo(h, new Uri("http://192.168.1.21:8000")));
    }
}

public sealed class JoinStateTests
{
    [Fact]
    public void Answers_about_a_join_request()
    {
        Assert.Equal((JoinState.Pending, "7156"), JoinRequest.Evaluate(new Me { Device = new Device { Id = "a", Pending = true, Code = "7156" } }, null));
        Assert.Equal((JoinState.Approved, null), JoinRequest.Evaluate(new Me { Device = new Device { Id = "a" } }, null));
        Assert.Equal((JoinState.Declined, null), JoinRequest.Evaluate(new Me { Device = null }, null));
        Assert.Equal((JoinState.Unknown, null), JoinRequest.Evaluate(null, new HttpRequestException("down")));
    }

    [Fact]
    public async Task A_hiccup_doesnt_change_where_it_stands()
    {
        var answers = new Queue<Func<Me>>([
            () => new Me { Device = new Device { Pending = true, Code = "1111" } },
            () => throw new HttpRequestException("wifi blip"),
            () => new Me { Device = new Device { Pending = true, Code = "1111" } },
            () => new Me { Device = new Device { Id = "x" } },
        ]);
        var seen = new List<(JoinState, string?)>();
        var end = await JoinRequest.WaitAsync(_ => Task.FromResult(answers.Dequeue()()), TimeSpan.FromMilliseconds(1), (s, c) => seen.Add((s, c)));
        Assert.Equal(JoinState.Approved, end);
        Assert.Equal([(JoinState.Pending, "1111"), (JoinState.Approved, null)], seen);
    }

    [Fact]
    public void Link_codes_and_names_are_checked()
    {
        Assert.Equal("123456", HubSetup.LinkDigits(" 123 456 "));
        Assert.Equal("link_code", Assert.Throws<FieldException>(() => HubSetup.LinkDigits("12345")).Field);
        Assert.Equal("my pc", HubSetup.CleanDeviceName("  my   pc "));
        Assert.Equal("name", Assert.Throws<FieldException>(() => HubSetup.CleanDeviceName("  ")).Field);
    }
}

public sealed class ClipboardSyncTests
{
    [Fact]
    public void Only_new_settled_text_is_sent_at_most_once_a_second_and_never_echoed()
    {
        var sync = new ClipboardSync();
        var t0 = DateTimeOffset.UnixEpoch;
        (bool, string) Text(string s) => (true, s);
        Assert.Null(sync.Observe(1, () => Text("already there"), t0)); // what's there at the start isn't news
        Assert.Null(sync.Observe(2, () => Text("new"), t0.AddMilliseconds(100)));
        Assert.Null(sync.Observe(2, () => Text("new"), t0.AddMilliseconds(300))); // settling
        Assert.Equal("new", sync.Observe(2, () => Text("new"), t0.AddMilliseconds(600)));
        Assert.Null(sync.Observe(3, () => Text("newer"), t0.AddMilliseconds(700)));
        Assert.Null(sync.Observe(3, () => Text("newer"), t0.AddMilliseconds(1200))); // a second since the last
        Assert.Equal("newer", sync.Observe(3, () => Text("newer"), t0.AddMilliseconds(1700)));
        sync.Applying("from the phone");
        Assert.Null(sync.Observe(4, () => Text("from the phone"), t0.AddSeconds(5))); // our own write coming back
        Assert.Null(sync.Observe(5, () => (false, ""), t0.AddSeconds(6))); // a secret, or not text
        Assert.Null(sync.Observe(6, () => Text(new string('x', ClipboardSync.MaxBytes + 1)), t0.AddSeconds(7)));
        Assert.Null(sync.Observe(6, () => Text("x"), t0.AddSeconds(9)));
    }
}

public sealed class DispatcherTests
{
    sealed class Source : IRemoteSource
    {
        public List<JsonObject> Replies { get; } = [];
        public List<(string Name, byte[] Data, string Mime, string To)> Files { get; } = [];
        public string Kind => "peer";

        public Task<bool> ReplyAsync(JsonObject message)
        {
            Replies.Add(message);
            return Task.FromResult(true);
        }

        public Task DeliverFileAsync(string name, byte[] data, string mime, string to, CancellationToken ct = default)
        {
            Files.Add((name, data, mime, to));
            return Task.CompletedTask;
        }
    }

    static JsonObject From(JsonObject m)
    {
        m["from"] = new JsonObject { ["id"] = "p1", ["name"] = "Phone" };
        return m;
    }

    [Fact]
    public async Task Only_switched_on_capabilities_the_platform_has_are_acted_on()
    {
        var fakes = new FakePlatform();
        var on = new HashSet<string> { Caps.Input, Caps.Screenshot };
        await using var d = new RemoteDispatcher(fakes.Services with { Lock = null }, () => on, () => "my pc");
        Assert.Equal([Caps.Input, Caps.Screenshot], d.Offered());
        var src = new Source();
        await d.DispatchAsync(From(new JsonObject { ["t"] = "input", ["ev"] = new JsonArray(new JsonObject { ["k"] = "scroll", ["dy"] = 1.5 }) }), src);
        await d.DispatchAsync(From(new JsonObject { ["t"] = "media", ["action"] = "next" }), src);   // switched off
        await d.DispatchAsync(From(new JsonObject { ["t"] = "cmd", ["cmd"] = "lock" }), src);        // no lock here
        await d.DispatchAsync(From(new JsonObject { ["t"] = "clip", ["text"] = "x" }), src);         // switched off
        await d.DispatchAsync(From(new JsonObject { ["t"] = "cmd", ["cmd"] = "screenshot" }), src);
        await d.DispatchAsync(From(new JsonObject { ["t"] = "rpc", ["id"] = "r7", ["method"] = "files.list" }), src);
        await Wait.For(() => src.Files.Count == 1 && !fakes.Input.Applied.IsEmpty, 5);
        Assert.Equal(new InputEvent("scroll", 0, 1.5, Mods: []), fakes.Input.Applied.Single()[0] with { Mods = [] });
        Assert.Empty(fakes.Media.Commands);
        Assert.Empty(fakes.Clipboard.Written);
        var (name, data, mime, to) = src.Files[0];
        Assert.Matches(@"^screenshot-my-pc-\d{8}-\d{6}\.png$", name);
        Assert.Equal(("image/png", "p1"), (mime, to));
        Assert.Equal(FakeScreenshot.Png, data);
        var rpc = Assert.Single(src.Replies);
        Assert.Equal(("rpc-result", "r7"), (rpc.Str("t"), rpc.Str("id")));
        Assert.Contains("files.list", rpc.Str("error"), StringComparison.Ordinal);
    }

    [Fact]
    public async Task Clipboard_from_another_device_is_written_and_not_sent_back()
    {
        var fakes = new FakePlatform();
        await using var d = new RemoteDispatcher(fakes.Services, () => DotNetPeer.AllCaps, () => "pc");
        d.Clip.Observe(fakes.Clipboard.Sequence, () => (false, ""), DateTimeOffset.UtcNow);
        await d.DispatchAsync(From(new JsonObject { ["t"] = "clip", ["text"] = "from the phone" }), new Source());
        await Wait.For(() => fakes.Clipboard.Written.Contains("from the phone"), 5);
        Assert.Null(d.Clip.Observe(fakes.Clipboard.Sequence, () => fakes.Clipboard.TryReadText(out var t) ? (true, t) : (false, ""), DateTimeOffset.UtcNow.AddSeconds(5)));
    }

    [Fact]
    public void Media_messages_parse_numbers_and_flags()
    {
        Assert.Equal(new MediaCommand("volume", 0.5, null, null), RemoteDispatcher.ParseMedia(new JsonObject { ["action"] = "volume", ["value"] = 0.5 }));
        Assert.Equal(new MediaCommand("mute", null, true, "spotify"), RemoteDispatcher.ParseMedia(new JsonObject { ["action"] = "mute", ["value"] = true, ["player"] = "spotify" }));
        Assert.Equal(new MediaCommand("mute", null, null, null), RemoteDispatcher.ParseMedia(new JsonObject { ["action"] = "mute" }));
    }
}

public sealed class NewSinceTests
{
    [Fact]
    public void Inbox_files_are_announced_once_complete_and_once_only()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_000_000);
        List<HubFile> inbox =
        [
            new() { Name = "b.jpg", Mtime = 999_990, From = "Phone" },
            new() { Name = "a.txt", Mtime = 999_900, From = "Phone" },
            new() { Name = "uploading.bin", Mtime = 999_995 },  // no sender yet, too new
            new() { Name = "old.bin", Mtime = 999_000 },         // no sender, but settled
        ];
        var (fresh, seen) = NewSince.Inbox(inbox, ["a.txt|999900", "gone.txt|1"], now);
        Assert.Equal(["old.bin", "b.jpg"], fresh.Select(f => f.Name));
        Assert.Equal(["b.jpg|999990", "a.txt|999900", "old.bin|999000"], seen);
    }

    [Fact]
    public void Messages_are_the_unread_ones_newer_than_the_last_shown()
    {
        List<ChatMessage> thread =
        [
            new() { From = "p", Text = "1", Ts = 1 }, new() { From = "me", Text = "x", Ts = 2 },
            new() { From = "p", Text = "2", Ts = 3 }, new() { From = "p", Text = "3", Ts = 4 },
        ];
        var (fresh, seen) = NewSince.Messages(thread, "p", 2, 1);
        Assert.Equal(["2", "3"], fresh.Select(m => m.Text));
        Assert.Equal(4, seen);
        Assert.Empty(NewSince.Messages(thread, "p", 5, 4).Fresh);
    }

    [Fact]
    public void Sizes_read_as_people_write_them() =>
        Assert.Equal(["512 B", "1.5 KB", "20 MB"], new long[] { 512, 1536, 20L << 20 }.Select(HubPoller.HumanSize));
}

[Collection(nameof(Interop.InteropCollection))]
public sealed class MdnsLoopbackTests
{
    [Fact]
    public async Task The_responder_answers_the_browsers_legacy_unicast_queries()
    {
        var lan = Common.Addresses.Lan().Where(a => !a.Contains(':', StringComparison.Ordinal)).ToList();
        Assert.SkipWhen(lan.Count == 0 || MdnsNetwork.Interfaces().Count == 0, "no LAN interface to try mDNS on");
        var type = DnsName.Parse("_droplet-test._tcp.local.");
        await using var responder = new MdnsResponder();
        Assert.SkipUnless(responder.Start(), "port 5353 can't be shared here");
        var id = Common.Hex.Random(4);
        await responder.AnnounceAsync(new ServiceAnnouncement
        {
            ServiceType = type, Instance = $"test {id}", Host = DnsName.Parse($"droplet-test-{id}.local."), Port = 4242,
            Txt = [new("id", id), new("hello", "wörld")], Addresses = [IPAddress.Parse(lan[0])],
        });
        var found = await new MdnsBrowser().BrowseAsync(type, TimeSpan.FromSeconds(3), i => i.Txt.GetValueOrDefault("id") == id);
        var inst = Assert.Single(found, i => i.Txt.GetValueOrDefault("id") == id);
        Assert.Equal((4242, "wörld", $"test {id}"), (inst.Port, inst.Txt["hello"], inst.Label));
        Assert.Contains(IPAddress.Parse(lan[0]), inst.Addresses);
    }
}
