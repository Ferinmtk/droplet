using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json.Nodes;
using Droplet.Core.Common;
using Droplet.Core.Mesh;
using Droplet.Core.Tests.Support;

namespace Droplet.Core.Tests.Interop;

/// <summary>
/// The .NET peer against the reference, the Linux agent, run as a real process
/// (<c>droplet-agent run --dry-run</c>) with a throwaway HOME, over this machine's LAN
/// address, both ways. Run one at a time: they share the machine's mesh ports and mDNS.
/// </summary>
[Collection(nameof(InteropCollection))]
public sealed class MeshInteropTests : IAsyncLifetime
{
    string root = "";

    public ValueTask InitializeAsync()
    {
        Assert.SkipWhen(Reference.Unavailable is not null, Reference.Unavailable ?? "");
        root = TestDirs.Make("interop");
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync()
    {
        TestDirs.Remove(root);
        return ValueTask.CompletedTask;
    }

    static string LanIp => Addresses.Lan().First(a => !a.Contains(':', StringComparison.Ordinal));

    /// <summary>Pairs the .NET peer with the agent, started from the .NET side. Returns the code both showed.</summary>
    internal static async Task<string> PairFromDotNetAsync(DotNetPeer n, LinuxAgent a)
    {
        var aId = (await a.StatusAsync()).Str("id")!;
        await Wait.For(() => n.Node.Nearby.Any(s => s.Id == aId), 30, "the agent to be seen over mDNS");
        var start = await n.Node.PairStartAsync(aId);
        JsonObject? request = null;
        await Wait.For(async () => (request = (await a.StatusAsync())["incoming"]?.AsArray().OfType<JsonObject>().FirstOrDefault()) is not null,
            20, "the agent to show the request");
        var req = request!;
        Assert.Equal(start.Code, req.Str("code"));
        Assert.Equal(n.Node.Identity.Fingerprint, req.Str("fp"));
        var answer = await a.CallAsync(new JsonObject { ["cmd"] = "pair-answer", ["request"] = req.Str("request"), ["accept"] = true });
        Assert.Equal("accepted", answer.Str("state"));
        await n.Node.PairConfirmAsync(start.Request, true);
        await Wait.For(() => n.Node.PairStatus(start.Request) == PairState.Accepted, 20, "the .NET side to see it accepted");
        Assert.Equal(TrustSource.Paired, n.Node.Trust.Get(a.Fingerprint)?.Source);
        Assert.Equal("paired", a.Trust()[n.Node.Identity.Fingerprint] is JsonObject peer ? peer.Str("source") : null);
        return start.Code;
    }

    [Fact]
    public async Task Pairing_both_ways_with_matching_codes_and_only_trusted_certificates_get_through()
    {
        await using var a = new LinuxAgent("alpha");
        await a.StartAsync();
        await using var n = await DotNetPeer.StartAsync(root, "dotnet");
        var aStatus = await a.StatusAsync();
        var aPort = (int)aStatus.Int("port")!;

        // mDNS both ways
        await Wait.For(() => n.Node.Nearby.Any(s => s.Fp == a.Fingerprint), 30, "the .NET peer to see the agent");
        await Wait.For(async () => (await a.StatusAsync())["nearby"]!.AsArray().OfType<JsonObject>().Any(p => p.Str("fp") == n.Node.Identity.Fingerprint),
            40, "the agent to see the .NET peer");

        // before pairing: the agent refuses our certificate in the handshake, and we refuse its
        Assert.False(await Tls.GetsHttpAnswerAsync(LanIp, aPort, n.Node.Identity.TlsCertificate, "/mesh/files/" + new string('0', 32)));
        var python = await Tls.PythonRequestAsync(a, LanIp, n.Node.Port, withCertificate: true, "/mesh/files/" + new string('0', 32));
        Assert.Contains("refused", python, StringComparison.Ordinal);
        Assert.True(n.Node.Refused >= 1);
        // a client with no certificate reaches only pairing, on both
        foreach (var port in new[] { aPort, n.Node.Port })
        {
            Assert.Equal(403, await Tls.StatusAsync(LanIp, port, null, "GET", "/mesh"));
            Assert.Equal(403, await Tls.StatusAsync(LanIp, port, null, "GET", "/mesh/files/" + new string('0', 32)));
            Assert.Equal(400, await Tls.StatusAsync(LanIp, port, null, "POST", "/mesh/pair", "{}"));
        }
        Assert.Equal("403", (await Tls.PythonRequestAsync(a, LanIp, n.Node.Port, withCertificate: false, "/mesh")).Trim());

        // .NET asks, the agent accepts: the same code on both
        await PairFromDotNetAsync(n, a);
        // mutual TLS works both ways now
        Assert.True(await Tls.GetsHttpAnswerAsync(LanIp, aPort, n.Node.Identity.TlsCertificate, "/mesh/files/" + new string('0', 32)));
        Assert.Equal("404", (await Tls.PythonRequestAsync(a, LanIp, n.Node.Port, withCertificate: true, "/mesh/files/" + new string('0', 32))).Trim());

        // unpairing tells the agent, which drops us too
        Assert.True(await n.Node.UnpairAsync(a.Fingerprint));
        await Wait.For(() => a.Trust()[n.Node.Identity.Fingerprint] is null, 10, "the agent to drop the .NET peer");
        Assert.Null(n.Node.Trust.Get(a.Fingerprint));

        // the agent asks, .NET accepts: the same code on both
        PairRequestInfo? seen = null;
        n.Node.PairingRequested += r => seen = r;
        var start = await a.CallAsync(new JsonObject { ["cmd"] = "pair-start", ["target"] = $"{LanIp}:{n.Node.Port}" });
        Assert.Null(start.Str("error"));
        await Wait.For(() => seen is not null, 10, "the .NET peer to show the request");
        Assert.Equal(start.Str("code"), seen!.Code);
        Assert.Equal(a.Fingerprint, seen.Fp);
        Assert.Equal(seen.Code, Assert.Single(n.Node.Incoming.Waiting()).Code);
        n.Node.PairAnswer(seen.Request, true);
        await a.CallAsync(new JsonObject { ["cmd"] = "pair-confirm", ["request"] = start.Str("request"), ["yes"] = true });
        await Wait.For(async () => (await a.CallAsync(new JsonObject { ["cmd"] = "pair-status", ["request"] = start.Str("request") })).Str("state") == "accepted",
            20, "the agent to see it accepted");
        Assert.Equal(TrustSource.Paired, n.Node.Trust.Get(a.Fingerprint)?.Source);
        Assert.Equal("paired", a.Trust()[n.Node.Identity.Fingerprint] is JsonObject peer ? peer.Str("source") : null);

        // a stranger's certificate is still refused by both
        using var stranger = MeshIdentity.LoadOrCreate(new FileIdentityStore(Path.Combine(root, "stranger")));
        Assert.False(await Tls.GetsHttpAnswerAsync(LanIp, aPort, stranger.TlsCertificate, "/mesh/files/" + new string('0', 32)));
        Assert.False(await Tls.GetsHttpAnswerAsync(LanIp, n.Node.Port, stranger.TlsCertificate, "/mesh/files/" + new string('0', 32)));
    }

    [Fact]
    public async Task A_denied_request_trusts_nobody()
    {
        await using var a = new LinuxAgent("alpha");
        await a.StartAsync();
        await using var n = await DotNetPeer.StartAsync(root, "dotnet");
        var start = await n.Node.PairStartAsync($"{LanIp}:{(await a.StatusAsync()).Int("port")}");
        JsonObject? request = null;
        await Wait.For(async () => (request = (await a.StatusAsync())["incoming"]?.AsArray().OfType<JsonObject>().FirstOrDefault()) is not null, 20);
        var req = request!;
        Assert.Equal(start.Code, req.Str("code"));
        await a.CallAsync(new JsonObject { ["cmd"] = "pair-answer", ["request"] = req.Str("request"), ["accept"] = false });
        await n.Node.PairConfirmAsync(start.Request, true);
        await Wait.For(() => n.Node.PairStatus(start.Request) == PairState.Denied, 20, "the .NET side to see it denied");
        Assert.Null(n.Node.Trust.Get(a.Fingerprint));
        Assert.Null(a.Trust()[n.Node.Identity.Fingerprint]);
    }
}

[CollectionDefinition(nameof(InteropCollection), DisableParallelization = true)]
public sealed class InteropCollection;

/// <summary>Raw TLS requests, to check who the mesh ports let through.</summary>
static class Tls
{
    /// <summary>
    /// Whether a request presenting <paramref name="cert"/> gets any HTTP answer. A refused
    /// certificate fails the handshake; with TLS 1.3 the client sees that on its first read.
    /// </summary>
    public static async Task<bool> GetsHttpAnswerAsync(string host, int port, X509Certificate2? cert, string path)
    {
        try
        {
            return (await RawAsync(host, port, cert, "GET", path, null)).StartsWith("HTTP/1.1", StringComparison.Ordinal);
        }
        catch (Exception e) when (e is AuthenticationException or IOException or SocketException)
        {
            return false;
        }
    }

