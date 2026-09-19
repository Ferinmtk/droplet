using System.Diagnostics;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Droplet.Core.Common;
using Droplet.Core.Mesh;

namespace Droplet.Core.Tests.Support;

/// <summary>
/// Where the reference implementation lives, and a Python that can run it: the Linux agent
/// (<c>agent/</c>) installed into a throwaway virtual environment, created once per run
/// (or <c>DROPLET_AGENT_PYTHON</c>, a Python that already has it). Interop tests skip
/// themselves when there's no Python, or on Windows.
/// </summary>
public static class Reference
{
    static readonly Lazy<string?> python = new(FindPython);
    static readonly Lock gate = new();

    /// <summary>The repository root.</summary>
    public static string RepoRoot { get; } = FindRepoRoot();

    static string FindRepoRoot()
    {
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d is not null; d = d.Parent)
        {
            if (File.Exists(Path.Combine(d.FullName, "agent", "pyproject.toml")) && File.Exists(Path.Combine(d.FullName, "app.py")))
            {
                return d.FullName;
            }
        }
        throw new InvalidOperationException("can't find the droplet repository above " + AppContext.BaseDirectory);
    }

    /// <summary>Why interop tests can't run here, or null when they can.</summary>
    public static string? Unavailable
    {
        get
        {
            if (OperatingSystem.IsWindows())
            {
                return "the Linux agent runs on Linux";
            }
            return Python is null ? "no Python 3 with venv to install the Linux agent into" : null;
        }
    }

    /// <summary>A Python with the agent installed.</summary>
    public static string? Python
    {
        get
        {
            lock (gate)
            {
                return python.Value;
            }
        }
    }

    static string? FindPython()
    {
        var given = Environment.GetEnvironmentVariable("DROPLET_AGENT_PYTHON");
        if (!string.IsNullOrEmpty(given) && File.Exists(given))
        {
            return given;
        }
        var venv = Path.Combine(Path.GetTempPath(), "droplet-net-tests", "agent-venv");
        var py = Path.Combine(venv, "bin", "python");
        if (!File.Exists(py) && Run("python3", ["-m", "venv", venv], 120) != 0)
        {
            return null;
        }
        if (Run(py, ["-c", "import droplet_agent"], 30) != 0 &&
            Run(py, ["-m", "pip", "install", "-q", Path.Combine(RepoRoot, "agent")], 600) != 0)
        {
            return null;
        }
        return py;
    }

    static int Run(string exe, string[] args, int seconds)
    {
        try
        {
            var psi = new ProcessStartInfo(exe) { RedirectStandardOutput = true, RedirectStandardError = true };
            foreach (var a in args)
            {
                psi.ArgumentList.Add(a);
            }
            using var p = Process.Start(psi)!;
            p.StandardOutput.ReadToEndAsync();
            p.StandardError.ReadToEndAsync();
            if (!p.WaitForExit(seconds * 1000))
            {
                p.Kill(true);
                return -1;
            }
            return p.ExitCode;
        }
        catch (System.ComponentModel.Win32Exception)
        {
            return -1;
        }
    }

    /// <summary>
    /// A short scratch directory: the agent's control socket lives under it, and a Unix
    /// socket path must stay under 108 bytes.
    /// </summary>
    public static string ShortDir(string name)
    {
        var d = Path.Combine("/tmp", $"dn-{name}-{Hex.Random(3)}");
        Directory.CreateDirectory(d);
        return d;
    }
}

/// <summary>
/// One <c>droplet-agent run --dry-run</c> process with its own HOME and XDG directories
/// (nothing on this computer is moved, typed, locked, copied or played), driven through
/// its control socket, as <c>agent/tests/e2e_mesh.py</c> does.
/// </summary>
public sealed class LinuxAgent : IAsyncDisposable
{
    Process? process;
    readonly StringBuilder logText = new();
    readonly Lock logGate = new();

