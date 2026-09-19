using System.ComponentModel;
using System.Runtime.InteropServices;
using Droplet.Core.Platform;
using Droplet.Windows.Interop;

namespace Droplet.Windows.Platform;

/// <summary>
/// Mouse and keyboard with SendInput: a port of the Go app's <c>input_windows.go</c>.
/// <para>
/// Windows won't deliver injected input to a window of a higher integrity level (UIPI):
/// while an elevated app (Task Manager, an installer, an admin terminal) is in front, keys
/// and clicks don't reach it, and SendInput reports fewer events inserted. Nothing reaches
/// the lock screen or a UAC prompt either (the secure desktop). Running droplet elevated
/// lifts the first limit; the second is by design.
/// </para>
/// </summary>
internal sealed class SendInputService : IInput
{
    readonly Lock gate = new();
    readonly InputTranslator translator = new();
    readonly PointerTracker pointer = new();

    /// <inheritdoc/>
    public void Apply(IReadOnlyList<InputEvent> events)
    {
        lock (gate)
        {
            Inject(translator.Translate(events));
        }
    }

    /// <inheritdoc/>
    public void ReleaseAll()
    {
        lock (gate)
        {
            pointer.Reset();
            Inject(translator.Release());
        }
    }

    /// <summary>Injects strokes made elsewhere (the media keys).</summary>
    public void InjectStrokes(List<Stroke> strokes)
    {
        lock (gate)
        {
            Inject(strokes);
        }
    }

    static ScreenRect VirtualScreen() => new(
        Native.GetSystemMetrics(Native.SM_XVIRTUALSCREEN), Native.GetSystemMetrics(Native.SM_YVIRTUALSCREEN),
        Native.GetSystemMetrics(Native.SM_CXVIRTUALSCREEN), Native.GetSystemMetrics(Native.SM_CYVIRTUALSCREEN));

    static INPUT Mouse(int dx, int dy, int data, uint flags) => new()
    {
        Type = Native.INPUT_MOUSE,
        U = new INPUTUNION { Mouse = new MOUSEINPUT { Dx = dx, Dy = dy, MouseData = unchecked((uint)data), Flags = flags } },
    };

    static INPUT Keyboard(ushort vk, ushort scan, uint flags) => new()
    {
        Type = Native.INPUT_KEYBOARD,
        U = new INPUTUNION { Keyboard = new KEYBDINPUT { Vk = vk, Scan = scan, Flags = flags } },
    };

    static (uint Down, uint Up) ButtonFlags(MouseButton b) => b switch
    {
        MouseButton.Left => (Native.MOUSEEVENTF_LEFTDOWN, Native.MOUSEEVENTF_LEFTUP),
        MouseButton.Right => (Native.MOUSEEVENTF_RIGHTDOWN, Native.MOUSEEVENTF_RIGHTUP),
        _ => (Native.MOUSEEVENTF_MIDDLEDOWN, Native.MOUSEEVENTF_MIDDLEUP),
    };

    /// <summary>Sends the strokes in one SendInput call, so nothing else can come between, say, a shortcut's modifiers.</summary>
    unsafe void Inject(List<Stroke> strokes)
    {
        var records = new List<INPUT>(strokes.Count);
        var screen = default(ScreenRect);
        ScreenPoint first = default, pos = default;
        var moved = false;
        var swapped = Native.GetSystemMetrics(Native.SM_SWAPBUTTON) != 0; // "left" means the primary button
        foreach (var st in strokes)
        {
            switch (st.Kind)
            {
                case StrokeKind.Move:
                    if (!moved)
                    {
                        if (!Native.GetCursorPos(out var c))
                        {
                            // e.g. while the secure desktop (lock screen, UAC) is up
                            throw new Win32Exception(Marshal.GetLastPInvokeError(), "can't read the cursor position (is the screen locked?)");
                        }
                        screen = VirtualScreen();
                        first = new ScreenPoint(c.X, c.Y);
                        pos = pointer.Base(first, DateTimeOffset.UtcNow);
                        moved = true;
                    }
                    pos = PointerTracker.Step(pos, st.Dx, st.Dy, screen);
                    var (nx, ny) = PointerTracker.Normalize(pos, screen);
                    records.Add(Mouse(nx, ny, 0, Native.MOUSEEVENTF_MOVE | Native.MOUSEEVENTF_ABSOLUTE | Native.MOUSEEVENTF_VIRTUALDESK));
                    break;
                case StrokeKind.Button:
                    {
                        var b = st.Button;
                        if (swapped && b is MouseButton.Left or MouseButton.Right)
                        {
                            b = b == MouseButton.Left ? MouseButton.Right : MouseButton.Left;
                        }
                        var (down, up) = ButtonFlags(b);
                        records.Add(Mouse(0, 0, 0, st.Down ? down : up));
                        break;
                    }
                case StrokeKind.Wheel:
                    records.Add(Mouse(0, 0, st.Delta, Native.MOUSEEVENTF_WHEEL));
                    break;
                case StrokeKind.HWheel:
                    records.Add(Mouse(0, 0, st.Delta, Native.MOUSEEVENTF_HWHEEL));
                    break;
                case StrokeKind.Key:
                    {
                        var scan = (ushort)Native.MapVirtualKey(st.Key.Vk, Native.MAPVK_VK_TO_VSC);
                        var flags = (st.Key.Extended ? Native.KEYEVENTF_EXTENDEDKEY : 0) | (st.Down ? 0 : Native.KEYEVENTF_KEYUP);
                        records.Add(Keyboard(st.Key.Vk, scan, flags));
                        break;
                    }
                case StrokeKind.Unicode:
                    records.Add(Keyboard(0, st.Unit, Native.KEYEVENTF_UNICODE | (st.Down ? 0 : Native.KEYEVENTF_KEYUP)));
                    break;
            }
        }
        if (moved)
        {
            pointer.Moved(first, pos, DateTimeOffset.UtcNow);
        }
        if (records.Count == 0)
        {
            return;
        }
        var array = records.ToArray();
        uint sent;
        fixed (INPUT* p = array)
        {
            sent = Native.SendInput((uint)array.Length, p, sizeof(INPUT));
        }
        if (sent != array.Length)
        {
            throw new Win32Exception(Marshal.GetLastPInvokeError(),
                $"Windows blocked {array.Length - sent} of {array.Length} input events: an app running as administrator is in front, or the screen is locked");
        }
    }
}
