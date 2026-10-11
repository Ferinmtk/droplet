using System.Globalization;
using System.Text.Json.Serialization;

namespace Droplet.Core.Mesh;

/// <summary>Where a transfer stands (docs/mesh.md §9.11).</summary>
public static class TransferState
{
    /// <summary>Bytes are moving (or about to).</summary>
    public const string Active = "active";

    /// <summary>Sent, or received.</summary>
    public const string Done = "done";

    /// <summary>Stopped for good.</summary>
    public const string Failed = "failed";

    /// <summary>Cancelled, here or by the other end.</summary>
    public const string Cancelled = "cancelled";

    /// <summary>Stopped part-way: it carries on when it can.</summary>
    public const string Waiting = "waiting";
}

/// <summary>What <see cref="MeshNode.CancelAsync"/> stopped.</summary>
/// <param name="Id">The transfer's (or the job's) id.</param>
/// <param name="Dir">"in" or "out".</param>
/// <param name="Name">The file's name, when it's a file.</param>
/// <param name="Peer">The other end's name.</param>
public sealed record CancelResult(string Id, string Dir, string? Name, string? Peer);

/// <summary>How <see cref="MeshNode.SendLinkAsync"/> sent a link.</summary>
/// <param name="How">"link" (opened there, or waiting with an Open button), or "message" (as a chat message, through the outbox).</param>
/// <param name="Route">The route a link went by.</param>
/// <param name="Job">The chat message's job, when it went as one.</param>
public sealed record LinkSent(string How, string? Route, OutboxJob? Job);

/// <summary>One file on its way, either way, as the window, the tray and <c>status</c> show it.</summary>
public sealed record TransferView
{
    /// <summary>The offer's id (also the outbox job's).</summary>
    [JsonPropertyName("id")] public required string Id { get; init; }

    /// <summary>"out" (this device sends it) or "in".</summary>
    [JsonPropertyName("dir")] public required string Dir { get; init; }

    /// <summary>The other end's fingerprint.</summary>
    [JsonPropertyName("fp")] public required string Fp { get; init; }

    /// <summary>The other end's name.</summary>
    [JsonPropertyName("peer")] public string Peer { get; init; } = "";

    /// <summary>The file's name.</summary>
    [JsonPropertyName("name")] public string Name { get; init; } = "";

    /// <summary>Its size.</summary>
    [JsonPropertyName("size")] public long Size { get; init; }

    /// <summary>Bytes so far.</summary>
    [JsonPropertyName("done")] public long Done { get; init; }

    /// <summary>0–100.</summary>
    [JsonPropertyName("percent")] public int Percent { get; init; }

    /// <summary>Bytes a second lately; null when nothing moved for a while.</summary>
    [JsonPropertyName("rate")] public long? Rate { get; init; }

    /// <summary>Seconds left at that speed; null when unknown.</summary>
    [JsonPropertyName("eta")] public long? Eta { get; init; }

    /// <summary><see cref="TransferState"/>.</summary>
    [JsonPropertyName("state")] public string State { get; init; } = TransferState.Active;

    /// <summary>Why it ended, when it didn't end well.</summary>
    [JsonPropertyName("error")] public string? Error { get; init; }

    /// <summary>"lan", "tailnet", "hub"... when known.</summary>
    [JsonPropertyName("route")] public string? Route { get; init; }
}

/// <summary>
/// Files on their way, either way: how far each has got, how fast, and how long it has left
/// (the reference's mesh/transfers.py). The numbers are real byte counts: what this device has
/// served of a file it sends, and what it has saved of one it receives. A finished transfer
/// stays listed for <see cref="Keep"/>, so a window that looks a moment later still sees how it ended.
/// </summary>
public sealed class Transfers
{
    /// <summary>How long a finished transfer stays listed.</summary>
    public static readonly TimeSpan Keep = TimeSpan.FromSeconds(120);

    const double Window = 4.0;      // seconds of samples the speed is worked out from

    sealed class Item
    {
        public required string Id;
        public required string Dir;
        public required string Fp;
        public string Peer = "";
        public string Name = "";
        public long Size;
        public long Done;
        public string State = TransferState.Active;
        public string? Error;
        public string? Route;
        public double Started;
        public double? Ended;
        public readonly Queue<(double T, long Done)> Samples = new();
    }

    readonly Func<double> clock;
    readonly Lock gate = new();
    readonly Dictionary<string, Item> items = [];

    /// <summary>Creates the list; <paramref name="clock"/> (seconds, only differences count) is for tests.</summary>
    public Transfers(Func<double>? clock = null)
    {
        this.clock = clock ?? (() => Environment.TickCount64 / 1000.0);
    }

    /// <summary>Raised when one starts, moves on or ends (often: a UI shows at most a few updates a second).</summary>
    public event Action<string>? Changed;

    /// <summary>A transfer begins, or starts again (resuming: what it had stays, unless <paramref name="done"/> says more).</summary>
    public void Start(string id, string direction, string fp, string peer, string name, long size, string? route = null, long done = 0)
    {
        var now = clock();
        lock (gate)
        {
            items.TryGetValue(id, out var old);
            var had = old is not null && old.State != TransferState.Cancelled ? old.Done : 0;
            var t = new Item
            {
                Id = id, Dir = direction, Fp = fp, Peer = peer, Name = name, Size = Math.Max(0, size), Done = Math.Max(done, had),
                Route = route, Started = old?.Started ?? now,
            };
            t.Samples.Enqueue((now, t.Done));
            items[id] = t;
        }
        Changed?.Invoke(id);
    }

