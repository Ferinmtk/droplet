using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Droplet.Core.Platform;
using Droplet.Windows.Interop;
using Droplet.Windows.Shell;

namespace Droplet.Windows.Platform;

/// <summary>
/// Every monitor (the whole virtual screen) as one PNG, with GDI: the Go app's
/// <c>capture_windows.go</c>. droplet is per-monitor DPI aware (app.manifest), so this is in
/// real pixels however each monitor is scaled. DRM-protected video comes out black.
/// </summary>
internal sealed class ScreenshotService : IScreenshot
{
    /// <inheritdoc/>
    public Task<byte[]> CapturePngAsync(CancellationToken ct = default)
    {
        // grab on this thread (it's quick), encode on an imaging thread
        var (pixels, w, h) = Grab();
        return Sta.Run(() => Encode(pixels, w, h));
    }

    static unsafe (byte[] Pixels, int Width, int Height) Grab()
    {
        int left = Native.GetSystemMetrics(Native.SM_XVIRTUALSCREEN), top = Native.GetSystemMetrics(Native.SM_YVIRTUALSCREEN);
        int w = Native.GetSystemMetrics(Native.SM_CXVIRTUALSCREEN), h = Native.GetSystemMetrics(Native.SM_CYVIRTUALSCREEN);
        if (w <= 0 || h <= 0)
        {
            throw new InvalidOperationException("no screen to capture");
        }
        var screen = Native.GetDC(0);
        if (screen == 0)
        {
            throw new InvalidOperationException("can't read the screen");
        }
        try
        {
            var mem = Native.CreateCompatibleDC(screen);
            if (mem == 0)
            {
                throw new InvalidOperationException("CreateCompatibleDC failed");
            }
            try
            {
                var bmp = Native.CreateCompatibleBitmap(screen, w, h);
                if (bmp == 0)
                {
                    throw new InvalidOperationException($"can't make a {w}×{h} bitmap");
                }
                try
                {
                    var old = Native.SelectObject(mem, bmp);
                    // CAPTUREBLT includes layered (translucent) windows; some drivers refuse it
                    var ok = Native.BitBlt(mem, 0, 0, w, h, screen, left, top, Native.SRCCOPY | Native.CAPTUREBLT) ||
                             Native.BitBlt(mem, 0, 0, w, h, screen, left, top, Native.SRCCOPY);
                    Native.SelectObject(mem, old); // GetDIBits wants the bitmap out of the DC
                    if (!ok)
                    {
                        throw new InvalidOperationException("BitBlt failed (is the screen locked?)");
                    }
                    var info = new BITMAPINFOHEADER
                    {
                        Size = (uint)sizeof(BITMAPINFOHEADER), Width = w, Height = -h, Planes = 1, BitCount = 32, // top-down, BI_RGB
                    };
                    // BITMAPINFO is the header plus a colour table, which 32-bit BI_RGB doesn't use; room for it anyway
                    var buf = stackalloc byte[sizeof(BITMAPINFOHEADER) + 16];
                    *(BITMAPINFOHEADER*)buf = info;
                    var pixels = GC.AllocateUninitializedArray<byte>(checked(w * h * 4));
                    fixed (byte* p = pixels)
                    {
                        if (Native.GetDIBits(mem, bmp, 0, (uint)h, p, (BITMAPINFOHEADER*)buf, Native.DIB_RGB_COLORS) != h)
                        {
                            throw new InvalidOperationException("GetDIBits failed");
                        }
                    }
                    return (pixels, w, h);
                }
                finally
                {
                    Native.DeleteObject(bmp);
                }
            }
            finally
            {
                Native.DeleteDC(mem);
            }
        }
        finally
        {
            _ = Native.ReleaseDC(0, screen);
        }
    }

    static byte[] Encode(byte[] bgra, int w, int h)
    {
        // BGRA from Windows with no meaningful alpha: Bgr32 ignores it
        var src = BitmapSource.Create(w, h, 96, 96, PixelFormats.Bgr32, null, bgra, w * 4);
        var enc = new PngBitmapEncoder();
        enc.Frames.Add(BitmapFrame.Create(src));
        using var ms = new MemoryStream();
        enc.Save(ms);
        return ms.ToArray();
    }
}

/// <summary>Locks the screen, as Win+L does.</summary>
internal sealed class LockService : ILock
{
    public void Lock()
    {
        if (!Native.LockWorkStation())
        {
            throw new System.ComponentModel.Win32Exception(System.Runtime.InteropServices.Marshal.GetLastPInvokeError(), "LockWorkStation failed");
        }
    }
}
