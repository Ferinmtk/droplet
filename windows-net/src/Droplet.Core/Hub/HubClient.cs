using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Droplet.Core.Hub;

/// <summary>
/// A client for a droplet hub's HTTP API, as one device (docs/remote.md §1,
/// docs/local-first.md §4). The token is sent as <c>Authorization: Bearer</c> and as
/// the <c>droplet_device</c> cookie (for hubs from before bearer tokens); a PIN login's
/// session cookie rides along. Cookies are kept by hand, not in a jar: they are the
/// device's identity and live in the config.
/// <para>
/// No <c>Origin</c> header is ever sent: the hub refuses writes whose Origin isn't the
/// hub itself. Redirects are never followed: a redirect to <c>/login</c> is an answer
/// ("this hub wants a PIN"), not somewhere to go.
/// </para>
/// </summary>
public sealed class HubClient : IDisposable
{
    const string DeviceCookie = "droplet_device";
    const string SessionCookie = "session";

    readonly HttpClient api;
    readonly HttpClient transfer;
    readonly bool ownsHandler;
    readonly HttpMessageHandler handler;

    /// <summary>How this app names itself in User-Agent.</summary>
    public static string UserAgent { get; set; } = "droplet-windows/2 (.NET)";

    /// <summary>
    /// A client for the hub at <paramref name="baseUrl"/> (e.g. https://t15.tail7375fe.ts.net).
    /// <paramref name="handler"/> connects: the pinned LAN handler, or null for normal,
    /// fully verified TLS. A handler passed in is shared, not disposed.
    /// </summary>
    public HubClient(string baseUrl, string? token = null, string? session = null, HttpMessageHandler? handler = null)
    {
        Base = ParseUrl(baseUrl);
        Token = token;
        Session = session;
        ownsHandler = handler is null;
        this.handler = handler ?? DefaultHandler();
        api = new HttpClient(this.handler, disposeHandler: false) { Timeout = TimeSpan.FromSeconds(20) };
        transfer = new HttpClient(this.handler, disposeHandler: false) { Timeout = Timeout.InfiniteTimeSpan };
    }

    /// <summary>The hub's base URL, without a trailing slash.</summary>
    public Uri Base { get; }

    /// <summary>The device token (the <c>droplet_device</c> cookie).</summary>
    public string? Token { get; set; }

    /// <summary>The session cookie from a PIN login.</summary>
    public string? Session { get; set; }

    /// <summary>A handler with normal TLS, no redirects, no cookies of its own, no proxy for LAN addresses.</summary>
    public static SocketsHttpHandler DefaultHandler() => new()
    {
        AllowAutoRedirect = false,
        UseCookies = false,
        ConnectTimeout = TimeSpan.FromSeconds(10),
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        AutomaticDecompression = DecompressionMethods.None,
    };

    /// <summary>
    /// Validates and normalises a hub URL typed by a person: https:// is assumed, the
    /// path loses its trailing slash, and query and fragment go.
    /// </summary>
    public static Uri ParseUrl(string? text)
    {
        var s = (text ?? "").Trim();
        if (s.Length == 0)
        {
            throw new FormatException("the hub URL is empty");
        }
        if (!s.Contains("://", StringComparison.Ordinal))
        {
            s = "https://" + s;
        }
        if (!Uri.TryCreate(s, UriKind.Absolute, out var u) || (u.Scheme != Uri.UriSchemeHttp && u.Scheme != Uri.UriSchemeHttps) ||
            string.IsNullOrEmpty(u.Host) || !string.IsNullOrEmpty(u.UserInfo))
        {
            throw new FormatException($"\"{s}\" isn't a hub URL (expected e.g. https://t15.tail7375fe.ts.net)");
        }
        var b = new UriBuilder(u) { Query = "", Fragment = "" };
        b.Path = b.Path.TrimEnd('/');
        return b.Uri;
    }

