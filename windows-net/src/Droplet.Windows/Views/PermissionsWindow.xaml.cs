using System.Windows;
using System.Windows.Controls;
using Droplet.Core.Mesh;
using Droplet.Windows.Services;

namespace Droplet.Windows.Views;

/// <summary>
/// One device's permissions (docs/mesh.md §9.9): whose device it is, a switch per capability
/// with a line on what it covers, and Pause. Changes apply at once, and the device is told.
/// </summary>
public partial class PermissionsWindow : Window
{
    readonly IPermsEditor editor;
    readonly Dictionary<string, CheckBox> boxes = [];
    bool loading;

    internal PermissionsWindow(IPermsEditor editor)
    {
        this.editor = editor;
        InitializeComponent();
        OwnRadio.Content = SharingText.OwnTitle;
        OwnLine.Text = SharingText.OwnLine;
        OtherRadio.Content = SharingText.OtherTitle;
        OtherLine.Text = SharingText.OtherLine;
        foreach (var cap in Perms.Capabilities)
        {
            var box = new CheckBox { Content = Perms.Labels[cap], Tag = cap, Style = (Style)FindResource("Switch") };
            box.Click += Switch_Click;
            boxes[cap] = box;
            Switches.Children.Add(box);
            Switches.Children.Add(new TextBlock { Text = Perms.Explain[cap], Style = (Style)FindResource("Caption"), Margin = new Thickness(28, 0, 0, 6) });
        }
        Activated += (_, _) => Load();
        Load();
    }

    /// <summary>Shows what's current; closes when the device isn't trusted any more.</summary>
    internal void Load()
    {
        if (editor.Current() is not { } v)
        {
            if (IsLoaded)
            {
                Close();
            }
            return;
        }
        loading = true;
        Title = $"{v.Name}: permissions";
        Heading.Text = $"What's shared with {v.Name}";
        OwnRadio.IsChecked = v.Relation != Perms.Other;
        OtherRadio.IsChecked = v.Relation == Perms.Other;
        foreach (var (cap, box) in boxes)
        {
            box.IsChecked = v.Allow.GetValueOrDefault(cap, true);
        }
        PausedBox.Content = $"Pause sharing with {v.Name}";
        PausedBox.IsChecked = v.Paused;
        PausedAllBanner.Visibility = v.PausedAll ? Visibility.Visible : Visibility.Collapsed;
        var word = SharingText.TheirWord(v.Name, v.Remote);
        TheirWord.Text = word ?? "";
        TheirWord.Visibility = word is null ? Visibility.Collapsed : Visibility.Visible;
        loading = false;
    }

    void Apply(Action change)
    {
        if (loading)
        {
            return;
        }
        Error.Visibility = Visibility.Collapsed;
        try
        {
            change();
        }
        catch (Exception e) when (e is ArgumentException or InvalidOperationException or System.IO.IOException)
        {
            Error.Text = e.Message;
            Error.Visibility = Visibility.Visible;
        }
        Load();
    }

    void Relation_Click(object sender, RoutedEventArgs e)
    {
        var relation = OtherRadio.IsChecked == true ? Perms.Other : Perms.Own;
        if (editor.Current()?.Relation != relation)
        {
            Apply(() => editor.Set(relation: relation)); // starts from its defaults
        }
    }

    void Switch_Click(object sender, RoutedEventArgs e)
    {
        if (sender is CheckBox { Tag: string cap } box)
        {
            Apply(() => editor.Set(allow: new Dictionary<string, bool> { [cap] = box.IsChecked == true }));
        }
    }

    void Paused_Click(object sender, RoutedEventArgs e) => Apply(() => editor.Set(paused: PausedBox.IsChecked == true));

    void Done_Click(object sender, RoutedEventArgs e) => Close();
}
