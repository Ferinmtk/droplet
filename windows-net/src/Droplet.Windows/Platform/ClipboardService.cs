using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Windows.Threading;
using Droplet.Core.Platform;
using Droplet.Windows.Interop;

namespace Droplet.Windows.Platform;

/// <summary>
/// The clipboard for live sync (text only): change detection by sequence number, reading
/// text while respecting apps that mark a copy as private, and writing. A port of the Go
/// app's <c>clipsync_windows.go</c>.
/// </summary>
/// <param name="dispatcher">The UI thread, which owns <paramref name="owner"/> and does the writes.</param>
/// <param name="owner">A window of that thread to own what's written (SetClipboardData needs one).</param>
internal sealed class ClipboardService(Dispatcher dispatcher, Func<nint> owner) : IClipboard
{
    // Password managers put these formats next to a secret so clipboard history, cloud sync
    // and tools like this one leave the copy alone.
    static readonly Lazy<(uint Exclude, uint ViewerIgnore, uint NoCloud, uint NoHistory)> Private = new(() => (
        Native.RegisterClipboardFormat("ExcludeClipboardContentFromMonitorProcessing"),
        Native.RegisterClipboardFormat("Clipboard Viewer Ignore"),
        Native.RegisterClipboardFormat("CanUploadToCloudClipboard"),
        Native.RegisterClipboardFormat("CanIncludeInClipboardHistory")));

    /// <inheritdoc/>
    public uint Sequence => Native.GetClipboardSequenceNumber();

    /// <summary>Opens the clipboard, retrying while another app holds it.</summary>
    static bool Open(nint window)
    {
        for (var i = 0; i < 10; i++)
        {
            if (Native.OpenClipboard(window))
            {
                return true;
            }
            Thread.Sleep(30);
        }
        return false;
    }

    static bool Available(uint format) => format != 0 && Native.IsClipboardFormatAvailable(format);

    /// <summary>A clipboard format's bytes (the clipboard must be open).</summary>
    static byte[]? Bytes(uint format)
    {
        var h = Native.GetClipboardData(format);
        if (h == 0)
        {
            return null;
        }
        var p = Native.GlobalLock(h);
        if (p == 0)
        {
            return null;
        }
        try
        {
            var size = (long)Native.GlobalSize(h);
            if (size is <= 0 or > 64 * 1024 * 1024)
            {
                return null;
            }
            var data = new byte[size];
            Marshal.Copy(p, data, 0, data.Length);
            return data;
        }
        finally
        {
            Native.GlobalUnlock(h);
        }
    }

    /// <inheritdoc/>
    public bool TryReadText(out string text)
    {
        text = "";
        var fmt = Private.Value;
        if (!Open(0))
        {
            return false;
        }
        try
        {
            if (Available(fmt.Exclude) || Available(fmt.ViewerIgnore))
            {
                return false;
            }
            foreach (var f in (ReadOnlySpan<uint>)[fmt.NoCloud, fmt.NoHistory])
            {
                // a DWORD; 0 means "don't"
                if (Available(f) && Bytes(f) is { Length: >= 4 } b && BitConverter.ToUInt32(b, 0) == 0)
                {
                    return false;
                }
            }
            if (!Available(Native.CF_UNICODETEXT) || Bytes(Native.CF_UNICODETEXT) is not { Length: >= 2 } data)
            {
                return false;
            }
            var chars = MemoryMarshal.Cast<byte, char>(data.AsSpan(0, data.Length & ~1));
            var end = chars.IndexOf('\0');
            text = new string(end >= 0 ? chars[..end] : chars);
            return text.Length > 0;
        }
        finally
        {
            Native.CloseClipboard();
        }
    }

    /// <inheritdoc/>
    public void WriteText(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        // on the owner window's thread, which answers the clipboard's messages
        dispatcher.Invoke(() => Write(owner(), text));
    }

    static unsafe void Write(nint window, string text)
    {
        if (!Open(window))
        {
            throw new InvalidOperationException("the clipboard is busy");
        }
        try
        {
            if (!Native.EmptyClipboard())
            {
                throw new Win32Exception(Marshal.GetLastPInvokeError(), "EmptyClipboard failed");
            }
            var bytes = (nuint)((text.Length + 1) * sizeof(char));
            var h = Native.GlobalAlloc(Native.GMEM_MOVEABLE, bytes);
            if (h == 0)
            {
                throw new Win32Exception(Marshal.GetLastPInvokeError(), "GlobalAlloc failed");
            }
            var p = Native.GlobalLock(h);
            if (p == 0)
            {
                Native.GlobalFree(h);
                throw new InvalidOperationException("GlobalLock failed");
            }
            var dest = new Span<char>((void*)p, text.Length + 1);
            text.AsSpan().CopyTo(dest);
            dest[^1] = '\0';
            Native.GlobalUnlock(h);
            if (Native.SetClipboardData(Native.CF_UNICODETEXT, h) == 0)
            {
                var err = Marshal.GetLastPInvokeError();
                Native.GlobalFree(h); // still ours when SetClipboardData fails
                throw new Win32Exception(err, "SetClipboardData failed");
            }
        }
        finally
        {
            Native.CloseClipboard();
        }
    }
}
