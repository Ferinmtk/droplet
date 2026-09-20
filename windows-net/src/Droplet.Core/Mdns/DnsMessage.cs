using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Droplet.Core.Mdns;

/// <summary>DNS record types mDNS/DNS-SD uses.</summary>
public static class DnsType
{
    /// <summary>IPv4 address.</summary>
    public const ushort A = 1;

    /// <summary>Pointer: service type to instance.</summary>
    public const ushort Ptr = 12;

    /// <summary>Text: DNS-SD key=value pairs.</summary>
    public const ushort Txt = 16;

    /// <summary>IPv6 address.</summary>
    public const ushort Aaaa = 28;

    /// <summary>Service: target host and port.</summary>
    public const ushort Srv = 33;

    /// <summary>Negative answers (read and ignored).</summary>
    public const ushort Nsec = 47;

    /// <summary>Any type (questions only).</summary>
    public const ushort Any = 255;
}

/// <summary>
/// A domain name. Kept as its labels, so a label may hold a dot or a space (DNS-SD
/// instance names do); <see cref="ToString"/> escapes those. Compared without regard
/// to ASCII case, as DNS does.
/// </summary>
public sealed class DnsName : IEquatable<DnsName>
{
    /// <summary>The labels, most specific first, without the root.</summary>
    public IReadOnlyList<string> Labels { get; }

    /// <summary>A name from its labels.</summary>
    public DnsName(IEnumerable<string> labels)
    {
        Labels = labels.ToArray();
        if (Labels.Any(l => l.Length == 0 || Encoding.UTF8.GetByteCount(l) > 63))
        {
            throw new ArgumentException("a DNS label must be 1 to 63 bytes", nameof(labels));
        }
    }

    /// <summary>Parses "instance._service._tcp.local." (a trailing dot is optional; "\." is a dot in a label).</summary>
    public static DnsName Parse(string dotted)
    {
        ArgumentNullException.ThrowIfNull(dotted);
        var labels = new List<string>();
        var cur = new StringBuilder();
        for (var i = 0; i < dotted.Length; i++)
        {
            var c = dotted[i];
            if (c == '\\' && i + 1 < dotted.Length)
            {
                cur.Append(dotted[++i]);
            }
            else if (c == '.')
            {
                if (cur.Length > 0)
                {
                    labels.Add(cur.ToString());
                }
                cur.Clear();
            }
            else
            {
                cur.Append(c);
            }
        }
        if (cur.Length > 0)
        {
            labels.Add(cur.ToString());
        }
        return new DnsName(labels);
    }

    /// <summary>A name made of one more label in front of <paramref name="parent"/>.</summary>
    public static DnsName Child(string label, DnsName parent)
    {
        ArgumentNullException.ThrowIfNull(parent);
        return new DnsName([label, .. parent.Labels]);
    }

    /// <summary>The name without its first label.</summary>
    public DnsName Parent => new(Labels.Skip(1));

    /// <summary>Whether this is <paramref name="parent"/> with exactly one more label.</summary>
    public bool IsChildOf(DnsName parent)
    {
        ArgumentNullException.ThrowIfNull(parent);
        return Labels.Count == parent.Labels.Count + 1 && Parent.Equals(parent);
    }

    /// <inheritdoc/>
    public bool Equals(DnsName? other) =>
        other is not null && Labels.Count == other.Labels.Count &&
        Labels.Zip(other.Labels).All(p => string.Equals(p.First, p.Second, StringComparison.OrdinalIgnoreCase));

    /// <inheritdoc/>
    public override bool Equals(object? obj) => Equals(obj as DnsName);

    /// <inheritdoc/>
    public override int GetHashCode()
    {
        var h = new HashCode();
        foreach (var l in Labels)
        {
            h.Add(l, StringComparer.OrdinalIgnoreCase);
        }
        return h.ToHashCode();
    }

    /// <summary>The dotted form, with a trailing dot.</summary>
    public override string ToString() =>
        string.Concat(Labels.Select(l => l.Replace("\\", "\\\\", StringComparison.Ordinal).Replace(".", "\\.", StringComparison.Ordinal) + "."));
}

