using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Droplet.Core.Common;

namespace Droplet.Core.Mesh;

/// <summary>
/// File names that can't escape the downloads folder, hide themselves, or be refused
/// by Windows: no path, no leading dots, no characters Windows forbids, no reserved
/// device names, at most 200 bytes (the extension kept).
/// </summary>
public static class SafeName
{
    static readonly HashSet<string> Reserved = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    };

    /// <summary>A safe version of <paramref name="raw"/>; "file" when nothing is left.</summary>
    public static string Of(string? raw)
    {
        var name = (raw ?? "").Replace('\\', '/');
        name = name[(name.LastIndexOf('/') + 1)..];
        var sb = new StringBuilder();
        foreach (var r in name.EnumerateRunes())
        {
            if (Clean.IsPrintable(r) && !"<>:\"|?*".Contains(r.ToString(), StringComparison.Ordinal))
            {
                sb.Append(r.ToString());
            }
        }
        name = sb.ToString().Trim().TrimStart('.').Trim();
        // Windows drops trailing dots and spaces itself, which could merge two names
        name = name.TrimEnd('.', ' ');
        if (Encoding.UTF8.GetByteCount(name) > 200)
        {
            var dot = name.LastIndexOf('.');
            var ext = dot > 0 && name.Length - dot - 1 <= 16 ? name[(dot + 1)..] : "";
            var stem = ext.Length > 0 ? name[..dot] : name;
            var budget = 200 - Encoding.UTF8.GetByteCount(ext) - (ext.Length > 0 ? 1 : 0);
            stem = TruncateUtf8(stem, budget);
            name = ext.Length > 0 ? $"{stem}.{ext}" : stem;
        }
        if (name.Length == 0)
        {
            return "file";
        }
        var bare = name.Split('.')[0].TrimEnd(' ');
        return Reserved.Contains(bare) ? "_" + name : name;
    }

    static string TruncateUtf8(string s, int maxBytes)
    {
        var sb = new StringBuilder();
        var n = 0;
        foreach (var r in s.EnumerateRunes())
        {
            n += r.Utf8SequenceLength;
            if (n > maxBytes)
            {
                break;
            }
            sb.Append(r.ToString());
        }
        return sb.ToString();
    }

    /// <summary>
    /// A path in <paramref name="dir"/> for <paramref name="name"/> that doesn't exist yet:
    /// "photo.jpg", then "photo (1).jpg" and so on, as Explorer does.
    /// </summary>
    public static string Unique(string dir, string name)
    {
        var p = Path.Combine(dir, name);
        var ext = Path.GetExtension(name);
        var stem = Path.GetFileNameWithoutExtension(name);
        if (stem.Length == 0)
        {
            (stem, ext) = (name, "");
        }
        for (var i = 1; File.Exists(p) || Directory.Exists(p); i++)
        {
            p = Path.Combine(dir, $"{stem} ({i}){ext}");
        }
        return p;
    }
}

/// <summary>A <c>Range</c> header, as far as resuming needs (a single <c>bytes=</c> range).</summary>
public static partial class RangeHeader
{
    [GeneratedRegex(@"^\s*bytes\s*=\s*(\d*)\s*-\s*(\d*)\s*$")]
    private static partial Regex Pattern();

    /// <summary>What a range means for a file.</summary>
    public enum Kind
    {
        /// <summary>No range (or a form not supported): the whole file.</summary>
        Whole,

        /// <summary>A satisfiable range.</summary>
        Part,

        /// <summary>Unsatisfiable: 416.</summary>
        Bad,
    }

    /// <summary>Parses a header into (kind, first, last) with last inclusive.</summary>
    public static (Kind Kind, long First, long Last) Parse(string? header, long size)
    {
        if (string.IsNullOrEmpty(header))
        {
            return (Kind.Whole, 0, size - 1);
        }
        var m = Pattern().Match(header);
        if (!m.Success || (m.Groups[1].Length == 0 && m.Groups[2].Length == 0))
        {
            return (Kind.Whole, 0, size - 1); // a form we don't support: send it all, as HTTP allows
        }
        if (!TryParse(m.Groups[1].Value, out var a) | !TryParse(m.Groups[2].Value, out var b))
        {
            return (Kind.Bad, 0, 0);
        }
        if (m.Groups[1].Length == 0)
        {
            // the last n bytes
            if (b == 0 || size == 0)
            {
                return (Kind.Bad, 0, 0);
            }
            return (Kind.Part, Math.Max(0, size - b), size - 1);
        }
        var last = m.Groups[2].Length > 0 ? b : size - 1;
        if (a >= size || last < a)
        {
            return (Kind.Bad, 0, 0);
        }
        return (Kind.Part, a, Math.Min(last, size - 1));
    }

