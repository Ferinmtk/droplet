namespace Droplet.Windows.Platform;

/// <summary>What media sessions say, made presentable: pure, so it's tested anywhere.</summary>
internal static class MediaNames
{
    static readonly Dictionary<string, string> Known = new(StringComparer.OrdinalIgnoreCase)
    {
        ["spotify"] = "Spotify", ["chrome"] = "Chrome", ["msedge"] = "Edge", ["firefox"] = "Firefox",
        ["308046b0af4a39cb"] = "Firefox", ["6f193ccc56814779"] = "Firefox", ["microsoft.zunemusic"] = "Media Player",
        ["microsoft.zunevideo"] = "Films & TV", ["vlc"] = "VLC", ["brave"] = "Brave", ["opera"] = "Opera",
        ["foobar2000"] = "foobar2000", ["musicbee"] = "MusicBee", ["itunes"] = "iTunes", ["applemusic"] = "Apple Music",
        ["appleinc.applemusicwin"] = "Apple Music",
    };

    /// <summary>
    /// An AppUserModelID made readable: "Spotify.exe" → "Spotify",
    /// "Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic" → "Media Player".
    /// </summary>
    public static string PlayerName(string? aumid)
    {
        var name = aumid ?? "";
        var slash = name.LastIndexOfAny(['\\', '/']);
        if (slash >= 0)
        {
            name = name[(slash + 1)..];
        }
        var bang = name.IndexOf('!', StringComparison.Ordinal);
        if (bang >= 0)
        {
            name = name[..bang];
        }
        var under = name.IndexOf('_', StringComparison.Ordinal);
        if (under > 0)
        {
            name = name[..under];
        }
        if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
        {
            name = name[..^4];
        }
        if (Known.TryGetValue(name, out var known))
        {
            return known;
        }
        return name.Length == 0 ? "Media" : name;
    }

    /// <summary>The protocol's status for a session's: Playing, Paused, or Stopped for everything else.</summary>
    public static string Status(int playbackStatus) => playbackStatus switch
    {
        4 => "Playing",
        5 => "Paused",
        _ => "Stopped",
    };

    /// <summary>
    /// A session's length and position in seconds. While playing, the position is moved on by
    /// the time since the app last reported it, as apps only report it now and then.
    /// </summary>
    public static (double Length, double Position) Timeline(TimeSpan start, TimeSpan end, TimeSpan position, DateTimeOffset updated, bool playing, DateTimeOffset now)
    {
        var length = (end - start).TotalSeconds;
        var pos = (position - start).TotalSeconds;
        if (playing && updated.Year > 2000 && updated <= now)
        {
            pos += (now - updated).TotalSeconds;
        }
        return (length, length > 0 ? Math.Clamp(pos, 0, length) : Math.Max(pos, 0));
    }

    /// <summary>How many volume-key presses span 0–100 % (each press moves 2 %).</summary>
    public const int VolumeKeySteps = 50;

    /// <summary>
    /// Sets a level with the volume keys alone: all the way down, then up in 2 % steps. Only
    /// when Core Audio isn't there.
    /// </summary>
    public static List<Stroke> VolumeKeys(double level)
    {
        var output = new List<Stroke>();
        for (var i = 0; i < VolumeKeySteps; i++)
        {
            InputTranslator.PressKey(output, new VirtualKey(Keys.VolumeDown, true), []);
        }
        var up = (int)Math.Round(Math.Clamp(level, 0, 1) * VolumeKeySteps);
        for (var i = 0; i < up; i++)
        {
            InputTranslator.PressKey(output, new VirtualKey(Keys.VolumeUp, true), []);
        }
        return output;
    }
}