/// <summary>A question.</summary>
/// <param name="Name">What's asked about.</param>
/// <param name="Type">The record type wanted.</param>
/// <param name="UnicastResponse">mDNS's QU bit: answer by unicast.</param>
public sealed record DnsQuestion(DnsName Name, ushort Type, bool UnicastResponse = false);

/// <summary>A resource record.</summary>
public abstract record DnsRecord(DnsName Name, ushort Type, uint Ttl, bool CacheFlush)
{
    /// <summary>Writes the record data (after the header).</summary>
    internal abstract void WriteData(DnsWriter w);
}

/// <summary>PTR: from a service type to an instance.</summary>
public sealed record PtrRecord(DnsName Name, uint Ttl, DnsName Target) : DnsRecord(Name, DnsType.Ptr, Ttl, false)
{
    internal override void WriteData(DnsWriter w) => w.WriteName(Target);
}

/// <summary>SRV: an instance's host and port.</summary>
public sealed record SrvRecord(DnsName Name, uint Ttl, bool CacheFlush, ushort Priority, ushort Weight, ushort Port, DnsName Target)
    : DnsRecord(Name, DnsType.Srv, Ttl, CacheFlush)
{
    internal override void WriteData(DnsWriter w)
    {
        w.WriteUInt16(Priority);
        w.WriteUInt16(Weight);
        w.WriteUInt16(Port);
        // RFC 2782: no compression in SRV targets (older resolvers can't follow it)
        w.WriteName(Target, compress: false);
    }
}

/// <summary>TXT: DNS-SD's key=value strings.</summary>
public sealed record TxtRecord(DnsName Name, uint Ttl, bool CacheFlush, IReadOnlyList<byte[]> Strings)
    : DnsRecord(Name, DnsType.Txt, Ttl, CacheFlush)
{
    /// <summary>A TXT record from key=value pairs, in order.</summary>
    public static TxtRecord FromPairs(DnsName name, uint ttl, IEnumerable<KeyValuePair<string, string>> pairs) =>
        new(name, ttl, true, pairs.Select(p => Encoding.UTF8.GetBytes(p.Key + "=" + p.Value)).Select(b => b.Length > 255 ? b[..255] : b).ToList());

    /// <summary>
    /// The pairs (RFC 6763 §6): keys are case-insensitive and only the first counts;
    /// strings without "=" (boolean attributes) and non-UTF-8 values are skipped.
    /// </summary>
    public Dictionary<string, string> Pairs()
    {
        var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var raw in Strings)
        {
            string s;
            try
            {
                s = new UTF8Encoding(false, true).GetString(raw);
            }
            catch (DecoderFallbackException)
            {
                continue;
            }
            var eq = s.IndexOf('=', StringComparison.Ordinal);
            if (eq <= 0)
            {
                continue;
            }
            d.TryAdd(s[..eq], s[(eq + 1)..]);
        }
        return d;
    }

    internal override void WriteData(DnsWriter w)
    {
        if (Strings.Count == 0)
        {
            w.WriteByte(0); // an empty TXT still holds one empty string
            return;
        }
        foreach (var s in Strings)
        {
            w.WriteByte((byte)s.Length);
            w.WriteBytes(s);
        }
    }
}

/// <summary>A or AAAA: a host's address.</summary>
public sealed record AddressRecord(DnsName Name, uint Ttl, bool CacheFlush, IPAddress Address)
    : DnsRecord(Name, Address.AddressFamily == AddressFamily.InterNetwork ? DnsType.A : DnsType.Aaaa, Ttl, CacheFlush)
{
    internal override void WriteData(DnsWriter w) => w.WriteBytes(Address.GetAddressBytes());
}

/// <summary>A record of a type this code doesn't use.</summary>
public sealed record OtherRecord(DnsName Name, ushort Type, uint Ttl, bool CacheFlush, byte[] Data) : DnsRecord(Name, Type, Ttl, CacheFlush)
{
    internal override void WriteData(DnsWriter w) => w.WriteBytes(Data);
}

/// <summary>A DNS message (RFC 1035 §4), as mDNS uses it (RFC 6762).</summary>
public sealed class DnsMessage
{
    /// <summary>The id: 0 in multicast mDNS; a legacy unicast answer echoes the query's.</summary>
    public ushort Id { get; set; }

    /// <summary>A response (QR), rather than a query.</summary>
    public bool IsResponse { get; set; }

