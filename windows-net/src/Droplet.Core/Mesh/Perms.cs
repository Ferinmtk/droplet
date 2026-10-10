using System.Text.Json.Nodes;
using Droplet.Core.Common;

namespace Droplet.Core.Mesh;

/// <summary>
/// Per-device permissions and Pause (docs/mesh.md §9.9), as the reference does them
/// (<c>agent/droplet_agent/mesh/perms.py</c>).
/// <para>
/// Each trusted peer has a <b>relation</b>, the owner's answer to "Is this your device, or
/// someone else's?", and a switch per <b>capability</b>. A peer can also be <b>paused</b>:
/// nothing goes to it and nothing from it is taken, except the few messages that keep the
/// link and its status working. A <b>global pause</b> does that for every peer at once.
/// </para>
/// <para>
/// Enforcement is local and both ways: this device checks what it sends and what it
/// accepts, whatever the peer says. What a peer says about its own switches (<c>perm</c>,
/// in its hello and as a message) is only a hint, for the UI and for not sending what it
/// would refuse anyway.
/// </para>
/// </summary>
public static class Perms
{
    /// <summary>Your own device.</summary>
    public const string Own = "own";

    /// <summary>Someone else's device.</summary>
    public const string Other = "other";

    /// <summary>"paused": the peer (or everything) is paused.</summary>
    public const string WhyPaused = "paused";

    /// <summary>"denied": the capability is switched off for the peer.</summary>
    public const string WhyDenied = "denied";

    /// <summary>Files, both ways (<c>offer</c>).</summary>
    public const string Files = "files";

    /// <summary>Messages, both ways (<c>text</c>).</summary>
    public const string Chat = "chat";

    /// <summary>The clipboard, both ways (<c>clip</c>: synced or sent on purpose).</summary>
    public const string Clipboard = "clipboard";

    /// <summary>Notifications, both ways (<c>notify</c>, <c>notify-removed</c>).</summary>
    public const string Notify = "notify";

    /// <summary>What the peer may control here: input, media, lock, screenshots, the presentation remote.</summary>
    public const string Control = "control";

    /// <summary>Whether the peer may ring this device.</summary>
    public const string Ring = "ring";

    /// <summary>SMS, files on the device and commands (<c>rpc</c> <c>files.*</c>, <c>sms.*</c>).</summary>
    public const string Access = "access";

    /// <summary>Every capability, in the reference's order.</summary>
    public static readonly IReadOnlyList<string> Capabilities = [Files, Chat, Clipboard, Notify, Control, Ring, Access];

    /// <summary>The relations.</summary>
    public static readonly IReadOnlyList<string> Relations = [Own, Other];

    /// <summary>Each capability's label, for switches.</summary>
    public static readonly IReadOnlyDictionary<string, string> Labels = new Dictionary<string, string>
    {
        [Files] = "Send and receive files", [Chat] = "Messages", [Clipboard] = "Shared clipboard", [Notify] = "Notifications",
        [Control] = "Remote control", [Ring] = "Ring", [Access] = "SMS, files on the device and commands",
    };

    /// <summary>The words in "&lt;name&gt; doesn't allow &lt;noun&gt; from you".</summary>
    public static readonly IReadOnlyDictionary<string, string> Nouns = new Dictionary<string, string>
    {
        [Files] = "files", [Chat] = "messages", [Clipboard] = "the clipboard", [Notify] = "notifications",
        [Control] = "remote control", [Ring] = "ringing", [Access] = "SMS, files and commands",
    };

    /// <summary>One line under each switch.</summary>
    public static readonly IReadOnlyDictionary<string, string> Explain = new Dictionary<string, string>
    {
        [Files] = "Files both ways.",
        [Chat] = "Messages both ways.",
        [Clipboard] = "Clipboard sync, and a clipboard sent on purpose, both ways.",
        [Notify] = "Notifications mirrored between it and this device, both ways.",
        [Control] = "It may use this device's mouse and keyboard, media, lock, screenshots and presentation remote.",
        [Ring] = "It may ring this device to find it.",
        [Access] = "It may read this device's SMS, browse its files and run commands, where it offers them.",
    };

    /// <summary>
    /// About this device only: the switch says what the peer may do here. What this device
    /// may do to the peer is the peer's own switch, which it enforces (and tells us, as a hint).
    /// </summary>
    public static readonly IReadOnlySet<string> InboundOnly = new HashSet<string>(StringComparer.Ordinal) { Control, Ring, Access };

    /// <summary>
    /// Always allowed, even paused: what keeps the link up, says why something was refused,
    /// and lets either side unpair. ack and nack answer what was sent before the pause.
    /// </summary>
    public static readonly IReadOnlySet<string> Always = new HashSet<string>(StringComparer.Ordinal)
    {
        "hello", "welcome", "ping", "pong", "perm", "unpair", "ack", "nack", "refused", "error", "rpc-result",
    };

