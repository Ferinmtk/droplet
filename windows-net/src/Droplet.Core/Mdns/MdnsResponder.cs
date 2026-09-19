using System.Net;
using System.Net.Sockets;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Droplet.Core.Mdns;

/// <summary>A DNS-SD service to announce.</summary>
public sealed record ServiceAnnouncement
{
    /// <summary>The service type ("_droplet-peer._tcp.local.").</summary>
    public required DnsName ServiceType { get; init; }

    /// <summary>The instance label ("maryanne 9b1617").</summary>
    public required string Instance { get; init; }

    /// <summary>The host name ("droplet-9b16173d305cd15a.local.").</summary>
    public required DnsName Host { get; init; }

    /// <summary>The port.</summary>
    public required int Port { get; init; }

    /// <summary>The TXT pairs, in order.</summary>
    public required IReadOnlyList<KeyValuePair<string, string>> Txt { get; init; }

    /// <summary>The addresses to announce for the host (LAN addresses only).</summary>
    public required IReadOnlyList<IPAddress> Addresses { get; init; }

    /// <summary>The instance's full name.</summary>
    public DnsName InstanceName => DnsName.Child(Instance, ServiceType);
}

/// <summary>
/// A minimal mDNS responder (RFC 6762) for one service, and a listener for what other
/// responders say (<see cref="ResponseReceived"/>). It answers the questions DNS-SD
/// browsers ask (PTR for the type, SRV, TXT, A and AAAA for the instance and host,
/// and the <c>_services._dns-sd._udp</c> meta-query), announces on start and on change,
/// and says goodbye on stop.
/// <para>
/// It binds UDP 5353 with address reuse, which is how responders share the port: on
/// Windows with the DNS Client service's own responder (and browsers); on Linux with
/// avahi. Replies follow the question: a query from a port other than 5353 (legacy
/// unicast) gets a unicast reply with its id, the question echoed and short TTLs; a
/// question with the QU bit gets a unicast reply; others are answered on the group.
/// </para>
/// <para>
/// Not done, because peers name themselves uniquely (the instance label carries the
/// peer id): probing and conflict resolution (§8, §9). Only IPv4 multicast is used,
/// which is what every droplet peer and hub listens on; AAAA records are still announced.
/// </para>
/// </summary>
public sealed class MdnsResponder : IAsyncDisposable
{
    const uint HostTtl = 120;      // RFC 6762 §10: records naming a host
    const uint OtherTtl = 4500;    // 75 minutes: the rest
    const uint LegacyTtl = 10;     // §6.7: at most 10 s in a legacy unicast answer

    static readonly DnsName ServicesMeta = DnsName.Parse("_services._dns-sd._udp.local.");

    readonly ILogger log;
    readonly Lock gate = new();
    readonly CancellationTokenSource stop = new();
    readonly List<MdnsNetwork.Interface> interfaces = [];
    Socket? socket;
    Task? receiver;
    ServiceAnnouncement? service;

    /// <summary>Creates a responder; call <see cref="Start"/>.</summary>
    public MdnsResponder(ILogger? logger = null) => log = logger ?? NullLogger.Instance;

    /// <summary>Every mDNS response heard on the group (others' announcements and answers), with its sender.</summary>
    public event Action<DnsMessage, IPEndPoint>? ResponseReceived;

    /// <summary>Whether the port was bound: false means this device can't be found, but can still browse.</summary>
    public bool Listening => socket is not null;

