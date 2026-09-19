using Droplet.Core.Common;
using Droplet.Core.Config;
using Droplet.Core.Hub;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Droplet.Core.LocalFirst;

/// <summary>No hub is set up yet.</summary>
public sealed class NotPairedException() : Exception("no hub set up yet");

/// <summary>
/// Keeps the current route to the hub: chooses it on demand, notices when the network
/// changes (Wi-Fi joined or left, Tailscale up or down), and moves from the tailnet to
/// the LAN when the hub appears there (docs/local-first.md §3). Thread-safe.
/// </summary>
[System.Diagnostics.CodeAnalysis.SuppressMessage("Design", "CA1001",
    Justification = "Its semaphores never allocate a wait handle; it lives as long as the app.")]
public sealed class RouteManager
{
    readonly SemaphoreSlim selecting = new(1, 1);
    readonly Lock gate = new();
    readonly SemaphoreSlim kick = new(0, 1);
    readonly ILogger log;
    Route? current;
    IdentityChange? changed;
    Exception? lastError;
    DateTimeOffset lastErrorAt;

    /// <summary>Creates a manager; <paramref name="target"/> reads the paired hub (from the config) each time.</summary>
    public RouteManager(Func<HubTarget> target, RouteDeps? deps = null, RouteOptions? options = null, ILogger? logger = null)
    {
        Target = target ?? throw new ArgumentNullException(nameof(target));
        log = logger ?? NullLogger.Instance;
        Deps = deps ?? RouteDeps.Default(logger);
        Options = options ?? new RouteOptions();
    }

    /// <summary>Reads the paired hub.</summary>
    public Func<HubTarget> Target { get; }

    /// <summary>The network access used.</summary>
    public RouteDeps Deps { get; }

    /// <summary>Timings.</summary>
    public RouteOptions Options { get; }

    /// <summary>Describes the network interfaces; a change means choosing again.</summary>
    public Func<string> NetSignature { get; init; } = Addresses.InterfaceSignature;

    /// <summary>How often the interfaces are compared.</summary>
    public TimeSpan NetPollEvery { get; init; } = TimeSpan.FromSeconds(4);

    /// <summary>After a network change, let DHCP finish.</summary>
    public TimeSpan SettleDelay { get; init; } = TimeSpan.FromMilliseconds(1500);

    /// <summary>While on the tailnet, look for the LAN this often.</summary>
    public TimeSpan LanLookEvery { get; init; } = TimeSpan.FromMinutes(3);

    /// <summary>Don't redo a failed selection sooner than this.</summary>
    public TimeSpan ErrorHoldoff { get; init; } = TimeSpan.FromSeconds(3);

    /// <summary>After each successful selection: what was learnt (the hub's LAN address, its tailnet URL...).</summary>
    public event Action<SelectResult>? Selected;

    /// <summary>When the route changes, including to none.</summary>
    public event Action<Route?>? RouteChanged;

    /// <summary>The route in use, if any.</summary>
    public Route? Current
    {
        get
        {
            lock (gate)
            {
                return current;
            }
        }
    }

    /// <summary>The hub's LAN identity having changed, or null.</summary>
    public IdentityChange? Changed
    {
        get
        {
            lock (gate)
            {
                return changed;
            }
        }
    }

    /// <summary>Forgets an identity change (after re-pairing).</summary>
    public void ClearChanged()
    {
        lock (gate)
        {
            changed = null;
        }
    }

    /// <summary>Why the last selection found no route, or null.</summary>
    public Exception? LastError
    {
        get
        {
            lock (gate)
            {
                return current is null ? lastError : null;
            }
        }
    }

    /// <summary>The current route, choosing one if there's none.</summary>
    public async Task<Route> EnsureAsync(CancellationToken ct = default)
    {
        Exception? err;
        DateTimeOffset at;
        lock (gate)
        {
            if (current is not null)
            {
                return current;
            }
            (err, at) = (lastError, lastErrorAt);
        }
        if (err is not null && DateTimeOffset.UtcNow - at < ErrorHoldoff)
        {
            throw err;
        }
        return await ReselectAsync(false, true, ct).ConfigureAwait(false) ?? throw (LastError ?? new UnreachableException(null, []));
    }

    /// <summary>Chooses the route again from scratch.</summary>
    public async Task<Route?> ReselectAsync(CancellationToken ct = default) => await ReselectAsync(false, false, ct).ConfigureAwait(false);

    /// <summary>Makes <paramref name="route"/> the route (it was just shown to work, e.g. by pairing).</summary>
    public void Set(Route route) => SetRoute(route, null);

