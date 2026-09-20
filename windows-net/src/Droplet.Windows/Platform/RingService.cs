using System.Runtime.InteropServices;
using Droplet.Core.Platform;
using Droplet.Windows.Interop;
using Microsoft.Extensions.Logging;

namespace Droplet.Windows.Platform;

/// <summary>
/// The ring: the tone, looped with PlaySound until stopped, or for a minute at most (a ring
/// gives up after a minute, like a phone). The WAV lives in unmanaged memory, as PlaySound
/// reads it while it plays.
/// </summary>
internal sealed class RingService : ISound, IDisposable
{
    static readonly TimeSpan Limit = TimeSpan.FromSeconds(60);

    readonly ILogger log;
    readonly Lock gate = new();
    readonly nint wav;
    Timer? timer;
    bool disposed;

    public RingService(ILogger log)
    {
        this.log = log;
        var bytes = RingTone.Wav();
        wav = Marshal.AllocHGlobal(bytes.Length);
        Marshal.Copy(bytes, 0, wav, bytes.Length);
    }

    /// <summary>Raised when the ring starts (true) or stops (false).</summary>
    public event Action<bool>? RingingChanged;

    /// <summary>Whether it's ringing now.</summary>
    public bool Ringing { get; private set; }

    /// <inheritdoc/>
    public unsafe void StartRing()
    {
        lock (gate)
        {
            if (disposed)
            {
                return;
            }
            if (!Native.PlaySound((byte*)wav, 0, Native.SND_ASYNC | Native.SND_MEMORY | Native.SND_LOOP | Native.SND_NODEFAULT))
            {
                log.LogWarning("ring: PlaySound failed");
            }
            timer?.Dispose();
            timer = new Timer(_ => StopRing(), null, Limit, Timeout.InfiniteTimeSpan);
            Ringing = true;
        }
        RingingChanged?.Invoke(true);
    }

    /// <inheritdoc/>
    public unsafe void StopRing()
    {
        bool was;
        lock (gate)
        {
            was = Ringing;
            timer?.Dispose();
            timer = null;
            Native.PlaySound(null, 0, 0);
            Ringing = false;
        }
        if (was)
        {
            RingingChanged?.Invoke(false);
        }
    }

    public void Dispose()
    {
        StopRing();
        lock (gate)
        {
            if (!disposed)
            {
                disposed = true;
                Marshal.FreeHGlobal(wav);
            }
        }
    }
}
