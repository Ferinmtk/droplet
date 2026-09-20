using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Droplet.Core.Common;

/// <summary>IP address classification shared by the mesh and local-first routing.</summary>
public static class Addresses
{
    static readonly (IPAddress Net, int Bits) TailnetV4 = (IPAddress.Parse("100.64.0.0"), 10);
    static readonly (IPAddress Net, int Bits) TailnetV6 = (IPAddress.Parse("fd7a:115c:a1e0::"), 48);

    /// <summary>Whether <paramref name="ip"/> is in Tailscale's ranges (100.64.0.0/10, fd7a:115c:a1e0::/48).</summary>
    public static bool IsTailnet(IPAddress ip)
    {
        ArgumentNullException.ThrowIfNull(ip);
        if (ip.IsIPv4MappedToIPv6)
        {
            ip = ip.MapToIPv4();
        }
        var (net, bits) = ip.AddressFamily == AddressFamily.InterNetwork ? TailnetV4 : TailnetV6;
        return ip.AddressFamily == net.AddressFamily && InPrefix(ip, net, bits);
    }

    /// <summary>Whether the text is an address in Tailscale's ranges.</summary>
    public static bool IsTailnet(string? address) => TryParse(address, out var ip) && IsTailnet(ip);

    /// <summary>Whether <paramref name="ip"/> is inside <paramref name="net"/>/<paramref name="bits"/>.</summary>
    public static bool InPrefix(IPAddress ip, IPAddress net, int bits)
    {
        ArgumentNullException.ThrowIfNull(ip);
        ArgumentNullException.ThrowIfNull(net);
        var a = ip.GetAddressBytes();
        var b = net.GetAddressBytes();
        if (a.Length != b.Length)
        {
            return false;
        }
        for (var i = 0; i < a.Length && bits > 0; i++, bits -= 8)
        {
            var mask = bits >= 8 ? 0xff : (byte)(0xff << (8 - bits));
            if ((a[i] & mask) != (b[i] & mask))
            {
                return false;
            }
        }
        return true;
    }

    /// <summary>Parses an address, dropping an IPv6 zone ("%eth0") and unmapping IPv4-in-IPv6.</summary>
    public static bool TryParse(string? text, out IPAddress ip)
    {
        ip = IPAddress.None;
        if (string.IsNullOrWhiteSpace(text))
        {
            return false;
        }
        var s = text.Trim();
        var pct = s.IndexOf('%', StringComparison.Ordinal);
        if (pct >= 0)
        {
            s = s[..pct];
        }
        if (!IPAddress.TryParse(s, out var parsed) || (s.Contains(':', StringComparison.Ordinal) != (parsed.AddressFamily == AddressFamily.InterNetworkV6)))
        {
            return false;
        }
        // IPAddress.TryParse accepts "1" as 0.0.0.1; only dotted quads count as IPv4 here
        if (parsed.AddressFamily == AddressFamily.InterNetwork && s.Count(c => c == '.') != 3)
        {
            return false;
        }
        ip = parsed.IsIPv4MappedToIPv6 ? parsed.MapToIPv4() : parsed;
        return true;
    }

    /// <summary>
    /// Addresses a peer may be reached at (docs/mesh.md trust list): valid, not
    /// unspecified, multicast or IPv6 link-local, without duplicates, at most
    /// <paramref name="limit"/>. Mirrors <c>trust.clean_addresses</c> in the reference.
    /// </summary>
    public static List<string> Clean(IEnumerable<string?>? items, int limit = 6)
    {
        var output = new List<string>();
        foreach (var a in items ?? [])
        {
            if (!TryParse(a, out var ip) || IsUnspecified(ip) || IsMulticast(ip) || (ip.AddressFamily == AddressFamily.InterNetworkV6 && ip.IsIPv6LinkLocal))
            {
                continue;
            }
            var s = ip.ToString();
            if (!output.Contains(s))
            {
                output.Add(s);
            }
        }
        return output.Count > limit ? output[..limit] : output;
    }

    /// <summary>0.0.0.0 or ::.</summary>
    public static bool IsUnspecified(IPAddress ip) => ip.Equals(IPAddress.Any) || ip.Equals(IPAddress.IPv6Any);

    /// <summary>224.0.0.0/4 or ff00::/8.</summary>
    public static bool IsMulticast(IPAddress ip) =>
        ip.AddressFamily == AddressFamily.InterNetwork ? (ip.GetAddressBytes()[0] & 0xf0) == 0xe0 : ip.IsIPv6Multicast;

    /// <summary>169.254.0.0/16 or fe80::/10.</summary>
    public static bool IsLinkLocal(IPAddress ip) =>
        ip.AddressFamily == AddressFamily.InterNetwork
            ? ip.GetAddressBytes() is [169, 254, ..]
            : ip.IsIPv6LinkLocal;

