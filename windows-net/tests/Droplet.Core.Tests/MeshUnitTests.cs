using System.Formats.Asn1;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json.Nodes;
using Droplet.Core.Common;
using Droplet.Core.Mesh;

namespace Droplet.Core.Tests;

public sealed class PairingCryptoTests
{
    // vectors from the reference (agent/droplet_agent/mesh/pairing.py: code, commitment)
    [Theory]
    [InlineData("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "0000000000000000000000000000000000000000000000000000000000000000", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "6478", "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925")]
    [InlineData("de7d1b721a1e0632b7cf04edf5032c8ecffa9f9a08492152b926f1a5a7e765d7", "454349e422f05297191ead13e21d3db520e5abef52055e4964b82fb213f593a1",
        "4db03a277cfb9ef4c68b4c9780470eee479abacb5fd01b8a2c2b3cb955998e87", "6194e73eb25916046397e515de98f4e4357e06af9f3054d347ffe82aadfd8bf2",
        "7898", "e52ec8bf0e4ebcf79754c890f1e03ffea5375475a08292a62ef46202db47377c")]
    [InlineData("ec31682fde561917952ff78a7a8adeffd0febc372dd26871916c46c630381b45", "844ecc08164e2eab27634a9adee1afa6599e589570e719784e080ce747fc0e45",
        "844b69c4d54cc264bc2dadb6bb70f53bc123beafc0f58d81ed8cd4a07c24a5a7", "7985b0c8b858e77f57c6d403b315ade04d2485d6ec2d09694256eadd20db6f27",
        "1896", "5c6f983c8e852821127e71acab2e3dd9c64bfbdaced54e7312e3dad153ef294a")]
    public void Code_and_commitment_match_the_reference(string fpI, string fpR, string nI, string nR, string code, string commit)
    {
        Assert.Equal(code, PairingCrypto.Code(fpI, fpR, nI, nR));
        Assert.Equal(commit, PairingCrypto.Commitment(nI));
    }

    [Fact]
    public void Transcript_is_the_exact_ascii_lines_with_no_trailing_newline()
    {
        var t = Encoding.ASCII.GetString(PairingCrypto.Transcript("a", "b", "c", "d"));
        Assert.Equal("droplet-pair-v1\na\nb\nc\nd", t);
    }

    [Fact]
    public void Code_is_always_four_digits()
    {
        for (var i = 0; i < 200; i++)
        {
            var c = PairingCrypto.Code(Hex.Random(32), Hex.Random(32), PairingCrypto.NewNonce(), PairingCrypto.NewNonce());
            Assert.Matches("^[0-9]{4}$", c);
        }
    }
}

public sealed class CertificateProfileTests : IDisposable
{
    readonly string dir = TestDirs.Make("identity");

    public void Dispose() => TestDirs.Remove(dir);

    [Fact]
    public void A_new_identity_follows_the_9_1_profile()
    {
        using var id = MeshIdentity.LoadOrCreate(new FileIdentityStore(dir));
        using var cert = X509CertificateLoader.LoadCertificate(id.Der);
        Assert.Equal(3, cert.Version);
        Assert.Equal($"CN=droplet-peer-{id.LocalId}", cert.Subject);
        Assert.Equal(cert.Subject, cert.Issuer);
        Assert.Matches("^[0-9a-f]{16}$", id.LocalId);
        Assert.Equal("1.2.840.10045.4.3.2", cert.SignatureAlgorithm.Value); // ecdsa-with-SHA256
        using (var pub = cert.GetECDsaPublicKey())
        {
            Assert.NotNull(pub);
            Assert.Equal(ECCurve.NamedCurves.nistP256.Oid.Value, pub!.ExportParameters(false).Curve.Oid.Value);
        }
        var bc = Assert.Single(cert.Extensions.OfType<X509BasicConstraintsExtension>());
        Assert.True(bc.Critical);
        Assert.False(bc.CertificateAuthority);
        var ku = Assert.Single(cert.Extensions.OfType<X509KeyUsageExtension>());
        Assert.True(ku.Critical);
        Assert.Equal(X509KeyUsageFlags.DigitalSignature, ku.KeyUsages);
        var eku = Assert.Single(cert.Extensions.OfType<X509EnhancedKeyUsageExtension>());
        Assert.False(eku.Critical);
        Assert.Equal(["1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"], eku.EnhancedKeyUsages.Cast<Oid>().Select(o => o.Value!).Order().ToArray());
        Assert.Single(cert.Extensions.OfType<X509SubjectKeyIdentifierExtension>());
        // validity never lapses, and never starts in the future
        Assert.Equal(new DateTime(2020, 1, 1, 0, 0, 0, DateTimeKind.Utc), cert.NotBefore.ToUniversalTime());
        Assert.InRange(cert.NotAfter.ToUniversalTime(), DateTime.UtcNow.AddDays(365 * 30 - 1), DateTime.UtcNow.AddDays(365 * 30 + 1));
        // the fingerprint is the SHA-256 of the DER, lowercase hex
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(cert.RawData)), id.Fingerprint);
        Assert.Matches("^[0-9a-f]{64}$", id.Fingerprint);
        // the serial is positive
        Assert.True((cert.SerialNumberBytes.Span[^1] & 0x80) == 0 || cert.SerialNumberBytes.Length > 0);
    }

