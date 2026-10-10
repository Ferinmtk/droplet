using System.Windows;
using Droplet.Core.Mesh;
using Droplet.Windows.Services;

namespace Droplet.Windows.Views;

/// <summary>
/// "Is &lt;name&gt; your device, or someone else's?", asked when this PC accepts a pairing or
/// confirms the code (docs/mesh.md §9.9): two choices, each with what it means. The answer
/// is kept here and never sent; the other device asks its own owner.
/// </summary>
public partial class RelationWindow : Window
{
    internal RelationWindow(string name)
    {
        InitializeComponent();
        Question.Text = SharingText.Question(name);
        OwnTitle.Text = SharingText.OwnTitle;
        OwnLine.Text = SharingText.OwnLine;
        OtherTitle.Text = SharingText.OtherTitle;
        OtherLine.Text = SharingText.OtherLine;
    }

    /// <summary>The answer: <see cref="Perms.Own"/>, <see cref="Perms.Other"/>, or null (cancelled).</summary>
    internal string? Choice { get; private set; }

    /// <summary>Asks, over <paramref name="owner"/> when there is one. Returns the answer, or null.</summary>
    internal static string? Ask(Window? owner, string name)
    {
        var w = new RelationWindow(name);
        if (owner is { IsVisible: true })
        {
            w.Owner = owner;
        }
        else
        {
            w.WindowStartupLocation = WindowStartupLocation.CenterScreen;
            w.Topmost = true;
        }
        w.ShowDialog();
        return w.Choice;
    }

    void Own_Click(object sender, RoutedEventArgs e) => Done(Perms.Own);

    void Other_Click(object sender, RoutedEventArgs e) => Done(Perms.Other);

    void Done(string relation)
    {
        Choice = relation;
        DialogResult = true;
    }
}
