using Droplet.Core.Platform;

namespace Droplet.Windows.Platform;

/// <summary>A mouse button.</summary>
internal enum MouseButton
{
    /// <summary>None (not a button stroke).</summary>
    None,

    /// <summary>The primary button.</summary>
    Left,

    /// <summary>The secondary button.</summary>
    Right,

    /// <summary>The middle button.</summary>
    Middle,
}

/// <summary>Which fields of a <see cref="Stroke"/> apply.</summary>
internal enum StrokeKind
{
    /// <summary>Moves the pointer by Dx, Dy whole pixels.</summary>
    Move,

    /// <summary>Presses (Down) or releases a mouse button.</summary>
    Button,

    /// <summary>Turns the wheel by Delta (positive: away from the user, scrolling up, Windows' convention).</summary>
    Wheel,

    /// <summary>Tilts the wheel by Delta (positive: scrolling right).</summary>
    HWheel,

    /// <summary>Presses (Down) or releases a virtual key.</summary>
    Key,

    /// <summary>Types one UTF-16 code unit (KEYEVENTF_UNICODE).</summary>
    Unicode,
}

/// <summary>One low-level input, close to one Windows INPUT structure.</summary>
internal readonly record struct Stroke(
    StrokeKind Kind, int Dx = 0, int Dy = 0, MouseButton Button = MouseButton.None, bool Down = false, int Delta = 0,
    VirtualKey Key = default, char Unit = '\0');

/// <summary>
/// Turns the protocol's input events into strokes: a port of the Go app's
/// <c>remote/input.go</c>. It carries the fractions of pixels and wheel notches from one
/// event to the next, and remembers which buttons are held, so they can be let go if the
/// controller goes away mid-drag. Not thread-safe (the dispatcher applies input in order,
/// one message at a time).
/// </summary>
internal sealed class InputTranslator
{
    /// <summary>One wheel notch (a line) in Windows' units.</summary>
    public const int WheelDelta = 120;

    // limits on one event, so a buggy or hostile controller can't fling the pointer to
    // infinity or queue a minute of scrolling
    const double MaxMove = 10000; // pixels per event
    const double MaxScroll = 100; // lines per event
    const int MaxClicks = 3;

    double fx, fy; // pixel fractions not yet moved
    double wy, wx; // wheel fractions (in WheelDelta units) not yet sent
    readonly Dictionary<MouseButton, bool> held = [];

    /// <summary>One input message's events, in order.</summary>
    public List<Stroke> Translate(IEnumerable<InputEvent> events)
    {
        ArgumentNullException.ThrowIfNull(events);
        var output = new List<Stroke>();
        foreach (var e in events)
        {
            Event(output, e);
        }
        return output;
    }

    static MouseButton ParseButton(string? s) => s switch
    {
        "left" or "" or null => MouseButton.Left,
        "right" => MouseButton.Right,
        "middle" => MouseButton.Middle,
        _ => MouseButton.None,
    };

    void Event(List<Stroke> output, InputEvent e)
    {
        switch (e.Kind)
        {
            case "move":
                Move(output, e.Dx, e.Dy);
                break;
            case "button":
                {
                    var b = ParseButton(e.Button);
                    if (b == MouseButton.None)
                    {
                        return;
                    }
                    held[b] = e.Down;
                    output.Add(new Stroke(StrokeKind.Button, Button: b, Down: e.Down));
                    break;
                }
            case "click":
                {
                    var b = ParseButton(e.Button);
                    if (b == MouseButton.None)
                    {
                        return;
                    }
                    var n = Math.Clamp(e.Count, 1, MaxClicks);
                    if (held.GetValueOrDefault(b))
                    {
                        // a click while the button is held down: let go first, so the click is a real press and release
                        output.Add(new Stroke(StrokeKind.Button, Button: b));
                    }
                    held.Remove(b);
                    for (var i = 0; i < n; i++)
                    {
                        output.Add(new Stroke(StrokeKind.Button, Button: b, Down: true));
                        output.Add(new Stroke(StrokeKind.Button, Button: b));
                    }
                    break;
                }
            case "scroll":
                Scroll(output, e.Dx, e.Dy);
                break;
            case "text":
                TypeText(output, e.Text ?? "");
                break;
            case "key":
                {
                    var mods = Keys.Mods(e.Mods);
                    if (Keys.TryLookup(e.Key, out var k))
                    {
                        PressKey(output, k, mods);
                    }
                    else if (e.Key is { } s && s.EnumerateRunes().Count() == 1 && mods.Count == 0)
                    {
                        // a character without a key name (like "é" or "/"): type it, unless it's
                        // meant as a shortcut that can't be expressed
                        TypeText(output, s);
                    }
                    break;
                }
        }
        // unknown event kinds are ignored, as the protocol asks
    }