    /// <summary>Drops the route and any error (the hub or its identity changed); the next Ensure chooses again.</summary>
    public void Reset()
    {
        lock (gate)
        {
            lastError = null;
            changed = null;
        }
        SetRoute(null, null);
    }

    /// <summary>Reports that <paramref name="route"/> stopped working; if it's current, it's dropped.</summary>
    public void Lost(Route route)
    {
        bool same;
        lock (gate)
        {
            same = current == route;
        }
        if (same)
        {
            log.LogInformation("route: lost {Kind} {Base}", route.Kind, route.Base);
            SetRoute(null, null);
        }
    }

    /// <summary>Asks <see cref="RunAsync"/> to look for the LAN now.</summary>
    public void Kick()
    {
        try
        {
            kick.Release();
        }
        catch (SemaphoreFullException)
        {
        }
    }

    void SetRoute(Route? route, Exception? error)
    {
        Route? old;
        lock (gate)
        {
            old = current;
            current = route;
            if (error is not null)
            {
                (lastError, lastErrorAt) = (error, DateTimeOffset.UtcNow);
            }
            else if (route is not null)
            {
                lastError = null;
            }
        }
        if (old != route)
        {
            if (route is not null)
            {
                log.LogInformation("route: {Label} {Base}", route.Label, route.Base);
            }
            RouteChanged?.Invoke(route);
        }
    }