    /// <summary>How far it has got: <paramref name="done"/> bytes.</summary>
    public void Progress(string id, long done)
    {
        var now = clock();
        lock (gate)
        {
            if (!items.TryGetValue(id, out var t) || t.State != TransferState.Active)
            {
                return;
            }
            t.Done = t.Size > 0 ? Math.Min(Math.Max(done, 0), t.Size) : Math.Max(done, 0);
            t.Samples.Enqueue((now, t.Done));
            while (t.Samples.Count > 2 && now - t.Samples.Peek().T > Window)
            {
                t.Samples.Dequeue();
            }
        }
        Changed?.Invoke(id);
    }

    /// <summary>It ended: <see cref="TransferState"/> <paramref name="state"/>, and why.</summary>
    public void Finish(string id, string state, string? error = null)
    {
        lock (gate)
        {
            if (!items.TryGetValue(id, out var t))
            {
                return;
            }
            t.State = state;
            t.Error = error;
            t.Ended = clock();
            if (state == TransferState.Done)
            {
                t.Done = t.Size;
            }
        }
        Changed?.Invoke(id);
    }

    /// <summary>One transfer, or null.</summary>
    public TransferView? Get(string id)
    {
        lock (gate)
        {
            return items.TryGetValue(id, out var t) ? View(t, clock()) : null;
        }
    }

    /// <summary>The ones moving now.</summary>
    public List<TransferView> Active() => List().Where(t => t.State == TransferState.Active).ToList();

    /// <summary>Every transfer: active ones first (oldest first), then the recently finished.</summary>
    public List<TransferView> List()
    {
        var now = clock();
        lock (gate)
        {
            foreach (var id in items.Where(p => p.Value.Ended is { } e && now - e > Keep.TotalSeconds).Select(p => p.Key).ToList())
            {
                items.Remove(id);
            }
            return items.Values.OrderBy(t => t.State != TransferState.Active).ThenBy(t => t.Started).Select(t => View(t, now)).ToList();
        }
    }

    static TransferView View(Item t, double now)
    {
        double? rate = null;
        if (t.State == TransferState.Active && t.Samples.Count >= 2)
        {
            var (t0, d0) = t.Samples.Peek();
            var (t1, d1) = t.Samples.Last();
            // quiet for a while: it's stalled, not still going at the last speed
            if (now - t1 < 3 && t1 > t0)
            {
                rate = (d1 - d0) / (t1 - t0);
            }
        }
        var left = t.Size - t.Done;
        return new TransferView
        {
            Id = t.Id, Dir = t.Dir, Fp = t.Fp, Peer = t.Peer, Name = t.Name, Size = t.Size, Done = t.Done, State = t.State, Error = t.Error,
            Route = t.Route,
            Percent = t.Size > 0 ? (int)(t.Done * 100 / t.Size) : t.State == TransferState.Done ? 100 : 0,
            Rate = rate is > 0 ? (long)rate.Value : null,
            Eta = rate is > 0 && left > 0 ? (long)(left / rate.Value + 0.5) : null,
        };
    }

    // --- the words ---------------------------------------------------------------------------

    /// <summary>"512 bytes", "3.2 MB" (as the reference writes it: 1024 to a K).</summary>
    public static string SizeText(double n)
    {
        foreach (var unit in new[] { "bytes", "KB", "MB", "GB" })
        {
            if (n < 1000 || unit == "GB")
            {
                return unit == "bytes"
                    ? string.Create(CultureInfo.InvariantCulture, $"{(long)n} {unit}")
                    : string.Create(CultureInfo.InvariantCulture, $"{n:0.0} {unit}").Replace(".0 ", " ", StringComparison.Ordinal);
            }
            n /= 1024;
        }
        return "";
    }

    /// <summary>"12 s left", "3 min left", "1 h 5 min left".</summary>
    public static string EtaText(long seconds)
    {
        if (seconds < 0)
        {
            return "";
        }
        if (seconds < 60)
        {
            return string.Create(CultureInfo.InvariantCulture, $"{Math.Max(seconds, 1)} s left");
        }
        if (seconds < 3600)
        {
            return string.Create(CultureInfo.InvariantCulture, $"{(seconds + 30) / 60} min left");
        }
        return string.Create(CultureInfo.InvariantCulture, $"{seconds / 3600} h {seconds % 3600 / 60} min left");
    }

    /// <summary>One transfer, in a line: "45% · 12 s left · 3.2 MB/s", or how it ended.</summary>
    public static string ProgressText(TransferView t)
    {
        ArgumentNullException.ThrowIfNull(t);
        switch (t.State)
        {
            case TransferState.Done:
                return t.Dir == "out" ? "Sent" : "Received";
            case TransferState.Cancelled:
                return t.Error is { Length: > 0 } e && e != "cancelled here" ? $"Cancelled: {e}" : "Cancelled";
            case TransferState.Failed:
                return $"Failed: {(string.IsNullOrEmpty(t.Error) ? "it stopped" : t.Error)}";
            case TransferState.Waiting:
                return string.Create(CultureInfo.InvariantCulture, $"Stopped at {t.Percent}%: it carries on when it can");
        }
        if (t.Route == "hub" && t.Done == 0)
        {
            return "Sending through the hub…";
        }
        var bits = new List<string> { string.Create(CultureInfo.InvariantCulture, $"{t.Percent}%") };
        if (t.Eta is { } eta)
        {
            bits.Add(EtaText(eta));
        }
        if (t.Rate is { } rate)
        {
            bits.Add(SizeText(rate) + "/s");
        }
        else if (t.Done == 0)
        {
            bits.Add("starting…");
        }
        return string.Join(" · ", bits);
    }
}
