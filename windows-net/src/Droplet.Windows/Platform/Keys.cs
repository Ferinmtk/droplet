namespace Droplet.Windows.Platform;

/// <summary>
/// A Windows virtual key, and whether it's an "extended" key. Extended keys (the arrows and
/// the block above them, the media keys, the Windows key) need KEYEVENTF_EXTENDEDKEY, or
/// Windows reads, say, ArrowLeft as numpad 4.
/// </summary>
internal readonly record struct VirtualKey(ushort Vk, bool Extended = false);

/// <summary>
/// The protocol's key names (the web's <c>KeyboardEvent.key</c>, docs/remote.md §3.1) as
/// virtual keys: a port of the Go app's <c>remote/keys.go</c>. Plain numbers, so it's
/// tested anywhere.
/// </summary>
internal static class Keys
{
    public const ushort Back = 0x08;
    public const ushort Tab = 0x09;
    public const ushort Return = 0x0D;
    public const ushort Escape = 0x1B;
    public const ushort Space = 0x20;
    public const ushort Prior = 0x21; // Page Up
    public const ushort Next = 0x22;  // Page Down
    public const ushort End = 0x23;
    public const ushort Home = 0x24;
    public const ushort Left = 0x25;
    public const ushort Up = 0x26;
    public const ushort Right = 0x27;
    public const ushort Down = 0x28;
    public const ushort Snapshot = 0x2C; // Print Screen
    public const ushort Insert = 0x2D;
    public const ushort Delete = 0x2E;
    public const ushort LWin = 0x5B;
    public const ushort Apps = 0x5D; // the context menu key
    public const ushort F1 = 0x70;
    public const ushort LShift = 0xA0;
    public const ushort LControl = 0xA2;
    public const ushort LMenu = 0xA4; // left Alt
    public const ushort VolumeMute = 0xAD;
    public const ushort VolumeDown = 0xAE;
    public const ushort VolumeUp = 0xAF;
    public const ushort MediaNextTrack = 0xB0;
    public const ushort MediaPrevTrack = 0xB1;
    public const ushort MediaStop = 0xB2;
    public const ushort MediaPlayPause = 0xB3;

    static readonly Dictionary<string, VirtualKey> Named = new(StringComparer.Ordinal)
    {
        ["Enter"] = new(Return),
        ["Backspace"] = new(Back),
        ["Tab"] = new(Tab),
        ["Escape"] = new(Escape),
        ["Esc"] = new(Escape),
        ["Space"] = new(Space),
        [" "] = new(Space), // what KeyboardEvent.key says for the space bar
        ["Spacebar"] = new(Space),
        ["Delete"] = new(Delete, true),
        ["Del"] = new(Delete, true),
        ["Insert"] = new(Insert, true),
        ["Home"] = new(Home, true),
        ["End"] = new(End, true),
        ["PageUp"] = new(Prior, true),
        ["PageDown"] = new(Next, true),
        ["ArrowUp"] = new(Up, true),
        ["ArrowDown"] = new(Down, true),
        ["ArrowLeft"] = new(Left, true),
        ["ArrowRight"] = new(Right, true),
        ["Up"] = new(Up, true),
        ["Down"] = new(Down, true),
        ["Left"] = new(Left, true),
        ["Right"] = new(Right, true),
        ["PrintScreen"] = new(Snapshot, true),
        ["ContextMenu"] = new(Apps, true),
        ["MediaPlayPause"] = new(MediaPlayPause, true),
        ["MediaNext"] = new(MediaNextTrack, true),
        ["MediaTrackNext"] = new(MediaNextTrack, true),
        ["MediaPrevious"] = new(MediaPrevTrack, true),
        ["MediaTrackPrevious"] = new(MediaPrevTrack, true),
        ["MediaStop"] = new(MediaStop, true),
        ["AudioVolumeUp"] = new(VolumeUp, true),
        ["AudioVolumeDown"] = new(VolumeDown, true),
        ["AudioVolumeMute"] = new(VolumeMute, true),
        // the modifiers themselves: a lone "Meta" opens the Start menu
        ["Control"] = new(LControl),
        ["Shift"] = new(LShift),
        ["Alt"] = new(LMenu),
        ["Meta"] = new(LWin, true),
        ["OS"] = new(LWin, true),
    };

    /// <summary>The modifiers, in the order they're pressed.</summary>
    static readonly (string Name, VirtualKey Key)[] ModOrder =
    [
        ("ctrl", new(LControl)), ("alt", new(LMenu)), ("shift", new(LShift)), ("meta", new(LWin, true)),
    ];

    /// <summary>A protocol key name as a virtual key: named keys, a–z, A–Z, 0–9 and F1–F24.</summary>
    public static bool TryLookup(string? name, out VirtualKey key)
    {
        key = default;
        if (string.IsNullOrEmpty(name))
        {
            return false;
        }
        if (Named.TryGetValue(name, out key))
        {
            return true;
        }
        if (name.Length == 1)
        {
            var c = name[0];
            if (c is >= 'a' and <= 'z')
            {
                key = new((ushort)(c - 'a' + 'A'));
                return true;
            }
            if (c is >= 'A' and <= 'Z' or >= '0' and <= '9')
            {
                key = new(c);
                return true;
            }
            return false;
        }
        if (name.Length is 2 or 3 && name[0] == 'F' && name.AsSpan(1).IndexOfAnyExceptInRange('0', '9') < 0)
        {
            var n = int.Parse(name.AsSpan(1), System.Globalization.CultureInfo.InvariantCulture);
            if (n is >= 1 and <= 24)
            {
                key = new((ushort)(F1 + n - 1));
                return true;
            }
        }
        return false;
    }

    /// <summary>
    /// A mods list as the keys to hold, in a fixed order, without duplicates. Unknown names
    /// are skipped; aliases a web page might send ("control", "cmd", "win", "super") count.
    /// </summary>
    public static List<VirtualKey> Mods(IEnumerable<string>? mods)
    {
        var want = new HashSet<string>(StringComparer.Ordinal);
        foreach (var m in mods ?? [])
        {
            var s = (m ?? "").Trim();
            if (Is(s, "ctrl", "control"))
            {
                want.Add("ctrl");
            }
            else if (Is(s, "alt", "option"))
            {
                want.Add("alt");
            }
            else if (Is(s, "shift"))
            {
                want.Add("shift");
            }
            else if (Is(s, "meta", "win", "super", "cmd", "os"))
            {
                want.Add("meta");
            }
        }
        return ModOrder.Where(m => want.Contains(m.Name)).Select(m => m.Key).ToList();
    }

    static bool Is(string s, params string[] names) => names.Any(n => s.Equals(n, StringComparison.OrdinalIgnoreCase));
}
