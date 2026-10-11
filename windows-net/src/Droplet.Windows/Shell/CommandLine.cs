namespace Droplet.Windows.Shell;

/// <summary>What droplet.exe was asked to do.</summary>
internal enum CommandKind
{
    /// <summary>Start (or come to the front): show the devices, or setup on a first run.</summary>
    Open,

    /// <summary>Start in the tray only (at sign-in).</summary>
    Background,

    /// <summary>A notification's droplet: link.</summary>
    Action,

    /// <summary>send --to &lt;dest&gt; &lt;files&gt; (Explorer's Send To).</summary>
    Send,

    /// <summary>Open Settings.</summary>
    Settings,

    /// <summary>Open the devices window.</summary>
    Devices,

    /// <summary>Set up (or change) a hub: optional, so only here and in Settings once there is one.</summary>
    Hub,

    /// <summary>Silence this PC.</summary>
    StopRing,

    /// <summary>Remove what droplet added to Windows.</summary>
    Uninstall,

    /// <summary>Print the version.</summary>
    Version,

    /// <summary>Print the usage.</summary>
    Help,

    /// <summary>Not understood.</summary>
    Invalid,
}

/// <summary>A parsed command line.</summary>
internal sealed record Command(CommandKind Kind, string? Target = null, IReadOnlyList<string>? Files = null, string? Error = null)
{
    /// <summary>The usage text.</summary>
    public const string Usage = """
        droplet: your devices, together, on your own network

        Usage:
          droplet                            start in the tray, or bring droplet to the front
          droplet send --to <device> <file>...  send files to a device, by its name
          droplet settings | devices         open Settings or the devices
          droplet stop-ring                  silence this PC
          droplet uninstall                  remove Start with Windows, Send To, the Start menu entry
                                             and the droplet: link handler (settings are kept)
          droplet version

        Advanced (an optional home server; droplet doesn't need one):
          droplet hub                        link this PC to a droplet hub
          droplet send --to hub <file>...    send files to the hub's shared storage
        """;

    /// <summary>Reads the arguments droplet.exe was started with.</summary>
    public static Command Parse(IReadOnlyList<string> args)
    {
        ArgumentNullException.ThrowIfNull(args);
        if (args.Count == 0)
        {
            return new(CommandKind.Open);
        }
        var first = args[0];
        if (first.StartsWith(ActionLinks.Scheme + ":", StringComparison.OrdinalIgnoreCase))
        {
            return new(CommandKind.Action, first);
        }
        switch (first.ToUpperInvariant())
        {
            case "--BACKGROUND":
                return new(CommandKind.Background);
            case "SETTINGS":
                return new(CommandKind.Settings);
            case "DEVICES":
                return new(CommandKind.Devices);
            case "HUB":
                return new(CommandKind.Hub);
            case "STOP-RING":
                return new(CommandKind.StopRing);
            case "UNINSTALL":
                return new(CommandKind.Uninstall);
            case "VERSION" or "--VERSION":
                return new(CommandKind.Version);
            case "HELP" or "-H" or "--HELP" or "/?":
                return new(CommandKind.Help);
            case "SEND":
                {
                    string? to = null;
                    var files = new List<string>();
                    for (var i = 1; i < args.Count; i++)
                    {
                        if (args[i] is "--to" or "-to" && i + 1 < args.Count && to is null)
                        {
                            to = args[++i];
                        }
                        else if (args[i].StartsWith("--to=", StringComparison.Ordinal) && to is null)
                        {
                            to = args[i][5..];
                        }
                        else
                        {
                            files.Add(args[i]);
                        }
                    }
                    return string.IsNullOrWhiteSpace(to) || files.Count == 0
                        ? new(CommandKind.Invalid, Error: "usage: droplet send --to <device> <file>...")
                        : new(CommandKind.Send, to, files);
                }
        }
        return new(CommandKind.Invalid, Error: $"unknown command \"{first}\"");
    }
}