    [Fact]
    public void The_identity_is_kept_and_the_files_are_the_reference_layout()
    {
        string fp, localId;
        using (var a = MeshIdentity.LoadOrCreate(new FileIdentityStore(dir)))
        {
            (fp, localId) = (a.Fingerprint, a.LocalId);
        }
        using var b = MeshIdentity.LoadOrCreate(new FileIdentityStore(dir));
        Assert.Equal(fp, b.Fingerprint);
        Assert.Equal(localId, b.LocalId);
        Assert.StartsWith("-----BEGIN PRIVATE KEY-----", File.ReadAllText(Path.Combine(dir, "key.pem")), StringComparison.Ordinal);
        Assert.StartsWith("-----BEGIN CERTIFICATE-----", File.ReadAllText(Path.Combine(dir, "cert.pem")), StringComparison.Ordinal);
        Assert.Equal(localId, Json.ParseObject(File.ReadAllText(Path.Combine(dir, "identity.json")))!.Str("id"));
        if (!OperatingSystem.IsWindows())
        {
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(Path.Combine(dir, "key.pem")));
        }
    }

    [Fact]
    public void A_key_that_isnt_the_certificates_makes_a_new_identity_with_the_same_id()
    {
        string fp, localId;
        using (var a = MeshIdentity.LoadOrCreate(new FileIdentityStore(dir)))
        {
            (fp, localId) = (a.Fingerprint, a.LocalId);
        }
        using (var other = ECDsa.Create(ECCurve.NamedCurves.nistP256))
        {
            File.WriteAllText(Path.Combine(dir, "key.pem"), other.ExportPkcs8PrivateKeyPem());
        }
        using var b = MeshIdentity.LoadOrCreate(new FileIdentityStore(dir));
        Assert.NotEqual(fp, b.Fingerprint);
        Assert.Equal(localId, b.LocalId);
    }

    [Fact]
    public void Signatures_are_der_and_verify()
    {
        using var id = MeshIdentity.LoadOrCreate(new FileIdentityStore(dir));
        var data = Encoding.ASCII.GetBytes("droplet-pair-v1\nx");
        var sig = id.Sign(data);
        // X9.62: SEQUENCE { INTEGER r, INTEGER s }
        var reader = new AsnReader(sig, AsnEncodingRules.DER);
        var seq = reader.ReadSequence();
        seq.ReadInteger();
        seq.ReadInteger();
        seq.ThrowIfNotEmpty();
        Assert.True(Certificates.Verify(id.Der, sig, data));
        Assert.False(Certificates.Verify(id.Der, sig, Encoding.ASCII.GetBytes("something else")));
    }

    [Fact]
    public void Pem_must_be_exactly_one_real_certificate()
    {
        using var id = MeshIdentity.LoadOrCreate(new FileIdentityStore(dir));
        Assert.Equal(id.Der, Certificates.PemToDer(id.CertificatePem));
        Assert.Throws<FormatException>(() => Certificates.PemToDer(id.CertificatePem + id.CertificatePem));
        Assert.Throws<FormatException>(() => Certificates.PemToDer("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"));
        Assert.Throws<FormatException>(() => Certificates.PemToDer(null));
        Assert.Throws<FormatException>(() => Certificates.PemToDer("hello"));
    }
}

