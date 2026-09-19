using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Interop;
using Droplet.Windows.Interop;

namespace Droplet.Windows.Tray;

/// <summary>
/// The notification-area icon: a small Shell_NotifyIcon wrapper written for droplet, not a
/// library. The WPF tray libraries are either under a licence that doesn't mix with the GPL
/// (Hardcodet's, CPOL) or bring a stack of dependencies for what's about 200 lines here. It
/// owns a hidden top-level window (a message-only one would miss TaskbarCreated, sent when
/// Explorer restarts), shows a WPF context menu, so the menu gets the app's Fluent theme,
/// and re-adds itself if Explorer restarts.
/// </summary>
internal sealed class NotifyIcon : IDisposable
{
    const uint Id = 1;
    const int CallbackMessage = Native.WM_APP + 1;

    readonly HwndSource window;
    readonly uint taskbarCreated;
    readonly Dictionary<string, byte[]> icons = [];
    nint hicon;
    string iconName = "";
    string tip = "droplet";
    bool added;
    bool disposed;

    public NotifyIcon()
    {
        window = new HwndSource(new HwndSourceParameters("droplet tray")
        {
            WindowStyle = 0, // an invisible overlapped window: never shown
            Width = 0,
            Height = 0,
        });
        window.AddHook(Hook);
        taskbarCreated = Native.RegisterWindowMessage("TaskbarCreated");
    }

    /// <summary>The hidden window: it owns what droplet writes to the clipboard too.</summary>
    public nint Handle => window.Handle;

    /// <summary>The menu shown on a right click.</summary>
    public ContextMenu? Menu { get; set; }

    /// <summary>A left click (or Enter on the keyboard).</summary>
    public event Action? Clicked;

    /// <summary>Raised just before the menu opens, so it can be brought up to date.</summary>
    public event Action? MenuOpening;

    /// <summary>Shows the icon with one of the embedded icons (tray, tray-dim, tray-live).</summary>
    public void Show(string icon, string tooltip)
    {
        ArgumentNullException.ThrowIfNull(icon);
        if (disposed)
        {
            return;
        }
        tooltip = tooltip.Length > 127 ? tooltip[..126] + "…" : tooltip;
        if (added && icon == iconName && tooltip == tip)
        {
            return;
        }
        if (icon != iconName || hicon == 0)
        {
            SetIcon(icon);
        }
        tip = tooltip;
        Apply(added ? Native.NIM_MODIFY : Native.NIM_ADD);
    }

    void SetIcon(string name)
    {
        if (!icons.TryGetValue(name, out var bytes))
        {
            using var s = typeof(NotifyIcon).Assembly.GetManifestResourceStream(name + ".ico")
                          ?? throw new FileNotFoundException("no such icon resource", name);
            using var ms = new MemoryStream();
            s.CopyTo(ms);
            icons[name] = bytes = ms.ToArray();
        }
        var dpi = Native.GetDpiForWindow(window.Handle);
        var size = Native.GetSystemMetricsForDpi(Native.SM_CXSMICON, dpi == 0 ? 96 : dpi);
        var (image, _) = IconFile.Pick(bytes, size);
        nint h;
        unsafe
        {
            fixed (byte* p = image.Span)
            {
                h = Native.CreateIconFromResourceEx(p, (uint)image.Length, true, 0x00030000, size, size, 0);
            }
        }
        if (h == 0)
        {
            return; // keep the old one
        }
        if (hicon != 0)
        {
            Native.DestroyIcon(hicon);
        }
        (hicon, iconName) = (h, name);
    }

    unsafe void Apply(uint message)
    {
        var data = new NOTIFYICONDATAW
        {
            Size = (uint)sizeof(NOTIFYICONDATAW),
            Wnd = window.Handle,
            Id = Id,
            Flags = Native.NIF_MESSAGE | Native.NIF_ICON | Native.NIF_TIP | Native.NIF_SHOWTIP,
            CallbackMessage = CallbackMessage,
            Icon = hicon,
        };
        var t = tip.AsSpan();
        for (var i = 0; i < t.Length && i < 127; i++)
        {
            data.Tip[i] = t[i];
        }
        if (Native.Shell_NotifyIcon(message, &data))
        {
            if (message == Native.NIM_ADD)
            {
                added = true;
                data.VersionOrTimeout = Native.NOTIFYICON_VERSION_4;
                Native.Shell_NotifyIcon(Native.NIM_SETVERSION, &data);
            }
        }
        else if (message == Native.NIM_MODIFY)
        {
            // Explorer lost it (restarted before TaskbarCreated arrived): add it again
            added = false;
            if (Native.Shell_NotifyIcon(Native.NIM_ADD, &data))
            {
                added = true;
                data.VersionOrTimeout = Native.NOTIFYICON_VERSION_4;
                Native.Shell_NotifyIcon(Native.NIM_SETVERSION, &data);
            }
        }
    }

    nint Hook(nint hwnd, int msg, nint wParam, nint lParam, ref bool handled)
    {
        if (msg == CallbackMessage)
        {
            // NOTIFYICON_VERSION_4: the event is in the low word of lParam
            switch ((int)(lParam & 0xFFFF))
            {
                case Native.NIN_SELECT or Native.NIN_KEYSELECT:
                    Clicked?.Invoke();
                    break;
                case Native.WM_CONTEXTMENU:
                    OpenMenu();
                    break;
            }
            handled = true;
        }
        else if (msg == (int)taskbarCreated && taskbarCreated != 0)
        {
            added = false; // Explorer restarted: the icon is gone
            if (hicon != 0)
            {
                Apply(Native.NIM_ADD);
            }
        }
        return 0;
    }

    void OpenMenu()
    {
        if (Menu is not { } menu)
        {
            return;
        }
        MenuOpening?.Invoke();
        menu.Placement = PlacementMode.MousePoint;
        menu.IsOpen = true;
        // the menu must be in front, or a click elsewhere wouldn't close it
        if (PresentationSource.FromVisual(menu) is HwndSource source)
        {
            Native.SetForegroundWindow(source.Handle);
        }
        menu.Focus();
    }

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }
        disposed = true;
        if (added)
        {
            unsafe
            {
                var data = new NOTIFYICONDATAW { Size = (uint)sizeof(NOTIFYICONDATAW), Wnd = window.Handle, Id = Id };
                Native.Shell_NotifyIcon(Native.NIM_DELETE, &data);
            }
            added = false;
        }
        if (hicon != 0)
        {
            Native.DestroyIcon(hicon);
            hicon = 0;
        }
        window.RemoveHook(Hook);
        window.Dispose();
    }
}
