using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using Droplet.Core.Common;

namespace Droplet.Core.Mdns;

/// <summary>The IPv4 interfaces mDNS runs on, and the group it uses.</summary>
public static class MdnsNetwork
{
    /// <summary>224.0.0.251.</summary>
    public static readonly IPAddress Group = IPAddress.Parse("224.0.0.251");

    /// <summary>5353.</summary>
    public const int Port = 5353;

    /// <summary>The mDNS group's endpoint.</summary>
    public static readonly IPEndPoint GroupEndPoint = new(Group, Port);

    /// <summary>An interface mDNS can use: its index and IPv4 address.</summary>
    public sealed record Interface(int Index, IPAddress Address, string Name);

    /// <summary>
    /// Up, multicast-capable IPv4 interfaces that aren't loopback, point-to-point or
    /// the tailnet (Tailscale carries no multicast). A PC can be on Wi-Fi and Ethernet,
    /// with VPN and Hyper-V adapters besides; each real one gets asked.
    /// </summary>
    public static List<Interface> Interfaces()
    {
        var list = new List<Interface>();
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up || !nic.SupportsMulticast ||
                    nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Ppp or NetworkInterfaceType.Tunnel ||
                    !nic.Supports(NetworkInterfaceComponent.IPv4))
                {
                    continue;
                }
                var props = nic.GetIPProperties();
                int index;
                try
                {
                    index = props.GetIPv4Properties()?.Index ?? -1;
                }
                catch (NetworkInformationException)
                {
                    continue;
                }
                var ip = props.UnicastAddresses
                    .Select(u => u.Address)
                    .FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a) &&
                                         !Addresses.IsTailnet(a) && !Addresses.IsLinkLocal(a));
                if (ip is null || index < 0)
                {
                    continue;
                }
                list.Add(new Interface(index, ip, nic.Name));
            }
        }
        catch (NetworkInformationException)
        {
        }
        return list;
    }

    /// <summary>
    /// Lets several sockets share a port: SO_REUSEADDR everywhere, and SO_REUSEPORT on
    /// Linux and macOS too, which is what avahi and other responders there set. On
    /// Windows SO_REUSEADDR is what lets this socket share 5353 with Windows' own mDNS
    /// responder (the DNS Client service) and browsers, which bind it the same way.
    /// </summary>
    public static void AllowSharedPort(Socket s)
    {
        ArgumentNullException.ThrowIfNull(s);
        s.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        if (OperatingSystem.IsLinux())
        {
            s.SetRawSocketOption(1 /* SOL_SOCKET */, 15 /* SO_REUSEPORT */, BitConverter.GetBytes(1));
        }
        else if (OperatingSystem.IsMacOS() || OperatingSystem.IsFreeBSD())
        {
            s.SetRawSocketOption(0xffff /* SOL_SOCKET */, 0x0200 /* SO_REUSEPORT */, BitConverter.GetBytes(1));
        }
    }

    /// <summary>Sends multicast out of one interface.</summary>
    public static void UseInterface(Socket s, Interface ifc)
    {
        ArgumentNullException.ThrowIfNull(s);
        ArgumentNullException.ThrowIfNull(ifc);
        // IP_MULTICAST_IF takes the interface index in network order on Windows (as an
        // index), or an address elsewhere; the address form works on both
        s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastInterface, ifc.Address.GetAddressBytes());
    }
}
