using System.Collections.Concurrent;
using System.Net;
using Droplet.Core.Common;
using Droplet.Core.Config;
using Droplet.Core.Hub;

namespace Droplet.Core.LocalFirst;

/// <summary>Which way a route goes.</summary>
public enum RouteKind
{
    /// <summary>Straight to the hub on the local network, pinned.</summary>
    Lan,

    /// <summary>The configured URL (normally the tailnet), with normal TLS.</summary>
    Remote,
}

/// <summary>One way to the hub (docs/local-first.md §3).</summary>
/// <param name="Kind">LAN or remote.</param>
/// <param name="Base">e.g. https://192.168.100.20:8443 or https://t15.tail7375fe.ts.net.</param>
/// <param name="Addr">LAN only: "ip:port".</param>
/// <param name="Pin">LAN only: the certificate fingerprint it's pinned to.</param>
public sealed record Route(RouteKind Kind, string Base, string Addr = "", string Pin = "")
{
    static readonly ConcurrentDictionary<string, SocketsHttpHandler> PinnedHandlers = new();
    static readonly Lazy<SocketsHttpHandler> Verified = new(HubClient.DefaultHandler);

    /// <summary>
    /// How to connect along it: the pinned handler on the LAN, the normal one otherwise.
    /// Handlers are shared (one per pin), so connections are reused across clients.
    /// </summary>
    public HttpMessageHandler Handler() => Kind == RouteKind.Lan
        ? PinnedHandlers.GetOrAdd(Pin, p => PinnedConnection.CreateHandler(p).Handler)
        : Verified.Value;

    /// <summary>A hub client along this route.</summary>
    public HubClient Client(string? token, string? session) => new(Base, token, session, Handler());

    /// <summary>How the route is shown: "on Wi-Fi" or "via Tailscale".</summary>
    public string Label => Kind switch
    {
        RouteKind.Lan => "on Wi-Fi",
        _ when IsTailnetUrl(Base) => "via Tailscale",
        _ => Uri.TryCreate(Base, UriKind.Absolute, out var u) ? "via " + u.Authority : "via " + Base,
    };

    /// <summary>The base URL for a LAN address.</summary>
    public static string LanBase(string addr) => "https://" + addr;

    /// <summary>Whether a URL points into a tailnet (a *.ts.net name or a 100.64.0.0/10 address).</summary>
    public static bool IsTailnetUrl(string? url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var u))
        {
            return false;
        }
        var h = u.Host.ToLowerInvariant();
        return h.EndsWith(".ts.net", StringComparison.Ordinal) || (IPAddress.TryParse(u.Host.Trim('[', ']'), out var ip) && Addresses.IsTailnet(ip));
    }

    /// <summary>Whether a URL is https (so answers along it were checked with normal TLS).</summary>
    public static bool IsHttps(string? url) => url?.StartsWith("https://", StringComparison.OrdinalIgnoreCase) == true;
}

/// <summary>What route selection needs to know about the paired hub.</summary>
/// <param name="Id">The hub's id.</param>
/// <param name="Fingerprint">Its LAN certificate's pin.</param>
/// <param name="Lan">Its last LAN addresses, newest first.</param>
/// <param name="Remote">The fallback URL (tailnet), or "".</param>
public sealed record HubTarget(string Id, string Fingerprint, IReadOnlyList<string> Lan, string Remote)
{
    /// <summary>Reads it from the config.</summary>
    public static HubTarget Of(AppConfig c)
    {
        ArgumentNullException.ThrowIfNull(c);
        return new(c.Hub?.Id ?? "", c.Hub?.Fingerprint ?? "", c.Hub?.Lan.ToList() ?? [], c.RemoteUrl);
    }

    /// <summary>Whether a LAN route is possible: a known hub and a pin.</summary>
    public bool CanLan => Id.Length > 0 && Common.Fingerprint.Normalize(Fingerprint) is not null;
}

/// <summary>The network access route selection needs; tests replace it.</summary>
public sealed record RouteDeps
{
    /// <summary>Searches the LAN for hubs: (wait, found → stop?, ct).</summary>
    public required Func<TimeSpan, Func<HubAnnouncement, bool>?, CancellationToken, Task<List<HubAnnouncement>>> Browse { get; init; }

    /// <summary>
    /// <c>/api/hub/info</c> from a base URL, with no credentials: pinned to the given
    /// fingerprint (LAN), or with normal TLS (null). A pinned call that meets another
    /// certificate throws <see cref="PinMismatchException"/>.
    /// </summary>
    public required Func<string, string?, CancellationToken, Task<HubInfo>> Info { get; init; }

    /// <summary>The real network.</summary>
    public static RouteDeps Default(Microsoft.Extensions.Logging.ILogger? logger = null)
    {
        var browser = new HubBrowser(logger);
        return new RouteDeps { Browse = browser.BrowseAsync, Info = InfoAsync };
    }

