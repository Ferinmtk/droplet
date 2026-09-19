using System.Net.Http;
using System.Globalization;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using Droplet.Windows.Services;

namespace Droplet.Windows.Views;

/// <summary>
/// A conversation with one device: a mesh peer's messages go directly (or through the hub,
/// or wait in the outbox); a device only the hub knows is written to through the hub.
/// Enter sends; Shift+Enter starts a new line.
/// </summary>
public partial class ChatWindow : Window
{
    readonly App app;
    readonly Destination dest;
    readonly DispatcherTimer poll;
    bool loading;

    internal ChatWindow(App app, Destination dest)
    {
        this.app = app;
        this.dest = dest;
        InitializeComponent();
        Title = $"{dest.Name} · droplet";
        PeerName.Text = dest.Name;
        PeerRoute.Text = dest.IsHub ? "Messages to the hub are saved there as text files." : dest.Route;
        app.Host.ChatChanged += OnChatChanged;
        // a hub's thread has no push to the app: look again now and then while the window is open
        poll = new DispatcherTimer(TimeSpan.FromSeconds(dest.Fp is null ? 5 : 30), DispatcherPriority.Background, async (_, _) => await LoadAsync(), Dispatcher);
        Loaded += async (_, _) =>
        {
            poll.Start();
            await LoadAsync();
            Input.Focus();
        };
        Closed += (_, _) =>
        {
            poll.Stop();
            app.Host.ChatChanged -= OnChatChanged;
        };
    }

    async void OnChatChanged(string key)
    {
        if (key == dest.Fp || key == dest.HubDeviceId || (dest.IsHub && key == "hub"))
        {
            await LoadAsync();
        }
    }

    async Task LoadAsync()
    {
        if (loading || dest.IsHub)
        {
            return;
        }
        loading = true;
        try
        {
            var lines = await app.Host.ChatAsync(dest);
            var atEnd = Scroller.VerticalOffset >= Scroller.ScrollableHeight - 4;
            Messages.ItemsSource = lines.Select(l => new ChatRow(l.Text,
                l.At.ToLocalTime().ToString("g", CultureInfo.CurrentCulture) + (l.Waiting ? " · waiting to be sent" : ""), l.Mine)).ToList();
            if (atEnd)
            {
                Scroller.ScrollToEnd();
            }
        }
        catch (Exception e) when (e is InvalidOperationException or HttpRequestException or Core.Hub.HubException or Core.LocalFirst.UnreachableException or Core.LocalFirst.NotPairedException)
        {
            ShowError("Couldn't load the conversation: " + e.Message);
        }
        finally
        {
            loading = false;
        }
    }

    void ShowError(string? message)
    {
        Error.Text = message ?? "";
        Error.Visibility = string.IsNullOrEmpty(message) ? Visibility.Collapsed : Visibility.Visible;
    }

    void Input_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && (Keyboard.Modifiers & ModifierKeys.Shift) == 0)
        {
            e.Handled = true;
            Send_Click(sender, e);
        }
    }

    async void Send_Click(object sender, RoutedEventArgs e)
    {
        var text = Input.Text.Trim();
        if (text.Length == 0)
        {
            return;
        }
        ShowError(null);
        SendButton.IsEnabled = false;
        Input.Clear();
        try
        {
            await app.Host.SendTextAsync(dest, text);
            await LoadAsync();
            Scroller.ScrollToEnd();
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            ShowError("Not sent: " + ex.Message);
            if (Input.Text.Length == 0)
            {
                Input.Text = text; // nothing lost
            }
        }
        finally
        {
            SendButton.IsEnabled = true;
            Input.Focus();
        }
    }
}
