using System.Collections.Concurrent;
using Droplet.Core.Platform;

namespace Droplet.Core.Tests.Support;

/// <summary>Records every input message instead of acting on it.</summary>
public sealed class FakeInput : IInput
{
    public ConcurrentQueue<IReadOnlyList<InputEvent>> Applied { get; } = new();
    public int Released;

    public void Apply(IReadOnlyList<InputEvent> events) => Applied.Enqueue(events);

    public void ReleaseAll() => Interlocked.Increment(ref Released);
}

/// <summary>A player that remembers what it was asked.</summary>
public sealed class FakeMedia : IMedia
{
    public ConcurrentQueue<MediaCommand> Commands { get; } = new();

    public MediaState Current { get; set; } = new()
    {
        Players = [new MediaPlayer { Id = "fake", Name = "Fake player", Status = "Paused", Title = "Test track", CanNext = true }],
        Active = "fake",
        Volume = new Volume(0.5, false),
    };

    public event Action? Changed;

    public Task PerformAsync(MediaCommand command, CancellationToken ct = default)
    {
        Commands.Enqueue(command);
        if (command.Action == "seek")
        {
            throw new NotSupportedException("seek isn't supported");
        }
        Changed?.Invoke();
        return Task.CompletedTask;
    }
}

/// <summary>A clipboard in memory.</summary>
public sealed class FakeClipboard : IClipboard
{
    readonly Lock gate = new();
    string text = "";
    uint seq;

    public ConcurrentQueue<string> Written { get; } = new();

    public uint Sequence
    {
        get
        {
            lock (gate)
            {
                return seq;
            }
        }
    }

    public bool TryReadText(out string value)
    {
        lock (gate)
        {
            value = text;
            return text.Length > 0;
        }
    }

    public void WriteText(string value)
    {
        Written.Enqueue(value);
        Copy(value);
    }

    /// <summary>Simulates copying text on this machine.</summary>
    public void Copy(string value)
    {
        lock (gate)
        {
            text = value;
            seq++;
        }
    }
}

public sealed class FakeNotifications : INotifications
{
    public ConcurrentQueue<Notification> Shown { get; } = new();
    public ConcurrentQueue<string> Cleared { get; } = new();

    public void Show(Notification notification) => Shown.Enqueue(notification);

    public void Clear(string tag) => Cleared.Enqueue(tag);
}

public sealed class FakeScreenshot : IScreenshot
{
    // a 1x1 PNG
    public static readonly byte[] Png = Convert.FromBase64String(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    public int Taken;

    public Task<byte[]> CapturePngAsync(CancellationToken ct = default)
    {
        Interlocked.Increment(ref Taken);
        return Task.FromResult(Png);
    }
}

public sealed class FakeLock : ILock
{
    public int Locked;

    public void Lock() => Interlocked.Increment(ref Locked);
}

public sealed class FakeSound : ISound
{
    public int Rings;
    public int Stops;

    public void StartRing() => Interlocked.Increment(ref Rings);

    public void StopRing() => Interlocked.Increment(ref Stops);
}

/// <summary>Every platform service, faked.</summary>
public sealed class FakePlatform
{
    public FakeInput Input { get; } = new();
    public FakeMedia Media { get; } = new();
    public FakeClipboard Clipboard { get; } = new();
    public FakeNotifications Notifications { get; } = new();
    public FakeScreenshot Screenshot { get; } = new();
    public FakeLock Lock { get; } = new();
    public FakeSound Sound { get; } = new();

    public PlatformServices Services => new()
    {
        Input = Input, Media = Media, Clipboard = Clipboard, Notifications = Notifications, Screenshot = Screenshot, Lock = Lock, Sound = Sound,
    };
}

public static class Wait
{
    /// <summary>Polls until <paramref name="condition"/> holds, or fails the test after <paramref name="seconds"/>.</summary>
    public static async Task For(Func<bool> condition, double seconds = 20, string? what = null)
    {
        var end = DateTime.UtcNow.AddSeconds(seconds);
        while (DateTime.UtcNow < end)
        {
            try
            {
                if (condition())
                {
                    return;
                }
            }
            catch (Exception)
            {
                // not yet
            }
            await Task.Delay(100);
        }
        Assert.Fail($"timed out after {seconds} s waiting for {what ?? "a condition"}");
    }

    /// <summary>Polls an async condition.</summary>
    public static async Task For(Func<Task<bool>> condition, double seconds = 20, string? what = null)
    {
        var end = DateTime.UtcNow.AddSeconds(seconds);
        while (DateTime.UtcNow < end)
        {
            try
            {
                if (await condition())
                {
                    return;
                }
            }
            catch (Exception)
            {
            }
            await Task.Delay(200);
        }
        Assert.Fail($"timed out after {seconds} s waiting for {what ?? "a condition"}");
    }
}