    /// <summary>Fetches <c>/api/hub/info</c>, pinned when <paramref name="pin"/> is given.</summary>
    public static async Task<HubInfo> InfoAsync(string baseUrl, string? pin, CancellationToken ct)
    {
        if (pin is null)
        {
            using var plain = new HubClient(baseUrl);
            return await plain.HubInfoAsync(ct).ConfigureAwait(false);
        }
        // a handler of its own, so a mismatch is this call's and not another probe's
        var (handler, pinned) = PinnedConnection.CreateHandler(pin);
        using (handler)
        {
            using var client = new HubClient(baseUrl, handler: handler);
            try
            {
                return await client.HubInfoAsync(ct).ConfigureAwait(false);
            }
            catch (HttpRequestException e) when (pinned.AsMismatch(e) is { } mismatch)
            {
                throw mismatch;
            }
        }
    }
}

/// <summary>Tunes route selection.</summary>
public sealed record RouteOptions
{
    /// <summary>Per LAN address tried (docs: about 1.5 s).</summary>
    public TimeSpan LanTimeout { get; init; } = TimeSpan.FromMilliseconds(1500);

    /// <summary>How long mDNS may take.</summary>
    public TimeSpan BrowseWait { get; init; } = TimeSpan.FromMilliseconds(1500);

    /// <summary>The tailnet can be slow to wake up.</summary>
    public TimeSpan RemoteTimeout { get; init; } = TimeSpan.FromSeconds(10);

    /// <summary>Look for the LAN only (while on the tailnet).</summary>
    public bool LanOnly { get; init; }

    /// <summary>How many remembered addresses to try.</summary>
    public int MaxLastAddrs { get; init; } = 3;
}

/// <summary>The hub's LAN certificate no longer matches the pin.</summary>
/// <param name="Want">The pinned fingerprint.</param>
/// <param name="Got">What the hub presents (or announces) now.</param>
/// <param name="Addr">Where.</param>
/// <param name="Verified">The tailnet (verified TLS) says <paramref name="Got"/> is the hub's now, so re-pairing can adopt it.</param>
public sealed record IdentityChange(string Want, string Got, string Addr, bool Verified = false)
{
    /// <summary>As words.</summary>
    public string Describe() => new PinMismatchException(Want, Got).Message;
}

/// <summary>What selection found.</summary>
/// <param name="Route">The route chosen.</param>
/// <param name="Info">What the chosen route's hub said about itself (null for a hub from before local-first).</param>
/// <param name="Changed">Set when a LAN address answered with another certificate, or announced another fingerprint, and no LAN route worked.</param>
public sealed record SelectResult(Route Route, HubInfo? Info, IdentityChange? Changed);

/// <summary>No route works.</summary>
public sealed class UnreachableException(IdentityChange? changed, IReadOnlyList<string> tried)
    : Exception(changed?.Describe() ?? "can't reach the hub")
{
    /// <summary>The hub's identity changed, when that's why.</summary>
    public IdentityChange? Changed { get; } = changed;

    /// <summary>What was tried and how it failed, for the log.</summary>
    public IReadOnlyList<string> Tried { get; } = tried;

    /// <summary>One line, for the log.</summary>
    public string Detail => string.Join("; ", Tried);
}

/// <summary>
/// Chooses the route to the hub (docs/local-first.md §3): the LAN first (an mDNS
/// answer with the hub's id, or the last address that worked, over pinned TLS), else
/// the remote URL with normal TLS. Both are tried at once; the remote one is used only
/// once the LAN has failed.
/// </summary>
public static class RouteSelector
{
    /// <summary>Finds the best working route to <paramref name="target"/>.</summary>
    public static async Task<SelectResult> SelectAsync(HubTarget target, RouteDeps deps, RouteOptions options, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(target);
        ArgumentNullException.ThrowIfNull(deps);
        ArgumentNullException.ThrowIfNull(options);
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var lanTask = target.CanLan
            ? SelectLanAsync(target, deps, options, cts.Token)
            : Task.FromResult(new LanOutcome(null, null, null, ["LAN: not paired for it (no certificate pin)"]));
        var remote = target.Remote.Length > 0 && !options.LanOnly;
        var remoteTask = remote ? RemoteAsync(target, deps, options, cts.Token) : null;
        try
        {
            var lan = await lanTask.ConfigureAwait(false);
            if (lan.Addr is not null)
            {
                return new SelectResult(new Route(RouteKind.Lan, Route.LanBase(lan.Addr), lan.Addr, Fingerprint.Normalize(target.Fingerprint)!), lan.Info, null);
            }
            var tried = lan.Tried.ToList();
            if (remoteTask is not null)
            {
                var (info, error) = await remoteTask.ConfigureAwait(false);
                if (error is null)
                {
                    var changed = lan.Changed;
                    if (changed is not null && info?.Fingerprint is { } fp && fp.Length > 0)
                    {
                        changed = changed with { Verified = Fingerprint.Normalize(fp) == changed.Got };
                    }
                    return new SelectResult(new Route(RouteKind.Remote, target.Remote), info, changed);
                }
                tried.Add($"remote {target.Remote}: {error.Message}");
            }
            ct.ThrowIfCancellationRequested();
            throw new UnreachableException(lan.Changed, tried);
        }
        finally
        {
            await cts.CancelAsync().ConfigureAwait(false);
        }
    }