    /// <summary>A short name for the hub, e.g. "t15" for t15.tail7375fe.ts.net.</summary>
    public string HubName
    {
        get
        {
            var h = Base.Host;
            if (Base.HostNameType == UriHostNameType.Dns)
            {
                var dot = h.IndexOf('.', StringComparison.Ordinal);
                if (dot > 0)
                {
                    return h[..dot];
                }
            }
            return h;
        }
    }

    /// <summary>The absolute URL of a hub path such as "/api/files".</summary>
    public Uri Url(string path) => new(Base.AbsoluteUri.TrimEnd('/') + path);

    HttpRequestMessage NewRequest(HttpMethod method, string path, HttpContent? content = null)
    {
        var req = new HttpRequestMessage(method, Url(path)) { Content = content, Version = HttpVersion.Version11 };
        var cookies = new List<string>();
        if (!string.IsNullOrEmpty(Token))
        {
            cookies.Add($"{DeviceCookie}={Token}");
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", Token);
        }
        if (!string.IsNullOrEmpty(Session))
        {
            cookies.Add($"{SessionCookie}={Session}");
        }
        if (cookies.Count > 0)
        {
            req.Headers.TryAddWithoutValidation("Cookie", string.Join("; ", cookies));
        }
        req.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
        return req;
    }

    /// <summary>Sends a request and turns redirects to login and error statuses into exceptions.</summary>
    async Task<HttpResponseMessage> SendAsync(HttpClient client, HttpRequestMessage req, CancellationToken ct,
        HttpCompletionOption completion = HttpCompletionOption.ResponseContentRead)
    {
        var resp = await client.SendAsync(req, completion, ct).ConfigureAwait(false);
        try
        {
            AbsorbCookies(resp);
            var code = (int)resp.StatusCode;
            if (code is >= 300 and < 400)
            {
                var location = resp.Headers.Location?.ToString() ?? "";
                if (location.Contains("/login", StringComparison.Ordinal))
                {
                    throw new PinRequiredException();
                }
                throw new HubStatusException(code, "unexpected redirect to " + location);
            }
            if (code >= 400)
            {
                var (message, pair) = await ErrorBodyAsync(resp, ct).ConfigureAwait(false);
                throw code switch
                {
                    403 when pair => new NotAllowedException(),
                    409 => new NameTakenException(message ?? "that name is taken"),
                    404 => new HubNotFoundException(),
                    _ => new HubStatusException(code, message),
                };
            }
            return resp;
        }
        catch
        {
            resp.Dispose();
            throw;
        }
    }

    static async Task<(string? Message, bool Pair)> ErrorBodyAsync(HttpResponseMessage resp, CancellationToken ct)
    {
        try
        {
            var bytes = await resp.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
            if (bytes.Length > 64 * 1024)
            {
                return (null, false);
            }
            if (Common.Json.ParseObject(bytes) is { } o)
            {
                return (Common.Json.Str(o, "error"), Common.Json.Bool(o, "pair") == true);
            }
        }
        catch (HttpRequestException)
        {
        }
        return (null, false);
    }

    void AbsorbCookies(HttpResponseMessage resp)
    {
        if (!resp.Headers.TryGetValues("Set-Cookie", out var values))
        {
            return;
        }
        foreach (var header in values)
        {
            var parts = header.Split(';');
            var eq = parts[0].IndexOf('=', StringComparison.Ordinal);
            if (eq <= 0)
            {
                continue;
            }
            var name = parts[0][..eq].Trim();
            var value = parts[0][(eq + 1)..].Trim().Trim('"');
            var deleted = parts.Skip(1).Any(a =>
            {
                var kv = a.Split('=', 2);
                return kv[0].Trim().Equals("max-age", StringComparison.OrdinalIgnoreCase) && kv.Length == 2 &&
                       int.TryParse(kv[1].Trim(), out var age) && age <= 0;
            });
            if (deleted || value.Length == 0)
            {
                continue;
            }
            if (name == DeviceCookie)
            {
                Token = value;
            }
            else if (name == SessionCookie)
            {
                Session = value;
            }
        }
    }