    public LinuxAgent(string name, JsonObject? mesh = null)
    {
        Name = name;
        Root = Reference.ShortDir(name);
        Home = Path.Combine(Root, "h");
        Runtime = Path.Combine(Root, "r");
        Directory.CreateDirectory(Runtime);
        if (!OperatingSystem.IsWindows())
        {
            File.SetUnixFileMode(Runtime, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }
        var cfg = Path.Combine(Home, ".config", "droplet-agent");
        Directory.CreateDirectory(cfg);
        ConfigFile = Path.Combine(cfg, "config.json");
        var m = mesh ?? [];
        m["downloads"] ??= Downloads;
        File.WriteAllText(ConfigFile, Json.ToText(new JsonObject { ["device"] = new JsonObject { ["id"] = "", ["name"] = name }, ["mesh"] = m }));
    }

    public string Name { get; }
    public string Root { get; }
    public string Home { get; }
    public string Runtime { get; }
    public string ConfigFile { get; }
    public string Downloads => Path.Combine(Home, "Downloads", "droplet");
    public string MeshConfig => Path.Combine(Home, ".config", "droplet-agent", "mesh");
    public string ChatFile => Path.Combine(Home, ".local", "share", "droplet-agent", "mesh", "chat.jsonl");
    public bool Running => process is { HasExited: false };

    ProcessStartInfo Info(params string[] args)
    {
        var psi = new ProcessStartInfo(Reference.Python!)
        {
            WorkingDirectory = Path.Combine(Reference.RepoRoot, "agent"),
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = true,
        };
        psi.ArgumentList.Add("-m");
        psi.ArgumentList.Add("droplet_agent");
        foreach (var a in args)
        {
            psi.ArgumentList.Add(a);
        }
        psi.Environment["HOME"] = Home;
        psi.Environment["XDG_CONFIG_HOME"] = Path.Combine(Home, ".config");
        psi.Environment["XDG_DATA_HOME"] = Path.Combine(Home, ".local", "share");
        psi.Environment["XDG_RUNTIME_DIR"] = Runtime;
        psi.Environment["PYTHONUNBUFFERED"] = "1";
        foreach (var k in new[] { "DBUS_SESSION_BUS_ADDRESS", "WAYLAND_DISPLAY", "DISPLAY" })
        {
            psi.Environment.Remove(k); // nothing reaches the real desktop, even by mistake
        }
        return psi;
    }

    /// <summary>Starts <c>run --dry-run</c> and waits for its control socket to answer.</summary>
    public async Task StartAsync()
    {
        var p = Process.Start(Info("run", "--dry-run", "-v"))!;
        p.OutputDataReceived += (_, e) => Append(e.Data);
        p.ErrorDataReceived += (_, e) => Append(e.Data);
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        process = p;
        await Wait.For(async () => (await StatusAsync()).Str("fp") is not null, 30, $"{Name} to start\n{Log}");
    }

    void Append(string? line)
    {
        if (line is null)
        {
            return;
        }
        lock (logGate)
        {
            logText.AppendLine(line);
        }
    }

    public string Log
    {
        get
        {
            lock (logGate)
            {
                return logText.ToString();
            }
        }
    }

    /// <summary>Stops it: SIGTERM, or SIGKILL for a crash mid-transfer.</summary>
    public async Task StopAsync(bool kill = false)
    {
        if (process is not { HasExited: false } p)
        {
            return;
        }
        if (kill)
        {
            p.Kill();
        }
        else
        {
            Signal(p.Id, 15);
        }
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        try
        {
            await p.WaitForExitAsync(cts.Token);
        }
        catch (OperationCanceledException)
        {
            p.Kill(true);
        }
        process = null;
    }

    static void Signal(int pid, int sig)
    {
        using var k = Process.Start(new ProcessStartInfo("kill") { ArgumentList = { $"-{sig}", pid.ToString(System.Globalization.CultureInfo.InvariantCulture) } })!;
        k.WaitForExit();
    }

    /// <summary>One request to the running agent's control socket (what its CLI does).</summary>
    public async Task<JsonObject> CallAsync(JsonObject request, double timeoutSeconds = 30)
    {
        using var s = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds));
        await s.ConnectAsync(new UnixDomainSocketEndPoint(Path.Combine(Runtime, "droplet-agent", "control.sock")), cts.Token);
        await s.SendAsync(Encoding.UTF8.GetBytes(Json.ToText(request) + "\n"), cts.Token);
        var buf = new List<byte>();
        var chunk = new byte[65536];
        while (!buf.Contains((byte)'\n'))
        {
            var n = await s.ReceiveAsync(chunk, cts.Token);
            if (n == 0)
            {
                break;
            }
            buf.AddRange(chunk.AsSpan(0, n).ToArray());
        }
        return Json.ParseObject(buf.ToArray()) ?? throw new InvalidOperationException("the agent's answer wasn't JSON");
    }

    public Task<JsonObject> StatusAsync() => CallAsync(new JsonObject { ["cmd"] = "status" }, 10);

    /// <summary>Runs its command line with the same environment (for setup).</summary>
    public async Task<(int Code, string Output)> CliAsync(params string[] args)
    {
        using var p = Process.Start(Info(args))!;
        var output = p.StandardOutput.ReadToEndAsync();
        var error = p.StandardError.ReadToEndAsync();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(120));
        await p.WaitForExitAsync(cts.Token);
        return (p.ExitCode, await output + await error);
    }

    /// <summary>Its mesh fingerprint, from its certificate.</summary>
    public string Fingerprint
    {
        get
        {
            var der = Certificates.PemToDer(File.ReadAllText(Path.Combine(MeshConfig, "cert.pem")));
            return Common.Fingerprint.Of(der);
        }
    }

    /// <summary>Its trust list's peers, by fingerprint.</summary>
    public JsonObject Trust()
    {
        var path = Path.Combine(MeshConfig, "trust.json");
        return File.Exists(path) ? Json.ParseObject(File.ReadAllText(path))?["peers"] as JsonObject ?? [] : [];
    }

    /// <summary>Its chat history.</summary>
    public List<JsonObject> Chat() =>
        File.Exists(ChatFile) ? File.ReadAllLines(ChatFile).Select(Json.ParseObject).OfType<JsonObject>().ToList() : [];

    public async ValueTask DisposeAsync()
    {
        await StopAsync();
        try
        {
            Directory.Delete(Root, true);
        }
        catch (IOException)
        {
        }
    }
}