    static async Task<(HubInfo? Info, Exception? Error)> RemoteAsync(HubTarget target, RouteDeps deps, RouteOptions o, CancellationToken ct)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(o.RemoteTimeout);
        try
        {
            var info = await deps.Info(target.Remote, null, cts.Token).ConfigureAwait(false);
            if (target.Id.Length > 0 && info.Id != target.Id)
            {
                return (null, new HubException($"{target.Remote} is a different hub (id {info.Id}, not {target.Id})"));
            }
            return (info, null);
        }
        catch (HubNotFoundException)
        {
            // a hub from before local-first: nothing to check it by, and it answered, so it's there
            return (null, null);
        }
        catch (Exception e) when (e is HubException or HttpRequestException or OperationCanceledException or IOException)
        {
            return (null, e is OperationCanceledException && !ct.IsCancellationRequested ? new TimeoutException("no answer in time") : e);
        }
    }

    sealed record LanOutcome(string? Addr, HubInfo? Info, IdentityChange? Changed, IReadOnlyList<string> Tried);

    /// <summary>
    /// Tries the remembered addresses at once, and whatever mDNS finds with the hub's id as
    /// it comes in; the first that answers with the pinned certificate and the right id wins.
    /// </summary>
    static async Task<LanOutcome> SelectLanAsync(HubTarget target, RouteDeps deps, RouteOptions o, CancellationToken ct)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var fp = Fingerprint.Normalize(target.Fingerprint)!;
        var gate = new Lock();
        var tried = new HashSet<string>();
        var notes = new List<string>();
        IdentityChange? changed = null;
        var winner = new TaskCompletionSource<(string Addr, HubInfo Info)>(TaskCreationOptions.RunContinuationsAsynchronously);
        var probes = new List<Task>();

        void Note(string s)
        {
            lock (gate)
            {
                notes.Add(s);
            }
        }

        void Start(string addr)
        {
            lock (gate)
            {
                if (!tried.Add(addr))
                {
                    return;
                }
                probes.Add(Probe(addr));
            }
        }

        async Task Probe(string addr)
        {
            using var pc = CancellationTokenSource.CreateLinkedTokenSource(cts.Token);
            pc.CancelAfter(o.LanTimeout);
            try
            {
                var info = await deps.Info(Route.LanBase(addr), fp, pc.Token).ConfigureAwait(false);
                if (info.Id != target.Id)
                {
                    Note($"LAN {addr}: a different hub (id {info.Id})");
                    return;
                }
                winner.TrySetResult((addr, info));
            }
            catch (PinMismatchException e)
            {
                lock (gate)
                {
                    changed ??= e.Got is null ? null : new IdentityChange(fp, e.Got, addr);
                }
                Note($"LAN {addr}: {e.Message}");
            }
            catch (Exception e) when (e is HubException or HttpRequestException or OperationCanceledException or IOException)
            {
                Note($"LAN {addr}: {(e is OperationCanceledException ? "no answer in time" : e.Message)}");
            }
        }

        foreach (var addr in target.Lan.Take(o.MaxLastAddrs))
        {
            Start(addr);
        }
        var browse = BrowseAsync();

        async Task BrowseAsync()
        {
            try
            {
                await deps.Browse(o.BrowseWait, h =>
                {
                    if (h.Id != target.Id)
                    {
                        return false; // someone else's hub
                    }
                    if (h.Fingerprint != fp)
                    {
                        // it says it's our hub but has another certificate: either not
                        // ours, or its certificate was regenerated
                        lock (gate)
                        {
                            if (changed is null && h.Endpoints.Count > 0)
                            {
                                changed = new IdentityChange(fp, h.Fingerprint, h.Endpoints[0]);
                            }
                        }
                        Note($"mDNS: {h.Instance} announces another certificate");
                        return false;
                    }
                    foreach (var ep in h.Endpoints)
                    {
                        Start(ep);
                    }
                    return true;
                }, cts.Token).ConfigureAwait(false);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                Note("mDNS: " + e.Message);
            }
            catch (OperationCanceledException)
            {
            }
        }

        // done when a probe wins, or mDNS and every probe it started have finished
        async Task AllDone()
        {
            await browse.ConfigureAwait(false);
            while (true)
            {
                Task[] pending;
                lock (gate)
                {
                    pending = [.. probes];
                }
                await Task.WhenAll(pending).ConfigureAwait(false);
                lock (gate)
                {
                    if (probes.Count == pending.Length)
                    {
                        return;
                    }
                }
            }
        }

        var all = AllDone();
        var first = await Task.WhenAny(winner.Task, all).ConfigureAwait(false);
        if (first == winner.Task || winner.Task.IsCompleted)
        {
            await cts.CancelAsync().ConfigureAwait(false); // the rest can stop
            var (addr, info) = await winner.Task.ConfigureAwait(false);
            return new LanOutcome(addr, info, null, []);
        }
        lock (gate)
        {
            if (tried.Count == 0 && notes.Count == 0)
            {
                notes.Add("LAN: no address to try and nothing found by mDNS");
            }
            return new LanOutcome(null, null, changed, [.. notes]);
        }
    }
}
