using System.Globalization;
using System.Text;

namespace Droplet.Core.Mesh;

/// <summary>Cleaning what peers say about themselves, exactly as the reference does (trust.py).</summary>
public static class Clean
{
    /// <summary>
    /// A name: whitespace runs collapsed to one space, only printable characters, at
    /// most 64 of them; <paramref name="fallback"/> when nothing is left.
    /// </summary>
    public static string Name(string? value, string fallback = "")
    {
        var collapsed = string.Join(' ', (value ?? "").Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
        var sb = new StringBuilder();
        var n = 0;
        foreach (var r in collapsed.EnumerateRunes())
        {
            if (!IsPrintable(r))
            {
                continue;
            }
            if (n++ == 64)
            {
                break;
            }
            sb.Append(r.ToString());
        }
        return sb.Length > 0 ? sb.ToString() : fallback;
    }

    /// <summary>Python's <c>str.isprintable</c> for one character: not a control, format, separator (other than space), surrogate, private-use or unassigned character.</summary>
    public static bool IsPrintable(Rune r)
    {
        if (r.Value == ' ')
        {
            return true;
        }
        return Rune.GetUnicodeCategory(r) switch
        {
            UnicodeCategory.Control or UnicodeCategory.Format or UnicodeCategory.Surrogate or UnicodeCategory.PrivateUse
                or UnicodeCategory.OtherNotAssigned or UnicodeCategory.LineSeparator or UnicodeCategory.ParagraphSeparator
                or UnicodeCategory.SpaceSeparator => false,
            _ => true,
        };
    }

    /// <summary>
    /// Capabilities: from a list or a comma-separated string; each ASCII letters, digits
    /// and dashes, at most 20 characters; sorted, without duplicates.
    /// </summary>
    public static List<string> Caps(IEnumerable<string>? caps)
    {
        var set = new SortedSet<string>(StringComparer.Ordinal);
        foreach (var raw in caps ?? [])
        {
            var c = raw.Trim();
            if (c.Length is > 0 and <= 20 && c.Replace("-", "", StringComparison.Ordinal) is { Length: > 0 } core &&
                core.All(ch => ch < 128 && char.IsAsciiLetterOrDigit(ch)))
            {
                set.Add(c);
            }
        }
        return [.. set];
    }

    /// <summary>Capabilities from a comma-separated string (a TXT record).</summary>
    public static List<string> Caps(string? csv) => Caps((csv ?? "").Split(','));

    /// <summary>A port: 1–65535, else null.</summary>
    public static int? Port(long? v) => v is > 0 and < 65536 ? (int)v : null;
}
