using System.Text.Json.Nodes;
using Droplet.Core.Common;
using Droplet.Core.Config;
using Droplet.Core.Hub;
using Droplet.Core.LocalFirst;
using Droplet.Core.Mesh;
using Droplet.Core.Remote;
using Droplet.Core.Tests.Support;

namespace Droplet.Core.Tests.Interop;

/// <summary>
/// The .NET app's whole stack (config, route manager, live connection, roster, mesh)
/// against a throwaway hub (<c>app.py</c>) and the Linux agent: the hub vouches for both
/// through its roster, and the mesh keeps working with the hub stopped. Plus local-first:
/// finding the hub over mDNS, pinning its LAN certificate, and joining over the LAN.
/// </summary>
[Collection(nameof(InteropCollection))]
public sealed class HubInteropTests : IAsyncLifetime
{
    const int HubPort = 8891;
    const int HubTlsPort = 8892;
    string root = "";

    public ValueTask InitializeAsync()
    {
        Assert.SkipWhen(Reference.Unavailable is not null, Reference.Unavailable ?? "");
        Assert.SkipWhen(LocalHub.Python is null, "no Python with the hub's packages (set DROPLET_HUB_PYTHON)");
        root = TestDirs.Make("hub");
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync()
    {
        TestDirs.Remove(root);
        return ValueTask.CompletedTask;
    }

    /// <summary>The .NET app as the Windows shell runs it (<see cref="DropletEngine"/>), with fake platform services.</summary>
    sealed class App : IAsyncDisposable
    {
        public required DropletEngine Engine { get; init; }
        public required FakePlatform Fakes { get; init; }
        public required TestLog Log { get; init; }
        public MeshNode Node => Engine.Mesh!;
        public HubLiveSession Live => Engine.Live;
        public RouteManager Routes => Engine.Routes;
        public string Downloads { get; init; } = "";

        /// <summary>Registers on the hub from this machine (loopback is trusted: no approval) and starts.</summary>
        public static async Task<(App App, Device Device, HubInfo Info)> JoinAndStartAsync(string dir, LocalHub hub, string name)
        {
            HubInfo info;
            using (var anon = new HubClient(hub.Url))
            {
                info = await anon.HubInfoAsync();
            }
            using var reg = new HubClient(hub.Url);
            var dev = await reg.RegisterAsync(name);
            Assert.False(dev.Pending);
            var paths = new AppPaths(dir);
            var store = ConfigStore.Open(paths, "");
            var downloads = Path.Combine(dir, "Downloads");
            store.Update(c =>
            {
                c.HubUrl = hub.Url;
                c.Hub = HubIdentities.FromInfo(info, PinSources.Lan, hub.Url);
                (c.DeviceToken, c.DeviceId, c.DeviceName) = (reg.Token, dev.Id, dev.Name);
                c.DownloadDir = downloads;
                c.ClipboardSync = true;
            });
            var log = new TestLog();
            var fakes = new FakePlatform();
            var engine = await DropletEngine.StartAsync(new EngineOptions
            {
                Paths = paths, Store = store, Services = fakes.Services, LoggerFactory = log, MeshRetry = TimeSpan.FromSeconds(2),
                App = "droplet-windows/test",
            });
            await engine.Routes.EnsureAsync();
            return (new App { Engine = engine, Fakes = fakes, Log = log, Downloads = downloads }, dev, info);
        }

        public ValueTask DisposeAsync() => Engine.DisposeAsync();
    }

    static Task SendAsync(System.Net.WebSockets.ClientWebSocket ws, JsonObject msg) =>
        ws.SendAsync(Json.ToUtf8(msg), System.Net.WebSockets.WebSocketMessageType.Text, true, default);

    static async Task<bool> Up(LocalHub hub)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(2) };
        try
        {
            return (await http.GetAsync($"{hub.Url}/api/hub/info")).IsSuccessStatusCode;
        }
        catch (HttpRequestException)
        {
            return false;
        }
        catch (TaskCanceledException)
        {
            return false;
        }
    }

    [Fact]
    public async Task The_roster_vouches_for_both_and_the_mesh_keeps_working_without_the_hub()
    {
        await using var hub = new LocalHub(HubPort, HubTlsPort);
        await hub.StartAsync();

        var (app, dev, info) = await App.JoinAndStartAsync(Path.Combine(root, "n"), hub, "dotnet-net");
        await using var _ = app;
        // the pinned LAN route wins over the plain URL
        Assert.Equal(RouteKind.Lan, app.Routes.Current!.Kind);
        await Wait.For(() => app.Live.IsLive, 20, "the live connection\n" + app.Log.Text);

        // the agent links to the same hub
        await using var a = new LinuxAgent("alpha");
        var setup = await a.CliAsync("setup", "--hub", hub.Url, "--name", "alpha-net");
        Assert.True(setup.Code == 0, setup.Output);
        var aDevice = Json.ParseObject(File.ReadAllText(a.ConfigFile))!;
        var aId = aDevice["device"]!["id"]!.GetValue<string>();
        var aToken = aDevice.Str("token")!;
        await a.StartAsync();

        // the roster arrives: they trust each other with no pairing, and the ids are the hub's
        await Wait.For(() => app.Node.Trust.Get(a.Fingerprint)?.Source == TrustSource.Roster, 40, "the .NET app to trust the agent\n" + app.Log.Text);
        await Wait.For(() => a.Trust()[app.Node.Identity.Fingerprint] is JsonObject e && e.Str("source") == "roster", 40, "the agent to trust the .NET app\n" + a.Log);
        Assert.Equal(aId, app.Node.Trust.Get(a.Fingerprint)!.Id);
        Assert.Equal(dev.Id, app.Node.PeerId);
        Assert.Equal(info.Id, app.Node.Trust.Get(a.Fingerprint)!.Hub);

        // direct, not through the hub
        var job = app.Node.SendText(a.Fingerprint, "roster hello");
        Assert.Equal("lan", (await app.Node.WaitJobAsync(job.Id, TimeSpan.FromSeconds(30)))!.Route);

        // clipboard sync: a copy here reaches the agent (over the hub and the open link)
        app.Fakes.Clipboard.Copy("copied on windows");
        await Wait.For(() => a.Log.Contains("would set 17 characters: 'copied on windows'", StringComparison.Ordinal), 10, "the agent's clipboard");

        // remote control through the hub's /ws reaches the same dispatch as over a link:
        // a controller (the agent's device, as a browser would be) sends input to this PC
        await Wait.For(() => app.Live.IsOnline(aId), 20, "the hub's presence to list the agent");
        using (var controller = new System.Net.WebSockets.ClientWebSocket())
        {
            controller.Options.SetRequestHeader("Authorization", "Bearer " + aToken);
            await controller.ConnectAsync(new Uri($"ws://127.0.0.1:{HubPort}/ws"), default);
            await SendAsync(controller, new JsonObject { ["t"] = "hello", ["caps"] = new JsonArray(), ["platform"] = "web", ["app"] = "test" });
            await SendAsync(controller, new JsonObject
            {
                ["t"] = "input", ["to"] = dev.Id, ["ev"] = new JsonArray(new JsonObject { ["k"] = "key", ["key"] = "F7" }),
            });
            await Wait.For(() => app.Fakes.Input.Applied.Any(e => e.Any(x => x.Key == "F7")), 10, "input through the hub");
            await controller.CloseAsync(System.Net.WebSockets.WebSocketCloseStatus.NormalClosure, "", default);
        }

        // the peer is off and the hub is up: text goes to the hub's mailbox; live input doesn't queue
        await a.StopAsync();
        await Wait.For(() => !app.Live.IsOnline(aId), 20, "the hub to see the agent gone");
        job = app.Node.SendText(a.Fingerprint, "for the mailbox");
        var mailbox = await app.Node.WaitJobAsync(job.Id, TimeSpan.FromSeconds(30));
        Assert.Equal((JobState.Done, "hub-mailbox"), (mailbox!.State, mailbox.Route));
        using (var asAgent = new HubClient(hub.Url, aToken))
        {
            Assert.Contains(await asAgent.ChatAsync(dev.Id), m => m.Text == "for the mailbox");
        }
        await Assert.ThrowsAsync<NoRouteException>(() => app.Node.SendLiveAsync(a.Fingerprint, new JsonObject { ["t"] = "input", ["ev"] = new JsonArray() }));
        await a.StartAsync();

        // the hub goes down: direct keeps working, both ways
        await hub.StopAsync();
        await Wait.For(async () => !await Up(hub), 10, "the hub to stop");
        await Wait.For(() => !app.Live.IsLive, 60, "the live connection to notice");
        job = app.Node.SendText(a.Fingerprint, "no hub needed");
        var direct = await app.Node.WaitJobAsync(job.Id, TimeSpan.FromSeconds(30));
        Assert.Equal((JobState.Done, "lan"), (direct!.State, direct.Route));
        Assert.Contains(a.Chat(), m => m.Str("body") == "no hub needed");
        var small = Files.Random(root, "small.bin", 3);
        job = app.Node.SendFile(a.Fingerprint, small);
        Assert.Equal(JobState.Done, (await app.Node.Outbox.WaitAsync(job.Id, j => j.State is JobState.Done or JobState.Failed, TimeSpan.FromSeconds(60)))!.State);
        Assert.Equal(Files.Sha256(small), Files.Sha256(Path.Combine(a.Downloads, "small.bin")));
        var back = await a.CallAsync(new JsonObject { ["cmd"] = "text", ["peer"] = dev.Id, ["body"] = "linux, no hub", ["wait"] = 20 });
        Assert.Equal("done", back.Str("state"));
        await a.CallAsync(new JsonObject
        {
            ["cmd"] = "send", ["peer"] = dev.Id, ["msg"] = new JsonObject { ["t"] = "input", ["ev"] = new JsonArray(new JsonObject { ["k"] = "key", ["key"] = "F5" }) },
        });
        await Wait.For(() => app.Fakes.Input.Applied.Any(e => e.Any(x => x.Key == "F5")), 5, "input from the agent, with no hub");

        // both off: kept in the outbox, and delivered directly when the peer is back
        await a.StopAsync();
        job = app.Node.SendText(a.Fingerprint, "kept in the outbox");
        var waiting = await app.Node.WaitJobAsync(job.Id, TimeSpan.FromSeconds(30));
        Assert.Equal(JobState.Queued, waiting!.State);
        await a.StartAsync();
        var delivered = await app.Node.Outbox.WaitAsync(job.Id, j => j.State == JobState.Done, TimeSpan.FromSeconds(60));
        Assert.Equal((JobState.Done, "lan"), (delivered!.State, delivered.Route));
        Assert.Contains(a.Chat(), m => m.Str("body") == "kept in the outbox");
    }

    [Fact]
    public async Task The_poll_loop_saves_the_inbox_shows_messages_and_rings()
    {
        await using var hub = new LocalHub(HubPort, HubTlsPort);
        await hub.StartAsync();
        var (app, dev, _) = await App.JoinAndStartAsync(Path.Combine(root, "p"), hub, "dotnet-poll");
        await using var _ = app;
        using var sender = new HubClient(hub.Url);
        await sender.RegisterAsync("sender");

        // a file for this PC: saved to the download folder, then removed from the hub
        await sender.UploadDataAsync(dev.Id, "note.txt", "hello"u8.ToArray(), "text/plain");
        app.Engine.Poller.Poke();
        var saved = Path.Combine(app.Downloads, "note.txt");
        await Wait.For(() => File.Exists(saved), 20, "the inbox file to be saved\n" + app.Log.Text);
        Assert.Equal("hello", await File.ReadAllTextAsync(saved));
        Assert.Contains(app.Fakes.Notifications.Shown, n => n.Title == "sender sent note.txt" && n.Buttons.Count == 2);
        using (var asApp = new HubClient(hub.Url, app.Engine.Store.Get().DeviceToken))
        {
            await Wait.For(async () => (await asApp.FilesAsync()).Inbox.Count == 0, 10, "the hub's inbox to be emptied");
        }
        Assert.Contains("note.txt|", Assert.Single(app.Engine.Store.Get().InboxSeen), StringComparison.Ordinal);

        // a message: shown once
        await sender.SendTextAsync(dev.Id, "hi from sender");
        app.Engine.Poller.Poke();
        await Wait.For(() => app.Fakes.Notifications.Shown.Any(n => n.Title == "sender" && n.Body == "hi from sender"), 20, "the message");
        app.Engine.Poller.Poke();
        await Task.Delay(1000);
        Assert.Single(app.Fakes.Notifications.Shown, n => n.Body == "hi from sender");

        // a ring: sounds until it's stopped elsewhere
        await sender.RingDeviceAsync(dev.Id);
        app.Engine.Poller.Poke();
        await Wait.For(() => app.Fakes.Sound.Rings == 1, 20, "the ring");
        Assert.Equal("sender", app.Engine.Poller.Status.RingingFrom);
        await sender.StopRingDeviceAsync(dev.Id);
        await Wait.For(() => app.Fakes.Sound.Stops == 1, 20, "the ring to stop");
        Assert.Null(app.Engine.Poller.Status.RingingFrom);
    }

    [Fact]
    public async Task Local_first_finds_the_hub_pins_its_certificate_and_joins_over_the_lan()
    {
        await using var hub = new LocalHub(HubPort, HubTlsPort);
        await hub.StartAsync();
        HubInfo info;
        using (var anon = new HubClient(hub.Url))
        {
            info = await anon.HubInfoAsync();
        }
        var fp = Fingerprint.Normalize(info.Fingerprint)!;
        var endpoint = Assert.Single(info.LanEndpoints());

        // mDNS finds it, with the id and fingerprint it reports
        var found = await new HubBrowser().BrowseAsync(TimeSpan.FromSeconds(3), h => h.Id == info.Id);
        var announced = Assert.Single(found, h => h.Id == info.Id);
        Assert.Equal(fp, announced.Fingerprint);
        Assert.Contains(endpoint, announced.Endpoints);

        // pinned: the right fingerprint connects; any other is refused and reports what it met
        Assert.Equal(info.Id, (await RouteDeps.InfoAsync("https://" + endpoint, fp, default)).Id);
        var mismatch = await Assert.ThrowsAsync<PinMismatchException>(() => RouteDeps.InfoAsync("https://" + endpoint, new string('a', 64), default));
        Assert.Equal(fp, mismatch.Got);
        // and without a pin, the self-signed certificate fails normal validation
        await Assert.ThrowsAsync<HttpRequestException>(() => RouteDeps.InfoAsync("https://" + endpoint, null, default));

        // route selection finds it over mDNS alone (no remembered address, no tailnet)
        var res = await RouteSelector.SelectAsync(new HubTarget(info.Id, fp, [], ""), RouteDeps.Default(), new RouteOptions());
        Assert.Equal(RouteKind.Lan, res.Route.Kind);
        Assert.Equal(endpoint, res.Route.Addr);
        // a pin that doesn't match is an identity change, never used
        var changed = await Assert.ThrowsAsync<UnreachableException>(() =>
            RouteSelector.SelectAsync(new HubTarget(info.Id, new string('b', 64), [endpoint], ""), RouteDeps.Default(), new RouteOptions()));
        Assert.Equal(fp, changed.Changed?.Got);

        // joining over the LAN: pending with a code, then let in from a trusted device
        var store = ConfigStore.OpenFile(Path.Combine(root, "j1", "config.json"));
        var routes = new RouteManager(() => HubTarget.Of(store.Get()));
        var setup = new HubSetup(store, routes);
        var joined = await setup.JoinAsync(info.Id, "net-joiner");
        Assert.True(joined.Pending);
        Assert.Matches("^[0-9]{4}$", joined.Code);
        var cfg = store.Get();
        Assert.True(cfg.Pending);
        Assert.Equal((info.Id, fp, PinSources.Lan), (cfg.Hub!.Id, cfg.Hub.Fingerprint, cfg.Hub.PinSource));
        Assert.Equal(JoinState.Pending, await setup.PollJoinAsync());
        Assert.Equal(joined.Code, store.Get().PairCode);
        using (var trustedDevice = new HubClient(hub.Url))
        {
            await trustedDevice.ApproveDeviceAsync(cfg.DeviceId!);
        }
        var states = new List<JoinState>();
        Assert.Equal(JoinState.Approved, await setup.WaitForJoinAsync((s, _) => states.Add(s)));
        Assert.True(store.Get().Registered);
        using (var c = routes.Current!.Client(store.Get().DeviceToken, null))
        {
            Assert.NotNull((await c.FilesAsync()).Self); // the token works now
        }

        // a request that's refused (the device removed) is declined, and its token forgotten
        var store2 = ConfigStore.OpenFile(Path.Combine(root, "j2", "config.json"));
        var setup2 = new HubSetup(store2, new RouteManager(() => HubTarget.Of(store2.Get())));
        Assert.True((await setup2.JoinAsync(info.Id, "net-joiner-2")).Pending);
        using (var trustedDevice = new HubClient(hub.Url))
        {
            await trustedDevice.RemoveDeviceAsync(store2.Get().DeviceId!);
        }
        Assert.Equal(JoinState.Declined, await setup2.PollJoinAsync());
        Assert.Null(store2.Get().DeviceToken);
        Assert.False(store2.Get().Pending);
    }
    [Fact]
    public async Task Setting_up_by_address_registers_renames_and_refuses_a_taken_name()
    {
        await using var hub = new LocalHub(HubPort, HubTlsPort);
        await hub.StartAsync();
        var store = ConfigStore.OpenFile(Path.Combine(root, "s1", "config.json"));
        var routes = new RouteManager(() => HubTarget.Of(store.Get()));
        var setup = new HubSetup(store, routes);

        // nothing set up and no address: the form says where to look
        var none = await Assert.ThrowsAsync<FieldException>(() => setup.SetupAsync(null, "net-setup"));
        Assert.Equal("hub_url", none.Field);

        // by address (this machine is trusted by the hub, as tailnet members are): let in at once
        var res = await setup.SetupAsync(hub.Url + "/", "net-setup");
        Assert.False(res.Pending);
        var cfg = store.Get();
        Assert.True(cfg.Registered);
        Assert.Equal(hub.Url, cfg.HubUrl);
        Assert.Equal("net-setup", cfg.DeviceName);
        var id = cfg.DeviceId;
        using (var c = new HubClient(hub.Url, cfg.DeviceToken))
        {
            Assert.Equal(id, (await c.FilesAsync()).Self?.Id);
        }

        // renaming keeps the device, along the current route
        res = await setup.SetupAsync(null, "net-renamed");
        Assert.Equal("net-renamed", res.Name);
        Assert.Equal((id, "net-renamed"), (store.Get().DeviceId, store.Get().DeviceName));
        using (var c = new HubClient(hub.Url, store.Get().DeviceToken))
        {
            Assert.Equal("net-renamed", (await c.FilesAsync()).Self?.Name);
        }

        // another device's name is refused, and nothing changes
        using (var other = new HubClient(hub.Url))
        {
            await other.RegisterAsync("net-other");
        }
        var taken = await Assert.ThrowsAsync<FieldException>(() => setup.SetupAsync(hub.Url, "net-other"));
        Assert.Equal("name", taken.Field);
        Assert.Equal("net-renamed", store.Get().DeviceName);
    }
}
