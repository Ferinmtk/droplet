using System.Runtime.InteropServices;

namespace Droplet.Windows.Interop;

// The Win32 calls the shell makes, source-generated (LibraryImport). Struct layouts
// follow the Windows SDK headers; the sizes that matter are checked at startup
// (Native.CheckLayouts) so a mistake fails loudly instead of corrupting memory.

[StructLayout(LayoutKind.Sequential)]
internal struct POINT
{
    public int X;
    public int Y;
}

[StructLayout(LayoutKind.Sequential)]
internal struct RECT
{
    public int Left;
    public int Top;
    public int Right;
    public int Bottom;
}

/// <summary>MOUSEINPUT: 32 bytes on 64-bit Windows, 24 on 32-bit.</summary>
[StructLayout(LayoutKind.Sequential)]
internal struct MOUSEINPUT
{
    public int Dx;
    public int Dy;
    public uint MouseData;
    public uint Flags;
    public uint Time;
    public nint ExtraInfo;
}

/// <summary>KEYBDINPUT.</summary>
[StructLayout(LayoutKind.Sequential)]
internal struct KEYBDINPUT
{
    public ushort Vk;
    public ushort Scan;
    public uint Flags;
    public uint Time;
    public nint ExtraInfo;
}

/// <summary>The union inside INPUT (the largest member, MOUSEINPUT, sets its size).</summary>
[StructLayout(LayoutKind.Explicit)]
internal struct INPUTUNION
{
    [FieldOffset(0)] public MOUSEINPUT Mouse;
    [FieldOffset(0)] public KEYBDINPUT Keyboard;
}

/// <summary>INPUT: a type tag and the union; 40 bytes on 64-bit Windows, 28 on 32-bit.</summary>
[StructLayout(LayoutKind.Sequential)]
internal struct INPUT
{
    public uint Type;
    public INPUTUNION U;
}

[StructLayout(LayoutKind.Sequential)]
internal struct BITMAPINFOHEADER
{
    public uint Size;
    public int Width;
    public int Height;
    public ushort Planes;
    public ushort BitCount;
    public uint Compression;
    public uint SizeImage;
    public int XPelsPerMeter;
    public int YPelsPerMeter;
    public uint ClrUsed;
    public uint ClrImportant;
}