    static async Task<T> ReadJsonAsync<T>(HttpResponseMessage resp, CancellationToken ct)
    {
        var type = resp.Content.Headers.ContentType?.MediaType ?? "";
        if (!type.Contains("json", StringComparison.OrdinalIgnoreCase))
        {
            throw new HubException($"the hub sent \"{type}\" where JSON was expected: is the hub URL right?");
        }
        return await resp.Content.ReadFromJsonAsync<T>(ct).ConfigureAwait(false)
               ?? throw new HubException("the hub sent an empty answer");
    }

    async Task<T> GetJsonAsync<T>(string path, CancellationToken ct)
    {
        using var resp = await SendAsync(api, NewRequest(HttpMethod.Get, path), ct).ConfigureAwait(false);
        return await ReadJsonAsync<T>(resp, ct).ConfigureAwait(false);
    }

    async Task<T> PostJsonAsync<T>(string path, HttpContent? content, CancellationToken ct)
    {
        using var resp = await SendAsync(api, NewRequest(HttpMethod.Post, path, content), ct).ConfigureAwait(false);
        return await ReadJsonAsync<T>(resp, ct).ConfigureAwait(false);
    }

    async Task PostAsync(string path, HttpContent? content, CancellationToken ct)
    {
        using var resp = await SendAsync(api, NewRequest(HttpMethod.Post, path, content), ct).ConfigureAwait(false);
    }

    static ByteArrayContent Body<T>(T value) => Common.Json.Content(value);

    // --- who the hub is, who we are -------------------------------------------------------

    /// <summary>
    /// <c>GET /api/hub/info</c>, which answers before a device is let in.
    /// <see cref="HubNotFoundException"/> means a hub from before local-first.
    /// </summary>
    public async Task<HubInfo> HubInfoAsync(CancellationToken ct = default)
    {
        var info = await GetJsonAsync<HubInfo>("/api/hub/info", ct).ConfigureAwait(false);
        if (string.IsNullOrEmpty(info.Id))
        {
            throw new HubException("the hub didn't say who it is");
        }
        return info;
    }

    /// <summary><c>GET /api/me</c>.</summary>
    public Task<Me> MeAsync(CancellationToken ct = default) => GetJsonAsync<Me>("/api/me", ct);

    /// <summary><c>GET /api/files</c> (which also marks this device online).</summary>
    public Task<FilesListing> FilesAsync(CancellationToken ct = default) => GetJsonAsync<FilesListing>("/api/files", ct);

    /// <summary>Posts the PIN; on success the session cookie is kept in <see cref="Session"/>.</summary>
    public async Task LoginAsync(string pin, CancellationToken ct = default)
    {
        using var req = NewRequest(HttpMethod.Post, "/login", new FormUrlEncodedContent([new("pin", pin)]));
        using var resp = await api.SendAsync(req, ct).ConfigureAwait(false);
        AbsorbCookies(resp);
        // success redirects home; a wrong PIN re-renders the form with 200
        if ((int)resp.StatusCode is not (>= 300 and < 400))
        {
            throw new WrongPinException();
        }
    }

    /// <summary>
    /// Names this device (or renames it when already registered). A new registration's
    /// token is kept in <see cref="Token"/>; on the LAN the answer may be
    /// <see cref="Device.Pending"/> with a <see cref="Device.Code"/>.
    /// </summary>
    public async Task<Device> RegisterAsync(string name, CancellationToken ct = default)
    {
        var d = await PostJsonAsync<Device>("/api/device", Body(new { name }), ct).ConfigureAwait(false);
        if (string.IsNullOrEmpty(Token))
        {
            throw new HubException("the hub didn't hand out a device token");
        }
        return d;
    }