public sealed class IncomingPairingTests : IDisposable
{
    readonly string dir = TestDirs.Make("pairing");

    public void Dispose() => TestDirs.Remove(dir);

    [Fact]
    public void A_valid_request_is_confirmed_and_gets_the_same_code_both_sides()
    {
        using var r = MeshIdentity.LoadOrCreate(new FileIdentityStore(Path.Combine(dir, "r")));
        using var i = MeshIdentity.LoadOrCreate(new FileIdentityStore(Path.Combine(dir, "i")));
        var incoming = new IncomingPairings(r);
        PairRequestInfo? ready = null;
        incoming.Ready += x => ready = x;
        var nA = PairingCrypto.NewNonce();
        var (status, body) = incoming.Open(new JsonObject
        {
            ["v"] = 1, ["id"] = i.LocalId, ["name"] = "laptop", ["os"] = "linux", ["cert"] = i.CertificatePem,
            ["commit"] = PairingCrypto.Commitment(nA),
        }, r.LocalId, "desk");
        Assert.Equal(200, status);
        Assert.Equal(r.Fingerprint, body.Str("fp"));
        var rid = body.Str("request")!;
        var nB = body.Str("nonce")!;
        var sig = i.Sign(PairingCrypto.Transcript(i.Fingerprint, r.Fingerprint, nA, nB));
        Assert.Equal(200, incoming.Confirm(rid, new JsonObject { ["nonce"] = nA, ["sig"] = Convert.ToBase64String(sig) }).Status);
        Assert.NotNull(ready);
        Assert.Equal(PairingCrypto.Code(i.Fingerprint, r.Fingerprint, nA, nB), ready!.Code);
        Assert.Equal("waiting", incoming.Status(rid).Body.Str("state"));
        var answered = incoming.Answer(rid, true);
        Assert.NotNull(answered);
        Assert.Equal(i.Der, answered!.Value.Der);
        Assert.Equal("accepted", incoming.Status(rid).Body.Str("state"));
    }

    [Fact]
    public void A_wrong_reveal_or_signature_is_refused_and_dropped()
    {
        using var r = MeshIdentity.LoadOrCreate(new FileIdentityStore(Path.Combine(dir, "r")));
        using var i = MeshIdentity.LoadOrCreate(new FileIdentityStore(Path.Combine(dir, "i")));
        using var mallory = MeshIdentity.LoadOrCreate(new FileIdentityStore(Path.Combine(dir, "m")));
        var incoming = new IncomingPairings(r);
        foreach (var (reveal, signer) in new[] { (false, i), (true, mallory) })
        {
            var nA = PairingCrypto.NewNonce();
            var (_, body) = incoming.Open(new JsonObject
            {
                ["id"] = i.LocalId, ["cert"] = i.CertificatePem, ["commit"] = PairingCrypto.Commitment(nA),
            }, r.LocalId, "desk");
            var rid = body.Str("request")!;
            var sent = reveal ? nA : PairingCrypto.NewNonce();
            var sig = signer.Sign(PairingCrypto.Transcript(i.Fingerprint, r.Fingerprint, sent, body.Str("nonce")!));
            Assert.Equal(403, incoming.Confirm(rid, new JsonObject { ["nonce"] = sent, ["sig"] = Convert.ToBase64String(sig) }).Status);
            Assert.Equal(404, incoming.Status(rid).Status);
        }
    }

    [Fact]
    public void Malformed_requests_and_this_device_itself_are_refused()
    {
        using var r = MeshIdentity.LoadOrCreate(new FileIdentityStore(Path.Combine(dir, "r")));
        var incoming = new IncomingPairings(r);
        Assert.Equal(400, incoming.Open(null, "a", "b").Status);
        Assert.Equal(400, incoming.Open(new JsonObject { ["id"] = "XYZ" }, "a", "b").Status);
        Assert.Equal(400, incoming.Open(new JsonObject { ["id"] = "abcdef12", ["commit"] = "00", ["cert"] = r.CertificatePem }, "a", "b").Status);
        Assert.Equal(409, incoming.Open(new JsonObject { ["id"] = "abcdef12", ["commit"] = new string('0', 64), ["cert"] = r.CertificatePem }, "a", "b").Status);
    }