    static double Clamp(double v, double limit) => double.IsNaN(v) ? 0 : Math.Clamp(v, -limit, limit);

    /// <summary>The integer part toward zero, forgiving the float error of summed fractions (ten moves of 0.3 make 3 pixels, not 2.9999…).</summary>
    static double Whole(double v) => Math.Truncate(v + Math.CopySign(1e-9, v));

    /// <summary>Adds whole pixels and keeps the fraction; consecutive moves are merged into one stroke.</summary>
    void Move(List<Stroke> output, double dx, double dy)
    {
        fx += Clamp(dx, MaxMove);
        fy += Clamp(dy, MaxMove);
        var ix = Whole(fx);
        var iy = Whole(fy);
        fx -= ix;
        fy -= iy;
        if (ix == 0 && iy == 0)
        {
            return;
        }
        if (output.Count > 0 && output[^1].Kind == StrokeKind.Move)
        {
            var last = output[^1];
            output[^1] = last with { Dx = last.Dx + (int)ix, Dy = last.Dy + (int)iy };
            return;
        }
        output.Add(new Stroke(StrokeKind.Move, (int)ix, (int)iy));
    }

    /// <summary>
    /// Lines to wheel units. The protocol's positive dy scrolls down, which on Windows is a
    /// negative wheel delta; positive dx scrolls right, a positive horizontal delta.
    /// </summary>
    void Scroll(List<Stroke> output, double dx, double dy)
    {
        wy += -Clamp(dy, MaxScroll) * WheelDelta;
        wx += Clamp(dx, MaxScroll) * WheelDelta;
        var v = Whole(wy);
        if (v != 0)
        {
            wy -= v;
            output.Add(new Stroke(StrokeKind.Wheel, Delta: (int)v));
        }
        var h = Whole(wx);
        if (h != 0)
        {
            wx -= h;
            output.Add(new Stroke(StrokeKind.HWheel, Delta: (int)h));
        }
    }

    /// <summary>Lets go of every button still held, and forgets carried fractions.</summary>
    public List<Stroke> Release()
    {
        var output = new List<Stroke>();
        foreach (var b in (ReadOnlySpan<MouseButton>)[MouseButton.Left, MouseButton.Right, MouseButton.Middle])
        {
            if (held.GetValueOrDefault(b))
            {
                output.Add(new Stroke(StrokeKind.Button, Button: b));
            }
        }
        held.Clear();
        (fx, fy, wx, wy) = (0, 0, 0, 0);
        return output;
    }

    /// <summary>Presses <paramref name="key"/> with the modifiers held, then releases them in reverse.</summary>
    public static void PressKey(List<Stroke> output, VirtualKey key, IReadOnlyList<VirtualKey> mods)
    {
        ArgumentNullException.ThrowIfNull(output);
        ArgumentNullException.ThrowIfNull(mods);
        var hold = mods.Where(m => m.Vk != key.Vk).ToList(); // "Shift" with mods ["shift"]: pressed once
        foreach (var m in hold)
        {
            output.Add(new Stroke(StrokeKind.Key, Key: m, Down: true));
        }
        output.Add(new Stroke(StrokeKind.Key, Key: key, Down: true));
        output.Add(new Stroke(StrokeKind.Key, Key: key));
        for (var i = hold.Count - 1; i >= 0; i--)
        {
            output.Add(new Stroke(StrokeKind.Key, Key: hold[i]));
        }
    }