    static bool TryParse(string s, out long v)
    {
        v = 0;
        return s.Length == 0 || long.TryParse(s, NumberStyles.None, CultureInfo.InvariantCulture, out v);
    }
}

/// <summary>A file this device offers one peer (docs/mesh.md §9.5).</summary>
public sealed class Offer
{
    /// <summary>Creates an offer.</summary>
    /// <param name="id">16–64 hex.</param>
    /// <param name="fp">The peer it's offered to.</param>
    /// <param name="name">The name it's offered under.</param>
    /// <param name="size">Its size.</param>
    /// <param name="mime">Its type.</param>
    /// <param name="open">Opens it for reading.</param>
    /// <param name="check">Why it can't be sent any more (it changed or went away), or null.</param>
    public Offer(string id, string fp, string name, long size, string mime, Func<Stream> open, Func<string?>? check = null)
    {
        Id = id;
        Fp = fp;
        Name = name;
        Size = size;
        Mime = mime;
        Open = open;
        Check = check;
    }

    /// <summary>The offer id.</summary>
    public string Id { get; }

    /// <summary>The peer it's for.</summary>
    public string Fp { get; }

    /// <summary>The name.</summary>
    public string Name { get; }

    /// <summary>The size.</summary>
    public long Size { get; }

    /// <summary>The type.</summary>
    public string Mime { get; }

    /// <summary>Opens the file.</summary>
    public Func<Stream> Open { get; }

    /// <summary>Why it can't be sent any more, or null.</summary>
    public Func<string?>? Check { get; }

    long sent;
    long lastActivity = Environment.TickCount64;

    /// <summary>Bytes served, all requests together.</summary>
    public long Sent => Interlocked.Read(ref sent);

    /// <summary>How long since a byte was served.</summary>
    public TimeSpan Idle => TimeSpan.FromMilliseconds(Environment.TickCount64 - Interlocked.Read(ref lastActivity));

    internal void Progress(long n)
    {
        Interlocked.Add(ref sent, n);
        Touch();
    }

    internal void Touch() => Interlocked.Exchange(ref lastActivity, Environment.TickCount64);

    readonly TaskCompletionSource<(bool Ok, string Error)> done = new(TaskCreationOptions.RunContinuationsAsynchronously);

    /// <summary>Completes with the receiver's ack (true) or nack (false, why).</summary>
    public Task<(bool Ok, string Error)> Done => done.Task;

    /// <summary>Records the outcome (the first one counts).</summary>
    public void Finish(bool ok, string error = "") => done.TrySetResult((ok, error));

    /// <summary>The offer message.</summary>
    public JsonObject Message() => new() { ["t"] = "offer", ["id"] = Id, ["name"] = Name, ["size"] = Size, ["mime"] = Mime };
}

/// <summary>Offers already received, by (sender, id), so a re-offer isn't saved twice.</summary>
public sealed class CompletedFiles(string path)
{
    const int Keep = 500;

    readonly Lock gate = new();
    readonly List<JsonObject> items = LoadItems(path);

    static List<JsonObject> LoadItems(string path)
    {
        var text = AtomicFile.TryReadText(path);
        if (text is null)
        {
            return [];
        }
        try
        {
            return JsonNode.Parse(text) is JsonArray a ? a.OfType<JsonObject>().Select(o => (JsonObject)o.DeepClone()).ToList() : [];
        }
        catch (JsonException)
        {
            return [];
        }
    }

    /// <summary>Where the offer was saved, "" when unknown, or null when it wasn't received.</summary>
    public string? Has(string fp, string id)
    {
        lock (gate)
        {
            var hit = items.FirstOrDefault(x => x.Str("fp") == fp && x.Str("id") == id);
            return hit is null ? null : hit.Str("path") ?? "";
        }
    }