    /// <summary>Binds 5353 and joins the group on every usable interface. Returns whether it could.</summary>
    public bool Start()
    {
        Socket? s = null;
        try
        {
            s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            MdnsNetwork.AllowSharedPort(s);
            s.Bind(new IPEndPoint(IPAddress.Any, MdnsNetwork.Port));
            s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 255);
            s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastLoopback, true);
            s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.PacketInformation, true);
        }
        catch (SocketException e)
        {
            log.LogWarning("mDNS: can't listen on port 5353 ({Error}): other devices won't find this one on the LAN", e.Message);
            s?.Dispose();
            return false;
        }
        socket = s;
        JoinGroups();
        receiver = Task.Run(ReceiveLoop);
        return true;
    }

    /// <summary>Joins the group on interfaces that appeared (call when the network changes).</summary>
    public void JoinGroups()
    {
        var s = socket;
        if (s is null)
        {
            return;
        }
        foreach (var ifc in MdnsNetwork.Interfaces())
        {
            lock (gate)
            {
                if (interfaces.Any(i => i.Index == ifc.Index && i.Address.Equals(ifc.Address)))
                {
                    continue;
                }
            }
            try
            {
                s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.AddMembership, new MulticastOption(MdnsNetwork.Group, ifc.Address));
                lock (gate)
                {
                    interfaces.Add(ifc);
                }
            }
            catch (SocketException e)
            {
                // already a member (the address moved between interfaces), or no multicast there
                log.LogDebug("mDNS: joining the group on {Interface}: {Error}", ifc.Name, e.Message);
            }
        }
    }

    /// <summary>Announces <paramref name="svc"/> (replacing what was announced), twice, a second apart.</summary>
    public async Task AnnounceAsync(ServiceAnnouncement svc)
    {
        ArgumentNullException.ThrowIfNull(svc);
        ServiceAnnouncement? old;
        lock (gate)
        {
            old = service;
            service = svc;
        }
        if (socket is null)
        {
            return;
        }
        if (old is not null && !old.InstanceName.Equals(svc.InstanceName))
        {
            SendMulticast(Records(old, goodbye: true));
        }
        SendMulticast(Records(svc, goodbye: false));
        try
        {
            await Task.Delay(1000, stop.Token).ConfigureAwait(false);
            SendMulticast(Records(svc, goodbye: false));
        }
        catch (OperationCanceledException)
        {
        }
    }

    /// <summary>The service currently announced.</summary>
    public ServiceAnnouncement? Current
    {
        get
        {
            lock (gate)
            {
                return service;
            }
        }
    }

    /// <summary>Sends a query on the group (to find others that answer on the group too).</summary>
    public void Query(DnsMessage query)
    {
        ArgumentNullException.ThrowIfNull(query);
        if (socket is not null)
        {
            SendMulticast(query);
        }
    }

    static DnsMessage Records(ServiceAnnouncement svc, bool goodbye)
    {
        var m = new DnsMessage { IsResponse = true, Authoritative = true };
        foreach (var r in AllRecords(svc, goodbye ? 0 : null))
        {
            m.Answers.Add(r);
        }
        return m;
    }

    static List<DnsRecord> AllRecords(ServiceAnnouncement svc, uint? ttl)
    {
        var inst = svc.InstanceName;
        var list = new List<DnsRecord>
        {
            new PtrRecord(svc.ServiceType, ttl ?? OtherTtl, inst),
            new SrvRecord(inst, ttl ?? HostTtl, true, 0, 0, (ushort)svc.Port, svc.Host),
            TxtRecord.FromPairs(inst, ttl ?? OtherTtl, svc.Txt),
        };
        list.AddRange(svc.Addresses.Select(a => new AddressRecord(svc.Host, ttl ?? HostTtl, true, a)));
        return list;
    }

    void SendMulticast(DnsMessage m)
    {
        var s = socket;
        if (s is null)
        {
            return;
        }
        var bytes = m.ToBytes();
        List<MdnsNetwork.Interface> ifcs;
        lock (gate)
        {
            ifcs = [.. interfaces];
        }
        foreach (var ifc in ifcs)
        {
            try
            {
                lock (gate)
                {
                    MdnsNetwork.UseInterface(s, ifc);
                    s.SendTo(bytes, MdnsNetwork.GroupEndPoint);
                }
            }
            catch (SocketException e)
            {
                log.LogDebug("mDNS: sending on {Interface}: {Error}", ifc.Name, e.Message);
            }
        }
    }

    async Task ReceiveLoop()
    {
        var s = socket!;
        var buf = new byte[9000];
        while (!stop.IsCancellationRequested)
        {
            SocketReceiveMessageFromResult r;
            try
            {
                r = await s.ReceiveMessageFromAsync(buf, SocketFlags.None, new IPEndPoint(IPAddress.Any, 0), stop.Token).ConfigureAwait(false);
            }
            catch (Exception e) when (e is OperationCanceledException or ObjectDisposedException)
            {
                return;
            }
            catch (SocketException e)
            {
                // Windows reports an ICMP "port unreachable" from an earlier send this way
                log.LogDebug("mDNS: receive: {Error}", e.Message);
                continue;
            }
            var from = (IPEndPoint)r.RemoteEndPoint;
            if (DnsMessage.Parse(buf.AsSpan(0, r.ReceivedBytes)) is not { } msg)
            {
                continue;
            }
            try
            {
                if (msg.IsResponse)
                {
                    ResponseReceived?.Invoke(msg, from);
                }
                else
                {
                    Answer(msg, from, r.PacketInformation.Interface);
                }
            }
            catch (Exception e)
            {
                log.LogDebug(e, "mDNS: handling a message from {From}", from);
            }
        }
    }

    void Answer(DnsMessage query, IPEndPoint from, int ifIndex)
    {
        ServiceAnnouncement? svc;
        lock (gate)
        {
            svc = service;
        }
        if (svc is null || query.Questions.Count == 0)
        {
            return;
        }
        var legacy = from.Port != MdnsNetwork.Port;
        var inst = svc.InstanceName;
        var answers = new List<DnsRecord>();
        var extra = new List<DnsRecord>();
        var unicast = legacy;
        var records = AllRecords(svc, null);
        var ptr = records[0];
        var srv = records[1];
        var txt = records[2];
        var addrs = records.Skip(3).ToList();
        foreach (var q in query.Questions)
        {
            unicast |= q.UnicastResponse;
            var any = q.Type == DnsType.Any;
            if (q.Name.Equals(ServicesMeta) && (any || q.Type == DnsType.Ptr))
            {
                answers.Add(new PtrRecord(ServicesMeta, OtherTtl, svc.ServiceType));
            }
            else if (q.Name.Equals(svc.ServiceType) && (any || q.Type == DnsType.Ptr))
            {
                answers.Add(ptr);
                extra.Add(srv);
                extra.Add(txt);
                extra.AddRange(addrs);
            }
            else if (q.Name.Equals(inst))
            {
                if (any || q.Type == DnsType.Srv)
                {
                    answers.Add(srv);
                    extra.AddRange(addrs);
                }
                if (any || q.Type == DnsType.Txt)
                {
                    answers.Add(txt);
                }
            }
            else if (q.Name.Equals(svc.Host))
            {
                answers.AddRange(addrs.Where(a => any || a.Type == q.Type));
            }
        }
        // known-answer suppression (§7.1): the asker already has our PTR
        if (query.Answers.OfType<PtrRecord>().Any(k => k.Name.Equals(svc.ServiceType) && k.Target.Equals(inst) && k.Ttl > OtherTtl / 2))
        {
            answers.Remove(ptr);
        }
        if (answers.Count == 0)
        {
            return;
        }
        var reply = new DnsMessage { IsResponse = true, Authoritative = true, Id = legacy ? query.Id : (ushort)0 };
        if (legacy)
        {
            // §6.7: echo the question, cap the TTLs, no cache-flush bits
            reply.Questions.AddRange(query.Questions);
            reply.Answers.AddRange(answers.Distinct().Select(Legacy));
            reply.Additionals.AddRange(extra.Distinct().Except(answers).Select(Legacy));
        }
        else
        {
            reply.Answers.AddRange(answers.Distinct());
            reply.Additionals.AddRange(extra.Distinct().Except(answers));
        }
        var bytes = reply.ToBytes();
        var s = socket;
        if (s is null)
        {
            return;
        }
        try
        {
            if (unicast)
            {
                s.SendTo(bytes, from);
                return;
            }
            MdnsNetwork.Interface? ifc;
            lock (gate)
            {
                ifc = interfaces.FirstOrDefault(i => i.Index == ifIndex);
            }
            if (ifc is null)
            {
                SendMulticast(reply);
                return;
            }
            lock (gate)
            {
                MdnsNetwork.UseInterface(s, ifc);
                s.SendTo(bytes, MdnsNetwork.GroupEndPoint);
            }
        }
        catch (SocketException e)
        {
            log.LogDebug("mDNS: answering {From}: {Error}", from, e.Message);
        }
    }

    static DnsRecord Legacy(DnsRecord r) => r switch
    {
        PtrRecord p => p with { Ttl = Math.Min(p.Ttl, LegacyTtl) },
        SrvRecord s => s with { Ttl = Math.Min(s.Ttl, LegacyTtl), CacheFlush = false },
        TxtRecord t => t with { Ttl = Math.Min(t.Ttl, LegacyTtl), CacheFlush = false },
        AddressRecord a => a with { Ttl = Math.Min(a.Ttl, LegacyTtl), CacheFlush = false },
        _ => r,
    };

    /// <summary>Says goodbye (TTL 0) and closes the socket.</summary>
    public async ValueTask DisposeAsync()
    {
        ServiceAnnouncement? svc;
        lock (gate)
        {
            svc = service;
            service = null;
        }
        if (svc is not null)
        {
            SendMulticast(Records(svc, goodbye: true));
        }
        await stop.CancelAsync().ConfigureAwait(false);
        socket?.Dispose();
        if (receiver is not null)
        {
            try
            {
                await receiver.ConfigureAwait(false);
            }
            catch (Exception e)
            {
                log.LogDebug(e, "mDNS receiver");
            }
        }
        stop.Dispose();
    }
}
