using System.IO;
using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Droplet.Core.Config;
using Droplet.Core.Hub;
using Droplet.Windows.Interop;
using Droplet.Windows.Services;
using Droplet.Windows.Shell;
using Droplet.Windows.Themes;
using Droplet.Windows.Tray;
using Droplet.Windows.Views;
using Microsoft.Extensions.Logging;

namespace Droplet.Windows;

/// <summary>
/// droplet in the tray. It starts the engine, keeps the tray icon up to date, opens the
/// windows, and carries out what other droplet.exe processes ask (a second launch, Send To,
/// a notification's button).
/// </summary>
public partial class App : Application
{
    readonly Command initial;
    readonly SingleInstance instance;
    readonly Dictionary<string, ChatWindow> chats = [];
    FileLog? fileLog;
    ILoggerFactory? logs;
    ILogger log = Microsoft.Extensions.Logging.Abstractions.NullLogger.Instance;
    NotifyIcon? tray;
    TrayController? trayController;
    AppHost? host;
    DevicesWindow? devices;
    SettingsWindow? settings;
    SetupWindow? setup;
    bool quitting;

    internal App(Command initial, SingleInstance instance)
    {
        this.initial = initial;
        this.instance = instance;
    }

    /// <summary>The running app, for the windows.</summary>
    internal AppHost Host => host ?? throw new InvalidOperationException("droplet is starting");

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        ThemeMode = ThemeMode.System;
        Resources.MergedDictionaries.Add(new ResourceDictionary { Source = new Uri("pack://application:,,,/droplet;component/Themes/Droplet.xaml", UriKind.Absolute) });
        Palette.Follow(this);

        var paths = AppPaths.Default();
        fileLog = new FileLog(paths.LogFile);
        logs = LoggerFactory.Create(b => b.AddProvider(fileLog).SetMinimumLevel(LogLevel.Information));
        log = logs.CreateLogger("droplet");
        var version = typeof(App).Assembly.GetName().Version?.ToString(3) ?? "2";
        log.LogInformation("droplet {Version} starting ({How})", version, Packaging.IsPackaged ? "packaged" : "unpackaged");
        HubClient.UserAgent = $"droplet-windows/{version} (.NET)";
        DispatcherUnhandledException += (_, args) =>
        {
            log.LogError(args.Exception, "unhandled");
            args.Handled = true; // the tray stays up; the log has the details
        };
        AppDomain.CurrentDomain.UnhandledException += (_, args) => log.LogError(args.ExceptionObject as Exception, "unhandled (fatal)");
        TaskScheduler.UnobservedTaskException += (_, args) =>
        {
            log.LogWarning(args.Exception, "unobserved task");
            args.SetObserved();
        };