    /// <summary>Records a received offer.</summary>
    public void Add(string fp, string id, string savedPath)
    {
        lock (gate)
        {
            items.Add(new JsonObject { ["fp"] = fp, ["id"] = id, ["path"] = savedPath, ["ts"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds() });
            if (items.Count > Keep)
            {
                items.RemoveRange(0, items.Count - Keep);
            }
            var a = new JsonArray();
            foreach (var x in items)
            {
                a.Add(x.DeepClone());
            }
            AtomicFile.WriteText(path, Json.ToText(a));
        }
    }
}

/// <summary>A download failed; <see cref="Permanent"/> ones are refused for good (nack).</summary>
public sealed class DownloadException(string message, bool permanent = false, Exception? inner = null) : Exception(message, inner)
{
    /// <summary>Don't retry: tell the sender with a nack.</summary>
    public bool Permanent { get; } = permanent;
}

/// <summary>Checked fields of an offer message.</summary>
/// <param name="Id">The offer id.</param>
/// <param name="Name">A safe name.</param>
/// <param name="Size">The size.</param>
/// <param name="Mime">The type.</param>
public sealed record OfferInfo(string Id, string Name, long Size, string Mime);

/// <summary>
/// Receiving files: checking offers, and downloading with resume. A partial download is
/// kept as a hidden <c>.droplet-&lt;fp prefix&gt;-&lt;id&gt;.part</c> beside the downloads,
/// so a re-offer of the same file (after either side restarted) carries on where it stopped.
/// </summary>
public static partial class FileReceiver
{
    /// <summary>An offer id: 16–64 lowercase hex.</summary>
    [GeneratedRegex("^[0-9a-f]{16,64}$")]
    public static partial Regex OfferIdPattern();

    [GeneratedRegex(@"^[\w.+-]+/[\w.+-]+$")]
    private static partial Regex MimePattern();

    [GeneratedRegex(@"^bytes (\d+)-(\d+)/(\d+)$")]
    private static partial Regex ContentRangePattern();

    /// <summary>Anything bigger is a lie: 1 TiB.</summary>
    public const long MaxSize = 1L << 40;

    const int Chunk = 256 * 1024;
    const long SpaceMargin = 64L * 1024 * 1024;

    /// <summary>Checks an offer message. Throws a permanent <see cref="DownloadException"/> when it's malformed.</summary>
    public static OfferInfo Check(JsonObject msg)
    {
        ArgumentNullException.ThrowIfNull(msg);
        var id = msg.Str("id");
        if (id is null || !OfferIdPattern().IsMatch(id))
        {
            throw new DownloadException("bad offer id", true);
        }
        if (msg.Int("size") is not { } size || size < 0 || size > MaxSize)
        {
            throw new DownloadException("bad size", true);
        }
        var mime = msg.Str("mime") ?? "";
        mime = MimePattern().IsMatch(mime) ? mime : "application/octet-stream";
        return new OfferInfo(id, SafeName.Of(msg.Str("name")), size, mime);
    }

    /// <summary>The partial file and its metadata for an offer.</summary>
    public static (string Part, string Meta) PartPaths(string dir, string fp, string id)
    {
        var baseName = $".droplet-{fp[..16]}-{id}";
        return (Path.Combine(dir, baseName + ".part"), Path.Combine(dir, baseName + ".json"));
    }

    /// <summary>
    /// Fetches an offer into <paramref name="dir"/>, resuming a partial one, trying each
    /// host in turn. Returns the saved file.
    /// </summary>
    public static async Task<string> DownloadAsync(MeshIdentity identity, string fp, IReadOnlyList<(string Host, int Port)> hosts,
        OfferInfo offer, string dir, Action<string>? log = null, Action<long, long>? progress = null, int tries = 5,
        TimeSpan? timeout = null, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(identity);
        ArgumentNullException.ThrowIfNull(hosts);
        ArgumentNullException.ThrowIfNull(offer);
        Directory.CreateDirectory(dir);
        var (part, meta) = PartPaths(dir, fp, offer.Id);
        var prev = Json.ParseObject(AtomicFile.TryReadText(meta));
        if (prev?.Int("size") != offer.Size || !File.Exists(part) || new FileInfo(part).Length > offer.Size)
        {
            File.Delete(part);
            AtomicFile.WriteText(meta, Json.ToText(new JsonObject { ["size"] = offer.Size, ["name"] = offer.Name, ["fp"] = fp }));
        }
        long Have() => File.Exists(part) ? new FileInfo(part).Length : 0;
        var free = new DriveInfo(Path.GetFullPath(dir)).AvailableFreeSpace;
        if (offer.Size - Have() + SpaceMargin > free)
        {
            throw new DownloadException($"not enough space for {offer.Name} ({offer.Size} bytes)", true);
        }
        var lastError = "no address to fetch it from";
        var delay = TimeSpan.FromSeconds(1);
        for (var attempt = 0; attempt < tries; attempt++)
        {
            foreach (var (host, port) in hosts)
            {
                if (Have() == offer.Size)
                {
                    break;
                }
                try
                {
                    await FetchAsync(identity, fp, host, port, offer, part, Have(), timeout ?? TimeSpan.FromSeconds(20), log, progress, ct)
                        .ConfigureAwait(false);
                    break;
                }
                catch (DownloadException e) when (!e.Permanent)
                {
                    lastError = e.Message;
                }
                catch (Exception e) when (e is IOException or HttpRequestException or TimeoutException ||
                                          (e is TaskCanceledException && !ct.IsCancellationRequested))
                {
                    lastError = $"{host}: {e.Message}";
                }
            }
            if (Have() == offer.Size)
            {
                break;
            }
            if (attempt < tries - 1)
            {
                log?.Invoke($"download of {offer.Name} stopped at {Have()} of {offer.Size} bytes ({lastError}); resuming in {delay.TotalSeconds:0} s");
                await Task.Delay(delay, ct).ConfigureAwait(false);
                delay = TimeSpan.FromSeconds(Math.Min(delay.TotalSeconds * 2, 16));
            }
            else
            {
                throw new DownloadException(lastError);
            }
        }
        if (!File.Exists(part))
        {
            await File.WriteAllBytesAsync(part, [], ct).ConfigureAwait(false);
        }
        if (Have() != offer.Size)
        {
            throw new DownloadException($"got {Have()} bytes, expected {offer.Size}");
        }
        var final = Claim(dir, offer.Name, part);
        File.Delete(meta);
        return final;
    }