    /// <summary>
    /// Trades a six-digit code from "Set up remote control of this device" for a token on
    /// that device, so this app and the PC's browser are one device on the hub. The new
    /// token replaces <see cref="Token"/>.
    /// </summary>
    public async Task<Linked> LinkAsync(string code, string client, CancellationToken ct = default)
    {
        // a token from an earlier registration mustn't come along: it would be the identity here
        Token = null;
        Linked linked;
        try
        {
            linked = await PostJsonAsync<Linked>("/api/device/link", Body(new { code, client }), ct).ConfigureAwait(false);
        }
        catch (HubStatusException e) when (e.Status == 403)
        {
            throw new BadLinkCodeException();
        }
        catch (HubNotFoundException)
        {
            throw new HubException("this hub can't link apps yet (update the hub)");
        }
        if (string.IsNullOrEmpty(linked.Token) || string.IsNullOrEmpty(linked.Id))
        {
            throw new HubException("the hub's answer had no device token");
        }
        Token = linked.Token;
        return linked;
    }

    /// <summary>Removes a device from the hub, as the web app's Devices list does.</summary>
    public Task RemoveDeviceAsync(string id, CancellationToken ct = default) =>
        PostAsync("/api/device/" + Uri.EscapeDataString(id) + "/remove", null, ct);

    /// <summary>Lets a device that asked to join in (needs a trusted device, or loopback on the hub).</summary>
    public Task ApproveDeviceAsync(string id, CancellationToken ct = default) =>
        PostAsync("/api/device/" + Uri.EscapeDataString(id) + "/approve", null, ct);

    // --- chat -------------------------------------------------------------------------

    /// <summary>The thread with another device (which marks it read on the hub).</summary>
    public async Task<List<ChatMessage>> ChatAsync(string deviceId, CancellationToken ct = default)
    {
        var o = await GetJsonAsync<ChatThread>("/api/chat/" + Uri.EscapeDataString(deviceId), ct).ConfigureAwait(false);
        return o.Messages ?? [];
    }

    sealed record ChatThread([property: System.Text.Json.Serialization.JsonPropertyName("messages")] List<ChatMessage>? Messages);

    /// <summary>
    /// A chat message to a device, or a text file on the hub when <paramref name="to"/> is "hub".
    /// The hub keeps it for a device that's offline (its mailbox).
    /// </summary>
    public async Task SendTextAsync(string to, string text, CancellationToken ct = default)
    {
        try
        {
            await PostAsync("/text", new FormUrlEncodedContent([new("text", text), new("to", to)]), ct).ConfigureAwait(false);
        }
        catch (HubNotFoundException)
        {
            throw new HubException($"no device \"{to}\" on the hub");
        }
    }

    // --- files ------------------------------------------------------------------------

    /// <summary>Removes a file from this device's inbox on the hub.</summary>
    public Task DeleteInboxAsync(string name, CancellationToken ct = default) =>
        PostAsync("/delete/inbox/" + Uri.EscapeDataString(name), null, ct);

    /// <summary>Streams an inbox file into <paramref name="destination"/>. Returns the bytes written.</summary>
    public async Task<long> DownloadAsync(string name, Stream destination, TransferProgress? progress = null, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(destination);
        using var resp = await SendAsync(transfer, NewRequest(HttpMethod.Get, "/d/inbox/" + Uri.EscapeDataString(name)), ct,
            HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false);
        var total = resp.Content.Headers.ContentLength ?? -1;
        await using var body = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        var buf = new byte[256 * 1024];
        long n = 0;
        int read;
        while ((read = await body.ReadAsync(buf, ct).ConfigureAwait(false)) > 0)
        {
            await destination.WriteAsync(buf.AsMemory(0, read), ct).ConfigureAwait(false);
            n += read;
            progress?.Invoke(n, total);
        }
        if (total >= 0 && n != total)
        {
            throw new IOException($"the download of {name} stopped at {n} of {total} bytes");
        }
        return n;
    }

    /// <summary>
    /// Uploads files to a device (by id) or to the hub ("hub"), streamed from disk with
    /// progress. Returns the names the hub saved them as.
    /// </summary>
    public async Task<List<string>> UploadAsync(string to, IReadOnlyList<string> paths, TransferProgress? progress = null,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(paths);
        var files = paths.Select(p => (Path: p, Name: Path.GetFileName(p))).ToList();
        return await UploadFilesAsync(to, files, null, progress, ct).ConfigureAwait(false);
    }

