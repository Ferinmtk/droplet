using System.Windows;

namespace Droplet.Windows.Views;

// What the windows' lists show. Public, because WPF binds only to public types; each row
// carries what it stands for (Item) for the click handlers.

/// <summary>A device in the list.</summary>
public sealed record DeviceRow(string Name, string Route, bool Online, string Os, object Item, string RealName = "", bool Selecting = false,
    bool Checked = false)
{
    /// <summary>The dot's opacity: full when reachable.</summary>
    public double DotOpacity => Online ? 1 : 0.3;

    /// <summary>A short line under the name (its own name first, when a nickname is shown).</summary>
    public string Detail => string.Join(" · ", new[] { RealName, Route, Os }.Where(s => s.Length > 0));

    /// <summary>The checkbox shows in Select mode.</summary>
    public Visibility CheckVisibility => Selecting ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>The dot shows otherwise.</summary>
    public Visibility DotVisibility => Selecting ? Visibility.Collapsed : Visibility.Visible;
}

/// <summary>A device asking to pair.</summary>
public sealed record RequestRow(string Title, string Code, string Request, string Name);

/// <summary>A device announcing itself nearby, not paired yet.</summary>
public sealed record NearbyRow(string Name, string Detail, string Target);

/// <summary>A hub found on the network.</summary>
public sealed record HubRow(string Name, string Detail, string Id);

/// <summary>A chat message; <paramref name="Url"/>, a web link it is (an Open button).</summary>
public sealed record ChatRow(string Text, string Meta, bool Mine, string? Url = null)
{
    public HorizontalAlignment Side => Mine ? HorizontalAlignment.Right : HorizontalAlignment.Left;

    /// <summary>The Open button shows for a link.</summary>
    public Visibility OpenVisibility => Url is null ? Visibility.Collapsed : Visibility.Visible;
}

/// <summary>
/// A file on its way, on a device's card and in the tray (docs/mesh.md §9.11): its name, which
/// way and with whom, a progress bar, "45% · 12 s left · 3.2 MB/s" (or how it ended), and Cancel.
/// </summary>
public sealed record TransferRow(string Id, string Name, string Way, double Percent, string Status, bool Active, bool CanCancel, bool Indeterminate)
{
    /// <summary>The progress bar shows while it's moving (or waiting part-way).</summary>
    public Visibility BarVisibility => CanCancel ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>Cancel shows while it can still be stopped.</summary>
    public Visibility CancelVisibility => CanCancel ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>A line for menus: "photo.jpg to pixel · 45% · 12 s left".</summary>
    public string MenuLine => $"{Name} {Way} · {Status}";

    /// <summary>One transfer, with <paramref name="peer"/> what this PC calls the other end.</summary>
    public static TransferRow Of(Core.Mesh.TransferView t, string peer)
    {
        ArgumentNullException.ThrowIfNull(t);
        var active = t.State is Core.Mesh.TransferState.Active;
        var canCancel = t.State is Core.Mesh.TransferState.Active or Core.Mesh.TransferState.Waiting;
        return new TransferRow(t.Id, t.Name, (t.Dir == "out" ? "to " : "from ") + peer, t.Percent, Core.Mesh.Transfers.ProgressText(t), active, canCancel,
            Indeterminate: active && t.Route == "hub" && t.Done == 0);
    }

    /// <summary>A file still waiting in the outbox, not started.</summary>
    public static TransferRow Queued(Core.Mesh.OutboxJob j, string peer)
    {
        ArgumentNullException.ThrowIfNull(j);
        var why = j.Error is { Length: > 0 } e ? e.StartsWith("waiting: ", StringComparison.Ordinal) ? e["waiting: ".Length..] : e : null;
        return new TransferRow(j.Id, j.Name ?? "file", "to " + peer, 0, why is null ? "Waiting to be sent" : $"Waiting: {why}", false, true, false);
    }
}