    /// <summary>The HTTP status of one request, presenting <paramref name="cert"/> (null: none).</summary>
    public static async Task<int> StatusAsync(string host, int port, X509Certificate2? cert, string method, string path, string? body = null)
    {
        var text = await RawAsync(host, port, cert, method, path, body);
        return int.Parse(text.Split(' ', 3)[1], System.Globalization.CultureInfo.InvariantCulture);
    }

    static async Task<string> RawAsync(string host, int port, X509Certificate2? cert, string method, string path, string? body)
    {
        using var tcp = new TcpClient();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        await tcp.ConnectAsync(host, port, cts.Token);
        await using var ssl = new SslStream(tcp.GetStream());
        await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = "",
            EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
            RemoteCertificateValidationCallback = (_, _, _, _) => true,
            LocalCertificateSelectionCallback = cert is null ? null : (_, _, _, _, _) => cert,
            ClientCertificates = cert is null ? null : [cert],
        }, cts.Token);
        var data = Encoding.UTF8.GetBytes(body ?? "");
        await ssl.WriteAsync(Encoding.ASCII.GetBytes($"{method} {path} HTTP/1.1\r\nHost: x\r\nContent-Length: {data.Length}\r\nConnection: close\r\n\r\n"), cts.Token);
        await ssl.WriteAsync(data, cts.Token);
        using var ms = new MemoryStream();
        await ssl.CopyToAsync(ms, cts.Token);
        return Encoding.UTF8.GetString(ms.ToArray());
    }

    /// <summary>
    /// One request to a mesh port from the reference's own TLS client (its
    /// <c>tlsctx.client_context</c>), presenting the agent's certificate or none. Prints the
    /// HTTP status, or "refused" when the TLS handshake (or first read) fails.
    /// </summary>
    public static async Task<string> PythonRequestAsync(LinuxAgent a, string host, int port, bool withCertificate, string path)
    {
        var script = $$"""
            import socket, sys
            from pathlib import Path
            from droplet_agent.mesh import identity, tlsctx
            ident = identity.load_or_create(Path({{Py(a.MeshConfig)}})) if {{(withCertificate ? "True" : "False")}} else None
            try:
                s = tlsctx.client_context(ident, None).wrap_socket(socket.create_connection(({{Py(host)}}, {{port}}), timeout=5))
                s.sendall(b"GET {{path}} HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                out = b""
                while True:
                    chunk = s.recv(65536)
                    if not chunk:
                        break
                    out += chunk
                print(int(out.split(b" ", 2)[1]))
            except Exception as e:
                print("refused", type(e).__name__, e)
            """;
        var psi = new System.Diagnostics.ProcessStartInfo(Reference.Python!)
        {
            WorkingDirectory = Path.Combine(Reference.RepoRoot, "agent"),
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        psi.ArgumentList.Add("-c");
        psi.ArgumentList.Add(script);
        using var p = System.Diagnostics.Process.Start(psi)!;
        var output = await p.StandardOutput.ReadToEndAsync();
        var error = await p.StandardError.ReadToEndAsync();
        await p.WaitForExitAsync();
        return output.Length > 0 ? output : "error: " + error;
    }

    static string Py(string s) => "'" + s.Replace("\\", "\\\\", StringComparison.Ordinal).Replace("'", "\\'", StringComparison.Ordinal) + "'";
}
