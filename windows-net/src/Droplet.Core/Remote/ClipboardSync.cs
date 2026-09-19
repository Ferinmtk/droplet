namespace Droplet.Core.Remote;

/// <summary>
/// Decides what clipboard text to send (docs/remote.md §3.6), and keeps the text this
/// device was just given from being sent back. A change must hold for a moment before
/// it's sent (apps set the clipboard more than once), at most one a second, at most 256 KB.
/// Thread-safe; the caller polls <see cref="Observe"/>.
/// </summary>
public sealed class ClipboardSync
{
    /// <summary>How often to poll the clipboard.</summary>
    public static readonly TimeSpan Poll = TimeSpan.FromMilliseconds(500);

    static readonly TimeSpan Settle = TimeSpan.FromMilliseconds(400);
    static readonly TimeSpan MinSpace = TimeSpan.FromSeconds(1);

    readonly Lock gate = new();
    uint seq;
    bool started;
    string pending = "";
    DateTimeOffset pendingAt;
    string synced = "";   // the text the other devices have too: what was last sent or received
    DateTimeOffset sentAt;

    /// <summary>The largest text synced, in bytes of UTF-8.</summary>
    public const int MaxBytes = 256 * 1024;

    /// <summary>
    /// Called on every poll with the clipboard's sequence number and a way to read its
    /// text. Returns text to send now, or null.
    /// </summary>
    public string? Observe(uint sequence, Func<(bool Ok, string Text)> read, DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(read);
        lock (gate)
        {
            if (!started)
            {
                // what's on the clipboard when syncing starts isn't news
                (started, seq) = (true, sequence);
                return null;
            }
            if (sequence != seq)
            {
                seq = sequence;
                var (ok, text) = read();
                if (!ok || text.Length == 0 || System.Text.Encoding.UTF8.GetByteCount(text) > MaxBytes)
                {
                    pending = ""; // a newer copy (an image, a secret) replaces unsent text
                }
                else if (text == synced)
                {
                    pending = ""; // our own write coming back, or nothing new
                }
                else
                {
                    (pending, pendingAt) = (text, now);
                }
            }
            if (pending.Length == 0 || now - pendingAt < Settle || now - sentAt < MinSpace)
            {
                return null;
            }
            var output = pending;
            (pending, synced, sentAt) = ("", output, now);
            return output;
        }
    }

    /// <summary>Records text from another device, just before it's written to the clipboard.</summary>
    public void Applying(string text)
    {
        lock (gate)
        {
            (synced, pending) = (text, "");
        }
    }

    /// <summary>Forgets everything, for a new connection.</summary>
    public void Reset()
    {
        lock (gate)
        {
            (seq, started, pending, pendingAt, synced, sentAt) = (0, false, "", default, "", default);
        }
    }
}