    /// <summary>Uploads one file under a chosen name and type (the mesh's route through the hub).</summary>
    public Task<List<string>> UploadFileAsync(string to, string path, string name, string? mime = null,
        TransferProgress? progress = null, CancellationToken ct = default) =>
        UploadFilesAsync(to, [(path, name)], mime, progress, ct);

    async Task<List<string>> UploadFilesAsync(string to, List<(string Path, string Name)> files, string? mime,
        TransferProgress? progress, CancellationToken ct)
    {
        long total = 0;
        foreach (var (path, _) in files)
        {
            if (Directory.Exists(path))
            {
                throw new IOException($"{Path.GetFileName(path)} is a folder; droplet sends files (zip the folder first)");
            }
            total += new FileInfo(path).Length;
        }
        var counter = new ProgressCounter(total, progress);
        using var form = new MultipartFormDataContent();
        var streams = new List<Stream>();
        try
        {
            foreach (var (path, name) in files)
            {
                var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1, FileOptions.Asynchronous | FileOptions.SequentialScan);
                streams.Add(fs);
                var part = new StreamContent(new CountingStream(fs, counter), 256 * 1024);
                part.Headers.ContentType = new MediaTypeHeaderValue(mime ?? "application/octet-stream");
                form.Add(part, "files", name);
            }
            return await PostUploadAsync(to, form, ct).ConfigureAwait(false);
        }
        finally
        {
            foreach (var s in streams)
            {
                await s.DisposeAsync().ConfigureAwait(false);
            }
        }
    }

    /// <summary>Uploads one in-memory file (a screenshot, say) to a device or the hub.</summary>
    public async Task UploadDataAsync(string to, string name, byte[] data, string mime = "application/octet-stream",
        CancellationToken ct = default)
    {
        using var form = new MultipartFormDataContent();
        var part = new ByteArrayContent(data);
        part.Headers.ContentType = new MediaTypeHeaderValue(mime);
        form.Add(part, "files", name);
        var saved = await PostUploadAsync(to, form, ct).ConfigureAwait(false);
        if (saved.Count == 0)
        {
            throw new HubException("the hub didn't save the file");
        }
    }

    async Task<List<string>> PostUploadAsync(string to, MultipartFormDataContent form, CancellationToken ct)
    {
        try
        {
            using var resp = await SendAsync(transfer, NewRequest(HttpMethod.Post, "/upload?to=" + Uri.EscapeDataString(to), form), ct)
                .ConfigureAwait(false);
            var o = await ReadJsonAsync<JsonObject>(resp, ct).ConfigureAwait(false);
            return Common.Json.Strings(o, "saved") ?? [];
        }
        catch (HubNotFoundException)
        {
            throw new HubException($"no device \"{to}\" on the hub");
        }
        catch (HubStatusException e) when (e.Status == 413)
        {
            throw new HubException("too big for this hub (see DROPLET_MAX_MB on the hub)");
        }
    }

    // --- ringing ----------------------------------------------------------------------

    /// <summary>The ring aimed at this device, or null. <see cref="RingUnsupportedException"/> for a hub without ringing.</summary>
    public async Task<Ring?> ActiveRingAsync(CancellationToken ct = default)
    {
        try
        {
            var o = await GetJsonAsync<JsonObject>("/api/ring", ct).ConfigureAwait(false);
            return o["ring"] is JsonObject r ? r.Deserialize<Ring>() : null;
        }
        catch (HubNotFoundException)
        {
            throw new RingUnsupportedException();
        }
    }

    /// <summary>Silences the ring aimed at this device.</summary>
    public async Task StopRingAsync(CancellationToken ct = default)
    {
        try
        {
            await PostAsync("/api/ring/stop", null, ct).ConfigureAwait(false);
        }
        catch (HubNotFoundException)
        {
            throw new RingUnsupportedException();
        }
    }

    /// <summary>Rings another device (by id), or the hub when <paramref name="target"/> is "hub".</summary>
    public async Task RingDeviceAsync(string target, CancellationToken ct = default)
    {
        // a 404 here can mean "no ringing" or "no such device": ask which
        await ActiveRingAsync(ct).ConfigureAwait(false);
        var path = target == "hub" ? "/api/hub/ring" : "/api/device/" + Uri.EscapeDataString(target) + "/ring";
        try
        {
            await PostAsync(path, null, ct).ConfigureAwait(false);
        }
        catch (HubNotFoundException)
        {
            throw new HubException($"no device \"{target}\" on the hub");
        }
    }

    /// <summary>Stops a ring this device started on another device (or the hub).</summary>
    public async Task StopRingDeviceAsync(string target, CancellationToken ct = default)
    {
        var path = target == "hub" ? "/api/hub/ring/stop" : "/api/device/" + Uri.EscapeDataString(target) + "/ring/stop";
        try
        {
            await PostAsync(path, null, ct).ConfigureAwait(false);
        }
        catch (HubNotFoundException)
        {
            throw new RingUnsupportedException();
        }
    }

    // --- the mesh roster (docs/mesh.md §9.6) ----------------------------------------------

    /// <summary><c>POST /api/mesh/announce</c>: this device's mesh identity. Returns whether it changed.</summary>
    public async Task<bool> MeshAnnounceAsync(JsonObject body, CancellationToken ct = default)
    {
        var o = await PostJsonAsync<JsonObject>("/api/mesh/announce", Body(body), ct)
            .ConfigureAwait(false);
        return Common.Json.Bool(o, "changed") == true;
    }

    /// <summary><c>GET /api/mesh/roster</c>: every other approved device's mesh identity.</summary>
    public Task<JsonObject> MeshRosterAsync(CancellationToken ct = default) => GetJsonAsync<JsonObject>("/api/mesh/roster", ct);

    /// <summary>
    /// Finds a destination by name, id or "hub" in the device list.
    /// Returns (id, display name); throws with the choices when there's no match.
    /// </summary>
    public static (string Id, string Name) Resolve(string target, IReadOnlyList<Device> devices)
    {
        ArgumentNullException.ThrowIfNull(devices);
        var t = (target ?? "").Trim();
        if (t.Equals("hub", StringComparison.OrdinalIgnoreCase))
        {
            return ("hub", "the hub");
        }
        if (devices.FirstOrDefault(d => d.Id == t) is { } byId)
        {
            return (byId.Id, byId.Name);
        }
        if (devices.FirstOrDefault(d => string.Equals(d.Name, t, StringComparison.OrdinalIgnoreCase)) is { } byName)
        {
            return (byName.Id, byName.Name);
        }
        var names = devices.Where(d => !d.Self).Select(d => d.Name).ToList();
        throw new KeyNotFoundException(names.Count == 0
            ? $"no device called \"{t}\" (the hub has no other devices; use \"hub\")"
            : $"no device called \"{t}\"; try one of: hub, {string.Join(", ", names)}");
    }

    /// <inheritdoc/>
    public void Dispose()
    {
        api.Dispose();
        transfer.Dispose();
        if (ownsHandler)
        {
            handler.Dispose();
        }
    }

    sealed class ProgressCounter(long total, TransferProgress? progress)
    {
        long done;

        public void Add(int n)
        {
            var now = Interlocked.Add(ref done, n);
            progress?.Invoke(now, total);
        }
    }

    /// <summary>Reports bytes as they're read, for upload progress.</summary>
    sealed class CountingStream(Stream inner, ProgressCounter counter) : Stream
    {
        public override bool CanRead => true;
        public override bool CanSeek => inner.CanSeek;
        public override bool CanWrite => false;
        public override long Length => inner.Length;

        public override long Position
        {
            get => inner.Position;
            set => inner.Position = value;
        }

        public override int Read(byte[] buffer, int offset, int count)
        {
            var n = inner.Read(buffer, offset, count);
            counter.Add(n);
            return n;
        }

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            var n = await inner.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
            counter.Add(n);
            return n;
        }

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
            ReadAsync(buffer.AsMemory(offset, count), cancellationToken).AsTask();

        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => inner.Seek(offset, origin);
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
