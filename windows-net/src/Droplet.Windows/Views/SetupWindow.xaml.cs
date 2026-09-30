using System.Net.Http;
using System.Windows;
using System.Windows.Controls;
using Droplet.Core.LocalFirst;
using Droplet.Windows.Services;

namespace Droplet.Windows.Views;

/// <summary>
/// Getting this PC into droplet (docs/local-first.md §4): join a hub found on the LAN and
/// compare the four-digit code, or use its PIN; link with a six-digit code to the device this
/// PC's browser already is; connect over the tailnet; or skip the hub and pair directly.
/// With a hub id, it re-joins a hub whose certificate changed, trusting the new one.
/// </summary>
[System.Diagnostics.CodeAnalysis.SuppressMessage("Reliability", "CA1001", Justification = "The window disposes it when it closes")]
public partial class SetupWindow : Window
{
    readonly App app;
    readonly string? repairHub;
    CancellationTokenSource? waiting;

    internal SetupWindow(App app, string? repairHub)
    {
        this.app = app;
        this.repairHub = repairHub;
        InitializeComponent();
        var cfg = app.Host.Config;
        NameBox.Text = cfg.DeviceName ?? Environment.MachineName.ToLowerInvariant();
        UrlBox.Text = cfg.RemoteUrl;
        if (repairHub is not null)
        {
            Heading.Text = "Re-pair with your hub";
            Subheading.Text = "Your hub has a new certificate. Join it again on this network and compare the code, so droplet trusts the new one.";
        }
        else if (cfg.Registered)
        {
            Heading.Text = "Change hub";
        }
        Loaded += async (_, _) =>
        {
            if (cfg.Pending && repairHub is null)
            {
                Wait(cfg.PairCode, Core.Polling.HubPoller.HubName(cfg));
            }
            await ScanAsync();
        };
        Closed += (_, _) =>
        {
            waiting?.Cancel();
            waiting?.Dispose();
            waiting = null;
        };
    }

    AppHost Host => app.Host;

    HubSetup Setup => Host.Engine?.Setup ?? throw new InvalidOperationException("droplet is restarting; try again in a moment");

    static void Show(TextBlock error, string? message)
    {
        error.Text = message ?? "";
        error.Visibility = string.IsNullOrEmpty(message) ? Visibility.Collapsed : Visibility.Visible;
    }

    void ClearErrors()
    {
        foreach (var t in (ReadOnlySpan<TextBlock>)[NameError, PinError, HubError, CodeError, UrlError, WaitError])
        {
            Show(t, null);
        }
    }

    /// <summary>Puts a setup error next to the field it's about.</summary>
    void FieldError(FieldException e, TextBlock fallback) => Show(e.Field switch
    {
        "name" => NameError,
        "pin" => PinError,
        "hub" => HubError,
        "link_code" => CodeError,
        "hub_url" => UrlError,
        _ => fallback,
    }, e.Message);

    string? SelectedHub => (Hubs.SelectedItem as HubRow)?.Id;

    void Busy(bool busy)
    {
        foreach (var b in (ReadOnlySpan<Button>)[JoinButton, LinkButton, TailnetButton, ScanButton])
        {
            b.IsEnabled = !busy;
        }
        Cursor = busy ? System.Windows.Input.Cursors.AppStarting : null;
    }

    async void Scan_Click(object sender, RoutedEventArgs e) => await ScanAsync();

    async Task ScanAsync()
    {
        ScanStatus.Text = "Looking…";
        ScanButton.IsEnabled = false;
        try
        {
            var found = await Setup.DiscoverAsync();
            var rows = found.Select(h => new HubRow(h.DisplayName, $"{string.Join(", ", h.Endpoints)} · id {h.Id}", h.Id)).ToList();
            Hubs.ItemsSource = rows;
            Hubs.SelectedItem = rows.FirstOrDefault(r => r.Id == repairHub) ?? (rows.Count == 1 ? rows[0] : null);
            ScanStatus.Text = rows.Count switch
            {
                0 => "None found. Is the hub on, and this PC on the same Wi-Fi? You can use its Tailscale address below instead.",
                1 => "Found one.",
                _ => $"Found {rows.Count}: choose yours.",
            };
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.Net.Sockets.SocketException)
        {
            ScanStatus.Text = "Couldn't look for hubs: " + ex.Message;
        }
        finally
        {
            ScanButton.IsEnabled = true;
        }
    }

    async void Join_Click(object sender, RoutedEventArgs e)
    {
        ClearErrors();
        if (SelectedHub is not { } hub)
        {
            Show(HubError, "Choose your hub in the list first.");
            return;
        }
        Busy(true);
        try
        {
            var res = await Setup.JoinAsync(hub, NameBox.Text, NullIfEmpty(PinBox.Password), trustNew: repairHub is not null);
            if (res.Pending)
            {
                Wait(res.Code, res.Hub);
            }
            else
            {
                Done($"This PC is \"{res.Name}\" on {res.Hub}.");
            }
        }
        catch (FieldException ex)
        {
            FieldError(ex, HubError);
        }
        catch (InvalidOperationException ex)
        {
            Show(HubError, ex.Message);
        }
        finally
        {
            Busy(false);
        }
    }

