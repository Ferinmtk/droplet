using System.Buffers.Binary;

namespace Droplet.Windows.Tray;

/// <summary>Picks an image out of an .ico file (pure, so it's tested anywhere).</summary>
internal static class IconFile
{
    /// <summary>
    /// The image to draw at <paramref name="size"/> pixels: the smallest one at least that big
    /// (Windows scales down more cleanly than up), else the biggest. Returns the image's bytes
    /// (PNG or DIB, as CreateIconFromResourceEx takes them) and its size.
    /// </summary>
    public static (ReadOnlyMemory<byte> Image, int Size) Pick(ReadOnlyMemory<byte> ico, int size)
    {
        var s = ico.Span;
        if (s.Length < 6 || BinaryPrimitives.ReadUInt16LittleEndian(s[2..]) != 1)
        {
            throw new FormatException("not an icon file");
        }
        var count = BinaryPrimitives.ReadUInt16LittleEndian(s[4..]);
        (int Offset, int Length, int Size)? best = null, biggest = null;
        for (var i = 0; i < count; i++)
        {
            var e = s.Slice(6 + 16 * i, 16);
            var dim = e[0] == 0 ? 256 : e[0];
            var length = (int)BinaryPrimitives.ReadUInt32LittleEndian(e[8..]);
            var offset = (int)BinaryPrimitives.ReadUInt32LittleEndian(e[12..]);
            if (offset < 0 || length <= 0 || offset > s.Length - length)
            {
                throw new FormatException("damaged icon file");
            }
            var entry = (offset, length, dim);
            if (dim >= size && (best is null || dim < best.Value.Size))
            {
                best = entry;
            }
            if (biggest is null || dim > biggest.Value.Size)
            {
                biggest = entry;
            }
        }
        var pick = best ?? biggest ?? throw new FormatException("empty icon file");
        return (ico.Slice(pick.Offset, pick.Length), pick.Size);
    }
}
