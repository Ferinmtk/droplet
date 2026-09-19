using System.Net.Http;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using Droplet.Core.Mesh;
using Droplet.Windows.Services;

namespace Droplet.Windows.Views;

/// <summary>
/// droplet's main window: the devices and how each is reached, what can be done with them,
/// devices asking to pair, and pairing a new one (both sides compare a four-digit code).
/// </summary>
public partial class DevicesWindow : Window
{
    readonly App app;
    readonly DispatcherTimer pairPoll;
    string? selectedKey;
    string? pairRequest;
    string pairPeer = "";

    internal DevicesWindow(App app)
    {
        this.app = app;
        InitializeComponent();
        pairPoll = new DispatcherTimer(TimeSpan.FromSeconds(1), DispatcherPriority.Normal, (_, _) => PollPairing(), Dispatcher);
        app.Host.Changed += Refresh;
        Closed += (_, _) =>
        {
            app.Host.Changed -= Refresh;
            pairPoll.Stop();
        };
        Refresh();
    }

    AppHost Host => app.Host;

    Destination? Selected => (List.SelectedItem as DeviceRow)?.Item as Destination;

    /// <summary>Shows what's current.</summary>
    internal void Refresh()
    {
        var tray = Host.Tray;
        StatusText.Text = tray.Tooltip().Replace("droplet — ", "", StringComparison.Ordinal);
        RingBanner.Visibility = tray.RingingFrom is null ? Visibility.Collapsed : Visibility.Visible;
        RingText.Text = $"{tray.RingingFrom} is ringing this PC.";
        ControlBanner.Visibility = tray.ControlledBy is null || tray.RemotePaused ? Visibility.Collapsed : Visibility.Visible;
        ControlText.Text = $"{tray.ControlledBy} is controlling this PC (or did in the last two minutes).";

        var rows = Host.Destinations.Select(d => new DeviceRow(d.Name, d.IsHub ? (d.Online ? "files and messages go here" : "offline") : d.Route, d.Online, d.Os, d)).ToList();
        List.ItemsSource = rows;
        var again = rows.FirstOrDefault(r => ((Destination)r.Item).Key == selectedKey) ?? (selectedKey is null ? rows.FirstOrDefault() : null);
        List.SelectedItem = again;
        EmptyText.Visibility = rows.Count == 0 && PairCard.Visibility != Visibility.Visible ? Visibility.Visible : Visibility.Collapsed;

        var mesh = Host.Engine?.Mesh;
        var requests = mesh?.Incoming.Waiting()
            .Select(r => new RequestRow($"{r.Name} wants to pair{(r.Os.Length > 0 ? $" ({r.Os})" : "")}", r.Code, r.Request)).ToList() ?? [];
        Requests.ItemsSource = requests;
        RequestsCard.Visibility = requests.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

        if (mesh is not null)
        {
            var trusted = mesh.Trust.All().Select(t => t.Fp).ToHashSet();
            var nearby = mesh.Nearby.Where(s => !trusted.Contains(s.Fp)).GroupBy(s => s.Fp).Select(g => g.First())
                .Select(s => new NearbyRow(s.Name, $"{(s.Os.Length > 0 ? s.Os + " · " : "")}{string.Join(", ", s.Addresses)}", s.Fp)).ToList();
            Nearby.ItemsSource = nearby;
            NoNearby.Visibility = nearby.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        ShowDetail();
    }

    void ShowDetail()
    {
        if (Selected is not { } d)
        {
            DetailCard.Visibility = Visibility.Collapsed;
            return;
        }
        selectedKey = d.Key;
        DetailCard.Visibility = Visibility.Visible;
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
        MessageButton.Visibility = Visibility.Visible;
        ClipboardButton.Visibility = Visibility.Visible;
        RingButton.Visibility = Visibility.Visible;
        UnpairButton.Visibility = d.Paired ? Visibility.Visible : Visibility.Collapsed;
        DetailNote.Text = d.Fp is not null && !d.Paired
            ? "Trusted because your hub lists it. To remove it, remove it on the hub."
            : "";
    }

    void List_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (Selected is { } d)
        {
            selectedKey = d.Key;
        }
        ShowDetail();
    }

    void OpenWeb_Click(object sender, RoutedEventArgs e) => app.OpenWeb();

    void Settings_Click(object sender, RoutedEventArgs e) => app.ShowSettings();

    async void StopRing_Click(object sender, RoutedEventArgs e) => await Host.StopRingAsync();

    void PauseRemote_Click(object sender, RoutedEventArgs e)
    {
        Host.SetRemotePaused(true);
        Refresh();
    }

    void SendFiles_Click(object sender, RoutedEventArgs e)
    {
        if (Selected is { } d)
        {
            app.PickAndSend(d);
        }
    }

    void Message_Click(object sender, RoutedEventArgs e)
    {
        if (Selected is { } d)
        {
            app.ShowChat(d);
        }
    }

    void Clipboard_Click(object sender, RoutedEventArgs e)
    {
        if (Selected is { } d)
        {
            app.SendClipboard(d);
        }
    }

