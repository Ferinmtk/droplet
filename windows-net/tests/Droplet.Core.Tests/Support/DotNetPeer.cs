using System.Collections.Concurrent;
using Droplet.Core.Mesh;
using Droplet.Core.Remote;
using Microsoft.Extensions.Logging;

namespace Droplet.Core.Tests.Support;

/// <summary>Collects log lines, for asserting on what happened.</summary>
public sealed class TestLog : ILoggerFactory, ILoggerProvider
{
    public ConcurrentQueue<string> Lines { get; } = new();

    public string Text => string.Join('\n', Lines);

    public ILogger CreateLogger(string categoryName) => new Logger(this, categoryName);

    public void AddProvider(ILoggerProvider provider) { }

    public void Dispose() { }

    sealed class Logger(TestLog log, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Debug;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            var line = $"{DateTime.Now:HH:mm:ss.fff} {logLevel,-11} {category}: {formatter(state, exception)}{(exception is null ? "" : " " + exception.Message)}";
            log.Lines.Enqueue(line);
            if (Environment.GetEnvironmentVariable("DROPLET_TEST_LOG") == "1")
            {
                Console.Error.WriteLine(line);
            }
        }
    }
}

/// <summary>A .NET mesh peer with fake platform services, as the Windows app will run it, minus the hub.</summary>
public sealed class DotNetPeer : IAsyncDisposable
{
    DotNetPeer(string root, MeshNode node, RemoteDispatcher dispatcher, FakePlatform fakes, TestLog log)
    {
        Root = root;
        Node = node;
        Dispatcher = dispatcher;
        Fakes = fakes;
        Log = log;
    }

    public string Root { get; }
    public MeshNode Node { get; }
    public RemoteDispatcher Dispatcher { get; }
    public FakePlatform Fakes { get; }
    public TestLog Log { get; }
    public string Downloads => Path.Combine(Root, "Downloads");

    /// <summary>Every capability switched on.</summary>
    public static readonly IReadOnlySet<string> AllCaps = new HashSet<string>(Caps.All);

    public static async Task<DotNetPeer> StartAsync(string root, string name, FakePlatform? fakes = null, int? port = null, long maxRate = 0,
        IMeshHost? host = null, TestLog? log = null)
    {
        fakes ??= new FakePlatform();
        log ??= new TestLog();
        var dispatcher = new RemoteDispatcher(fakes.Services, () => AllCaps, () => name, log.CreateLogger("droplet.remote"));
        var node = new MeshNode(host ?? new NoHubHost(name, dispatcher.Offered()), new MeshOptions
        {
            ConfigDir = Path.Combine(root, "mesh"),
            DataDir = Path.Combine(root, "mesh", "data"),
            Downloads = Path.Combine(root, "Downloads"),
            Port = port,
            MaxRate = maxRate,
            RetryEvery = TimeSpan.FromSeconds(2),
            LoggerFactory = log,
        }, dispatcher, fakes.Services);
        await node.StartAsync();
        return new DotNetPeer(root, node, dispatcher, fakes, log);
    }

    public async ValueTask DisposeAsync()
    {
        await Node.DisposeAsync();
        await Dispatcher.DisposeAsync();
    }
}
