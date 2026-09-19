using System.Text.Json;
using System.Text.Json.Serialization;
using Droplet.Core.Common;

namespace Droplet.Core.Mesh;

/// <summary>Where an outbox job stands.</summary>
public static class JobState
{
    /// <summary>Waiting for a route.</summary>
    public const string Queued = "queued";

    /// <summary>Being delivered.</summary>
    public const string Sending = "sending";

    /// <summary>Delivered.</summary>
    public const string Done = "done";

    /// <summary>Failed for good.</summary>
    public const string Failed = "failed";
}

/// <summary>A text or a file on its way to one peer.</summary>
public sealed record OutboxJob
{
    /// <summary>32 hex; also the text's message id and the file's offer id.</summary>
    [JsonPropertyName("id")] public required string Id { get; init; }

    /// <summary>"text" or "file".</summary>
    [JsonPropertyName("kind")] public required string Kind { get; init; }

    /// <summary>The peer's fingerprint.</summary>
    [JsonPropertyName("fp")] public required string Fp { get; init; }

    /// <summary>The peer's name, for reporting.</summary>
    [JsonPropertyName("peer")] public string Peer { get; init; } = "";

    /// <summary>When it was queued, Unix seconds.</summary>
    [JsonPropertyName("created")] public double Created { get; init; }

    /// <summary><see cref="JobState"/>.</summary>
    [JsonPropertyName("state")] public string State { get; init; } = JobState.Queued;

    /// <summary>Delivery attempts so far.</summary>
    [JsonPropertyName("attempts")] public int Attempts { get; init; }

    /// <summary>How it went: lan, tailnet, hub or hub-mailbox.</summary>
    [JsonPropertyName("route")] public string? Route { get; init; }

    /// <summary>Why it's waiting or failed.</summary>
    [JsonPropertyName("error")] public string? Error { get; init; }

    /// <summary>A transfer that stopped part-way and is retried directly.</summary>
    [JsonPropertyName("retry")] public bool Retry { get; init; }

    /// <summary>Text: the message.</summary>
    [JsonPropertyName("body")] public string? Body { get; init; }

    /// <summary>File: where it is (not copied).</summary>
    [JsonPropertyName("path")] public string? Path { get; init; }

    /// <summary>File: the name it's sent under.</summary>
    [JsonPropertyName("name")] public string? Name { get; init; }

    /// <summary>File: its type.</summary>
    [JsonPropertyName("mime")] public string? Mime { get; init; }

    /// <summary>File: its size when queued.</summary>
    [JsonPropertyName("size")] public long Size { get; init; }

    /// <summary>File: its modification time when queued (ticks, UTC).</summary>
    [JsonPropertyName("mtime")] public long Mtime { get; init; }

    /// <summary>File: delete it once delivered or failed (a screenshot written for sending).</summary>
    [JsonPropertyName("cleanup")] public bool Cleanup { get; init; }
}

/// <summary>
/// Chat messages and files waiting for a route (docs/mesh.md §5, route 5). Every text
/// and file this device sends becomes a job here first, so a send survives a restart,
/// and each peer's messages go out in order. A file job refers to the file where it is;
/// its size and modification time are recorded, and a file that changed or went away
/// fails the job rather than sending something else.
/// </summary>
public sealed class Outbox
{
    const int KeepFinished = 200;
    static readonly JsonSerializerOptions Options = Json.Indented;

    readonly string path;
    readonly Lock gate = new();
    readonly Dictionary<string, OutboxJob> jobs = [];
    readonly Dictionary<string, OutboxJob> finished = [];
    readonly Queue<string> finishedOrder = new();
    TaskCompletionSource changed = new(TaskCreationOptions.RunContinuationsAsynchronously);

    /// <summary>Loads the outbox; whatever was being sent when the app stopped is waiting again.</summary>
    public Outbox(string path)
    {
        this.path = path;
        var text = AtomicFile.TryReadText(path);
        if (text is null)
        {
            return;
        }
        try
        {
            foreach (var j in JsonSerializer.Deserialize<List<OutboxJob>>(text) ?? [])
            {
                if (j.Kind is "text" or "file" && !string.IsNullOrEmpty(j.Id))
                {
                    jobs[j.Id] = j with { State = JobState.Queued };
                }
            }
        }
        catch (JsonException)
        {
        }
    }

    void Save() => AtomicFile.Write(path, JsonSerializer.SerializeToUtf8Bytes(jobs.Values.OrderBy(j => j.Created).ToList(), Options));

    void Signal()
    {
        var old = changed;
        changed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        old.TrySetResult();
    }

    static double Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() / 1000.0;

    /// <summary>Queues a chat message.</summary>
    public OutboxJob AddText(string fp, string peerName, string body) =>
        Add(new OutboxJob { Id = Hex.Random(16), Kind = "text", Fp = fp, Peer = peerName, Created = Now(), Body = body });

    /// <summary>Queues a file, recording its size and modification time.</summary>
    public OutboxJob AddFile(string fp, string peerName, string filePath, string name, string mime, bool cleanup = false)
    {
        var fi = new FileInfo(filePath);
        return Add(new OutboxJob
        {
            Id = Hex.Random(16), Kind = "file", Fp = fp, Peer = peerName, Created = Now(), Path = fi.FullName, Name = name, Mime = mime,
            Size = fi.Length, Mtime = fi.LastWriteTimeUtc.Ticks, Cleanup = cleanup,
        });
    }