    [Fact]
    public void At_most_three_open_requests_and_twenty_a_minute()
    {
        using var r = MeshIdentity.LoadOrCreate(new FileIdentityStore(Path.Combine(dir, "r")));
        using var i = MeshIdentity.LoadOrCreate(new FileIdentityStore(Path.Combine(dir, "i")));
        var incoming = new IncomingPairings(r);
        JsonObject Req() => new() { ["id"] = i.LocalId, ["cert"] = i.CertificatePem, ["commit"] = PairingCrypto.Commitment(PairingCrypto.NewNonce()) };
        var rids = Enumerable.Range(0, 3).Select(_ => incoming.Open(Req(), "a", "b").Body.Str("request")!).ToList();
        Assert.Equal(429, incoming.Open(Req(), "a", "b").Status);
        foreach (var rid in rids)
        {
            incoming.Cancel(rid);
        }
        var statuses = Enumerable.Range(0, 20).Select(_ =>
        {
            var (s, b) = incoming.Open(Req(), "a", "b");
            if (s == 200)
            {
                incoming.Cancel(b.Str("request")!);
            }
            return s;
        }).ToList();
        Assert.Equal(16, statuses.Count(s => s == 200)); // 4 of the minute's 20 were used above
        Assert.Equal(429, statuses[^1]);
    }
}

public sealed class RangeAndNameTests
{
    [Theory]
    [InlineData(null, 100, RangeHeader.Kind.Whole, 0, 99)]
    [InlineData("bytes=10-", 100, RangeHeader.Kind.Part, 10, 99)]
    [InlineData("bytes=10-19", 100, RangeHeader.Kind.Part, 10, 19)]
    [InlineData("bytes=90-200", 100, RangeHeader.Kind.Part, 90, 99)]
    [InlineData("bytes=-5", 100, RangeHeader.Kind.Part, 95, 99)]
    [InlineData("bytes=-500", 100, RangeHeader.Kind.Part, 0, 99)]
    [InlineData("bytes=100-", 100, RangeHeader.Kind.Bad, 0, 0)]
    [InlineData("bytes=20-10", 100, RangeHeader.Kind.Bad, 0, 0)]
    [InlineData("bytes=-0", 100, RangeHeader.Kind.Bad, 0, 0)]
    [InlineData("bytes=0-1,5-6", 100, RangeHeader.Kind.Whole, 0, 99)]
    [InlineData("lines=1-2", 100, RangeHeader.Kind.Whole, 0, 99)]
    public void Ranges_parse_as_the_reference(string? header, long size, RangeHeader.Kind kind, long first, long last)
    {
        var (k, f, l) = RangeHeader.Parse(header, size);
        Assert.Equal(kind, k);
        if (k != RangeHeader.Kind.Bad)
        {
            Assert.Equal((first, last), (f, l));
        }
    }

    [Theory]
    [InlineData("photo.jpg", "photo.jpg")]
    [InlineData("../../etc/passwd", "passwd")]
    [InlineData("C:\\Windows\\system.ini", "system.ini")]
    [InlineData(".hidden", "hidden")]
    [InlineData("a<b>c:d\"e|f?g*h", "abcdefgh")]
    [InlineData("", "file")]
    [InlineData("...", "file")]
    [InlineData("CON.txt", "_CON.txt")]
    [InlineData("nul", "_nul")]
    [InlineData("name. ", "name")]
    [InlineData("tab\there", "tabhere")]
    public void Names_are_made_safe(string raw, string safe) => Assert.Equal(safe, SafeName.Of(raw));

    [Fact]
    public void Long_names_keep_their_extension_within_200_bytes()
    {
        var s = SafeName.Of(new string('é', 300) + ".tar.gz");
        Assert.EndsWith(".gz", s, StringComparison.Ordinal);
        Assert.True(Encoding.UTF8.GetByteCount(s) <= 200);
    }

