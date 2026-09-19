using System.Windows;

namespace Droplet.Windows.Views;

// What the windows' lists show. Public, because WPF binds only to public types; each row
// carries what it stands for (Item) for the click handlers.

/// <summary>A device in the list.</summary>
public sealed record DeviceRow(string Name, string Route, bool Online, string Os, object Item)
{
    /// <summary>The dot's opacity: full when reachable.</summary>
    public double DotOpacity => Online ? 1 : 0.3;

    /// <summary>A short line under the name.</summary>
    public string Detail => Os.Length > 0 ? $"{Route} · {Os}" : Route;
}

/// <summary>A device asking to pair.</summary>
public sealed record RequestRow(string Title, string Code, string Request);

/// <summary>A device announcing itself nearby, not paired yet.</summary>
public sealed record NearbyRow(string Name, string Detail, string Target);

/// <summary>A hub found on the network.</summary>
public sealed record HubRow(string Name, string Detail, string Id);

/// <summary>A chat message.</summary>
public sealed record ChatRow(string Text, string Meta, bool Mine)
{
    public HorizontalAlignment Side => Mine ? HorizontalAlignment.Right : HorizontalAlignment.Left;
}