    // interfaces whose addresses no other device on the LAN can reach: containers,
    // VMs, VPNs (Tailscale addresses reach peers through the roster instead)
    static readonly string[] Virtual =
    [
        "docker", "br-", "veth", "virbr", "vnet", "podman", "cni", "flannel", "kube", "lxc", "lxd",
        "tailscale", "tun", "wg", "zt", "vboxnet", "vmnet", "lo",
    ];

    static readonly string[] VirtualDescriptions =
    [
        "hyper-v", "virtualbox", "vmware", "tailscale", "wireguard", "zerotier", "tap-windows", "wintun",
        "loopback", "vethernet", "docker", "wsl", "openvpn",
    ];

    /// <summary>
    /// Whether an interface only reaches this machine, a VM, a container or a VPN,
    /// by type, name (Linux) or description (Windows).
    /// </summary>
    public static bool IsVirtual(NetworkInterface nic)
    {
        ArgumentNullException.ThrowIfNull(nic);
        if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel or NetworkInterfaceType.Ppp)
        {
            return true;
        }
        var name = nic.Name.ToLowerInvariant();
        var desc = nic.Description.ToLowerInvariant();
        // Linux names interfaces by kind (docker0, wg0); Windows names them for
        // people ("Local Area Connection", which a prefix "lo" would wrongly match)
        // and describes the driver, which is what tells a virtual adapter apart
        if (!OperatingSystem.IsWindows() && Virtual.Any(v => name.StartsWith(v, StringComparison.Ordinal)))
        {
            return true;
        }
        return VirtualDescriptions.Any(v => desc.Contains(v, StringComparison.Ordinal) || name.Contains(v, StringComparison.Ordinal));
    }

    /// <summary>
    /// This machine's LAN addresses, the main one first: up, non-virtual interfaces;
    /// no loopback, link-local, multicast or tailnet addresses (docs/mesh.md §2).
    /// </summary>
    public static List<string> Lan()
    {
        var found = new List<IPAddress>();
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up || IsVirtual(nic))
                {
                    continue;
                }
                foreach (var u in nic.GetIPProperties().UnicastAddresses)
                {
                    var ip = u.Address;
                    if (IPAddress.IsLoopback(ip) || IsLinkLocal(ip) || IsMulticast(ip) || IsUnspecified(ip) || IsTailnet(ip))
                    {
                        continue;
                    }
                    found.Add(ip);
                }
            }
        }
        catch (NetworkInformationException)
        {
            // no interface list: nothing to announce
        }
        var primary = PrimaryIPv4();
        return found
            .Select(ip => ip.ToString())
            .Distinct()
            .OrderBy(a => a != primary)
            .ThenBy(a => a.Contains(':', StringComparison.Ordinal))
            .ThenBy(a => a, StringComparer.Ordinal)
            .ToList();
    }

    /// <summary>The address this machine would use to reach the internet: its main LAN address.</summary>
    public static string? PrimaryIPv4()
    {
        try
        {
            using var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            // nothing is sent: connecting a UDP socket only picks the interface (TEST-NET-1)
            s.Connect(new IPEndPoint(IPAddress.Parse("192.0.2.1"), 9));
            return (s.LocalEndPoint as IPEndPoint)?.Address.ToString();
        }
        catch (SocketException)
        {
            return null;
        }
    }

    /// <summary>
    /// A short description of the up interfaces and their addresses. A change means the
    /// network changed (Wi-Fi joined or left, a VPN up or down).
    /// </summary>
    public static string InterfaceSignature()
    {
        try
        {
            var parts = new List<string>();
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                {
                    continue;
                }
                foreach (var u in nic.GetIPProperties().UnicastAddresses)
                {
                    parts.Add(nic.Name + "=" + u.Address);
                }
            }
            parts.Sort(StringComparer.Ordinal);
            return string.Join(',', parts);
        }
        catch (NetworkInformationException e)
        {
            return "error: " + e.Message;
        }
    }

    /// <summary>Whether this machine is on a tailnet right now: an up interface has a Tailscale address.</summary>
    public static bool TailscaleUp()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up)
                .SelectMany(n => n.GetIPProperties().UnicastAddresses)
                .Any(u => IsTailnet(u.Address));
        }
        catch (NetworkInformationException)
        {
            return false;
        }
    }

    /// <summary>"host:port", with brackets for IPv6.</summary>
    public static string HostPort(string host, int port) =>
        host.Contains(':', StringComparison.Ordinal) && !host.StartsWith('[') ? $"[{host}]:{port}" : $"{host}:{port}";
}
