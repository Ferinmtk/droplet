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
    readonly DispatcherTimer gatewayScan;
    bool scanning;
    string? selectedKey;
    string deviceKey = "", requestKey = "", nearbyKey = "";
    string? pairRequest;
    string pairPeer = "";

    internal DevicesWindow(App app)
    {
        this.app = app;
        InitializeComponent();
        Card.SendFilesButton.Click += SendFiles_Click;
        Card.MessageButton.Click += Message_Click;
        Card.ClipboardButton.Click += Clipboard_Click;
        Card.RingButton.Click += Ring_Click;
        Card.PauseButton.Click += Pause_Click;
        Card.PermissionsButton.Click += Permissions_Click;
        Card.UnpairButton.Click += Unpair_Click;
        pairPoll = new DispatcherTimer(TimeSpan.FromSeconds(1), DispatcherPriority.Normal, (_, _) => PollPairing(), Dispatcher);
        // while pairing is open: ask the gateway who it is, for a phone's hotspot (docs/mesh.md §9.10)
        gatewayScan = new DispatcherTimer(TimeSpan.FromSeconds(5), DispatcherPriority.Background, (_, _) => _ = ScanGatewaysAsync(), Dispatcher);
        app.Host.Changed += Refresh;
        Closed += (_, _) =>
        {
            app.Host.Changed -= Refresh;
            pairPoll.Stop();
            gatewayScan.Stop();
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
        PausedBanner.Visibility = tray.PausedAll ? Visibility.Visible : Visibility.Collapsed;

        // the list is rebuilt only when it changed, so selection, focus and scrolling stay put
        var rows = Host.Destinations.Select(d => new DeviceRow(d.Name, RowRoute(d), d.Online, d.Os, d)).ToList();
        if (Changed(ref deviceKey, rows.Select(r => $"{((Destination)r.Item).Key}|{r.Name}|{r.Route}|{r.Online}|{r.Os}|{SharingKey((Destination)r.Item)}")))
        {
            List.ItemsSource = rows;
            List.SelectedItem = rows.FirstOrDefault(r => ((Destination)r.Item).Key == selectedKey) ?? (selectedKey is null ? rows.FirstOrDefault() : null);
        }
        EmptyText.Visibility = rows.Count == 0 && PairCard.Visibility != Visibility.Visible ? Visibility.Visible : Visibility.Collapsed;

        var mesh = Host.Engine?.Mesh;
        var requests = mesh?.Incoming.Waiting()
            .Select(r => new RequestRow($"{r.Name} wants to pair{(r.Os.Length > 0 ? $" ({r.Os})" : "")}", r.Code, r.Request, r.Name)).ToList() ?? [];
        if (Changed(ref requestKey, requests.Select(r => r.Request + r.Code)))
        {
            Requests.ItemsSource = requests;
        }
        RequestsCard.Visibility = requests.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

        if (mesh is not null)
        {
            var trusted = mesh.Trust.All().Select(t => t.Fp).ToHashSet();
            var nearby = mesh.Nearby.Where(s => !trusted.Contains(s.Fp)).GroupBy(s => s.Fp).Select(g => g.First())
                .Select(s => new NearbyRow(s.Name, $"{(s.Os.Length > 0 ? s.Os + " · " : "")}{string.Join(", ", s.Addresses)}", s.Fp)).ToList();
            if (Changed(ref nearbyKey, nearby.Select(n => n.Target + n.Name + n.Detail)))
            {
                Nearby.ItemsSource = nearby;
            }
            NoNearby.Visibility = nearby.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        ShowDetail();
    }

    /// <summary>The line under a device's name: how it's reached, and whether it's paused or someone else's.</summary>
    internal static string RowRoute(Destination d)
    {
        if (d.IsHub)
        {
            return d.Online ? "files and messages go here" : "offline";
        }
        var route = d.Paused ? "paused" : d.PausedByPeer ? $"{d.Route} · paused by it" : d.Route;
        return d.Other ? $"{route} · someone else's" : route;
    }

    /// <summary>What the detail card shows about sharing, so a change rebuilds the list (and the card) even when the line under the name stays.</summary>
    static string SharingKey(Destination d) =>
        string.Join(",", Perms.Capabilities.Select(c => d.Allow?.GetValueOrDefault(c, true) == false ? "0" : "1")) +
        $"|{d.Paused}|{d.PausedByPeer}|{d.Other}|{d.Refused}|{d.Caps.Count}|" +
        (d.Remote is { } r ? string.Join(",", r.Allow.Select(p => $"{p.Key}={p.Value}")) : "-");

    static bool Changed(ref string last, IEnumerable<string> parts)
    {
        var now = string.Join("\n", parts);
        if (now == last)
        {
            return false;
        }
        last = now;
        return true;
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
        Card.Show(d, Host.Tray.PausedAll);
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
            App.ShowError(ex.Message);
        }
        Host.Refresh();
    }

    void Accept_Click(object sender, RoutedEventArgs e)
    {
        // whose device it is decides what it may do: asked here, never sent to it
        if ((sender as FrameworkElement)?.Tag is RequestRow r && RelationWindow.Ask(this, r.Name) is { } relation)
        {
            Host.AnswerPairing(r.Request, true, relation);
            Refresh();
        }
    }

    void Deny_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is RequestRow r)
        {
            Host.AnswerPairing(r.Request, false);
            Refresh();
        }
    }

    void ResumeAll_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Host.SetPausedAll(false);
        }
        catch (InvalidOperationException ex)
        {
            App.ShowError(ex.Message);
        }
    }

    void Pause_Click(object sender, RoutedEventArgs e)
    {
        if (Selected is { Fp: not null } d)
        {
            Host.SetPeerPaused(d, !d.Paused);
        }
    }

    void Permissions_Click(object sender, RoutedEventArgs e)
    {
        if (Selected is { Fp: { } fp })
        {
            app.ShowPermissions(fp, this);
        }
    }

    // --- pairing a new device ----------------------------------------------------------------------

    void ShowPairing_Click(object sender, RoutedEventArgs e) => OpenPairing();

    /// <summary>Shows the pairing section.</summary>
    internal void OpenPairing()
    {
        PairCard.Visibility = Visibility.Visible;
        EmptyText.Visibility = Visibility.Collapsed;
        if (!gatewayScan.IsEnabled)
        {
            gatewayScan.Start();
            _ = ScanGatewaysAsync();
        }
        Address.Focus();
    }

    /// <summary>
    /// On a phone's hotspot nothing announces itself: the device serving it is the gateway, so
    /// it's asked who it is (each gateway has its own back-off), and devices that asked this PC
    /// show up too. Then the list is shown again.
    /// </summary>
    async Task ScanGatewaysAsync()
    {
        if (scanning || Host.Engine?.Mesh is not { } m)
        {
            return;
        }
        scanning = true;
        try
        {
            await m.ScanGatewaysAsync().ConfigureAwait(true);
        }
        catch (Exception ex) when (ex is ObjectDisposedException or InvalidOperationException)
        {
            // the mesh stopped meanwhile
        }
        finally
        {
            scanning = false;
        }
        if (IsLoaded)
        {
            Refresh();
        }
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
        // whose device it is decides what it may do: asked here, never sent to it
        if (RelationWindow.Ask(this, pairPeer) is not { } relation)
        {
            return;
        }
        try
        {
            await m.PairConfirmAsync(pairRequest, true, relation);
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
            PairState.Accepted => m.Trust.Find(pairPeer).FirstOrDefault() is { IsOther: true }
                ? $"Paired with {pairPeer}, as someone else's device: files, messages and ring (Permissions… changes that)."
                : $"Paired with {pairPeer}.",
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