    /// <summary>
    /// What each of this device's announced caps needs from the peer before the peer is told
    /// about it (its hello's <c>caps</c>): a peer that can't use it isn't offered it.
    /// </summary>
    public static readonly IReadOnlyDictionary<string, string> CapNeeds = new Dictionary<string, string>
    {
        ["input"] = Control, ["media"] = Control, ["lock"] = Control, ["screenshot"] = Control, ["clipboard"] = Clipboard, ["notify"] = Notify,
    };

    /// <summary>Your own device: everything on.</summary>
    public static IReadOnlyDictionary<string, bool> OwnDefaults { get; } = Capabilities.ToDictionary(c => c, _ => true);

    /// <summary>Someone else's: files, messages and ring; no clipboard, notifications, remote control or access.</summary>
    public static IReadOnlyDictionary<string, bool> OtherDefaults { get; } = new Dictionary<string, bool>
    {
        [Files] = true, [Chat] = true, [Clipboard] = false, [Notify] = false, [Control] = false, [Ring] = true, [Access] = false,
    };

    /// <summary>A relation's defaults, as a new dictionary.</summary>
    public static Dictionary<string, bool> Defaults(string? relation) =>
        new(relation == Other ? OtherDefaults : OwnDefaults);

    /// <summary>"own" or "other"; anything else, or nothing, is "own" (what every peer was before).</summary>
    public static string CleanRelation(string? relation) => relation == Other ? Other : Own;

    /// <summary>Every capability, as a bool: the relation's default where <paramref name="allow"/> doesn't say.</summary>
    public static Dictionary<string, bool> CleanAllow(IEnumerable<KeyValuePair<string, bool>>? allow, string? relation = Own)
    {
        var output = Defaults(relation);
        foreach (var (c, on) in allow ?? [])
        {
            if (output.ContainsKey(c))
            {
                output[c] = on;
            }
        }
        return output;
    }

    /// <summary>The booleans of a JSON object's members (anything else skipped); null when it isn't an object.</summary>
    public static Dictionary<string, bool>? BoolsOf(JsonNode? node)
    {
        if (node is not JsonObject o)
        {
            return null;
        }
        var output = new Dictionary<string, bool>(StringComparer.Ordinal);
        foreach (var (k, _) in o)
        {
            if (o.Bool(k) is { } b)
            {
                output[k] = b;
            }
        }
        return output;
    }

    /// <summary>
    /// The capability a message needs, either way. Null: it needs none (but still stops
    /// while paused, unless it's one of <see cref="Always"/>).
    /// </summary>
    public static string? Capability(JsonObject msg)
    {
        ArgumentNullException.ThrowIfNull(msg);
        return msg.Str("t") switch
        {
            "text" => Chat,
            "offer" or "file" or "file-end" => Files,
            "clip" => Clipboard,
            "notify" or "notify-removed" => Notify,
            "input" or "media" or "cmd" => Control,
            "ring" or "ring-stop" => Ring,
            "rpc" => (msg.Str("method") ?? "").StartsWith("media.", StringComparison.Ordinal) ? Control : Access,
            // what's playing is part of remote control; the battery level is just there
            "state" => msg.Str("kind") == "media" ? Control : null,
            _ => null,
        };
    }

    /// <summary>A message of type <paramref name="t"/> (for checks that need only the type).</summary>
    public static JsonObject Message(string t) => new() { ["t"] = t };

    /// <summary>
    /// Whether a message may come from (<paramref name="outgoing"/> false) or go to (true) the
    /// peer <paramref name="entry"/>, by this device's own settings. Null if it may; else why
    /// (<see cref="WhyPaused"/> or <see cref="WhyDenied"/>) and the capability ("" for none).
    /// </summary>
    public static (string Why, string Cap)? Check(TrustEntry? entry, JsonObject msg, bool pausedAll = false, bool outgoing = false)
    {
        ArgumentNullException.ThrowIfNull(msg);
        var t = msg.Str("t") ?? "";
        if (Always.Contains(t))
        {
            return null;
        }
        var cap = Capability(msg);
        if (pausedAll || entry is { Paused: true })
        {
            return (WhyPaused, cap ?? "");
        }
        if (outgoing && cap is not null && InboundOnly.Contains(cap) && t != "state")
        {
            return null; // the peer decides what this device may do to it
        }
        if (cap is not null && !Allowed(entry, cap))
        {
            return (WhyDenied, cap);
        }
        return null;
    }