    /// <summary>An authoritative answer (AA), as every mDNS response is.</summary>
    public bool Authoritative { get; set; }

    /// <summary>The questions.</summary>
    public List<DnsQuestion> Questions { get; } = [];

    /// <summary>The answers.</summary>
    public List<DnsRecord> Answers { get; } = [];

    /// <summary>The authority records (probes put their proposed records here).</summary>
    public List<DnsRecord> Authorities { get; } = [];

    /// <summary>The additional records.</summary>
    public List<DnsRecord> Additionals { get; } = [];

    /// <summary>Every record in the message.</summary>
    public IEnumerable<DnsRecord> AllRecords => Answers.Concat(Authorities).Concat(Additionals);

    /// <summary>The wire form.</summary>
    public byte[] ToBytes()
    {
        var w = new DnsWriter();
        w.WriteUInt16(Id);
        ushort flags = 0;
        if (IsResponse)
        {
            flags |= 0x8000;
        }
        if (Authoritative)
        {
            flags |= 0x0400;
        }
        w.WriteUInt16(flags);
        w.WriteUInt16((ushort)Questions.Count);
        w.WriteUInt16((ushort)Answers.Count);
        w.WriteUInt16((ushort)Authorities.Count);
        w.WriteUInt16((ushort)Additionals.Count);
        foreach (var q in Questions)
        {
            w.WriteName(q.Name);
            w.WriteUInt16(q.Type);
            w.WriteUInt16((ushort)(1 | (q.UnicastResponse ? 0x8000 : 0)));
        }
        foreach (var r in AllRecords)
        {
            w.WriteName(r.Name);
            w.WriteUInt16(r.Type);
            w.WriteUInt16((ushort)(1 | (r.CacheFlush ? 0x8000 : 0)));
            w.WriteUInt32(r.Ttl);
            var lengthAt = w.Reserve(2);
            var start = w.Length;
            r.WriteData(w);
            w.Patch(lengthAt, (ushort)(w.Length - start));
        }
        return w.ToArray();
    }

    /// <summary>Parses a message; null when it's malformed.</summary>
    public static DnsMessage? Parse(ReadOnlySpan<byte> data)
    {
        try
        {
            var r = new DnsReader(data.ToArray());
            var m = new DnsMessage { Id = r.ReadUInt16() };
            var flags = r.ReadUInt16();
            m.IsResponse = (flags & 0x8000) != 0;
            m.Authoritative = (flags & 0x0400) != 0;
            if ((flags & 0x7800) != 0)
            {
                return null; // not a standard query or response
            }
            int qd = r.ReadUInt16(), an = r.ReadUInt16(), ns = r.ReadUInt16(), ar = r.ReadUInt16();
            for (var i = 0; i < qd; i++)
            {
                var name = r.ReadName();
                var type = r.ReadUInt16();
                var cls = r.ReadUInt16();
                m.Questions.Add(new DnsQuestion(name, type, (cls & 0x8000) != 0));
            }
            foreach (var (count, list) in new[] { (an, m.Answers), (ns, m.Authorities), (ar, m.Additionals) })
            {
                for (var i = 0; i < count; i++)
                {
                    list.Add(r.ReadRecord());
                }
            }
            return m;
        }
        catch (Exception e) when (e is FormatException or ArgumentException or IndexOutOfRangeException or ArgumentOutOfRangeException)
        {
            return null;
        }
    }
}

/// <summary>Writes DNS wire format, compressing names.</summary>
internal sealed class DnsWriter
{
    readonly List<byte> buf = new(512);
    readonly Dictionary<string, int> offsets = new(StringComparer.OrdinalIgnoreCase);

    public int Length => buf.Count;

    public void WriteByte(byte b) => buf.Add(b);

    public void WriteBytes(ReadOnlySpan<byte> b)
    {
        foreach (var x in b)
        {
            buf.Add(x);
        }
    }

    public void WriteUInt16(ushort v)
    {
        buf.Add((byte)(v >> 8));
        buf.Add((byte)v);
    }

    public void WriteUInt32(uint v)
    {
        WriteUInt16((ushort)(v >> 16));
        WriteUInt16((ushort)v);
    }

    public int Reserve(int n)
    {
        var at = buf.Count;
        for (var i = 0; i < n; i++)
        {
            buf.Add(0);
        }
        return at;
    }

