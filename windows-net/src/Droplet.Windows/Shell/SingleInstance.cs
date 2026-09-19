using System.IO;
using System.IO.Pipes;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using Droplet.Windows.Interop;

namespace Droplet.Windows.Shell;

/// <summary>
/// One droplet per Windows sign-in. The first takes a session-local mutex and listens on a
/// named pipe only this user can open; a later droplet.exe (a second launch, a Send To entry,
/// a notification's button) hands its command line to it and exits.
/// </summary>
internal sealed class SingleInstance : IDisposable
{
    const string MutexName = @"Local\droplet-windows";

    /// <summary>The Go app's mutex: the two shouldn't run at once.</summary>
    const string GoAppMutex = @"Local\droplet-companion";

    readonly Mutex mutex;
    readonly CancellationTokenSource stop = new();

    SingleInstance(Mutex mutex) => this.mutex = mutex;

    /// <summary>A request from another droplet.exe: its arguments and working directory.</summary>
    public sealed record Request(string[] Args, string Cwd);

    static string PipeName()
    {
        var sid = WindowsIdentity.GetCurrent().User?.Value ?? Environment.UserName;
        Native.ProcessIdToSessionId((uint)Environment.ProcessId, out var session);
        return $"droplet-{sid}-{session}";
    }

    /// <summary>Whether the Go app's tray is running in this session.</summary>
    public static bool GoAppRunning()
    {
        if (Mutex.TryOpenExisting(GoAppMutex, out var m))
        {
            m.Dispose();
            return true;
        }
        return false;
    }

    /// <summary>Becomes the one droplet, or returns null when another one is running.</summary>
    public static SingleInstance? TryAcquire()
    {
        var m = new Mutex(true, MutexName, out var created);
        if (created)
        {
            return new SingleInstance(m);
        }
        m.Dispose();
        return null;
    }

    /// <summary>Hands a command line to the running droplet. False when it didn't answer.</summary>
    public static bool Forward(string[] args)
    {
        try
        {
            using var pipe = new NamedPipeClientStream(".", PipeName(), PipeDirection.InOut, PipeOptions.CurrentUserOnly);
            pipe.Connect(5000);
            var body = JsonSerializer.SerializeToUtf8Bytes(new Request(args, Environment.CurrentDirectory));
            pipe.Write(BitConverter.GetBytes(body.Length));
            pipe.Write(body);
            pipe.Flush();
            var ok = new byte[1];
            return pipe.Read(ok, 0, 1) == 1 && ok[0] == 1;
        }
        catch (Exception e) when (e is IOException or TimeoutException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    /// <summary>Listens for other droplet.exe processes until disposed; <paramref name="handle"/> runs for each.</summary>
    public void Listen(Action<Request> handle)
    {
        _ = Task.Run(async () =>
        {
            var name = PipeName();
            while (!stop.IsCancellationRequested)
            {
                try
                {
                    await using var server = new NamedPipeServerStream(name, PipeDirection.InOut, 1, PipeTransmissionMode.Byte,
                        PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                    await server.WaitForConnectionAsync(stop.Token).ConfigureAwait(false);
                    using var timeout = CancellationTokenSource.CreateLinkedTokenSource(stop.Token);
                    timeout.CancelAfter(TimeSpan.FromSeconds(10));
                    var len = new byte[4];
                    await server.ReadExactlyAsync(len, timeout.Token).ConfigureAwait(false);
                    var n = BitConverter.ToInt32(len);
                    if (n is <= 0 or > 1 << 20)
                    {
                        continue;
                    }
                    var body = new byte[n];
                    await server.ReadExactlyAsync(body, timeout.Token).ConfigureAwait(false);
                    var req = JsonSerializer.Deserialize<Request>(body);
                    if (req?.Args is not null)
                    {
                        handle(req with { Cwd = req.Cwd ?? "" });
                    }
                    await server.WriteAsync(new byte[] { 1 }, timeout.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (stop.IsCancellationRequested)
                {
                    return;
                }
                catch (Exception e) when (e is IOException or OperationCanceledException or JsonException or UnauthorizedAccessException)
                {
                    await Task.Delay(200).ConfigureAwait(false);
                }
            }
        });
    }

    public void Dispose()
    {
        stop.Cancel();
        stop.Dispose();
        try
        {
            mutex.ReleaseMutex();
        }
        catch (ApplicationException)
        {
        }
        mutex.Dispose();
    }
}

/// <summary>Where the log goes: <c>droplet.log</c>, restarted when it passes 1 MB.</summary>
internal sealed class FileLog : Microsoft.Extensions.Logging.ILoggerProvider
{
    readonly Lock gate = new();
    readonly string path;
    StreamWriter? writer;

    public FileLog(string path)
    {
        this.path = path;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            if (File.Exists(path) && new FileInfo(path).Length > 1 << 20)
            {
                File.Move(path, path + ".old", overwrite: true);
            }
            writer = new StreamWriter(new FileStream(path, FileMode.Append, FileAccess.Write, FileShare.ReadWrite), new UTF8Encoding(false)) { AutoFlush = true };
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            writer = null;
        }
    }

    /// <summary>The log file.</summary>
    public string FilePath => path;

    public Microsoft.Extensions.Logging.ILogger CreateLogger(string categoryName) => new Logger(this, categoryName);

    void Write(string line)
    {
        lock (gate)
        {
            try
            {
                writer?.WriteLine(line);
            }
            catch (IOException)
            {
            }
        }
    }

    public void Dispose()
    {
        lock (gate)
        {
            writer?.Dispose();
            writer = null;
        }
    }

    sealed class Logger(FileLog log, string category) : Microsoft.Extensions.Logging.ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(Microsoft.Extensions.Logging.LogLevel logLevel) => logLevel >= Microsoft.Extensions.Logging.LogLevel.Information;

        public void Log<TState>(Microsoft.Extensions.Logging.LogLevel logLevel, Microsoft.Extensions.Logging.EventId eventId, TState state, Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel))
            {
                return;
            }
            var level = logLevel switch
            {
                Microsoft.Extensions.Logging.LogLevel.Warning => "WARN ",
                Microsoft.Extensions.Logging.LogLevel.Error or Microsoft.Extensions.Logging.LogLevel.Critical => "ERROR",
                _ => "INFO ",
            };
            var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {level} [{category}] {formatter(state, exception)}";
            if (exception is not null)
            {
                line += Environment.NewLine + exception;
            }
            log.Write(line);
        }
    }
}