    async void Ring_Click(object sender, RoutedEventArgs e)
    {
        if (Selected is { } d)
        {
            await Host.RingAsync(d);
        }
    }

    async void Unpair_Click(object sender, RoutedEventArgs e)
    {
        if (Selected is not { Fp: { } fp } d || Host.Engine?.Mesh is not { } m)
        {
            return;
        }
        if (MessageBox.Show(this, $"Stop trusting {d.Name}? It won't reach this PC directly until you pair again.", "Unpair",
                MessageBoxButton.OKCancel, MessageBoxImage.Question) != MessageBoxResult.OK)
        {
            return;
        }
        try
        {
            await m.UnpairAsync(fp);
            selectedKey = null;
        }
        catch (InvalidOperationException ex)
        {
            app.ShowError(ex.Message);
        }
        Host.Refresh();
    }

    void Accept_Click(object sender, RoutedEventArgs e) => Answer(sender, true);

    void Deny_Click(object sender, RoutedEventArgs e) => Answer(sender, false);

    void Answer(object sender, bool accept)
    {
        if ((sender as FrameworkElement)?.Tag is string request)
        {
            Host.AnswerPairing(request, accept);
            Refresh();
        }
    }

    // --- pairing a new device ----------------------------------------------------------------------

    void ShowPairing_Click(object sender, RoutedEventArgs e) => OpenPairing();

    /// <summary>Shows the pairing section.</summary>
    internal void OpenPairing()
    {
        PairCard.Visibility = Visibility.Visible;
        EmptyText.Visibility = Visibility.Collapsed;
        Address.Focus();
    }

    void PairNearby_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is string fp && Host.Engine?.Mesh?.Nearby.FirstOrDefault(s => s.Fp == fp) is { } peer)
        {
            // by fingerprint: the pairing then checks the device it reaches is the one announced
            _ = StartPairingAsync(fp, peer.Name);
        }
    }

    void PairAddress_Click(object sender, RoutedEventArgs e)
    {
        if (Address.Text.Trim() is { Length: > 0 } target)
        {
            _ = StartPairingAsync(target, target);
        }
    }

    async Task StartPairingAsync(string target, string shown)
    {
        if (Host.Engine?.Mesh is not { } m)
        {
            PairFailed("Direct connections are switched off in Settings (Mesh).");
            return;
        }
        PairError.Visibility = Visibility.Collapsed;
        PairProgress.Visibility = Visibility.Visible;
        PairButtons.Visibility = Visibility.Collapsed;
        PairCode.Text = "";
        PairText.Text = $"Asking {shown}…";
        try
        {
            var start = await m.PairStartAsync(target);
            (pairRequest, pairPeer) = (start.Request, start.PeerName);
            PairText.Text = $"{start.PeerName} shows a code too. Does it match this one?";
            PairCode.Text = start.Code;
            PairButtons.Visibility = Visibility.Visible;
        }
        catch (Exception ex) when (ex is PairException or ArgumentException or InvalidOperationException or HttpRequestException)
        {
            PairFailed(ex.Message);
        }
    }

    async void PairMatch_Click(object sender, RoutedEventArgs e)
    {
        if (pairRequest is null || Host.Engine?.Mesh is not { } m)
        {
            return;
        }
        try
        {
            await m.PairConfirmAsync(pairRequest, true);
            PairButtons.Visibility = Visibility.Collapsed;
            PairText.Text = $"Waiting for {pairPeer} to say yes too…";
            pairPoll.Start();
        }
        catch (ArgumentException ex)
        {
            PairFailed(ex.Message);
        }
    }

    async void PairCancel_Click(object sender, RoutedEventArgs e)
    {
        if (pairRequest is { } r && Host.Engine?.Mesh is { } m)
        {
            try
            {
                await m.PairConfirmAsync(r, false);
            }
            catch (Exception ex) when (ex is ArgumentException or HttpRequestException or PairException)
            {
                // it's gone either way
            }
        }
        pairRequest = null;
        pairPoll.Stop();
        PairProgress.Visibility = Visibility.Collapsed;
    }

    void PollPairing()
    {
        if (pairRequest is null || Host.Engine?.Mesh is not { } m)
        {
            pairPoll.Stop();
            return;
        }
        var state = m.PairStatus(pairRequest);
        if (!PairState.IsFinal(state))
        {
            return;
        }
        pairPoll.Stop();
        pairRequest = null;
        PairButtons.Visibility = Visibility.Collapsed;
        PairCode.Text = "";
        PairText.Text = state switch
        {
            PairState.Accepted => $"Paired with {pairPeer}.",
            PairState.Denied => $"{pairPeer} said no.",
            PairState.Cancelled => "Cancelled.",
            _ => $"{pairPeer} didn't answer in time. Try again.",
        };
        Host.Refresh();
    }

    void PairFailed(string message)
    {
        PairProgress.Visibility = Visibility.Collapsed;
        PairError.Text = message;
        PairError.Visibility = Visibility.Visible;
    }
}
