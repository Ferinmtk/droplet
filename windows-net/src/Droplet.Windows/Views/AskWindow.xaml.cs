using System.Windows;
using System.Windows.Controls;

namespace Droplet.Windows.Views;

/// <summary>
/// Asks for one line: a link to send ("Send a link…"), a nickname ("Nickname…"), or a message
/// for several devices. <c>check</c> says what's wrong with it (or returns it tidied), so a
/// bad link or name is caught here, before anything is sent.
/// </summary>
public partial class AskWindow : Window
{
    readonly Func<string, string>? check;

    /// <param name="heading">The question.</param>
    /// <param name="explain">A line under it.</param>
    /// <param name="ok">The button's word: "Send", "Save".</param>
    /// <param name="text">What's in the box to begin with.</param>
    /// <param name="check">Returns the answer tidied, or throws <see cref="ArgumentException"/> saying what's wrong.</param>
    /// <param name="emptyOk">An empty answer is one (clearing a nickname).</param>
    internal AskWindow(string heading, string explain, string ok, string text = "", Func<string, string>? check = null, bool emptyOk = false)
    {
        this.check = check;
        EmptyOk = emptyOk;
        InitializeComponent();
        Heading.Text = heading;
        Explain.Text = explain;
        Explain.Visibility = explain.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        OkButton.Content = ok;
        Answer.Text = text;
        Loaded += (_, _) =>
        {
            Answer.Focus();
            Answer.SelectAll();
        };
        Update();
    }

    bool EmptyOk { get; }

    /// <summary>The answer, checked; null until OK.</summary>
    internal string? Result { get; private set; }

    /// <summary>Asks, over <paramref name="owner"/> when there is one. Returns the answer, or null.</summary>
    internal static string? Ask(Window? owner, string heading, string explain, string ok, string text = "", Func<string, string>? check = null,
        bool emptyOk = false)
    {
        var w = new AskWindow(heading, explain, ok, text, check, emptyOk);
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
        return w.Result;
    }

    void Answer_TextChanged(object sender, TextChangedEventArgs e) => Update();

    void Update()
    {
        if (OkButton is null)
        {
            return;
        }
        OkButton.IsEnabled = EmptyOk || Answer.Text.Trim().Length > 0;
        Problem.Visibility = Visibility.Collapsed;
    }

    void Ok_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Result = check is null ? Answer.Text.Trim() : check(Answer.Text);
            DialogResult = true;
        }
        catch (ArgumentException ex)
        {
            Problem.Text = Sentence(ex.Message);
            Problem.Visibility = Visibility.Visible;
        }
    }

    /// <summary>"that link has no address in it" → "That link has no address in it."</summary>
    internal static string Sentence(string s) =>
        s.Length == 0 ? s : char.ToUpperInvariant(s[0]) + s[1..] + (s.EndsWith('.') ? "" : ".");
}
