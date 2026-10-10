using System.Text;

namespace Droplet.Core.Mesh;

/// <summary>
/// Links sent to another device (docs/mesh.md §9.10, the reference's mesh/links.py). Only web
/// addresses travel as links, and only those are ever opened: <c>http</c> and <c>https</c>,
/// with a host, no spaces, control or invisible formatting characters, and at most
/// <see cref="MaxUrl"/> characters. Anything else (<c>file:</c>, <c>javascript:</c>,
/// <c>data:</c>, an app's own scheme) is refused when it's sent and when it arrives, and never opened.
/// </summary>
public static class Links
{
    /// <summary>The longest link that travels.</summary>
    public const int MaxUrl = 2048;

    /// <summary>
    /// The link, trimmed, if it's one that may be sent and opened. Throws
    /// <see cref="ArgumentException"/> saying why not.
    /// </summary>
    public static string CheckUrl(string? value)
    {
        if (value is null)
        {
            throw new ArgumentException("a link must be text");
        }
        var url = value.Trim();
        if (url.Length == 0)
        {
            throw new ArgumentException("the link is empty");
        }
        if (url.Length > MaxUrl)
        {
            throw new ArgumentException($"that link is too long ({MaxUrl} characters at most)");
        }
        foreach (var r in url.EnumerateRunes())
        {
            if (Rune.IsWhiteSpace(r) || !Clean.IsPrintable(r) || Names.IsHidden(r))
            {
                throw new ArgumentException("a link can't hold spaces or control characters");
            }
        }
        var colon = url.IndexOf(':', StringComparison.Ordinal);
        var scheme = colon > 0 ? url[..colon].ToLowerInvariant() : "";
        if (scheme is not ("http" or "https"))
        {
            throw new ArgumentException("only web links (http:// or https://) can be sent and opened");
        }
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || string.IsNullOrEmpty(uri.Host) ||
            !url[(colon + 1)..].StartsWith("//", StringComparison.Ordinal))
        {
            throw new ArgumentException("that link has no address in it");
        }
        return url;
    }

    /// <summary>Whether <paramref name="value"/> is a link that may be sent and opened.</summary>
    public static bool IsUrl(string? value)
    {
        try
        {
            CheckUrl(value);
            return true;
        }
        catch (ArgumentException)
        {
            return false;
        }
    }

    /// <summary>A chat message that's nothing but a web link: the link (for an Open button), else null.</summary>
    public static string? OnlyUrl(string? text)
    {
        var t = (text ?? "").Trim();
        if (!t.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !t.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }
        return IsUrl(t) ? t : null;
    }
}

/// <summary>
/// Names someone typed: this device's (renaming it) or a nickname for another (docs/mesh.md
/// §9.10, the reference's trust.check_name).
/// </summary>
public static class Names
{
    /// <summary>The longest name, as the hub allows it.</summary>
    public const int MaxName = 40;

    /// <summary>Formatting characters that could hide or reorder text: U+200B–200F, U+202A–202E, U+2066–2069, U+FEFF, U+00AD.</summary>
    public static bool IsHidden(Rune r) =>
        r.Value is (>= 0x200B and <= 0x200F) or (>= 0x202A and <= 0x202E) or (>= 0x2066 and <= 0x2069) or 0xFEFF or 0x00AD;

    /// <summary>
    /// A name, checked: runs of white space become one space, and it's 1 to
    /// <paramref name="limit"/> characters with no control or invisible characters. Throws
    /// <see cref="ArgumentException"/> saying why not.
    /// </summary>
    public static string Check(string? value, string what = "a name", int limit = MaxName, bool emptyOk = false)
    {
        if (value is null)
        {
            throw new ArgumentException($"{what} must be text");
        }
        foreach (var r in value.EnumerateRunes())
        {
            if ((!Clean.IsPrintable(r) && r.Value is not (0x20 or 0x09 or 0x0A)) || IsHidden(r))
            {
                throw new ArgumentException($"{what} can't hold control characters");
            }
        }
        var name = string.Join(' ', value.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
        if (name.Length == 0 && !emptyOk)
        {
            throw new ArgumentException($"{what} can't be empty");
        }
        if (name.EnumerateRunes().Count() > limit)
        {
            throw new ArgumentException($"{what} can be {limit} characters at most");
        }
        return name;
    }

    /// <summary>A nickname read back from the trust list: checked, or "" when it isn't one.</summary>
    public static string CleanNickname(string? value)
    {
        try
        {
            return value is null ? "" : Check(value, emptyOk: true);
        }
        catch (ArgumentException)
        {
            return "";
        }
    }
}
