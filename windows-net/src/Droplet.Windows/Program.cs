using System.IO;
using Droplet.Windows.Interop;
using Droplet.Windows.Shell;

namespace Droplet.Windows;

/// <summary>droplet.exe's entry point: one droplet per sign-in; later launches hand their command line to it.</summary>
internal static class Program
{
    [STAThread]
    static int Main(string[] args)
    {
        Native.CheckLayouts();
        var cmd = Command.Parse(args);
        switch (cmd.Kind)
        {
            case CommandKind.Version:
                Print(typeof(Program).Assembly.GetName().Version?.ToString(3) is { } v ? $"droplet {v}" : "droplet");
                return 0;
            case CommandKind.Help:
                Print(Command.Usage);
                return 0;
            case CommandKind.Invalid:
                Print(cmd.Error + Environment.NewLine + Environment.NewLine + Command.Usage, error: true);
                return 2;
        }
        using var instance = SingleInstance.TryAcquire();
        if (instance is null)
        {
            if (SingleInstance.Forward(args))
            {
                return 0;
            }
            if (cmd.Kind is CommandKind.Open or CommandKind.Settings or CommandKind.Devices)
            {
                Native.MessageBox(0, "droplet is already running, but isn't answering. Look for the drop in the notification area, or restart droplet.",
                    "droplet", 0x40);
            }
            return 1;
        }
        if (SingleInstance.GoAppRunning())
        {
            Native.MessageBox(0, "The older droplet app is running. Quit it from its icon in the notification area (Quit droplet), then start this one again.\n\n" +
                                 "This version imports its settings, so nothing needs setting up again.", "droplet", 0x40);
            return 1;
        }
        if (cmd.Kind == CommandKind.StopRing)
        {
            return 0; // droplet wasn't running: nothing is ringing
        }
        var app = new App(cmd, instance);
        app.InitializeComponent();
        return app.Run();
    }

    /// <summary>Prints to the console droplet was started from, if any (droplet.exe is a GUI program, so it has none of its own).</summary>
    static void Print(string text, bool error = false)
    {
        if (Native.AttachConsole(Native.ATTACH_PARENT_PROCESS))
        {
            try
            {
                using var w = new StreamWriter(System.Console.OpenStandardOutput()) { AutoFlush = true };
                w.WriteLine();
                w.WriteLine(text);
            }
            catch (IOException)
            {
            }
        }
        else
        {
            Native.MessageBox(0, text, "droplet", error ? 0x10u : 0x40u);
        }
    }
}