    /// <summary>
    /// Types text as Unicode keystrokes: each UTF-16 code unit (both halves of a surrogate
    /// pair, for emoji) pressed and released. Line breaks and tabs become real Enter and Tab
    /// presses, which apps treat differently from the characters; other control characters
    /// are dropped.
    /// </summary>
    public static void TypeText(List<Stroke> output, string text)
    {
        ArgumentNullException.ThrowIfNull(output);
        ArgumentNullException.ThrowIfNull(text);
        var prevCr = false;
        Span<char> units = stackalloc char[2];
        foreach (var r in text.EnumerateRunes())
        {
            var v = r.Value;
            if (v == '\n' && prevCr)
            {
                // the \n of a \r\n pair: Enter was pressed for the \r
            }
            else if (v is '\r' or '\n')
            {
                PressKey(output, new VirtualKey(Keys.Return), []);
            }
            else if (v == '\t')
            {
                PressKey(output, new VirtualKey(Keys.Tab), []);
            }
            else if (v is < 0x20 or 0x7f or (>= 0x80 and < 0xa0))
            {
                // control characters have no keystroke
            }
            else
            {
                var n = r.EncodeToUtf16(units);
                for (var i = 0; i < n; i++)
                {
                    output.Add(new Stroke(StrokeKind.Unicode, Unit: units[i], Down: true));
                    output.Add(new Stroke(StrokeKind.Unicode, Unit: units[i]));
                }
            }
            prevCr = v == '\r';
        }
    }
}

/// <summary>A screen position in physical pixels.</summary>
internal readonly record struct ScreenPoint(int X, int Y);

/// <summary>The virtual screen: all monitors' bounding box, in physical pixels.</summary>
internal readonly record struct ScreenRect(int Left, int Top, int Width, int Height);

/// <summary>
/// Turns relative moves into absolute positions: a port of the Go app's
/// <c>remote/pointer.go</c>.
/// <para>
/// Why absolute: a relative SendInput move goes through Windows' pointer speed and "Enhance
/// pointer precision" curve. The controller has applied its own acceleration already, so a
/// relative move would be accelerated twice and feel different on every PC. An absolute
/// move lands exactly where it's sent.
/// </para>
/// <para>
/// Each batch starts from the real cursor position (so the physical mouse and the screen's
/// edges are respected). Right after an injection the cursor may not have caught up; if it
/// still reads exactly where the previous batch started, the previous target is used
/// instead, so quickly arriving batches don't lose movement.
/// </para>
/// </summary>
internal sealed class PointerTracker
{
    /// <summary>How long after an injection a cursor reading that hasn't moved is taken as stale.</summary>
    static readonly TimeSpan StaleWindow = TimeSpan.FromMilliseconds(50);

    ScreenPoint lastBase, lastTarget;
    DateTimeOffset lastAt;
    bool have;

    /// <summary>Where the next batch of moves starts, given the cursor reading.</summary>
    public ScreenPoint Base(ScreenPoint cursor, DateTimeOffset now) =>
        have && now - lastAt < StaleWindow && cursor == lastBase && cursor != lastTarget ? lastTarget : cursor;

    /// <summary>Records a batch that started at <paramref name="cursor"/> and ended at <paramref name="target"/>.</summary>
    public void Moved(ScreenPoint cursor, ScreenPoint target, DateTimeOffset now) =>
        (lastBase, lastTarget, lastAt, have) = (cursor, target, now, true);

    /// <summary>Forgets the last batch.</summary>
    public void Reset() => have = false;

    /// <summary>Moves from <paramref name="pos"/> by (dx, dy), kept inside the virtual screen.</summary>
    public static ScreenPoint Step(ScreenPoint pos, int dx, int dy, ScreenRect screen)
    {
        long x = (long)pos.X + dx, y = (long)pos.Y + dy;
        if (screen.Width <= 0 || screen.Height <= 0)
        {
            return new ScreenPoint((int)Math.Clamp(x, int.MinValue, int.MaxValue), (int)Math.Clamp(y, int.MinValue, int.MaxValue));
        }
        return new ScreenPoint(
            (int)Math.Clamp(x, screen.Left, (long)screen.Left + screen.Width - 1),
            (int)Math.Clamp(y, screen.Top, (long)screen.Top + screen.Height - 1));
    }

    /// <summary>
    /// A pixel position in SendInput's absolute coordinates (0–65535 across the virtual
    /// desktop, MOUSEEVENTF_VIRTUALDESK), aiming at the middle of the pixel so rounding can't
    /// land on its neighbour.
    /// </summary>
    public static (int X, int Y) Normalize(ScreenPoint p, ScreenRect screen)
    {
        static int Norm(int v, int origin, int size)
        {
            if (size <= 0)
            {
                return 0;
            }
            var n = (((long)v - origin) * 65536 + 32768) / size;
            return (int)Math.Clamp(n, 0, 65535);
        }
        return (Norm(p.X, screen.Left, screen.Width), Norm(p.Y, screen.Top, screen.Height));
    }
}
