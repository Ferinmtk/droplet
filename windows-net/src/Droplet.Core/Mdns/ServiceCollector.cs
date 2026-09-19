using System.Net;
using System.Net.Sockets;

namespace Droplet.Core.Mdns;

/// <summary>A DNS-SD service instance found on the LAN, with all its records in.</summary>
public sealed record ServiceInstance
{
    /// <summary>The instance's full name ("slim 9b1617._droplet-peer._tcp.local.").</summary>
    public required DnsName Name { get; init; }

    /// <summary>The instance label, as shown to people ("slim 9b1617").</summary>
    public string Label => Name.Labels[0];

    /// <summary>The port from its SRV record.</summary>
    public required int Port { get; init; }

    /// <summary>The host its SRV record names.</summary>
    public required DnsName Host { get; init; }

    /// <summary>Its addresses: the host's A/AAAA records, else where the answer came from.</summary>
    public required IReadOnlyList<IPAddress> Addresses { get; init; }

    /// <summary>Its TXT pairs.</summary>
    public required IReadOnlyDictionary<string, string> Txt { get; init; }
}

/// <summary>
/// Gathers mDNS answers into service instances of one type. It's fed whole messages,
/// so it can be tested without a network, and knows which questions to ask next.
/// </summary>
public sealed class ServiceCollector(DnsName serviceType)
{
    readonly Lock gate = new();
    readonly HashSet<DnsName> instances = [];
    readonly Dictionary<DnsName, SrvRecord> srv = [];
    readonly Dictionary<DnsName, TxtRecord> txt = [];
    readonly Dictionary<DnsName, List<IPAddress>> addrs = [];
    readonly Dictionary<DnsName, List<IPAddress>> sources = [];
    readonly HashSet<DnsName> gone = [];

    /// <summary>The service type collected ("_droplet-peer._tcp.local.").</summary>
    public DnsName ServiceType { get; } = serviceType;

    /// <summary>Reads one message received from <paramref name="source"/>.</summary>
    public void Add(DnsMessage msg, IPAddress? source)
    {
        ArgumentNullException.ThrowIfNull(msg);
        if (!msg.IsResponse)
        {
            return;
        }
        if (source is { IsIPv4MappedToIPv6: true })
        {
            source = source.MapToIPv4();
        }
        lock (gate)
        {
            foreach (var r in msg.AllRecords)
            {
                switch (r)
                {
                    case PtrRecord p when p.Name.Equals(ServiceType) && p.Target.IsChildOf(ServiceType):
                        if (p.Ttl == 0)
                        {
                            // a goodbye: the instance is going away
                            Forget(p.Target);
                            gone.Add(p.Target);
                        }
                        else
                        {
                            instances.Add(p.Target);
                            gone.Remove(p.Target);
                            AddSource(p.Target, source);
                        }
                        break;
                    case SrvRecord s when s.Name.IsChildOf(ServiceType) && s.Ttl > 0:
                        srv[s.Name] = s;
                        instances.Add(s.Name);
                        AddSource(s.Name, source);
                        break;
                    case TxtRecord t when t.Name.IsChildOf(ServiceType) && t.Ttl > 0:
                        txt[t.Name] = t;
                        instances.Add(t.Name);
                        break;
                    case AddressRecord a when a.Ttl > 0:
                        {
                            var ip = a.Address;
                            if (Common.Addresses.IsUnspecified(ip) || Common.Addresses.IsMulticast(ip) ||
                                (IPAddress.IsLoopback(ip) && source is not null && !IPAddress.IsLoopback(source)))
                            {
                                continue;
                            }
                            if (!addrs.TryGetValue(a.Name, out var list))
                            {
                                addrs[a.Name] = list = [];
                            }
                            if (!list.Contains(ip))
                            {
                                list.Add(ip);
                            }
                            break;
                        }
                }
            }
        }
    }

    void AddSource(DnsName inst, IPAddress? source)
    {
        if (source is null || source.AddressFamily != AddressFamily.InterNetwork)
        {
            return;
        }
        if (!sources.TryGetValue(inst, out var list))
        {
            sources[inst] = list = [];
        }
        if (!list.Contains(source))
        {
            list.Add(source);
        }
    }

    void Forget(DnsName inst)
    {
        instances.Remove(inst);
        srv.Remove(inst);
        txt.Remove(inst);
        sources.Remove(inst);
    }

    /// <summary>Instances withdrawn (a goodbye) since the last call.</summary>
    public List<DnsName> TakeGone()
    {
        lock (gate)
        {
            var g = gone.ToList();
            gone.Clear();
            return g;
        }
    }

    /// <summary>The instances whose SRV, TXT and an address are all in.</summary>
    public List<ServiceInstance> Complete()
    {
        lock (gate)
        {
            var output = new List<ServiceInstance>();
            foreach (var inst in instances.OrderBy(i => i.ToString(), StringComparer.OrdinalIgnoreCase))
            {
                if (!srv.TryGetValue(inst, out var s) || s.Port == 0 || !txt.TryGetValue(inst, out var t))
                {
                    continue;
                }
                var list = addrs.TryGetValue(s.Target, out var a) && a.Count > 0 ? a : sources.GetValueOrDefault(inst);
                if (list is null || list.Count == 0)
                {
                    continue;
                }
                output.Add(new ServiceInstance
                {
                    Name = inst, Port = s.Port, Host = s.Target, Addresses = [.. list], Txt = t.Pairs(),
                });
            }
            return output;
        }
    }

    /// <summary>
    /// The next query: the service's PTR, plus SRV and TXT for instances missing them,
    /// and A for hosts with no address yet.
    /// </summary>
    public DnsMessage NextQuery()
    {
        var q = new DnsMessage();
        q.Questions.Add(new DnsQuestion(ServiceType, DnsType.Ptr));
        lock (gate)
        {
            var asked = 0;
            foreach (var inst in instances)
            {
                if (asked >= 8)
                {
                    break;
                }
                if (!srv.TryGetValue(inst, out var s))
                {
                    q.Questions.Add(new DnsQuestion(inst, DnsType.Srv));
                    asked++;
                }
                if (!txt.ContainsKey(inst))
                {
                    q.Questions.Add(new DnsQuestion(inst, DnsType.Txt));
                    asked++;
                }
                if (s is not null && (!addrs.TryGetValue(s.Target, out var a) || a.Count == 0))
                {
                    q.Questions.Add(new DnsQuestion(s.Target, DnsType.A));
                    asked++;
                }
            }
        }
        return q;
    }
}
