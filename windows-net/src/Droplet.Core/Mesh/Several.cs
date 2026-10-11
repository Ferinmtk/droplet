namespace Droplet.Core.Mesh;

/// <summary>How a send to one of several devices went.</summary>
public static class SendResult
{
    /// <summary>Delivered.</summary>
    public const string Sent = "sent";

    /// <summary>Waiting in the outbox: the device is away or paused, and it goes when it can.</summary>
    public const string Waiting = "waiting";

    /// <summary>Refused (switched off, here or there), or it failed for good.</summary>
    public const string Refused = "refused";
}

/// <summary>One device's result, when sending to several.</summary>
/// <param name="Fp">The device's fingerprint.</param>
/// <param name="Name">What it's called here.</param>
/// <param name="Result"><see cref="SendResult"/>.</param>
/// <param name="Detail">Why it waits or was refused; the route when sent.</param>
public sealed record DeviceResult(string Fp, string Name, string Result, string? Detail);

/// <summary>
/// Sending to several devices at once (docs/mesh.md §9.11): purely local. Each device gets its
/// own job and its own result, exactly as if it were sent to it alone, so permissions, Pause
/// and routes apply to each.
/// </summary>
public static class Several
{
    /// <summary>
    /// Queues one job per device with <paramref name="send"/> (<see cref="MeshNode.SendText"/>,
    /// <see cref="MeshNode.SendFile"/>…) and waits up to <paramref name="wait"/> for each to be
    /// delivered, refused, or found to be waiting. Results are in the order given.
    /// </summary>
    public static async Task<List<DeviceResult>> SendAsync(MeshNode node, IEnumerable<string> fps, Func<string, OutboxJob> send, TimeSpan wait,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(node);
        ArgumentNullException.ThrowIfNull(fps);
        ArgumentNullException.ThrowIfNull(send);
        var tasks = fps.Distinct().Select(async fp =>
        {
            var name = node.Trust.Get(fp)?.Label ?? Common.Fingerprint.Short(fp);
            OutboxJob job;
            try
            {
                job = send(fp);
            }
            catch (Exception e) when (e is RefusedException or ArgumentException or NoRouteException)
            {
                return new DeviceResult(fp, name, SendResult.Refused, e.Message);
            }
            var done = await node.WaitJobAsync(job.Id, wait, ct).ConfigureAwait(false) ?? job;
            return Of(fp, name, done);
        }).ToList();
        return [.. await Task.WhenAll(tasks).ConfigureAwait(false)];
    }

    /// <summary>A job's state as one device's result.</summary>
    public static DeviceResult Of(string fp, string name, OutboxJob job)
    {
        ArgumentNullException.ThrowIfNull(job);
        return job.State switch
        {
            JobState.Done => new DeviceResult(fp, name, SendResult.Sent, job.Route),
            JobState.Failed or JobState.Cancelled => new DeviceResult(fp, name, SendResult.Refused, job.Error),
            _ => new DeviceResult(fp, name, SendResult.Waiting, Waiting(job.Error)),
        };
    }

    static string? Waiting(string? error) =>
        error is not null && error.StartsWith("waiting: ", StringComparison.Ordinal) ? error["waiting: ".Length..] : error;

    /// <summary>"Sent to 2 of 3 devices", or what one device's result was.</summary>
    public static string Summary(IReadOnlyList<DeviceResult> results)
    {
        ArgumentNullException.ThrowIfNull(results);
        var sent = results.Count(r => r.Result == SendResult.Sent);
        var waiting = results.Count(r => r.Result == SendResult.Waiting);
        var refused = results.Count - sent - waiting;
        var parts = new List<string> { $"Sent to {sent} of {results.Count} device{(results.Count == 1 ? "" : "s")}" };
        if (waiting > 0)
        {
            parts.Add($"{waiting} waiting");
        }
        if (refused > 0)
        {
            parts.Add($"{refused} refused");
        }
        return string.Join(", ", parts);
    }

    /// <summary>One device's result, in a line: "pixel: sent", "t15: waiting (t15 is paused…)".</summary>
    public static string Line(DeviceResult r)
    {
        ArgumentNullException.ThrowIfNull(r);
        return r.Result == SendResult.Sent || string.IsNullOrEmpty(r.Detail) ? $"{r.Name}: {r.Result}" : $"{r.Name}: {r.Result} ({r.Detail})";
    }
}
