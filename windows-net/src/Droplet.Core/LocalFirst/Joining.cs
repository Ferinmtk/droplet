using Droplet.Core.Common;
using Droplet.Core.Config;
using Droplet.Core.Hub;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Droplet.Core.LocalFirst;

/// <summary>Where a request to join a hub stands (docs/local-first.md §4).</summary>
public enum JoinState
{
    /// <summary>No answer this time (network trouble): ask again.</summary>
    Unknown,

    /// <summary>Waiting for the owner to allow it.</summary>
    Pending,

    /// <summary>Let in: the token now works everywhere.</summary>
    Approved,

    /// <summary>Denied, or expired (the hub drops unanswered requests after a day): the token is dead.</summary>
    Declined,
}

/// <summary>
/// Follows a LAN join request: a new device names itself with <c>POST /api/device</c>,
/// gets a four-digit code and waits, polling <c>GET /api/me</c>, until the owner allows it
/// from one of their devices (the pending flag goes) or denies it (the device is gone).
/// </summary>
public static class JoinRequest
{
    /// <summary>How often to ask (the docs say every 2–3 s).</summary>
    public static readonly TimeSpan Every = TimeSpan.FromMilliseconds(2500);

    /// <summary>Reads one <c>/api/me</c> answer (or its failure). The code is set while pending.</summary>
    public static (JoinState State, string? Code) Evaluate(Me? me, Exception? error) => (me, error) switch
    {
        (_, not null) => (JoinState.Unknown, null),
        (null, _) => (JoinState.Unknown, null),
        ({ Device: null }, _) => (JoinState.Declined, null),
        ({ Device.Pending: true }, _) => (JoinState.Pending, me.Device.Code),
        _ => (JoinState.Approved, null),
    };

    /// <summary>
    /// Asks every <paramref name="every"/> until the request is approved or declined, or
    /// cancelled. <paramref name="update"/> hears every change of state (and a new code).
    /// A hiccup (no answer) doesn't change where the request stands.
    /// </summary>
    public static async Task<JoinState> WaitAsync(Func<CancellationToken, Task<Me>> me, TimeSpan every,
        Action<JoinState, string?>? update = null, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(me);
        JoinState? last = null;
        string? lastCode = null;
        while (true)
        {
            Me? answer = null;
            Exception? error = null;
            using (var one = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                one.CancelAfter(TimeSpan.FromSeconds(15));
                try
                {
                    answer = await me(one.Token).ConfigureAwait(false);
                }
                catch (Exception e) when (e is HubException or HttpRequestException or OperationCanceledException or IOException)
                {
                    error = e;
                }
            }
            ct.ThrowIfCancellationRequested();
            var (state, code) = Evaluate(answer, error);
            if (state == JoinState.Unknown && last is { } l)
            {
                (state, code) = (l, lastCode);
            }
            if ((state != last || code != lastCode) && update is not null)
            {
                update(state, code);
            }
            (last, lastCode) = (state, code);
            if (state is JoinState.Approved or JoinState.Declined)
            {
                return state;
            }
            await Task.Delay(every, ct).ConfigureAwait(false);
        }
    }
}

/// <summary>A problem with one field of a setup form: the field, and what to tell the person.</summary>
public sealed class FieldException(string field, string message) : Exception(message)
{
    /// <summary>The form field: hub, name, pin, link_code, hub_url.</summary>
    public string Field { get; } = field;
}

/// <summary>What asking to join did.</summary>
/// <param name="Name">The device's name on the hub.</param>
/// <param name="Hub">The hub's name.</param>
/// <param name="Pending">Waiting to be let in.</param>
/// <param name="Code">The code to compare while waiting.</param>
public sealed record JoinResult(string Name, string Hub, bool Pending, string? Code);

/// <summary>What linking did.</summary>
/// <param name="Id">The device this app now is.</param>
/// <param name="Name">Its name.</param>
/// <param name="Replaced">The device this app was before, when it was a separate one on the same hub (still listed there).</param>
public sealed record LinkResult(string Id, string Name, Device? Replaced);

