using System.Text.Json.Nodes;
using Droplet.Core.Config;
using Droplet.Core.LocalFirst;
using Droplet.Core.Mesh;
using Droplet.Core.Tests.Support;

namespace Droplet.Core.Tests;

/// <summary>
/// A phone's notifications mirrored straight to this PC over the mesh (docs/mesh.md §9.4):
/// the toast each <c>notify</c> becomes, its tag (one per notification, within Windows'
/// limit), its removal, and the <c>notify</c> cap that tells phones to send them.
/// </summary>
public sealed class PhoneNotificationTests : IAsyncLifetime
{
    const string Fp = "3c10a2b4c6d8e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f70819";
    const string Key = "0|com.whatsapp|1|null|10123";

    readonly string root = TestDirs.Make("phone-notify");
    readonly List<DotNetPeer> peers = [];

    public ValueTask InitializeAsync() => ValueTask.CompletedTask;

    public async ValueTask DisposeAsync()
    {
        foreach (var p in peers)
        {
            await p.DisposeAsync();
        }
        TestDirs.Remove(root);
    }

    [Fact]
    public void A_tag_is_one_per_notification_and_fits_windows()
    {
        var tag = PhoneNotifications.Tag(Fp, Key);
        Assert.StartsWith(PhoneNotifications.TagPrefix, tag, StringComparison.Ordinal);
        Assert.True(tag.Length <= 60, tag);
        Assert.Equal(tag, PhoneNotifications.Tag(Fp, Key));                                    // an update replaces it
        Assert.NotEqual(tag, PhoneNotifications.Tag(Fp, Key + "2"));                           // another notification
        Assert.NotEqual(tag, PhoneNotifications.Tag(Fp.Replace("3c10", "4d21", StringComparison.Ordinal), Key)); // another phone
        // long keys from the same app don't collide once cut to Windows' length, as they did when the tag was "fp:key"
        var a = PhoneNotifications.Tag(Fp, "0|com.google.android.apps.messaging|1|conversation-1|10234");
        var b = PhoneNotifications.Tag(Fp, "0|com.google.android.apps.messaging|1|conversation-2|10234");
        Assert.NotEqual(a, b);
    }

    [Fact]
    public void A_notify_becomes_a_toast_from_the_phone()
    {
        var t = PhoneNotifications.Toast(new JsonObject { ["t"] = "notify", ["key"] = Key, ["app"] = "WhatsApp", ["title"] = "Mum", ["text"] = "Dinner at 7" }, "pixel", Fp);
        Assert.Equal(("Mum (pixel)", "Dinner at 7", "WhatsApp"), (t.Title, t.Body, t.App));
        Assert.Equal(PhoneNotifications.Tag(Fp, Key), t.Tag);
        Assert.False(t.Urgent);

        // missing parts: the phone's name stands in, and long text is cut
        var bare = PhoneNotifications.Toast(new JsonObject { ["t"] = "notify", ["text"] = new string('x', 5000), ["app"] = 5 }, "pixel", Fp);
        Assert.Equal(("pixel (pixel)", "pixel"), (bare.Title, bare.App));
        Assert.Equal(1000, bare.Body.Length);
        Assert.StartsWith(PhoneNotifications.TagPrefix, bare.Tag, StringComparison.Ordinal); // still the phone's: the switch covers it

        Assert.Equal(PhoneNotifications.Tag(Fp, Key), PhoneNotifications.RemovedTag(new JsonObject { ["t"] = "notify-removed", ["key"] = Key }, Fp));
        Assert.Null(PhoneNotifications.RemovedTag(new JsonObject { ["t"] = "notify-removed", ["key"] = 5 }, Fp));
        Assert.Null(PhoneNotifications.RemovedTag(new JsonObject { ["t"] = "notify-removed" }, Fp));
    }

    [Fact]
    public async Task A_phones_notification_is_shown_replaced_and_taken_away()
    {
        var phone = await StartAsync("phone");
        var pc = await StartAsync("pc");
        phone.Node.Trust.AddPaired(TrustEntry.Make(pc.Node.PeerId, "pc", pc.Node.Identity.CertificatePem, TrustSource.Paired, ["127.0.0.1"], pc.Node.Port));
        pc.Node.Trust.AddPaired(TrustEntry.Make(phone.Node.PeerId, "pixel", phone.Node.Identity.CertificatePem, TrustSource.Paired, port: phone.Node.Port));
        var link = await phone.Node.DirectAsync(pc.Node.Identity.Fingerprint);
        Assert.NotNull(link);
        var shown = pc.Fakes.Notifications.Shown;

        Assert.True(await link!.SendAsync(new JsonObject { ["t"] = "notify", ["key"] = Key, ["app"] = "WhatsApp", ["title"] = "Mum", ["text"] = "Dinner at 7" }));
        await Wait.For(() => shown.Count == 1, 10, "the toast");
        Assert.True(await link.SendAsync(new JsonObject { ["t"] = "notify", ["key"] = Key, ["app"] = "WhatsApp", ["title"] = "Mum", ["text"] = "Make it 8" }));
        await Wait.For(() => shown.Count == 2, 10, "the update");
        var toasts = shown.ToArray();
        var tag = PhoneNotifications.Tag(phone.Node.Identity.Fingerprint, Key);
        Assert.All(toasts, t => Assert.Equal(tag, t.Tag)); // the same tag: the update replaces the toast
        Assert.Equal(("Mum (pixel)", "Make it 8"), (toasts[1].Title, toasts[1].Body));

        Assert.True(await link.SendAsync(new JsonObject { ["t"] = "notify-removed", ["key"] = Key }));
        await Wait.For(() => pc.Fakes.Notifications.Cleared.Contains(tag), 10, "the toast taken away");
    }

    [Fact]
    public void The_notify_cap_follows_the_setting()
    {
        var store = ConfigStore.Open(new AppPaths(Path.Combine(root, "cfg")), "");
        Assert.True(store.Get().PhoneNotifications);   // on by default
        var bridge = new HubMeshBridge(store, new RouteManager(() => HubTarget.Of(store.Get())), null, null);
        Assert.Contains(PhoneNotifications.Cap, bridge.MeshCaps);
        store.Update(c => c.PhoneNotifications = false);
        Assert.DoesNotContain(PhoneNotifications.Cap, bridge.MeshCaps);
        // saved, and read back
        Assert.False(ConfigStore.Open(new AppPaths(Path.Combine(root, "cfg")), "").Get().PhoneNotifications);
    }

    async Task<DotNetPeer> StartAsync(string name)
    {
        var p = await DotNetPeer.StartAsync(Path.Combine(root, name), name, port: 0);
        peers.Add(p);
        return p;
    }
}
