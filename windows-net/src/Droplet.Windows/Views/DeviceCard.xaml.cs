using System.Windows;
using System.Windows.Controls;
using Droplet.Core.Mesh;
using Droplet.Windows.Services;

namespace Droplet.Windows.Views;

/// <summary>
/// One device in the Devices window: how it's reached; whether it's someone else's, paused
/// here or paused by it (docs/mesh.md §9.9); and what can be done with it, each action greyed
/// with the reason when it would be refused. The window wires the buttons.
/// </summary>
public partial class DeviceCard : UserControl
{
    /// <summary>Creates an empty card; <see cref="Show"/> fills it.</summary>
    public DeviceCard()
    {
        InitializeComponent();
    }

    /// <summary>Shows <paramref name="d"/>; <paramref name="pausedAll"/>: Pause everything is on.</summary>
    internal void Show(Destination d, bool pausedAll)
    {
        DetailName.Text = d.Name;
        DetailRoute.Text = d.IsHub
            ? "Your hub. Files sent here land in its shared storage; open droplet to see them."
            : d.Route switch
            {
                "on Wi-Fi" => "Connected directly, on this network.",
                "via Tailscale" => "Connected directly, over Tailscale.",
                "via the hub" => "Reached through your hub.",
                "nearby" => "On this network; droplet connects when there's something to send.",
                _ => d.Fp is not null
                    ? "Not reachable now. Messages and files wait, and go when it's back."
                    : "Offline. The hub keeps messages and files for it.",
            };
        UnpairButton.Visibility = d.Paired ? Visibility.Visible : Visibility.Collapsed;

        // what's shared with it: only mesh peers have permissions
        var peer = d.Fp is not null;
        OtherBadge.Visibility = d.Other ? Visibility.Visible : Visibility.Collapsed;
        var state = SharingText.State(d, pausedAll);
        PausedBadge.Visibility = state is null ? Visibility.Collapsed : Visibility.Visible;
        PausedBadgeText.Text = state ?? "";
        Gate(SendFilesButton, Destinations.Blocked(d, Perms.Files, pausedAll));
        Gate(MessageButton, Destinations.Blocked(d, Perms.Chat, pausedAll));
        Gate(ClipboardButton, Destinations.Blocked(d, Perms.Clipboard, pausedAll));
        Gate(RingButton, Destinations.Blocked(d, Perms.Ring, pausedAll));
        PauseButton.Visibility = peer ? Visibility.Visible : Visibility.Collapsed;
        PauseButton.Content = d.Paused ? "Resume" : "Pause";
        PauseButton.ToolTip = d.Paused ? $"Share with {d.Name} again" : $"Share nothing with {d.Name}, either way, until you resume";
        PermissionsButton.Visibility = peer ? Visibility.Visible : Visibility.Collapsed;
        DetailNote.Text = Note(d);
        DetailNote.Visibility = DetailNote.Text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>The line under the buttons: why the hub's devices can't be unpaired here, what the device said, or what it last refused.</summary>
    internal static string Note(Destination d)
    {
        var notes = new List<string>();
        if (d.Fp is null)
        {
            return "";
        }
        if (!d.Paired)
        {
            notes.Add("Trusted because your hub lists it. To remove it, remove it on the hub.");
        }
        if (SharingText.TheirWord(d.Name, d.Remote) is { } word && (d.PausedByPeer || d.Remote!.Allow.Values.Contains(false)))
        {
            notes.Add(word);
        }
        else if (d.Refused is { } refused)
        {
            notes.Add($"Last refused: {refused}.");
        }
        return string.Join(" ", notes);
    }

    /// <summary>
    /// A button for something that may not go now: greyed with the reason when it's refused,
    /// still on (with a note) when it only waits for a resume.
    /// </summary>
    static void Gate(Button b, Block? block)
    {
        b.IsEnabled = block is null || block.Waits;
        b.ToolTip = block is null ? null : block.Waits ? $"{block.Text}. It waits, and goes when sharing resumes." : block.Text;
    }
}
