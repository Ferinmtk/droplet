using System.Runtime.InteropServices;
using Droplet.Core.Platform;

namespace Droplet.Windows.Platform;

/// <summary>
/// The system volume through Core Audio's IAudioEndpointVolume: exact levels and mute,
/// where the volume keys only step 2 % at a time and toggle. Each call looks up the default
/// output device afresh, so plugging in headphones is followed. The Go app's
/// <c>audio_windows.go</c>, with .NET's COM interop instead of vtable slots.
/// </summary>
internal static class CoreAudio
{
    const int ERender = 0;
    const int EConsole = 0;
    const int ClsctxAll = 0x17;
    static readonly Guid IidAudioEndpointVolume = new("5CDF2C82-841E-4546-9722-0CF74078229A");

    [ComImport]
    [Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    sealed class MMDeviceEnumerator;

    [ComImport]
    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator
    {
        [PreserveSig]
        int EnumAudioEndpoints(int dataFlow, int stateMask, out nint devices);

        [PreserveSig]
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [ComImport]
    [Guid("D666063F-1587-4E43-81F1-B948E807363F")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice
    {
        [PreserveSig]
        int Activate(ref Guid iid, int clsCtx, nint activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object endpoint);
    }

    /// <summary>IAudioEndpointVolume, in vtable order up to GetMute.</summary>
    [ComImport]
    [Guid("5CDF2C82-841E-4546-9722-0CF74078229A")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioEndpointVolume
    {
        [PreserveSig] int RegisterControlChangeNotify(nint notify);
        [PreserveSig] int UnregisterControlChangeNotify(nint notify);
        [PreserveSig] int GetChannelCount(out uint count);
        [PreserveSig] int SetMasterVolumeLevel(float levelDb, ref Guid context);
        [PreserveSig] int SetMasterVolumeLevelScalar(float level, ref Guid context);
        [PreserveSig] int GetMasterVolumeLevel(out float levelDb);
        [PreserveSig] int GetMasterVolumeLevelScalar(out float level);
        [PreserveSig] int SetChannelVolumeLevel(uint channel, float levelDb, ref Guid context);
        [PreserveSig] int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid context);
        [PreserveSig] int GetChannelVolumeLevel(uint channel, out float levelDb);
        [PreserveSig] int GetChannelVolumeLevelScalar(uint channel, out float level);
        [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);
        [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }

    static T WithEndpoint<T>(Func<IAudioEndpointVolume, T> use)
    {
        object? enumerator = null, device = null, endpoint = null;
        try
        {
            enumerator = new MMDeviceEnumerator();
            var e = (IMMDeviceEnumerator)enumerator;
            Marshal.ThrowExceptionForHR(e.GetDefaultAudioEndpoint(ERender, EConsole, out var dev));
            device = dev;
            var iid = IidAudioEndpointVolume;
            Marshal.ThrowExceptionForHR(dev.Activate(ref iid, ClsctxAll, 0, out endpoint));
            return use((IAudioEndpointVolume)endpoint);
        }
        finally
        {
            foreach (var o in (ReadOnlySpan<object?>)[endpoint, device, enumerator])
            {
                if (o is not null && Marshal.IsComObject(o))
                {
                    Marshal.ReleaseComObject(o);
                }
            }
        }
    }

    /// <summary>The volume and mute. Throws when there's no output device.</summary>
    public static Volume Get() => WithEndpoint(ep =>
    {
        Marshal.ThrowExceptionForHR(ep.GetMasterVolumeLevelScalar(out var level));
        Marshal.ThrowExceptionForHR(ep.GetMute(out var muted));
        return new Volume(Math.Clamp(level, 0, 1), muted);
    });

    /// <summary>Sets the level, 0..1.</summary>
    public static void SetLevel(double level) => WithEndpoint(ep =>
    {
        var ctx = Guid.Empty;
        Marshal.ThrowExceptionForHR(ep.SetMasterVolumeLevelScalar((float)Math.Clamp(level, 0, 1), ref ctx));
        return 0;
    });

    /// <summary>Mutes or unmutes.</summary>
    public static void SetMuted(bool muted) => WithEndpoint(ep =>
    {
        var ctx = Guid.Empty;
        Marshal.ThrowExceptionForHR(ep.SetMute(muted, ref ctx));
        return 0;
    });
}
