using System.Net;
using System.Net.Sockets;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Droplet.Core.Mdns;

/// <summary>
/// Looks for DNS-SD services with one-shot ("legacy unicast", RFC 6762 §5.1 and §6.7)
/// queries, as the Go app did for hubs: each query goes to the mDNS group from an
/// ordinary ephemeral port, one socket per interface, and responders answer straight
/// back to that port. So browsing needs neither port 5353 (which Windows' own mDNS
/// service holds) nor a multicast group membership, nor an inbound firewall rule: the
/// replies are responses to this app's own traffic, which Windows Firewall lets through.
/// </summary>
public sealed class MdnsBrowser(ILogger? logger = null)
{
    readonly ILogger log = logger ?? NullLogger.Instance;

    /// <summary>
    /// Searches for up to <paramref name="wait"/>. <paramref name="found"/>, if set, is
    /// called once per instance as soon as its records are complete; returning true stops
    /// the search early. Returns every complete instance seen.
    /// </summary>
    public async Task<List<ServiceInstance>> BrowseAsync(DnsName serviceType, TimeSpan wait,
        Func<ServiceInstance, bool>? found = null, CancellationToken ct = default)
    {
        var sockets = Open();
        if (sockets.Count == 0)
        {
            throw new SocketException((int)SocketError.NetworkUnreachable);
        }
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(wait);
        var col = new ServiceCollector(serviceType);
        var reported = new HashSet<DnsName>();
        var done = false;
        var gate = new Lock();

        void Send()
        {
            var bytes = col.NextQuery().ToBytes();
            foreach (var s in sockets)
            {
                try
                {
                    s.SendTo(bytes, MdnsNetwork.GroupEndPoint);
                }
                catch (SocketException e)
                {
                    log.LogDebug("mDNS query on {Socket}: {Error}", s.LocalEndPoint, e.Message);
                }
            }
        }

        async Task Receive(Socket s)
        {
            var buf = new byte[9000];
            while (!cts.IsCancellationRequested)
            {
                SocketReceiveFromResult r;
                try
                {
                    r = await s.ReceiveFromAsync(buf, SocketFlags.None, new IPEndPoint(IPAddress.Any, 0), cts.Token).ConfigureAwait(false);
                }
                catch (Exception e) when (e is OperationCanceledException or SocketException or ObjectDisposedException)
                {
                    return;
                }
                var from = (IPEndPoint)r.RemoteEndPoint;
                if (from.Port != MdnsNetwork.Port)
                {
                    continue; // responders answer from the mDNS port
                }
                if (DnsMessage.Parse(buf.AsSpan(0, r.ReceivedBytes)) is not { } msg)
                {
                    continue;
                }
                col.Add(msg, from.Address);
                if (found is null)
                {
                    continue;
                }
                foreach (var inst in col.Complete())
                {
                    lock (gate)
                    {
                        if (done || !reported.Add(inst.Name))
                        {
                            continue;
                        }
                    }
                    if (found(inst))
                    {
                        lock (gate)
                        {
                            done = true;
                        }
                        await cts.CancelAsync().ConfigureAwait(false);
                        return;
                    }
                }
            }
        }

        try
        {
            var readers = sockets.Select(Receive).ToList();
            Send();
            // ask again a few times: UDP gets lost, and follow-up questions fill in
            // records a responder left out of its first answer
            for (var i = 0; i < 3 && !cts.IsCancellationRequested; i++)
            {
                try
                {
                    await Task.Delay(350, cts.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                Send();
            }
            await Task.WhenAll(readers).ConfigureAwait(false);
        }
        finally
        {
            foreach (var s in sockets)
            {
                s.Dispose();
            }
        }
        ct.ThrowIfCancellationRequested();
        return col.Complete();
    }

    List<Socket> Open()
    {
        var list = new List<Socket>();
        foreach (var ifc in MdnsNetwork.Interfaces())
        {
            Socket? s = null;
            try
            {
                s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
                s.Bind(new IPEndPoint(ifc.Address, 0));
                MdnsNetwork.UseInterface(s, ifc);
                s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 255);
                // a responder on this same machine answers too
                s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastLoopback, true);
                list.Add(s);
            }
            catch (SocketException e)
            {
                log.LogDebug("mDNS: can't query on {Interface}: {Error}", ifc.Name, e.Message);
                s?.Dispose();
            }
        }
        if (list.Count == 0)
        {
            // no usable interface list: let the system pick the route
            try
            {
                var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
                s.Bind(new IPEndPoint(IPAddress.Any, 0));
                list.Add(s);
            }
            catch (SocketException e)
            {
                log.LogDebug("mDNS: no socket to query from: {Error}", e.Message);
            }
        }
        return list;
    }
}