    /// <summary>Whether a capability is switched on for a peer (an unknown peer: no).</summary>
    public static bool Allowed(TrustEntry? entry, string cap)
    {
        if (entry is null)
        {
            return false;
        }
        if (entry.Allow is not { } allow)
        {
            return true; // an entry from before permissions: your own device, as it was
        }
        return allow.TryGetValue(cap, out var on) ? on : Defaults(entry.Relation).GetValueOrDefault(cap, true);
    }

    /// <summary>What this device tells a peer about how it treats it (<c>perm</c>): a hint for its UI.</summary>
    public static JsonObject RemoteView(TrustEntry? entry, bool pausedAll)
    {
        var allow = CleanAllow(entry?.Allow, entry?.Relation ?? Own);
        var o = new JsonObject();
        foreach (var c in Capabilities)
        {
            o[c] = allow[c];
        }
        return new JsonObject { ["paused"] = pausedAll || entry is { Paused: true }, ["allow"] = o };
    }

    /// <summary>A peer's <c>perm</c>, cleaned; null if it sent none (an older peer: everything as before).</summary>
    public static RemotePerm? ParseRemote(JsonNode? perm)
    {
        if (perm is not JsonObject o)
        {
            return null;
        }
        var allow = new Dictionary<string, bool>(StringComparer.Ordinal);
        if (o["allow"] is JsonObject a)
        {
            foreach (var c in Capabilities)
            {
                if (a.Bool(c) is { } on)
                {
                    allow[c] = on;
                }
            }
        }
        return new RemotePerm(o.Bool("paused") == true, allow);
    }

    /// <summary>What the peer said it would do with <paramref name="cap"/> from us: "paused", "denied" or null.</summary>
    public static string? RemoteRefuses(RemotePerm? remote, string? cap)
    {
        if (remote is null)
        {
            return null;
        }
        if (remote.Paused)
        {
            return WhyPaused;
        }
        if (cap is not null && remote.Allow.TryGetValue(cap, out var on) && !on)
        {
            return WhyDenied;
        }
        return null;
    }

    static string Noun(string? cap) => cap is not null && Nouns.TryGetValue(cap, out var n) ? n : string.IsNullOrEmpty(cap) ? "that" : cap;

    /// <summary>What the sender shows: "Brian's laptop doesn't allow the clipboard from you".</summary>
    public static string RefusalText(string name, string why, string? cap) =>
        why == WhyPaused ? $"{name} paused sharing with you" : $"{name} doesn't allow {Noun(cap)} from you";

    /// <summary>Why this device itself won't send it.</summary>
    public static string LocalText(string name, string why, string? cap, bool pausedAll = false)
    {
        if (why == WhyPaused)
        {
            return pausedAll ? "everything is paused on this device: resume to send" : $"{name} is paused: resume it to send";
        }
        var noun = Noun(cap);
        return $"{char.ToUpperInvariant(noun[0])}{noun[1..]} with {name} is switched off here";
    }

    /// <summary>The capabilities switched off, in order: for a line like "off: clipboard, notify".</summary>
    public static List<string> Off(TrustEntry entry)
    {
        ArgumentNullException.ThrowIfNull(entry);
        return Capabilities.Where(c => !Allowed(entry, c)).ToList();
    }
}

/// <summary>What a peer said about how it treats this device (its <c>perm</c>): a hint only.</summary>
/// <param name="Paused">It paused sharing with this device (or everything).</param>
/// <param name="Allow">The switches it named; one it didn't name counts as on.</param>
public sealed record RemotePerm(bool Paused, IReadOnlyDictionary<string, bool> Allow)
{
    /// <summary>Whether it said it takes <paramref name="cap"/> from this device.</summary>
    public bool Takes(string cap) => !Paused && Allow.GetValueOrDefault(cap, true);
}

/// <summary>The last thing a peer refused from this device, and why.</summary>
/// <param name="Re">The message type it refused.</param>
/// <param name="Cap">The capability, when known.</param>
/// <param name="Why">"paused" or "denied".</param>
/// <param name="Text">What to show: "t15 doesn't allow the clipboard from you".</param>
/// <param name="At">When.</param>
public sealed record Refusal(string Re, string? Cap, string Why, string Text, DateTimeOffset At);

/// <summary>
/// Not sent: this device's own settings say no (<see cref="Local"/>), or the peer said it
/// would refuse it.
/// </summary>
public sealed class RefusedException(string message, string why, string? cap, bool local) : Exception(message)
{
    /// <summary>"paused" or "denied".</summary>
    public string Why { get; } = why;

    /// <summary>The capability.</summary>
    public string? Cap { get; } = cap;

    /// <summary>This device's own settings said no (as opposed to the peer's word).</summary>
    public bool Local { get; } = local;
}