    /// <summary>Gives the finished part its final, unique name, never overwriting anything.</summary>
    static string Claim(string dir, string name, string part)
    {
        while (true)
        {
            var final = SafeName.Unique(dir, name);
            try
            {
                File.Move(part, final, overwrite: false);
                return final;
            }
            catch (IOException) when (File.Exists(final))
            {
                // the name was taken meanwhile: pick the next
            }
        }
    }

    static async Task FetchAsync(MeshIdentity identity, string fp, string host, int port, OfferInfo offer, string part, long have,
        TimeSpan timeout, Action<string>? log, Action<long, long>? progress, CancellationToken ct)
    {
        using var handler = MeshTls.Handler(identity, fp, connectTimeout: timeout);
        using var http = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
        using var req = new HttpRequestMessage(HttpMethod.Get, new Uri($"https://{Addresses.HostPort(host, port)}/mesh/files/{offer.Id}"))
        {
            Version = System.Net.HttpVersion.Version11,
            VersionPolicy = HttpVersionPolicy.RequestVersionExact,
        };
        req.Headers.TryAddWithoutValidation("User-Agent", MeshProtocol.UserAgent);
        if (have > 0)
        {
            req.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(have, null);
        }
        using var headersCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        headersCts.CancelAfter(timeout);
        using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, headersCts.Token).ConfigureAwait(false);
        var status = (int)resp.StatusCode;
        if (status is 404 or 410)
        {
            throw new DownloadException("the sender doesn't offer it any more", true);
        }
        if (have > 0 && status == 200)
        {
            have = 0; // it ignored the range: start again
        }
        else if (status is not (200 or 206))
        {
            throw new DownloadException($"the sender answered {status}");
        }
        if (status == 206)
        {
            var cr = resp.Content.Headers.ContentRange?.ToString() ?? "";
            var m = ContentRangePattern().Match(cr);
            if (!m.Success || long.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture) != have ||
                long.Parse(m.Groups[3].Value, CultureInfo.InvariantCulture) != offer.Size)
            {
                throw new DownloadException("the sender's range doesn't match the partial file");
            }
        }
        if (have > 0)
        {
            log?.Invoke($"resuming {Path.GetFileName(part)} at byte {have} of {offer.Size}");
        }
        await using var body = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        await using var f = new FileStream(part, have > 0 ? FileMode.OpenOrCreate : FileMode.Create, FileAccess.Write, FileShare.None, Chunk, true);
        f.Position = have;
        f.SetLength(have);
        var got = have;
        var buf = new byte[Chunk];
        while (true)
        {
            // no progress for this long: the transfer stopped
            using var readCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            readCts.CancelAfter(timeout);
            int n;
            try
            {
                n = await body.ReadAsync(buf, readCts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                throw new TimeoutException($"no data for {timeout.TotalSeconds:0} s");
            }
            if (n == 0)
            {
                break;
            }
            if (got + n > offer.Size)
            {
                throw new DownloadException("the sender sent more than it offered", true);
            }
            await f.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
            got += n;
            progress?.Invoke(got, offer.Size);
        }
        await f.FlushAsync(ct).ConfigureAwait(false);
        f.Flush(flushToDisk: true);
        if (got != offer.Size)
        {
            throw new DownloadException($"the connection ended at {got} of {offer.Size} bytes");
        }
    }
}