/// <summary>NOTIFYICONDATAW, as of Windows Vista (with guidItem and hBalloonIcon).</summary>
[StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
internal unsafe struct NOTIFYICONDATAW
{
    public uint Size;
    public nint Wnd;
    public uint Id;
    public uint Flags;
    public uint CallbackMessage;
    public nint Icon;
    public fixed char Tip[128];
    public uint State;
    public uint StateMask;
    public fixed char Info[256];
    public uint VersionOrTimeout;
    public fixed char InfoTitle[64];
    public uint InfoFlags;
    public Guid Item;
    public nint BalloonIcon;
}

internal static partial class Native
{
    // --- input -----------------------------------------------------------------------------------

    public const uint INPUT_MOUSE = 0;
    public const uint INPUT_KEYBOARD = 1;

    public const uint MOUSEEVENTF_MOVE = 0x0001;
    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
    public const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    public const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    public const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    public const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    public const uint MOUSEEVENTF_WHEEL = 0x0800;
    public const uint MOUSEEVENTF_HWHEEL = 0x1000;
    public const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;
    public const uint MOUSEEVENTF_ABSOLUTE = 0x8000;

    public const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
    public const uint KEYEVENTF_KEYUP = 0x0002;
    public const uint KEYEVENTF_UNICODE = 0x0004;

    public const int SM_CXSMICON = 49;
    public const int SM_SWAPBUTTON = 23;
    public const int SM_XVIRTUALSCREEN = 76;
    public const int SM_YVIRTUALSCREEN = 77;
    public const int SM_CXVIRTUALSCREEN = 78;
    public const int SM_CYVIRTUALSCREEN = 79;

    public const uint MAPVK_VK_TO_VSC = 0;

    [LibraryImport("user32.dll", SetLastError = true)]
    public static unsafe partial uint SendInput(uint count, INPUT* inputs, int size);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool GetCursorPos(out POINT point);

    [LibraryImport("user32.dll")]
    public static partial int GetSystemMetrics(int index);

    [LibraryImport("user32.dll")]
    public static partial int GetSystemMetricsForDpi(int index, uint dpi);

    [LibraryImport("user32.dll", EntryPoint = "MapVirtualKeyW")]
    public static partial uint MapVirtualKey(uint code, uint mapType);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool LockWorkStation();

    // --- clipboard -------------------------------------------------------------------------------

    public const uint CF_UNICODETEXT = 13;
    public const uint GMEM_MOVEABLE = 0x0002;

    [LibraryImport("user32.dll")]
    public static partial uint GetClipboardSequenceNumber();

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool OpenClipboard(nint owner);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool CloseClipboard();

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool EmptyClipboard();

    [LibraryImport("user32.dll", SetLastError = true)]
    public static partial nint GetClipboardData(uint format);

    [LibraryImport("user32.dll", SetLastError = true)]
    public static partial nint SetClipboardData(uint format, nint data);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool IsClipboardFormatAvailable(uint format);

    [LibraryImport("user32.dll", EntryPoint = "RegisterClipboardFormatW", StringMarshalling = StringMarshalling.Utf16)]
    public static partial uint RegisterClipboardFormat(string name);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    public static partial nint GlobalAlloc(uint flags, nuint bytes);

    [LibraryImport("kernel32.dll")]
    public static partial nint GlobalFree(nint mem);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    public static partial nint GlobalLock(nint mem);

    [LibraryImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool GlobalUnlock(nint mem);

    [LibraryImport("kernel32.dll")]
    public static partial nuint GlobalSize(nint mem);

    // --- screen capture --------------------------------------------------------------------------

    public const uint SRCCOPY = 0x00CC0020;
    public const uint CAPTUREBLT = 0x40000000;
    public const uint DIB_RGB_COLORS = 0;

    [LibraryImport("user32.dll")]
    public static partial nint GetDC(nint wnd);

    [LibraryImport("user32.dll")]
    public static partial int ReleaseDC(nint wnd, nint dc);

    [LibraryImport("gdi32.dll")]
    public static partial nint CreateCompatibleDC(nint dc);

    [LibraryImport("gdi32.dll")]
    public static partial nint CreateCompatibleBitmap(nint dc, int width, int height);

    [LibraryImport("gdi32.dll")]
    public static partial nint SelectObject(nint dc, nint obj);

    [LibraryImport("gdi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool BitBlt(nint dest, int x, int y, int width, int height, nint src, int srcX, int srcY, uint rop);

    [LibraryImport("gdi32.dll")]
    public static unsafe partial int GetDIBits(nint dc, nint bitmap, uint start, uint lines, void* bits, BITMAPINFOHEADER* info, uint usage);

    [LibraryImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool DeleteObject(nint obj);

    [LibraryImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool DeleteDC(nint dc);

    // --- sound -----------------------------------------------------------------------------------

    public const uint SND_ASYNC = 0x0001;
    public const uint SND_NODEFAULT = 0x0002;
    public const uint SND_MEMORY = 0x0004;
    public const uint SND_LOOP = 0x0008;

    [LibraryImport("winmm.dll", EntryPoint = "PlaySoundW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static unsafe partial bool PlaySound(byte* sound, nint module, uint flags);

    // --- the tray --------------------------------------------------------------------------------

    public const uint NIM_ADD = 0;
    public const uint NIM_MODIFY = 1;
    public const uint NIM_DELETE = 2;
    public const uint NIM_SETVERSION = 4;
    public const uint NIF_MESSAGE = 0x01;
    public const uint NIF_ICON = 0x02;
    public const uint NIF_TIP = 0x04;
    public const uint NIF_SHOWTIP = 0x80;
    public const uint NOTIFYICON_VERSION_4 = 4;
    public const int NIN_SELECT = 0x0400;
    public const int NIN_KEYSELECT = 0x0401;
    public const int WM_CONTEXTMENU = 0x007B;
    public const int WM_LBUTTONDBLCLK = 0x0203;
    public const int WM_APP = 0x8000;

    [LibraryImport("shell32.dll", EntryPoint = "Shell_NotifyIconW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static unsafe partial bool Shell_NotifyIcon(uint message, NOTIFYICONDATAW* data);

    [LibraryImport("user32.dll", EntryPoint = "RegisterWindowMessageW", StringMarshalling = StringMarshalling.Utf16)]
    public static partial uint RegisterWindowMessage(string name);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool SetForegroundWindow(nint wnd);

    [LibraryImport("user32.dll")]
    public static partial uint GetDpiForWindow(nint wnd);

    [LibraryImport("user32.dll", SetLastError = true)]
    public static unsafe partial nint CreateIconFromResourceEx(byte* bits, uint size, [MarshalAs(UnmanagedType.Bool)] bool icon, uint version, int cx, int cy, uint flags);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool DestroyIcon(nint icon);

    // --- the process -----------------------------------------------------------------------------

    public const int APPMODEL_ERROR_NO_PACKAGE = 15700;
    public const int ERROR_INSUFFICIENT_BUFFER = 122;

    [LibraryImport("kernel32.dll")]
    public static unsafe partial int GetCurrentPackageFullName(ref uint length, char* name);

    [LibraryImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool ProcessIdToSessionId(uint pid, out uint session);

    [LibraryImport("shell32.dll", EntryPoint = "SetCurrentProcessExplicitAppUserModelID", StringMarshalling = StringMarshalling.Utf16)]
    public static partial int SetCurrentProcessExplicitAppUserModelID(string appId);

    [LibraryImport("user32.dll", EntryPoint = "MessageBoxW", StringMarshalling = StringMarshalling.Utf16)]
    public static partial int MessageBox(nint owner, string text, string caption, uint type);

    [LibraryImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool AttachConsole(uint processId);

    public const uint ATTACH_PARENT_PROCESS = unchecked((uint)-1);

    /// <summary>Fails fast if a struct's size differs from what Windows expects.</summary>
    public static unsafe void CheckLayouts()
    {
        var input = Environment.Is64BitProcess ? 40 : 28;
        if (sizeof(INPUT) != input || sizeof(BITMAPINFOHEADER) != 40 || sizeof(POINT) != 8)
        {
            throw new InvalidOperationException($"interop layout mismatch: INPUT {sizeof(INPUT)}, BITMAPINFOHEADER {sizeof(BITMAPINFOHEADER)}");
        }
        var nid = Environment.Is64BitProcess ? 976 : 956;
        if (sizeof(NOTIFYICONDATAW) != nid)
        {
            throw new InvalidOperationException($"interop layout mismatch: NOTIFYICONDATAW {sizeof(NOTIFYICONDATAW)}");
        }
    }
}