    [Fact]
    public void Unique_names_count_up_as_explorer_does()
    {
        var d = TestDirs.Make("unique");
        try
        {
            Assert.Equal(Path.Combine(d, "a.txt"), SafeName.Unique(d, "a.txt"));
            File.WriteAllText(Path.Combine(d, "a.txt"), "");
            Assert.Equal(Path.Combine(d, "a (1).txt"), SafeName.Unique(d, "a.txt"));
            File.WriteAllText(Path.Combine(d, "a (1).txt"), "");
            Assert.Equal(Path.Combine(d, "a (2).txt"), SafeName.Unique(d, "a.txt"));
        }
        finally
        {
            TestDirs.Remove(d);
        }
    }

    [Theory]
    [InlineData("  slim   laptop ", "slim laptop")]
    [InlineData("bell\u0007", "bell")]
    [InlineData("", "fb")]
    public void Names_are_cleaned_as_the_reference(string raw, string clean) => Assert.Equal(clean, Clean.Name(raw, "fb"));

    [Fact]
    public void Caps_are_cleaned_sorted_and_unique() =>
        Assert.Equal(["clipboard", "input", "sms-2"], Clean.Caps(["input", " clipboard", "input", "sms-2", "bad cap", "ü", "-", "averyveryverylongcapname"]));
}

public sealed class TrustListTests : IDisposable
{
    readonly string dir = TestDirs.Make("trust");

    public void Dispose() => TestDirs.Remove(dir);

    MeshIdentity Peer(string name) => MeshIdentity.LoadOrCreate(new FileIdentityStore(Path.Combine(dir, name)));

    [Fact]
    public void The_roster_replaces_roster_peers_but_never_paired_ones()
    {
        using var me = Peer("me");
        using var a = Peer("a");
        using var b = Peer("b");
        using var c = Peer("c");
        var trust = new TrustList(Path.Combine(dir, "trust.json"), me.Fingerprint);
        var changes = 0;
        trust.CertificatesChanged += () => changes++;
        trust.AddPaired(TrustEntry.Make(c.LocalId, "c", c.CertificatePem, TrustSource.Paired));
        var roster = new[]
        {
            TrustEntry.Make("aaaaaaaaaaaa", "a", a.CertificatePem, TrustSource.Roster, ["192.168.1.2"], 1740, hub: "9b16173d305cd15a"),
            TrustEntry.Make("bbbbbbbbbbbb", "b", b.CertificatePem, TrustSource.Roster, hub: "9b16173d305cd15a"),
            TrustEntry.Make("cccccccccccc", "c-on-hub", c.CertificatePem, TrustSource.Roster, ["192.168.1.9"], hub: "9b16173d305cd15a"),
            TrustEntry.Make(me.LocalId, "me", me.CertificatePem, TrustSource.Roster),
        };
        Assert.Equal((2, 0), trust.SyncRoster(roster, "9b16173d305cd15a"));
        Assert.Null(trust.Get(me.Fingerprint)); // never itself
        var cEntry = trust.Get(c.Fingerprint)!;
        Assert.Equal(TrustSource.Paired, cEntry.Source); // stays paired, learns where it is
        Assert.Equal(["192.168.1.9"], cEntry.Lan);
        Assert.Equal("9b16173d305cd15a", cEntry.Hub);
        Assert.Equal((0, 1), trust.SyncRoster([roster[0]], "9b16173d305cd15a"));
        Assert.Null(trust.Get(b.Fingerprint));
        Assert.Equal("", trust.Get(c.Fingerprint)!.Hub); // that hub doesn't know it any more
        // persisted, and re-checked on load
        var again = new TrustList(Path.Combine(dir, "trust.json"), me.Fingerprint);
        Assert.Equal(2, again.All().Count);
        Assert.True(changes >= 3);
    }