/// <summary>What re-pairing after an identity change did.</summary>
/// <param name="Done">The new certificate was confirmed over the tailnet and is pinned now.</param>
/// <param name="Join">It couldn't be: ask to join again on the LAN (trusting the new certificate), as a new device.</param>
/// <param name="HubId">The hub to join.</param>
/// <param name="Reason">Why it couldn't be confirmed.</param>
public sealed record RepairResult(bool Done, bool Join, string? HubId = null, string? Reason = null);

/// <summary>
/// Getting in to a hub (docs/local-first.md §4, docs/remote.md §1): joining a hub found
/// on the LAN (trust on first use, confirmed by comparing the code), linking to an
/// existing device with a six-digit code, the PIN, and re-pairing after the hub's
/// certificate changed. Everything it learns goes to the config.
/// </summary>
public sealed class HubSetup(ConfigStore store, RouteManager routes, ILogger? logger = null)
{
    readonly ILogger log = logger ?? NullLogger.Instance;
    readonly Lock gate = new();
    List<HubAnnouncement> discovered = [];

    /// <summary>How long a scan of the LAN takes.</summary>
    public static readonly TimeSpan DiscoverWait = TimeSpan.FromSeconds(2);

    /// <summary>How this app labels itself to the hub when linking.</summary>
    public static string LinkClient()
    {
        var host = Environment.MachineName;
        return string.IsNullOrEmpty(host) ? "droplet for Windows" : "droplet for Windows on " + host.ToLowerInvariant();
    }

    /// <summary>Scans the LAN for hubs.</summary>
    public async Task<List<HubAnnouncement>> DiscoverAsync(CancellationToken ct = default)
    {
        var hubs = await routes.Deps.Browse(DiscoverWait, null, ct).ConfigureAwait(false);
        lock (gate)
        {
            discovered = hubs;
        }
        return hubs;
    }

    async Task<HubAnnouncement?> FindDiscoveredAsync(string id, CancellationToken ct)
    {
        lock (gate)
        {
            if (discovered.FirstOrDefault(h => h.Id == id) is { } known)
            {
                return known;
            }
        }
        HubAnnouncement? found = null;
        await routes.Deps.Browse(DiscoverWait, h =>
        {
            if (h.Id != id)
            {
                return false;
            }
            found = h;
            return true;
        }, ct).ConfigureAwait(false);
        return found;
    }