/// <summary>A throwaway hub (<c>app.py</c>) with its own DROPLET_HOME.</summary>
public sealed class LocalHub : IAsyncDisposable
{
    Process? process;
    readonly StringBuilder logText = new();
    readonly Lock logGate = new();

    public LocalHub(int port = 8891, int tlsPort = 8892)
    {
        Port = port;
        TlsPort = tlsPort;
        Home = Reference.ShortDir("hub");
    }

    public int Port { get; }
    public int TlsPort { get; }
    public string Home { get; }
    public string Url => $"http://127.0.0.1:{Port}";

    /// <summary>The hub's Python: the repository's own virtual environment, or DROPLET_HUB_PYTHON.</summary>
    public static string? Python
    {
        get
        {
            var given = Environment.GetEnvironmentVariable("DROPLET_HUB_PYTHON");
            if (!string.IsNullOrEmpty(given) && File.Exists(given))
            {
                return given;
            }
            // the worktree's main checkout keeps the hub's venv
            foreach (var root in new[] { Reference.RepoRoot, Path.Combine(Reference.RepoRoot, "..", "..", "droplet") })
            {
                var py = Path.Combine(root, ".venv", "bin", "python");
                if (File.Exists(py))
                {
                    return Path.GetFullPath(py);
                }
            }
            return null;
        }
    }

    public string Log
    {
        get
        {
            lock (logGate)
            {
                return logText.ToString();
            }
        }
    }

    public async Task StartAsync()
    {
        var psi = new ProcessStartInfo(Python ?? throw new InvalidOperationException("no Python for the hub (set DROPLET_HUB_PYTHON)"))
        {
            WorkingDirectory = Reference.RepoRoot,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        psi.ArgumentList.Add("app.py");
        psi.Environment["DROPLET_HOME"] = Home;
        psi.Environment["DROPLET_PORT"] = Port.ToString(System.Globalization.CultureInfo.InvariantCulture);
        psi.Environment["DROPLET_LAN_TLS_PORT"] = TlsPort.ToString(System.Globalization.CultureInfo.InvariantCulture);
        psi.Environment["DROPLET_HOST"] = "0.0.0.0";
        psi.Environment["DROPLET_NAME"] = "droplet-net";
        psi.Environment["DROPLET_PUSH"] = "0";
        psi.Environment["PYTHONUNBUFFERED"] = "1";
        var p = Process.Start(psi)!;
        p.OutputDataReceived += (_, e) => Append(e.Data);
        p.ErrorDataReceived += (_, e) => Append(e.Data);
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        process = p;
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(2) };
        await Wait.For(async () => (await http.GetAsync($"{Url}/api/hub/info")).IsSuccessStatusCode, 30, "the hub to start\n" + Log);
    }

    void Append(string? line)
    {
        if (line is null)
        {
            return;
        }
        lock (logGate)
        {
            logText.AppendLine(line);
        }
    }

    public async Task StopAsync()
    {
        if (process is not { HasExited: false } p)
        {
            return;
        }
        using (var k = Process.Start(new ProcessStartInfo("kill") { ArgumentList = { "-15", p.Id.ToString(System.Globalization.CultureInfo.InvariantCulture) } })!)
        {
            await k.WaitForExitAsync();
        }
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        try
        {
            await p.WaitForExitAsync(cts.Token);
        }
        catch (OperationCanceledException)
        {
            p.Kill(true);
        }
        process = null;
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync();
        try
        {
            Directory.Delete(Home, true);
        }
        catch (IOException)
        {
        }
    }
}

public static class Files
{
    /// <summary>A file of random bytes.</summary>
    public static string Random(string dir, string name, int megabytes)
    {
        var path = Path.Combine(dir, name);
        using var f = File.Create(path);
        var buf = new byte[1024 * 1024];
        for (var i = 0; i < megabytes; i++)
        {
            RandomNumberGenerator.Fill(buf);
            f.Write(buf);
        }
        return path;
    }

    public static string Sha256(string path)
    {
        using var f = File.OpenRead(path);
        return Convert.ToHexStringLower(SHA256.HashData(f));
    }
}
