using System.Buffers.Binary;

namespace Droplet.Windows.Platform;

/// <summary>
/// The ring tone: a bright two-note chime, loud enough to find a PC across the room, built
/// at startup instead of shipping a WAV file. The Go app's <c>sound/sound.go</c>, sample for
/// sample.
/// </summary>
internal static class RingTone
{
    const int Rate = 22050;

    /// <summary>One 1.6 s cycle of the tone as a 16-bit mono WAV, meant to be looped.</summary>
    public static byte[] Wav()
    {
        const double length = 1.6;
        (double At, double Freq)[] notes = [(0, 1318.5), (0.18, 1760), (0.36, 2093), (0.72, 1318.5), (0.9, 1760), (1.08, 2093)];
        var n = (int)(length * Rate);
        var mix = new double[n];
        foreach (var (at, freq) in notes)
        {
            var start = (int)(at * Rate);
            for (var i = start; i < n; i++)
            {
                var t = (double)(i - start) / Rate;
                // fast attack, bell-like decay
                var env = Math.Min(t / 0.004, 1) * Math.Exp(-t * 7);
                if (t > 0.01 && env < 1e-4)
                {
                    break;
                }
                var s = Math.Sin(2 * Math.PI * freq * t) +
                        0.35 * Math.Sin(2 * Math.PI * freq * 2.01 * t) * Math.Exp(-t * 4) +
                        0.15 * Math.Sin(2 * Math.PI * freq * 3.02 * t) * Math.Exp(-t * 9);
                mix[i] += s * env;
            }
        }
        // fade the last 50 ms so the loop point doesn't click
        var fade = Rate / 20;
        for (var i = 0; i < fade; i++)
        {
            mix[n - 1 - i] *= (double)i / fade;
        }
        var peak = mix.Max(Math.Abs);
        var wav = new byte[44 + n * 2];
        var w = wav.AsSpan();
        "RIFF"u8.CopyTo(w);
        BinaryPrimitives.WriteUInt32LittleEndian(w[4..], (uint)(36 + n * 2));
        "WAVEfmt "u8.CopyTo(w[8..]);
        BinaryPrimitives.WriteUInt32LittleEndian(w[16..], 16);
        BinaryPrimitives.WriteUInt16LittleEndian(w[20..], 1); // PCM
        BinaryPrimitives.WriteUInt16LittleEndian(w[22..], 1); // mono
        BinaryPrimitives.WriteUInt32LittleEndian(w[24..], Rate);
        BinaryPrimitives.WriteUInt32LittleEndian(w[28..], Rate * 2);
        BinaryPrimitives.WriteUInt16LittleEndian(w[32..], 2);
        BinaryPrimitives.WriteUInt16LittleEndian(w[34..], 16);
        "data"u8.CopyTo(w[36..]);
        BinaryPrimitives.WriteUInt32LittleEndian(w[40..], (uint)(n * 2));
        for (var i = 0; i < n; i++)
        {
            // loud, just short of clipping
            BinaryPrimitives.WriteInt16LittleEndian(w[(44 + i * 2)..], (short)(mix[i] / peak * 0.95 * short.MaxValue));
        }
        return wav;
    }
}