    OutboxJob Add(OutboxJob job)
    {
        lock (gate)
        {
            jobs[job.Id] = job;
            Save();
            Signal();
        }
        return job;
    }

    /// <summary>A job, waiting or finished (recently), or null.</summary>
    public OutboxJob? Get(string id)
    {
        lock (gate)
        {
            return jobs.GetValueOrDefault(id) ?? finished.GetValueOrDefault(id);
        }
    }

    /// <summary>The jobs still to deliver, oldest first.</summary>
    public List<OutboxJob> Queued()
    {
        lock (gate)
        {
            return [.. jobs.Values.OrderBy(j => j.Created)];
        }
    }

    /// <summary>One peer's jobs, oldest first.</summary>
    public List<OutboxJob> ForPeer(string fp) => Queued().Where(j => j.Fp == fp).ToList();

    /// <summary>Changes a job; a finished one moves out of the outbox.</summary>
    public OutboxJob? Update(string id, Func<OutboxJob, OutboxJob> change)
    {
        ArgumentNullException.ThrowIfNull(change);
        lock (gate)
        {
            if (!jobs.TryGetValue(id, out var j))
            {
                return null;
            }
            j = change(j);
            if (j.State is JobState.Done or JobState.Failed)
            {
                jobs.Remove(id);
                finished[id] = j;
                finishedOrder.Enqueue(id);
                while (finishedOrder.Count > KeepFinished)
                {
                    finished.Remove(finishedOrder.Dequeue());
                }
            }
            else
            {
                jobs[id] = j;
            }
            Save();
            Signal();
            return j;
        }
    }

    /// <summary>
    /// Waits until <paramref name="until"/> holds for the job, or the time is up. Returns
    /// the job (null if unknown).
    /// </summary>
    public async Task<OutboxJob?> WaitAsync(string id, Func<OutboxJob, bool> until, TimeSpan timeout, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(until);
        var end = DateTime.UtcNow + timeout;
        while (true)
        {
            Task next;
            OutboxJob? j;
            lock (gate)
            {
                j = jobs.GetValueOrDefault(id) ?? finished.GetValueOrDefault(id);
                next = changed.Task;
            }
            if (j is null || until(j))
            {
                return j;
            }
            var left = end - DateTime.UtcNow;
            if (left <= TimeSpan.Zero)
            {
                return j;
            }
            try
            {
                await next.WaitAsync(left, ct).ConfigureAwait(false);
            }
            catch (TimeoutException)
            {
            }
        }
    }
}

/// <summary>One chat message sent or received directly.</summary>
public sealed record ChatEntry
{
    /// <summary>A unique id: "&lt;sender fp prefix&gt;:&lt;message id&gt;" for received ones, the job id for sent ones.</summary>
    [JsonPropertyName("id")] public required string Id { get; init; }

    /// <summary>"in" or "out".</summary>
    [JsonPropertyName("dir")] public required string Dir { get; init; }

    /// <summary>The peer's fingerprint.</summary>
    [JsonPropertyName("fp")] public required string Fp { get; init; }

    /// <summary>The peer's id.</summary>
    [JsonPropertyName("peer")] public string? Peer { get; init; }

    /// <summary>The peer's name.</summary>
    [JsonPropertyName("name")] public string? Name { get; init; }

    /// <summary>The text.</summary>
    [JsonPropertyName("body")] public required string Body { get; init; }

    /// <summary>When, Unix seconds.</summary>
    [JsonPropertyName("ts")] public double Ts { get; init; }

    /// <summary>How a sent message went.</summary>
    [JsonPropertyName("route")] public string? Route { get; init; }
}

/// <summary>Chat sent and received directly, one JSON line each. Ids make repeats harmless.</summary>
public sealed class ChatLog
{
    readonly string path;
    readonly Lock gate = new();
    readonly HashSet<string> ids = [];

    /// <summary>Opens the log at <paramref name="path"/>.</summary>
    public ChatLog(string path)
    {
        this.path = path;
        try
        {
            foreach (var line in File.ReadLines(path))
            {
                if (Json.ParseObject(line)?.Str("id") is { } id)
                {
                    ids.Add(id);
                }
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
        }
    }

    /// <summary>Stores a message; false if one with that id is already there.</summary>
    public bool Add(ChatEntry entry)
    {
        ArgumentNullException.ThrowIfNull(entry);
        lock (gate)
        {
            var key = $"{entry.Dir}:{entry.Id}";
            if (ids.Contains(key) || ids.Contains(entry.Id))
            {
                return false;
            }
            ids.Add(key);
            AtomicFile.CreatePrivateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
            File.AppendAllText(path, JsonSerializer.Serialize(entry, Json.Compact) + "\n");
            return true;
        }
    }

    /// <summary>The last <paramref name="n"/> messages.</summary>
    public List<ChatEntry> Recent(int n = 50)
    {
        try
        {
            return File.ReadLines(path).TakeLast(n)
                .Select(l =>
                {
                    try
                    {
                        return JsonSerializer.Deserialize<ChatEntry>(l);
                    }
                    catch (JsonException)
                    {
                        return null;
                    }
                })
                .OfType<ChatEntry>()
                .ToList();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return [];
        }
    }
}
