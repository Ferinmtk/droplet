using Droplet.Core.Config;

namespace Droplet.Windows.ViewModels;

/// <summary>What the first start opens.</summary>
internal enum FirstStart
{
    /// <summary>Set up already: the devices window.</summary>
    Devices,

    /// <summary>Nothing yet: pairing a device directly, the hub-free start.</summary>
    Pairing,

    /// <summary>A hub given but not joined: its setup window.</summary>
    HubSetup,
}

/// <summary>
/// The hub is optional, and hidden until this PC has one (<see cref="AppConfig.UsesHub"/>):
/// someone who never set one up never reads the word. A PC linked to a hub keeps all of it.
/// Pure, so it's tested anywhere. A hub is added with <c>droplet hub</c> (the setup window).
/// </summary>
internal static class HubUi
{
    /// <summary>What opening droplet shows: pairing on a fresh install, the hub's setup only for a hub that's half set up.</summary>
    public static FirstStart OnOpen(AppConfig cfg, int trusted)
    {
        ArgumentNullException.ThrowIfNull(cfg);
        if (cfg.Registered || cfg.Pending || trusted > 0)
        {
            return FirstStart.Devices;
        }
        return cfg.UsesHub ? FirstStart.HubSetup : FirstStart.Pairing;
    }

    /// <summary>The note under Rename.</summary>
    public static string NameNote(bool usesHub) =>
        "Rename this PC: its name on your devices, told to the ones connected now." + (usesHub ? " With a hub, the hub renames it first." : "");

    /// <summary>The note under Direct connections.</summary>
    public static string MeshNote(bool usesHub) => usesHub
        ? "Your devices talk to this PC directly, on your Wi-Fi or over Tailscale, and the hub is only a helper. " +
          "Only devices you paired, or that your hub vouches for, can connect."
        : "Your devices talk to this PC directly, on your Wi-Fi or over Tailscale. Only devices you paired can connect.";
}