    /// <summary>
    /// Runs a selection. <paramref name="lanOnly"/> keeps the current route unless the LAN
    /// turns up; <paramref name="onlyIfNone"/> returns the current route if another caller
    /// chose one meanwhile.
    /// </summary>
    async Task<Route?> ReselectAsync(bool lanOnly, bool onlyIfNone, CancellationToken ct)
    {
        await selecting.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (onlyIfNone && Current is { } already)
            {
                return already;
            }
            var target = Target();
            if (target.Id.Length == 0)
            {
                // a hub we know no identity of (not migrated yet, or from before
                // local-first): use its URL as it is
                if (target.Remote.Length == 0)
                {
                    var err = new NotPairedException();
                    SetRoute(null, err);
                    throw err;
                }
                var r = new Route(RouteKind.Remote, target.Remote);
                SetRoute(r, null);
                return r;
            }
            if (lanOnly && !target.CanLan)
            {
                return Current;
            }
            SelectResult res;
            try
            {
                res = await RouteSelector.SelectAsync(target, Deps, Options with { LanOnly = lanOnly }, ct).ConfigureAwait(false);
            }
            catch (UnreachableException e)
            {
                log.LogInformation("route: no route to the hub: {Detail}", e.Detail);
                if (e.Changed is not null)
                {
                    lock (gate)
                    {
                        changed = e.Changed;
                    }
                }
                if (lanOnly)
                {
                    return Current; // the LAN isn't there: stay put
                }
                SetRoute(null, e);
                throw;
            }
            lock (gate)
            {
                if (res.Route.Kind == RouteKind.Lan)
                {
                    changed = null; // the pinned certificate answered: all is well
                }
                else if (res.Changed is not null)
                {
                    changed = res.Changed;
                }
                var pinned = Fingerprint.Normalize(target.Fingerprint);
                if (res.Route.Kind == RouteKind.Remote && res.Info?.Fingerprint is { Length: > 0 } fp && pinned is not null &&
                    Fingerprint.Normalize(fp) != pinned && Route.IsHttps(target.Remote))
                {
                    // the hub itself, over verified TLS, reports a new certificate
                    changed = new IdentityChange(pinned, Fingerprint.Normalize(fp) ?? fp, target.Remote, Verified: true);
                }
            }
            Selected?.Invoke(res);
            SetRoute(res.Route, null);
            return res.Route;
        }
        finally
        {
            selecting.Release();
        }
    }

    /// <summary>
    /// Watches for network changes until cancelled: when the interfaces change it chooses
    /// the route again, and while on the remote route it looks for the LAN every few minutes.
    /// </summary>
    public async Task RunAsync(CancellationToken ct)
    {
        var sig = NetSignature();
        var lastLook = DateTimeOffset.UtcNow;
        DateTimeOffset? settleAt = null;
        while (!ct.IsCancellationRequested)
        {
            var kicked = false;
            try
            {
                kicked = await kick.WaitAsync(NetPollEvery, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            try
            {
                if (kicked)
                {
                    lastLook = DateTimeOffset.UtcNow;
                    var r = Current;
                    if (r?.Kind == RouteKind.Lan)
                    {
                        continue;
                    }
                    await ReselectQuietlyAsync(r is not null, ct).ConfigureAwait(false);
                    continue;
                }
                var s = NetSignature();
                if (s != sig)
                {
                    sig = s;
                    log.LogInformation("route: the network changed");
                    settleAt = DateTimeOffset.UtcNow + SettleDelay;
                    continue;
                }
                if (settleAt is { } due && DateTimeOffset.UtcNow >= due)
                {
                    settleAt = null;
                    lastLook = DateTimeOffset.UtcNow;
                    await ReselectQuietlyAsync(false, ct).ConfigureAwait(false);
                    continue;
                }
                if (Current?.Kind == RouteKind.Remote && DateTimeOffset.UtcNow - lastLook >= LanLookEvery)
                {
                    lastLook = DateTimeOffset.UtcNow;
                    await ReselectQuietlyAsync(true, ct).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    async Task ReselectQuietlyAsync(bool lanOnly, CancellationToken ct)
    {
        try
        {
            await ReselectAsync(lanOnly, false, ct).ConfigureAwait(false);
        }
        catch (Exception e) when (e is UnreachableException or NotPairedException)
        {
            // recorded as the last error
        }
    }
}

/// <summary>
/// What the app learns about its hub over time: working out an older config's hub
/// identity (<see cref="MigrateAsync"/>), and folding what each selection learnt into the
/// stored identity (<see cref="Remember"/>).
/// </summary>
public static class HubIdentities
{
    /// <summary>How many LAN addresses are remembered.</summary>
    public const int MaxLan = 4;

    /// <summary>How long to wait before trying a failed migration again.</summary>
    public static readonly TimeSpan MigrateRetry = TimeSpan.FromMinutes(10);

    /// <summary>
    /// The identity a hub describes in <c>/api/hub/info</c>. The fingerprint is taken only
    /// when <paramref name="pinSource"/> says the answer is trustworthy (verified TLS, or the
    /// pinned LAN connection it was checked against).
    /// </summary>
    public static HubIdentity FromInfo(HubInfo info, string? pinSource, string? remoteUrl)
    {
        ArgumentNullException.ThrowIfNull(info);
        var h = new HubIdentity { Id = info.Id, Name = info.Name, HttpPort = info.Lan.HttpPort, Tailnet = info.Tailnet ?? "" };
        if (Fingerprint.Normalize(info.Fingerprint) is { } fp && !string.IsNullOrEmpty(pinSource))
        {
            (h.Fingerprint, h.PinSource) = (fp, pinSource);
        }
        h.Lan = MergeAddrs([], info.LanEndpoints());
        if (string.IsNullOrEmpty(h.Tailnet) && Route.IsTailnetUrl(remoteUrl))
        {
            h.Tailnet = remoteUrl;
        }
        return h;
    }

    /// <summary>
    /// Works out the identity of the hub an older config points at (only a URL and a token):
    /// over <paramref name="remoteUrl"/> when it's https and answers (as trustworthy as its
    /// TLS, so its fingerprint is pinned); otherwise with mDNS, trusting on first use, but
    /// only a hub that ties itself to that URL. Returns null for a hub from before
    /// local-first (it answered, but can't say who it is).
    /// </summary>
    public static async Task<HubIdentity?> MigrateAsync(string remoteUrl, RouteDeps deps, RouteOptions o, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(deps);
        ArgumentNullException.ThrowIfNull(o);
        if (!Uri.TryCreate(remoteUrl, UriKind.Absolute, out var u) || string.IsNullOrEmpty(u.Host))
        {
            throw new HubException($"can't migrate: bad hub URL \"{remoteUrl}\"");
        }
        Exception? remoteError = null;
        if (Route.IsHttps(remoteUrl))
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(o.RemoteTimeout);
            try
            {
                var info = await deps.Info(remoteUrl, null, cts.Token).ConfigureAwait(false);
                return FromInfo(info, PinSources.Tailnet, remoteUrl);
            }
            catch (HubNotFoundException)
            {
                return null;
            }
            catch (Exception e) when (e is HubException or HttpRequestException or OperationCanceledException && !ct.IsCancellationRequested)
            {
                remoteError = e;
            }
        }
        var hubs = await deps.Browse(o.BrowseWait * 2, null, ct).ConfigureAwait(false);
        HubAnnouncement? match = null;
        foreach (var h in hubs.Where(h => TiedTo(h, u)))
        {
            if (match is not null && match.Id != h.Id)
            {
                throw new HubException("more than one hub on the LAN claims this hub's address; pair again from Settings");
            }
            match = h;
        }
        if (match is null)
        {
            throw remoteError ?? new HubException("the hub isn't on this network");
        }
        foreach (var ep in match.Endpoints)
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(o.LanTimeout);
            try
            {
                var info = await deps.Info(Route.LanBase(ep), match.Fingerprint, cts.Token).ConfigureAwait(false);
                if (info.Id != match.Id)
                {
                    continue;
                }
                var h = FromInfo(info, null, remoteUrl);
                // pinned to what mDNS announced, which the connection just matched
                (h.Fingerprint, h.PinSource) = (match.Fingerprint, PinSources.Lan);
                h.Lan = MergeAddrs([ep], h.Lan);
                if (string.IsNullOrEmpty(h.Tailnet))
                {
                    h.Tailnet = match.Tailnet;
                }
                if (h.HttpPort == 0)
                {
                    h.HttpPort = match.HttpPort;
                }
                return h;
            }
            catch (Exception e) when (e is HubException or HttpRequestException or OperationCanceledException && !ct.IsCancellationRequested)
            {
            }
        }
        throw new HubException("the hub on this network didn't answer with the certificate it announces");
    }

    /// <summary>Whether an announcement is the hub at <paramref name="u"/>: its tailnet URL, or one of its LAN addresses and ports.</summary>
    public static bool TiedTo(HubAnnouncement h, Uri u)
    {
        ArgumentNullException.ThrowIfNull(h);
        ArgumentNullException.ThrowIfNull(u);
        if (h.Tailnet.Length > 0 && string.Equals(h.Tailnet.TrimEnd('/'), u.GetLeftPart(UriPartial.Path).TrimEnd('/'), StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }
        if (!System.Net.IPAddress.TryParse(u.Host.Trim('[', ']'), out var ip))
        {
            return false;
        }
        if (ip.IsIPv4MappedToIPv6)
        {
            ip = ip.MapToIPv4();
        }
        return h.Addresses.Any(a => a.Equals(ip)) && (u.IsDefaultPort || u.Port == h.HttpPort || u.Port == h.Port);
    }

    /// <summary>
    /// Folds what a selection learnt into the stored identity: the LAN address that
    /// answered goes first, and the hub's own account of its addresses, name and tailnet
    /// URL is kept. A first fingerprint is adopted only from verified TLS (an https remote
    /// route); a different one never is (that takes re-pairing). Returns whether anything changed.
    /// </summary>
    public static bool Remember(HubIdentity h, SelectResult res, string? remoteUrl)
    {
        ArgumentNullException.ThrowIfNull(h);
        ArgumentNullException.ThrowIfNull(res);
        var before = System.Text.Json.JsonSerializer.Serialize(h);
        var fresh = new List<string>();
        if (res.Route.Kind == RouteKind.Lan && res.Route.Addr.Length > 0)
        {
            fresh.Add(res.Route.Addr);
        }
        var info = res.Info?.Id == h.Id ? res.Info : null;
        if (info is not null)
        {
            fresh.AddRange(info.LanEndpoints());
        }
        h.Lan = MergeAddrs(fresh, h.Lan);
        if (info is not null)
        {
            if (!string.IsNullOrEmpty(info.Name))
            {
                h.Name = info.Name;
            }
            if (info.Lan.HttpPort > 0)
            {
                h.HttpPort = info.Lan.HttpPort;
            }
            if (!string.IsNullOrEmpty(info.Tailnet))
            {
                h.Tailnet = info.Tailnet;
            }
            if (string.IsNullOrEmpty(h.Fingerprint) && res.Route.Kind == RouteKind.Remote && Route.IsHttps(remoteUrl) &&
                Fingerprint.Normalize(info.Fingerprint) is { } fp)
            {
                (h.Fingerprint, h.PinSource) = (fp, PinSources.Tailnet);
            }
        }
        return System.Text.Json.JsonSerializer.Serialize(h) != before;
    }

    /// <summary>Puts <paramref name="first"/> before <paramref name="rest"/>, without duplicates or junk, at most <see cref="MaxLan"/>.</summary>
    public static List<string> MergeAddrs(IEnumerable<string> first, IEnumerable<string> rest)
    {
        var output = new List<string>();
        foreach (var a in first.Concat(rest))
        {
            var colon = a.LastIndexOf(':');
            if (colon <= 0 || !System.Net.IPAddress.TryParse(a[..colon].Trim('[', ']'), out _) ||
                !int.TryParse(a[(colon + 1)..], System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out _) ||
                output.Contains(a))
            {
                continue;
            }
            output.Add(a);
            if (output.Count == MaxLan)
            {
                break;
            }
        }
        return output;
    }
}