    public void Patch(int at, ushort v)
    {
        buf[at] = (byte)(v >> 8);
        buf[at + 1] = (byte)v;
    }

    public void WriteName(DnsName name, bool compress = true)
    {
        var labels = name.Labels;
        for (var i = 0; i < labels.Count; i++)
        {
            var suffix = string.Join('\0', labels.Skip(i));
            if (compress && offsets.TryGetValue(suffix, out var at))
            {
                WriteUInt16((ushort)(0xc000 | at));
                return;
            }
            if (buf.Count < 0x3fff)
            {
                offsets.TryAdd(suffix, buf.Count);
            }
            var bytes = Encoding.UTF8.GetBytes(labels[i]);
            buf.Add((byte)bytes.Length);
            WriteBytes(bytes);
        }
        buf.Add(0);
    }

    public byte[] ToArray() => [.. buf];
}

/// <summary>Reads DNS wire format, following compression pointers safely.</summary>
internal sealed class DnsReader(byte[] data)
{
    int pos;

    public ushort ReadUInt16()
    {
        var v = BinaryPrimitives.ReadUInt16BigEndian(data.AsSpan(pos, 2));
        pos += 2;
        return v;
    }

    public uint ReadUInt32()
    {
        var v = BinaryPrimitives.ReadUInt32BigEndian(data.AsSpan(pos, 4));
        pos += 4;
        return v;
    }

    public DnsName ReadName()
    {
        var (name, next) = ReadNameAt(pos);
        pos = next;
        return name;
    }

    (DnsName, int) ReadNameAt(int at)
    {
        var labels = new List<string>();
        var end = -1;
        var jumps = 0;
        var total = 0;
        while (true)
        {
            var len = data[at];
            if (len == 0)
            {
                at++;
                break;
            }
            if ((len & 0xc0) == 0xc0)
            {
                var target = ((len & 0x3f) << 8) | data[at + 1];
                if (end < 0)
                {
                    end = at + 2;
                }
                // a pointer must point backwards, and there can't be many: no loops
                if (target >= at || ++jumps > 32)
                {
                    throw new FormatException("bad compression pointer");
                }
                at = target;
                continue;
            }
            if ((len & 0xc0) != 0)
            {
                throw new FormatException("bad label");
            }
            total += len + 1;
            if (total > 255)
            {
                throw new FormatException("name too long");
            }
            labels.Add(Encoding.UTF8.GetString(data, at + 1, len));
            at += 1 + len;
        }
        return (new DnsName(labels), end >= 0 ? end : at);
    }

    public DnsRecord ReadRecord()
    {
        var name = ReadName();
        var type = ReadUInt16();
        var cls = ReadUInt16();
        var ttl = ReadUInt32();
        var len = ReadUInt16();
        if (pos + len > data.Length)
        {
            throw new FormatException("record data past the end");
        }
        var start = pos;
        var end = pos + len;
        var flush = (cls & 0x8000) != 0;
        DnsRecord record;
        switch (type)
        {
            case DnsType.Ptr:
                record = new PtrRecord(name, ttl, ReadName());
                break;
            case DnsType.Srv:
                {
                    var prio = ReadUInt16();
                    var weight = ReadUInt16();
                    var port = ReadUInt16();
                    record = new SrvRecord(name, ttl, flush, prio, weight, port, ReadName());
                    break;
                }
            case DnsType.Txt:
                {
                    var strings = new List<byte[]>();
                    while (pos < end)
                    {
                        var n = data[pos++];
                        if (pos + n > end)
                        {
                            throw new FormatException("TXT string past the end");
                        }
                        if (n > 0)
                        {
                            strings.Add(data.AsSpan(pos, n).ToArray());
                        }
                        pos += n;
                    }
                    record = new TxtRecord(name, ttl, flush, strings);
                    break;
                }
            case DnsType.A when len == 4:
            case DnsType.Aaaa when len == 16:
                record = new AddressRecord(name, ttl, flush, new IPAddress(data.AsSpan(pos, len)));
                break;
            default:
                record = new OtherRecord(name, type, ttl, flush, data.AsSpan(start, len).ToArray());
                break;
        }
        pos = end;
        return record;
    }
}
