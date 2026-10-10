using Droplet.Core.Mesh;

namespace Droplet.Windows.Services;

/// <summary>One device's permissions as the Permissions window shows them (docs/mesh.md §9.9).</summary>
/// <param name="Name">The device's name.</param>
/// <param name="Relation">"own" or "other".</param>
/// <param name="Allow">This PC's switch per capability for it.</param>
/// <param name="Paused">Paused here.</param>
/// <param name="Remote">What it said it takes from this PC, while linked.</param>
/// <param name="PausedAll">Pause everything is on.</param>
internal sealed record PermsView(string Name, string Relation, IReadOnlyDictionary<string, bool> Allow, bool Paused, RemotePerm? Remote, bool PausedAll);

/// <summary>Reads and changes one device's permissions.</summary>
internal interface IPermsEditor
{
    /// <summary>Now; null when the device isn't trusted any more.</summary>
    PermsView? Current();

    /// <summary>A new relation (which starts from its defaults), switches, or pause.</summary>
    void Set(string? relation = null, IReadOnlyDictionary<string, bool>? allow = null, bool? paused = null);
}

/// <summary>What the windows say about sharing (pure, so it's tested anywhere).</summary>
internal static class SharingText
{
    /// <summary>The two answers to "Is it your device, or someone else's?", with one line each.</summary>
    public const string OwnTitle = "My device";

    /// <summary>What "My device" means.</summary>
    public const string OwnLine = "One of yours: files, messages, the clipboard, notifications and remote control, as with your other devices.";

    /// <summary>Someone else's.</summary>
    public const string OtherTitle = "Someone else's";

    /// <summary>What "Someone else's" means.</summary>
    public const string OtherLine = "A friend's or a colleague's: files, messages and ring only. No clipboard, notifications or remote control.";

    /// <summary>The question asked at pairing.</summary>
    public static string Question(string name) => $"Is {name} your device, or someone else's?";

    /// <summary>What the device said about how it treats this PC, in a sentence; null when it said nothing.</summary>
    public static string? TheirWord(string name, RemotePerm? remote)
    {
        if (remote is null)
        {
            return null;
        }
        if (remote.Paused)
        {
            return $"{name} paused sharing with this PC.";
        }
        var off = Perms.Capabilities.Where(c => !remote.Allow.GetValueOrDefault(c, true)).Select(c => Perms.Nouns[c]).ToList();
        return off.Count == 0 ? $"{name} allows everything from this PC." : $"{name} doesn't allow {Join(off)} from this PC.";
    }

    /// <summary>"a", "a or b", "a, b or c".</summary>
    public static string Join(IReadOnlyList<string> items)
    {
        ArgumentNullException.ThrowIfNull(items);
        return items.Count switch
        {
            0 => "",
            1 => items[0],
            _ => string.Join(", ", items.Take(items.Count - 1)) + " or " + items[^1],
        };
    }

    /// <summary>The state under a device's name: "Paused", "Paused by Brian's laptop", "Everything is paused", or null.</summary>
    public static string? State(Destination d, bool pausedAll)
    {
        ArgumentNullException.ThrowIfNull(d);
        if (d.Fp is null)
        {
            return null;
        }
        return pausedAll ? "Everything is paused" : d.Paused ? "Paused" : d.PausedByPeer ? $"Paused by {d.Name}" : null;
    }
}