    /// <summary>
    /// Connects to a hub found on the LAN, pinned to the certificate it announces (trust
    /// on first use: pairing, where the owner compares the code, confirms it's the right
    /// hub). A paired hub that now announces another certificate is refused unless
    /// <paramref name="trustNew"/> (re-pairing).
    /// </summary>
    public async Task<(Route Route, HubInfo Info, HubAnnouncement Hub)> ReachDiscoveredAsync(string id, bool trustNew, CancellationToken ct = default)
    {
        var h = await FindDiscoveredAsync(id, ct).ConfigureAwait(false)
                ?? throw new FieldException("hub", "That hub isn't answering on this network any more. Scan again.");
        var cfg = store.Get();
        if (cfg.Hub is { } paired && paired.Id == h.Id && !string.IsNullOrEmpty(paired.Fingerprint) && paired.Fingerprint != h.Fingerprint && !trustNew)
        {
            throw new FieldException("hub", "This hub's identity changed since this PC paired with it. If you reset the hub, choose Re-pair.");
        }
        Exception? last = null;
        foreach (var ep in h.Endpoints)
        {
            var r = new Route(RouteKind.Lan, Route.LanBase(ep), ep, h.Fingerprint);
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(5));
            try
            {
                var info = await routes.Deps.Info(r.Base, h.Fingerprint, cts.Token).ConfigureAwait(false);
                if (info.Id != h.Id)
                {
                    last = new HubException($"it answered as a different hub ({info.Id})");
                    continue;
                }
                return (r, info, h);
            }
            catch (Exception e) when (e is HubException or HttpRequestException or OperationCanceledException && !ct.IsCancellationRequested)
            {
                last = e;
            }
        }
        var msg = $"Couldn't connect to {h.DisplayName} securely";
        msg += last is PinMismatchException
            ? ": its certificate isn't the one it announces, so it may not be your hub."
            : last is not null ? ": " + last.Message : "";
        throw new FieldException("hub", msg);
    }

    /// <summary>The identity to store for a hub reached on the LAN.</summary>
    static HubIdentity LanIdentity(AppConfig cfg, HubAnnouncement h, Route r, HubInfo info)
    {
        var id = HubIdentities.FromInfo(info, null, null);
        (id.Fingerprint, id.PinSource) = (h.Fingerprint, PinSources.Lan);
        if (cfg.Hub is { } old && old.Id == h.Id && old.Fingerprint == h.Fingerprint && !string.IsNullOrEmpty(old.PinSource))
        {
            id.PinSource = old.PinSource; // the same pin as before, from wherever it came
        }
        id.Lan = HubIdentities.MergeAddrs([r.Addr], id.Lan);
        if (string.IsNullOrEmpty(id.Tailnet))
        {
            id.Tailnet = h.Tailnet;
        }
        if (id.HttpPort == 0)
        {
            id.HttpPort = h.HttpPort;
        }
        if (string.IsNullOrEmpty(id.Name))
        {
            id.Name = h.Name;
        }
        return id;
    }

    /// <summary>A device name typed in Settings, checked.</summary>
    public static string CleanDeviceName(string? s)
    {
        var name = string.Join(' ', (s ?? "").Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
        if (name.Length == 0)
        {
            throw new FieldException("name", "Give this PC a name, like \"maryanne\".");
        }
        if (name.EnumerateRunes().Count() > 40)
        {
            throw new FieldException("name", "Keep the name under 40 characters.");
        }
        return name;
    }

    /// <summary>
    /// Asks a hub found on the LAN to let this PC in, as a new device called
    /// <paramref name="name"/>. With the hub's PIN it's let in at once; otherwise it waits
    /// for the owner to allow it on another device, comparing the code; follow it with
    /// <see cref="PollJoinAsync"/>. <paramref name="trustNew"/> accepts a paired hub's new
    /// certificate (re-pairing).
    /// </summary>
    public async Task<JoinResult> JoinAsync(string hubId, string name, string? pin = null, bool trustNew = false, CancellationToken ct = default)
    {
        name = CleanDeviceName(name);
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(30));
        var (r, info, h) = await ReachDiscoveredAsync(hubId, trustNew, cts.Token).ConfigureAwait(false);
        // a new device: no earlier token comes along
        using var c = r.Client(null, null);
        if (!string.IsNullOrEmpty(pin))
        {
            try
            {
                await c.LoginAsync(pin, cts.Token).ConfigureAwait(false);
            }
            catch (WrongPinException)
            {
                throw new FieldException("pin", "That PIN is wrong.");
            }
        }
        Device dev;
        try
        {
            dev = await c.RegisterAsync(name, cts.Token).ConfigureAwait(false);
        }
        catch (NameTakenException)
        {
            throw new FieldException("name", $"\"{name}\" is already a device on this hub (perhaps this PC, from before). " +
                                             "Pick another name, or remove the old one under Devices on another device first.");
        }
        catch (HubStatusException e) when (!string.IsNullOrEmpty(e.HubMessage))
        {
            throw new FieldException("hub", e.HubMessage);
        }
        var cfg = store.Get();
        var id = LanIdentity(cfg, h, r, info);
        store.Update(n =>
        {
            if (n.Hub?.Id != id.Id)
            {
                n.HubUrl = id.Tailnet ?? ""; // another hub: its own tailnet URL (or none), not the old one
            }
            n.Hub = id;
            (n.DeviceToken, n.Session) = (c.Token, c.Session);
            (n.DeviceId, n.DeviceName) = (dev.Id, dev.Name);
            (n.PairPending, n.PairCode) = (dev.Pending, dev.Code);
            (n.InboxSeen, n.ChatSeen) = ([], []); // a new device: nothing seen yet
        });
        routes.Reset();
        routes.Set(r);
        if (dev.Pending)
        {
            log.LogInformation("pairing: asked {Hub} to let {Name} in (code {Code})", id.Name, dev.Name, dev.Code);
        }
        else
        {
            log.LogInformation("pairing: joined {Hub} as {Name}", id.Name, dev.Name);
        }
        return new JoinResult(dev.Name, id.Name ?? h.DisplayName, dev.Pending, dev.Code);
    }

    /// <summary>
    /// Asks the hub whether this PC's join request was answered, and records it: the code
    /// while pending; the device once approved; a dead token once declined.
    /// </summary>
    public async Task<JoinState> PollJoinAsync(CancellationToken ct = default)
    {
        var cfg = store.Get();
        if (!cfg.Pending)
        {
            return cfg.Registered ? JoinState.Approved : JoinState.Declined;
        }
        Me? me = null;
        Exception? error = null;
        Route? r = null;
        try
        {
            r = await routes.EnsureAsync(ct).ConfigureAwait(false);
            using var c = r.Client(cfg.DeviceToken, cfg.Session);
            me = await c.MeAsync(ct).ConfigureAwait(false);
        }
        catch (Exception e) when (e is HubException or HttpRequestException or UnreachableException or NotPairedException || (e is OperationCanceledException && !ct.IsCancellationRequested))
        {
            error = e;
            if (r is not null && e is HttpRequestException)
            {
                routes.Lost(r);
            }
        }
        var (state, code) = JoinRequest.Evaluate(me, error);
        Record(state, code, me);
        return state;
    }

    /// <summary>Records an answer about the join request: the code while pending; the device once approved; a dead token once declined.</summary>
    void Record(JoinState state, string? code, Me? me)
    {
        switch (state)
        {
            case JoinState.Pending when code != store.Get().PairCode:
                store.Update(n => n.PairCode = code);
                break;
            case JoinState.Approved:
                store.Update(n =>
                {
                    (n.PairPending, n.PairCode) = (false, null);
                    (n.DeviceId, n.DeviceName) = (me!.Device!.Id, me.Device.Name);
                });
                log.LogInformation("pairing: let in as {Name}", me!.Device!.Name);
                break;
            case JoinState.Declined:
                store.Update(n =>
                {
                    (n.DeviceToken, n.Session, n.DeviceId) = (null, null, null);
                    (n.PairPending, n.PairCode) = (false, null);
                });
                log.LogInformation("pairing: the request was declined (or it expired)");
                break;
        }
    }

    /// <summary>Follows this PC's join request until it's answered, recording and reporting each change.</summary>
    public Task<JoinState> WaitForJoinAsync(Action<JoinState, string?>? update = null, CancellationToken ct = default) =>
        JoinRequest.WaitAsync(async c =>
        {
            var cfg = store.Get();
            var r = await routes.EnsureAsync(c).ConfigureAwait(false);
            using var client = r.Client(cfg.DeviceToken, cfg.Session);
            var me = await client.MeAsync(c).ConfigureAwait(false);
            var (state, code) = JoinRequest.Evaluate(me, null);
            Record(state, code, me);
            return me;
        }, JoinRequest.Every, update, ct);

    /// <summary>Lets a waiting device in with the hub's PIN instead.</summary>
    public async Task SignInWithPinAsync(string pin, CancellationToken ct = default)
    {
        var cfg = store.Get();
        if (!cfg.Pending)
        {
            throw new InvalidOperationException("this PC isn't waiting to be let in");
        }
        var r = await routes.EnsureAsync(ct).ConfigureAwait(false);
        using var c = r.Client(cfg.DeviceToken, cfg.Session);
        try
        {
            await c.LoginAsync(pin, ct).ConfigureAwait(false);
        }
        catch (WrongPinException)
        {
            throw new FieldException("pin", "That PIN is wrong.");
        }
        store.Update(n => n.Session = c.Session);
    }

    /// <summary>Forgets a join request that's still waiting (the hub drops it by itself after a day).</summary>
    public void CancelJoin()
    {
        store.Update(n =>
        {
            if (n.Pending)
            {
                (n.DeviceToken, n.Session, n.DeviceId) = (null, null, null);
                (n.PairPending, n.PairCode) = (false, null);
            }
        });
        routes.Reset();
    }

    /// <summary>
    /// Sets this PC up on a hub by its address (normally its tailnet URL: tailnet members
    /// are let in at once, with nothing to compare), or renames it on the hub it's on
    /// (<paramref name="hubUrl"/> null or that hub's URL). A new hub means a new device;
    /// the paired hub's own tailnet URL, added now, keeps the device and adds the URL to
    /// the hub's identity. The Go app's Settings "Save", less the local settings.
    /// </summary>
    public async Task<JoinResult> SetupAsync(string? hubUrl, string name, string? pin = null, CancellationToken ct = default)
    {
        name = CleanDeviceName(name);
        var cfg = store.Get();
        string? url = null;
        if (!string.IsNullOrWhiteSpace(hubUrl))
        {
            try
            {
                url = HubClient.ParseUrl(hubUrl).AbsoluteUri.TrimEnd('/');
            }
            catch (FormatException e)
            {
                throw new FieldException("hub_url", e.Message);
            }
        }
        if (url is null && string.IsNullOrEmpty(cfg.DeviceToken))
        {
            throw new FieldException("hub_url", "Choose your hub on this network, or enter its Tailscale address.");
        }
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(20));
        var moved = url is not null && !SameHub(url, cfg.HubUrl);
        HubIdentity? ident = null;
        if (moved && cfg.Hub is not null && !string.IsNullOrEmpty(cfg.DeviceToken) &&
            await IdentityFromUrlAsync(url!, cts.Token).ConfigureAwait(false) is { } same && same.Id == cfg.Hub.Id)
        {
            // the paired hub's own tailnet URL, added now: the same device
            moved = false;
            ident = MergeUrlIdentity(cfg.Hub, same, url!);
        }
        var viaUrl = url is not null && (moved || string.IsNullOrEmpty(cfg.DeviceToken));
        HubClient c;
        if (viaUrl)
        {
            c = moved ? new HubClient(url!) : new HubClient(url!, cfg.DeviceToken, cfg.Session);
        }
        else
        {
            Route r;
            try
            {
                r = await routes.EnsureAsync(cts.Token).ConfigureAwait(false);
            }
            catch (Exception e) when (e is UnreachableException or NotPairedException)
            {
                throw new FieldException("hub_url", $"Can't reach {Polling.HubPoller.HubName(cfg)} right now: {e.Message}.");
            }
            c = r.Client(cfg.DeviceToken, cfg.Session);
        }
        using (c)
        {
            if (!string.IsNullOrEmpty(pin))
            {
                try
                {
                    await c.LoginAsync(pin, cts.Token).ConfigureAwait(false);
                }
                catch (WrongPinException)
                {
                    throw new FieldException("pin", "That PIN is wrong.");
                }
                catch (Exception e) when (e is HubException or HttpRequestException or OperationCanceledException && !ct.IsCancellationRequested)
                {
                    throw new FieldException("hub_url", Unreachable(c, e));
                }
            }
            Me me;
            try
            {
                me = await c.MeAsync(cts.Token).ConfigureAwait(false);
            }
            catch (PinRequiredException)
            {
                throw new FieldException("pin", "This hub asks for a PIN. Enter it and try again.");
            }
            catch (Exception e) when (e is HubException or HttpRequestException or OperationCanceledException && !ct.IsCancellationRequested)
            {
                throw new FieldException("hub_url", Unreachable(c, e));
            }
            if (me.Device is null)
            {
                c.Token = null; // unknown to the hub (removed, or never registered)
            }
            Device dev;
            if (me.Device is null || me.Device.Name != name)
            {
                try
                {
                    dev = await c.RegisterAsync(name, cts.Token).ConfigureAwait(false);
                }
                catch (NameTakenException)
                {
                    throw new FieldException("name", $"\"{name}\" is already a device on this hub (perhaps this PC's browser). " +
                                                     "Pick another name, or link with a code instead.");
                }
                catch (HubStatusException e) when (!string.IsNullOrEmpty(e.HubMessage))
                {
                    throw new FieldException("name", e.HubMessage);
                }
                catch (Exception e) when (e is HubException or HttpRequestException or OperationCanceledException && !ct.IsCancellationRequested)
                {
                    throw new FieldException("name", Unreachable(c, e));
                }
            }
            else
            {
                dev = me.Device;
            }
            // a rename answers without the pending flag; /api/me had it
            var (pending, code) = (dev.Pending, dev.Code);
            if (me.Device is { Pending: true } && !string.IsNullOrEmpty(c.Token))
            {
                (pending, code) = (true, me.Device.Code);
            }
            if (viaUrl)
            {
                ident = await IdentityFromUrlAsync(url!, cts.Token).ConfigureAwait(false);
            }
            store.Update(n =>
            {
                if (moved || n.DeviceId != dev.Id)
                {
                    (n.InboxSeen, n.ChatSeen) = ([], []); // a new device: nothing seen yet
                }
                if (url is not null && (viaUrl || ident is not null))
                {
                    n.HubUrl = url;
                }
                if (ident is not null || moved)
                {
                    n.Hub = ident; // null: a hub that can't say who it is; the URL is used as it is
                }
                (n.DeviceToken, n.Session) = (c.Token, c.Session);
                (n.DeviceId, n.DeviceName) = (dev.Id, dev.Name);
                (n.PairPending, n.PairCode) = (pending, pending ? code : null);
            });
            routes.Reset();
            var hubName = Polling.HubPoller.HubName(store.Get());
            log.LogInformation("setup: {Name} on {Hub}{Pending}", dev.Name, hubName, pending ? $", waiting to be let in (code {code})" : "");
            return new JoinResult(dev.Name, hubName, pending, pending ? code : null);
        }
    }

    static string Unreachable(HubClient c, Exception e) => e switch
    {
        OperationCanceledException => $"No answer from {c.Base.Authority}. Is the hub on, and is Tailscale connected on this PC?",
        HttpRequestException => $"Can't reach {c.Base.Authority}. Is the hub running, and is Tailscale connected on this PC?",
        _ => e.Message,
    };

    /// <summary>
    /// Asks a hub URL who the hub is. Only an https answer is trusted with its certificate
    /// fingerprint; anything else is null, and the URL is used as it is.
    /// </summary>
    async Task<HubIdentity?> IdentityFromUrlAsync(string url, CancellationToken ct)
    {
        if (!Route.IsHttps(url))
        {
            return null;
        }
        try
        {
            var info = await routes.Deps.Info(url, null, ct).ConfigureAwait(false);
            return HubIdentities.FromInfo(info, PinSources.Tailnet, url);
        }
        catch (Exception e) when (e is HubException or HttpRequestException or OperationCanceledException && !ct.IsCancellationRequested)
        {
            log.LogInformation("hub identity from {Url}: {Error}", url, e.Message);
            return null;
        }
    }

    /// <summary>
    /// Adds what a paired hub says over its (verified) URL to its stored identity. The pin
    /// stays: a different fingerprint is an identity change, which only re-pairing accepts.
    /// </summary>
    internal static HubIdentity MergeUrlIdentity(HubIdentity stored, HubIdentity fromUrl, string url)
    {
        var h = stored.Clone();
        h.Tailnet = url;
        if (!string.IsNullOrEmpty(fromUrl.Name))
        {
            h.Name = fromUrl.Name;
        }
        if (string.IsNullOrEmpty(h.Fingerprint))
        {
            (h.Fingerprint, h.PinSource) = (fromUrl.Fingerprint, fromUrl.PinSource);
        }
        else if (h.Fingerprint == fromUrl.Fingerprint)
        {
            h.PinSource = PinSources.Tailnet; // now vouched for by verified TLS too
        }
        return h;
    }

    /// <summary>A six-digit link code, checked.</summary>
    public static string LinkDigits(string? code)
    {
        var digits = new string((code ?? "").Where(char.IsAsciiDigit).ToArray());
        return digits.Length == 6 ? digits : throw new FieldException("link_code", "The code is six digits, like 123456.");
    }

    /// <summary>
    /// Makes this app part of an existing device (normally this PC's browser), using the
    /// six-digit code droplet shows under "Set up remote control of this device". With no
    /// <paramref name="hubUrl"/> (or the hub already set up), along the current route.
    /// </summary>
    public async Task<LinkResult> LinkAsync(string? hubUrl, string code, string? pin = null, CancellationToken ct = default)
    {
        var cfg = store.Get();
        var digits = LinkDigits(code);
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(20));
        var current = string.IsNullOrWhiteSpace(hubUrl);
        string? url = null;
        if (!current)
        {
            try
            {
                url = HubClient.ParseUrl(hubUrl).AbsoluteUri.TrimEnd('/');
            }
            catch (FormatException e)
            {
                throw new FieldException("hub_url", e.Message);
            }
            current = cfg.Hub is not null && SameHub(url, cfg.HubUrl);
        }
        if (current)
        {
            if (cfg.Hub is null && cfg.RemoteUrl.Length == 0)
            {
                throw new FieldException("hub_url", "Choose your hub first.");
            }
            Route r;
            try
            {
                r = await routes.EnsureAsync(cts.Token).ConfigureAwait(false);
            }
            catch (Exception e) when (e is UnreachableException or NotPairedException)
            {
                throw new FieldException("link_code", $"Can't reach the hub: {e.Message}.");
            }
            using var c = r.Client(null, cfg.Session);
            return await LinkWithAsync(c, digits, pin, cfg.Hub, null, cts.Token).ConfigureAwait(false);
        }
        using var byUrl = new HubClient(url!, null, SameHub(url!, cfg.HubUrl) ? cfg.Session : null);
        HubIdentity? ident = null;
        try
        {
            ident = await HubIdentities.MigrateAsync(url!, routes.Deps, routes.Options, cts.Token).ConfigureAwait(false);
        }
        catch (Exception e) when (e is HubException or HttpRequestException)
        {
            log.LogInformation("hub identity for {Url}: {Error}", url, e.Message);
        }
        return await LinkWithAsync(byUrl, digits, pin, ident, url, cts.Token).ConfigureAwait(false);
    }

    /// <summary>Links over the LAN, with a hub found by <see cref="DiscoverAsync"/>.</summary>
    public async Task<LinkResult> LinkLanAsync(string hubId, string code, string? pin = null, CancellationToken ct = default)
    {
        var digits = LinkDigits(code);
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(30));
        var (r, info, h) = await ReachDiscoveredAsync(hubId, false, cts.Token).ConfigureAwait(false);
        using var c = r.Client(null, null);
        var res = await LinkWithAsync(c, digits, pin, LanIdentity(store.Get(), h, r, info), null, cts.Token).ConfigureAwait(false);
        routes.Set(r);
        return res;
    }

    static bool SameHub(string a, string? b) =>
        string.Equals(a.TrimEnd('/'), (b ?? "").TrimEnd('/'), StringComparison.OrdinalIgnoreCase);

    async Task<LinkResult> LinkWithAsync(HubClient c, string digits, string? pin, HubIdentity? ident, string? hubUrl, CancellationToken ct)
    {
        var cfg = store.Get();
        if (!string.IsNullOrEmpty(pin))
        {
            try
            {
                await c.LoginAsync(pin, ct).ConfigureAwait(false);
            }
            catch (WrongPinException)
            {
                throw new FieldException("pin", "That PIN is wrong.");
            }
        }
        Linked linked;
        try
        {
            linked = await c.LinkAsync(digits, LinkClient(), ct).ConfigureAwait(false);
        }
        catch (PinRequiredException)
        {
            throw new FieldException("pin", "This hub asks for a PIN. Enter it and link again.");
        }
        catch (BadLinkCodeException)
        {
            throw new FieldException("link_code", "That code is wrong, used or expired. Make a new one in droplet.");
        }
        catch (HubException e)
        {
            throw new FieldException("link_code", e.Message);
        }
        bool moved;
        if (ident is not null && cfg.Hub is not null)
        {
            moved = ident.Id != cfg.Hub.Id;
        }
        else if (hubUrl is not null)
        {
            moved = !SameHub(hubUrl, cfg.HubUrl);
        }
        else
        {
            moved = cfg.Hub is null && ident is not null;
        }
        Device? replaced = !moved && !string.IsNullOrEmpty(cfg.DeviceToken) && !string.IsNullOrEmpty(cfg.DeviceId) && cfg.DeviceId != linked.Id
            ? new Device { Id = cfg.DeviceId, Name = cfg.DeviceName ?? "" }
            : null;
        store.Update(n =>
        {
            if (moved || n.DeviceId != linked.Id)
            {
                // a different device: its inbox and chats are new to us
                (n.InboxSeen, n.ChatSeen) = ([], []);
            }
            if (hubUrl is not null)
            {
                n.HubUrl = hubUrl;
            }
            else if (moved && ident is not null)
            {
                n.HubUrl = ident.Tailnet ?? "";
            }
            if (ident is not null || moved)
            {
                n.Hub = ident;
            }
            (n.DeviceToken, n.Session) = (linked.Token, c.Session);
            (n.DeviceId, n.DeviceName) = (linked.Id, linked.Name);
            (n.PairPending, n.PairCode) = (false, null);
        });
        routes.Reset();
        return new LinkResult(linked.Id, linked.Name, replaced);
    }

    /// <summary>
    /// Deals with the hub's certificate having changed. When the hub can be asked over the
    /// tailnet (verified TLS), its new fingerprint is pinned and the device carries on as it
    /// was. Otherwise the new certificate can only be trusted by joining again on the LAN, as
    /// a new device: the old token is never sent to a certificate nobody has vouched for.
    /// </summary>
    public async Task<RepairResult> RepairAsync(CancellationToken ct = default)
    {
        var cfg = store.Get();
        var hub = cfg.Hub ?? throw new InvalidOperationException("this PC isn't paired with a hub");
        var remote = cfg.RemoteUrl;
        var reason = "The hub can't be reached over Tailscale to confirm its new certificate.";
        if (Route.IsHttps(remote))
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(15));
            try
            {
                var info = await routes.Deps.Info(remote, null, cts.Token).ConfigureAwait(false);
                var fp = Fingerprint.Normalize(info.Fingerprint);
                if (info.Id == hub.Id && fp is not null)
                {
                    store.Update(n =>
                    {
                        if (n.Hub?.Id == info.Id)
                        {
                            (n.Hub.Fingerprint, n.Hub.PinSource) = (fp, PinSources.Tailnet);
                        }
                    });
                    log.LogInformation("hub identity: re-pinned {Hub} to {Fp}, confirmed over {Url}", hub.Id, fp, remote);
                    routes.Reset();
                    return new RepairResult(true, false);
                }
                reason = info.Id != hub.Id ? $"{remote} is a different hub now." : "The hub has no LAN certificate at the moment.";
            }
            catch (Exception e) when (e is HubException or HttpRequestException or OperationCanceledException && !ct.IsCancellationRequested)
            {
                reason = "Couldn't ask the hub over Tailscale: " + e.Message;
            }
        }
        return new RepairResult(false, true, hub.Id, reason);
    }
}