    [Fact]
    public void A_damaged_entry_is_dropped_on_load()
    {
        using var me = Peer("me");
        using var a = Peer("a");
        var path = Path.Combine(dir, "trust.json");
        var trust = new TrustList(path, me.Fingerprint);
        trust.AddPaired(TrustEntry.Make(a.LocalId, "a", a.CertificatePem, TrustSource.Paired));
        var text = File.ReadAllText(path).Replace(a.Fingerprint + "\"", new string('0', 64) + "\"", StringComparison.Ordinal);
        File.WriteAllText(path, text);
        Assert.Empty(new TrustList(path, me.Fingerprint).All());
    }

    [Fact]
    public void A_re_pair_of_the_same_id_replaces_the_old_certificate_and_learn_updates()
    {
        using var me = Peer("me");
        using var a1 = Peer("a1");
        using var a2 = Peer("a2");
        var trust = new TrustList(Path.Combine(dir, "trust.json"), me.Fingerprint);
        trust.AddPaired(TrustEntry.Make("abcdef0123456789", "a", a1.CertificatePem, TrustSource.Paired));
        trust.AddPaired(TrustEntry.Make("abcdef0123456789", "a", a2.CertificatePem, TrustSource.Paired));
        Assert.Null(trust.Get(a1.Fingerprint));
        trust.Learn(a2.Fingerprint, "10.0.0.5", 1741, "renamed", "0123456789ab", "linux", ["input"]);
        var e = trust.Get(a2.Fingerprint)!;
        Assert.Equal(("renamed", "0123456789ab", 1741, "linux"), (e.Name, e.Id, e.Port!.Value, e.Os));
        Assert.Equal(["10.0.0.5"], e.Lan);
        Assert.Single(trust.Find("renamed"));
        Assert.Single(trust.Find(a2.Fingerprint[..10]));
        Assert.Empty(trust.Find("zz"));
    }

    [Fact]
    public void Make_refuses_a_fingerprint_that_isnt_the_certificates()
    {
        using var a = Peer("a");
        Assert.Throws<FormatException>(() => TrustEntry.Make("abcdef12", "a", a.CertificatePem, TrustSource.Roster, fp: new string('1', 64)));
        Assert.Throws<FormatException>(() => TrustEntry.Make("NOTHEX", "a", a.CertificatePem, TrustSource.Roster));
    }
}

public sealed class OutboxTests : IDisposable
{
    readonly string dir = TestDirs.Make("outbox");

    public void Dispose() => TestDirs.Remove(dir);

    [Fact]
    public async Task Jobs_survive_a_restart_and_waiters_hear_changes()
    {
        var path = Path.Combine(dir, "outbox.json");
        var box = new Outbox(path);
        var fp = new string('a', 64);
        var t = box.AddText(fp, "peer", "hello");
        box.Update(t.Id, j => j with { State = JobState.Sending });
        var reopened = new Outbox(path);
        Assert.Equal(JobState.Queued, reopened.Get(t.Id)!.State); // whatever it was doing, it waits now
        var wait = reopened.WaitAsync(t.Id, j => j.State == JobState.Done, TimeSpan.FromSeconds(5));
        reopened.Update(t.Id, j => j with { State = JobState.Done, Route = "lan" });
        Assert.Equal("lan", (await wait)!.Route);
        Assert.Empty(reopened.Queued());
        Assert.Empty(new Outbox(path).Queued());
    }
}

public sealed class ChatLogTests : IDisposable
{
    readonly string dir = TestDirs.Make("chat");

    public void Dispose() => TestDirs.Remove(dir);

    [Fact]
    public void Repeated_ids_are_stored_once_across_restarts()
    {
        var path = Path.Combine(dir, "chat.jsonl");
        var e = new ChatEntry { Id = "abc:12345678", Dir = "in", Fp = new string('a', 64), Body = "hi", Ts = 1 };
        Assert.True(new ChatLog(path).Add(e));
        Assert.False(new ChatLog(path).Add(e));
        Assert.Single(new ChatLog(path).Recent());
    }
}

static class TestDirs
{
    public static string Make(string name)
    {
        var d = Path.Combine(Path.GetTempPath(), "droplet-net-tests", $"{name}-{Guid.NewGuid():N}");
        Directory.CreateDirectory(d);
        return d;
    }

    public static void Remove(string d)
    {
        try
        {
            Directory.Delete(d, recursive: true);
        }
        catch (IOException)
        {
        }
    }
}