        if (!Packaging.IsPackaged)
        {
            _ = Native.SetCurrentProcessExplicitAppUserModelID(Registration.AppId);
            Registration.Register(AppHost.ExePath, WriteIcon(paths), log);
        }
        tray = new NotifyIcon();
        host = new AppHost(Dispatcher, logs, paths, () => tray.Handle);
        trayController = new TrayController(this, host, tray);
        trayController.Update();
        instance.Listen(req => Dispatcher.BeginInvoke(() => _ = HandleAsync(Command.Parse(req.Args), req.Cwd)));
        try
        {
            await host.StartAsync();
        }
        catch (Exception ex)
        {
            log.LogError(ex, "start");
            Native.MessageBox(0, "droplet couldn't start:\n\n" + ex.Message + $"\n\nThe log is at {paths.LogFile}.", "droplet", 0x10);
            Quit();
            return;
        }
        host.NeedsSettings += ShowSettings;
        host.PairingRequested += _ => devices?.Refresh();
        await HandleAsync(PackagedActivation(initial), Environment.CurrentDirectory);
    }

    /// <summary>
    /// A packaged droplet started at sign-in (its StartupTask) or by a droplet: link gets no
    /// command line from Windows, only activation details.
    /// </summary>
    static Command PackagedActivation(Command cmd)
    {
        if (!Packaging.IsPackaged || cmd.Kind != CommandKind.Open)
        {
            return cmd;
        }
        try
        {
            return global::Windows.ApplicationModel.AppInstance.GetActivatedEventArgs() switch
            {
                global::Windows.ApplicationModel.Activation.IStartupTaskActivatedEventArgs => new Command(CommandKind.Background),
                global::Windows.ApplicationModel.Activation.IProtocolActivatedEventArgs p => Command.Parse([p.Uri.OriginalString]),
                _ => cmd,
            };
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or InvalidOperationException or UnauthorizedAccessException)
        {
            return cmd;
        }
    }

    /// <summary>Notifications show droplet's icon from a file (unpackaged).</summary>
    static string? WriteIcon(AppPaths paths)
    {
        try
        {
            var p = Path.Combine(paths.Root, "droplet.png");
            if (!File.Exists(p))
            {
                Directory.CreateDirectory(paths.Root);
                using var src = GetResourceStream(new Uri("pack://application:,,,/Assets/droplet-256.png", UriKind.Absolute))!.Stream;
                using var dst = File.Create(p);
                src.CopyTo(dst);
            }
            return p;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    bool NeedsSetup()
    {
        var cfg = Host.Config;
        return !cfg.Registered && !cfg.Pending && (Host.Engine?.Mesh?.Trust.All().Count ?? 0) == 0;
    }

    /// <summary>Carries out a command line: this process's own, or another droplet.exe's.</summary>
    async Task HandleAsync(Command cmd, string cwd)
    {
        if (host is null || quitting)
        {
            return;
        }
        try
        {
            switch (cmd.Kind)
            {
                case CommandKind.Open when NeedsSetup():
                    ShowSetup();
                    break;
                case CommandKind.Open or CommandKind.Devices:
                    ShowDevices();
                    break;
                case CommandKind.Settings:
                    ShowSettings();
                    break;
                case CommandKind.Action:
                    await host.HandleActionAsync(cmd.Target!);
                    break;
                case CommandKind.StopRing:
                    await host.StopRingAsync();
                    break;
                case CommandKind.Send:
                    await SendFromExplorerAsync(cmd.Target!, cmd.Files!.Select(f => Path.GetFullPath(f, string.IsNullOrEmpty(cwd) ? Environment.CurrentDirectory : cwd)).ToList());
                    break;
                case CommandKind.Uninstall:
                    {
                        var errors = await host.UninstallAsync();
                        var text = errors.Count == 0
                            ? "Removed Start with Windows, the Send To entries, the Start menu entry and the droplet: link handler. " +
                              $"Your settings are kept in {host.Paths.Root}: delete that folder to forget this PC."
                            : "Some of it couldn't be removed:\n\n" + string.Join("\n", errors);
                        Native.MessageBox(0, text, "droplet", 0x40);
                        break;
                    }
            }
        }
        catch (Exception e) when (e is not OutOfMemoryException)
        {
            log.LogWarning(e, "command {Kind}", cmd.Kind);
            ShowError(e.Message);
        }
    }

    /// <summary>Explorer's Send To: the device may not be known yet if droplet has only just started.</summary>
    async Task SendFromExplorerAsync(string target, List<string> files)
    {
        for (var i = 0; ; i++)
        {
            try
            {
                var d = Services.Destinations.Resolve(target, Host.Destinations);
                await Host.SendFilesAsync(d, files);
                return;
            }
            catch (ArgumentException e) when (i < 30)
            {
                if (i == 29)
                {
                    Host.Toasts.Show(new Core.Platform.Notification { Title = "Nothing sent", Body = e.Message, Tag = "send" });
                    return;
                }
                Host.Refresh();
                await Task.Delay(500);
            }
        }
    }

    // --- windows ------------------------------------------------------------------------------------

    static void Front(Window w)
    {
        if (w.WindowState == WindowState.Minimized)
        {
            w.WindowState = WindowState.Normal;
        }
        w.Show();
        w.Activate();
        w.Topmost = true; // brought in front of whatever had focus, then back to normal
        w.Topmost = false;
        w.Focus();
    }

    internal void ShowDevices()
    {
        if (host?.Engine is null)
        {
            return;
        }
        if (devices is null)
        {
            devices = new DevicesWindow(this);
            devices.Closed += (_, _) => devices = null;
        }
        Front(devices);
    }

    /// <summary>The devices window, open at pairing a new device.</summary>
    internal void ShowPairing()
    {
        ShowDevices();
        devices?.OpenPairing();
    }

    internal void ShowSettings()
    {
        if (host?.Engine is null)
        {
            return;
        }
        if (settings is null)
        {
            settings = new SettingsWindow(this);
            settings.Closed += (_, _) => settings = null;
        }
        Front(settings);
    }

    /// <summary>Joining a hub, linking, the tailnet, or pairing directly. <paramref name="repairHub"/> re-joins a hub whose certificate changed.</summary>
    internal void ShowSetup(string? repairHub = null)
    {
        if (host?.Engine is null)
        {
            return;
        }
        if (setup is not null && repairHub is null)
        {
            Front(setup);
            return;
        }
        setup?.Close();
        var w = new SetupWindow(this, repairHub);
        w.Closed += (_, _) =>
        {
            if (setup == w)
            {
                setup = null;
            }
        };
        setup = w;
        Front(w);
    }

    internal void ShowChat(Destination d)
    {
        if (!chats.TryGetValue(d.Key, out var w))
        {
            w = new ChatWindow(this, d);
            chats[d.Key] = w;
            w.Closed += (_, _) => chats.Remove(d.Key);
        }
        Front(w);
    }

    internal void ShowError(string message) =>
        Native.MessageBox(0, message, "droplet", 0x30);

    internal void OpenWeb()
    {
        if (host?.Engine is { } e)
        {
            var url = e.Poller.WebUrl("/");
            if (Uri.TryCreate(url, UriKind.Absolute, out var u) && (u.Scheme == Uri.UriSchemeHttps || u.Scheme == Uri.UriSchemeHttp))
            {
                Files.Open(u.AbsoluteUri, log);
            }
            else
            {
                ShowError("droplet doesn't know the hub's web address yet. Set it up under Settings.");
            }
        }
    }

    internal void OpenDownloads()
    {
        var dir = Host.Config.DownloadDir;
        try
        {
            Directory.CreateDirectory(dir);
            Files.Open(dir, log);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException)
        {
            ShowError($"Can't open {dir}: {e.Message}");
        }
    }

    internal void PickAndSend(Destination d)
    {
        var dialog = new Microsoft.Win32.OpenFileDialog { Multiselect = true, Title = $"Send to {d.Name} with droplet", CheckFileExists = true };
        if (dialog.ShowDialog() == true && dialog.FileNames.Length > 0)
        {
            _ = Host.SendFilesAsync(d, dialog.FileNames);
        }
    }

    internal void SendClipboard(Destination d) => _ = Host.SendClipboardAsync(d, ReadClipboard());

    /// <summary>What's on the clipboard: copied files, an image (as PNG), or text.</summary>
    static AppHost.Clip ReadClipboard()
    {
        try
        {
            if (Clipboard.ContainsFileDropList())
            {
                return new AppHost.Clip(null, Clipboard.GetFileDropList().Cast<string>().ToList(), null);
            }
            if (Clipboard.ContainsImage() && Clipboard.GetImage() is { } img)
            {
                var enc = new PngBitmapEncoder();
                enc.Frames.Add(BitmapFrame.Create(img));
                using var ms = new MemoryStream();
                enc.Save(ms);
                return new AppHost.Clip(null, [], ms.ToArray());
            }
            return new AppHost.Clip(Clipboard.ContainsText() ? Clipboard.GetText() : null, [], null);
        }
        catch (System.Runtime.InteropServices.COMException)
        {
            return new AppHost.Clip(null, [], null); // another app holds the clipboard
        }
    }

    /// <summary>Closes everything and exits.</summary>
    internal async void Quit()
    {
        if (quitting)
        {
            return;
        }
        quitting = true;
        foreach (Window w in Windows)
        {
            w.Close();
        }
        tray?.Dispose();
        if (host is not null)
        {
            try
            {
                await host.DisposeAsync();
            }
            catch (Exception e) when (e is not OutOfMemoryException)
            {
                log.LogWarning(e, "stopping");
            }
        }
        log.LogInformation("droplet stopped");
        logs?.Dispose();
        fileLog?.Dispose();
        Shutdown();
    }

    protected override void OnSessionEnding(SessionEndingCancelEventArgs e)
    {
        // Windows is signing out: take the icon away and stop any ring now; there's no time for more
        tray?.Dispose();
        host?.Ring.StopRing();
        base.OnSessionEnding(e);
    }
}