    async void Link_Click(object sender, RoutedEventArgs e)
    {
        ClearErrors();
        Busy(true);
        try
        {
            var pin = NullIfEmpty(PinBox.Password);
            var res = SelectedHub is { } hub
                ? await Setup.LinkLanAsync(hub, CodeBox.Text, pin)
                : await Setup.LinkAsync(NullIfEmpty(UrlBox.Text), CodeBox.Text, pin);
            Done($"This app is now \"{res.Name}\", the same device as this PC's browser." +
                 (res.Replaced is { } old ? $" The device it was before, \"{old.Name}\", is still listed on the hub: remove it under Devices in droplet." : ""));
        }
        catch (FieldException ex)
        {
            FieldError(ex, CodeError);
        }
        catch (InvalidOperationException ex)
        {
            Show(CodeError, ex.Message);
        }
        finally
        {
            Busy(false);
        }
    }

    async void Tailnet_Click(object sender, RoutedEventArgs e)
    {
        ClearErrors();
        if (NullIfEmpty(UrlBox.Text) is not { } url)
        {
            Show(UrlError, "Type the hub's Tailscale address, like https://your-hub.tailnet.ts.net.");
            return;
        }
        Busy(true);
        try
        {
            var res = await Setup.SetupAsync(url, NameBox.Text, NullIfEmpty(PinBox.Password));
            if (res.Pending)
            {
                Wait(res.Code, res.Hub);
            }
            else
            {
                Done($"This PC is \"{res.Name}\" on {res.Hub}.");
            }
        }
        catch (FieldException ex)
        {
            FieldError(ex, UrlError);
        }
        catch (InvalidOperationException ex)
        {
            Show(UrlError, ex.Message);
        }
        finally
        {
            Busy(false);
        }
    }

    void Direct_Click(object sender, RoutedEventArgs e)
    {
        app.ShowPairing();
        Close();
    }

    static string? NullIfEmpty(string? s) => string.IsNullOrWhiteSpace(s) ? null : s.Trim();

    // --- waiting to be let in ------------------------------------------------------------------------

    void Wait(string? code, string hub)
    {
        ChoosePanel.Visibility = Visibility.Collapsed;
        DonePanel.Visibility = Visibility.Collapsed;
        WaitPanel.Visibility = Visibility.Visible;
        WaitTitle.Text = $"Waiting for {hub} to let this PC in";
        WaitCode.Text = code ?? "…";
        WaitText.Text = $"On one of your devices, droplet asks whether to let \"{Host.Config.DeviceName}\" in. " +
                        "Check it shows this same code, then allow it. That's how you know this PC found your hub and not an impostor.";
        Host.Refresh();
        waiting?.Cancel();
        waiting?.Dispose();
        waiting = new CancellationTokenSource();
        var ct = waiting.Token;
        _ = Task.Run(async () =>
        {
            try
            {
                var state = await Setup.WaitForJoinAsync((s, c) => Dispatcher.BeginInvoke(() =>
                {
                    if (c is not null)
                    {
                        WaitCode.Text = c;
                    }
                }), ct);
                await Dispatcher.InvokeAsync(() => Answered(state));
            }
            catch (OperationCanceledException)
            {
            }
            catch (InvalidOperationException ex)
            {
                await Dispatcher.InvokeAsync(() => Show(WaitError, ex.Message));
            }
        }, CancellationToken.None);
    }

    void Answered(JoinState state)
    {
        Host.Engine?.Poller.Poke();
        Host.Refresh();
        if (state == JoinState.Approved)
        {
            Done($"This PC is \"{Host.Config.DeviceName}\" on {Core.Polling.HubPoller.HubName(Host.Config)}.");
        }
        else
        {
            WaitPanel.Visibility = Visibility.Collapsed;
            ChoosePanel.Visibility = Visibility.Visible;
            Show(HubError, "The hub said no, or the request expired. You can ask again.");
        }
    }

    async void WaitPin_Click(object sender, RoutedEventArgs e)
    {
        Show(WaitError, null);
        WaitPinButton.IsEnabled = false;
        try
        {
            await Setup.SignInWithPinAsync(WaitPin.Password);
            Host.Engine?.Poller.Poke();
        }
        catch (Exception ex) when (ex is FieldException or InvalidOperationException or HttpRequestException)
        {
            Show(WaitError, ex.Message);
        }
        finally
        {
            WaitPinButton.IsEnabled = true;
        }
    }

    void CancelJoin_Click(object sender, RoutedEventArgs e)
    {
        waiting?.Cancel();
        try
        {
            Setup.CancelJoin();
        }
        catch (InvalidOperationException)
        {
        }
        Host.Refresh();
        WaitPanel.Visibility = Visibility.Collapsed;
        ChoosePanel.Visibility = Visibility.Visible;
    }

    void Done(string text)
    {
        waiting?.Cancel();
        Host.Engine?.Poller.Poke();
        Host.Refresh();
        ChoosePanel.Visibility = Visibility.Collapsed;
        WaitPanel.Visibility = Visibility.Collapsed;
        DonePanel.Visibility = Visibility.Visible;
        DoneText.Text = text + " Files, messages and rings from your other devices now reach it, and it can reach them.";
    }

    void Devices_Click(object sender, RoutedEventArgs e)
    {
        app.ShowDevices();
        Close();
    }

    void Close_Click(object sender, RoutedEventArgs e) => Close();
}
