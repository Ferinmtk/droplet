using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Droplet.Core.Common;

/// <summary>JSON conventions shared by the hub API, the mesh and the config.</summary>
public static class Json
{
    /// <summary>
    /// Compact, and without HTML escaping, so text isn't inflated past a frame limit by
    /// &lt; and friends. Unicode is written as is (valid JSON; every peer reads UTF-8).
    /// </summary>
    public static readonly JsonSerializerOptions Compact = new()
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        WriteIndented = false,
    };

    /// <summary>For files people may open: indented.</summary>
    public static readonly JsonSerializerOptions Indented = new()
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        WriteIndented = true,
    };

    /// <summary>A JSON object as compact UTF-8.</summary>
    public static byte[] ToUtf8(JsonNode node) => JsonSerializer.SerializeToUtf8Bytes(node, Compact);

    /// <summary>A JSON object as a compact string.</summary>
    public static string ToText(JsonNode node) => node.ToJsonString(Compact);

    /// <summary>Parses UTF-8 as a JSON object; null when it isn't one.</summary>
    public static JsonObject? ParseObject(ReadOnlySpan<byte> utf8)
    {
        try
        {
            var reader = new Utf8JsonReader(utf8, new JsonReaderOptions { MaxDepth = 64 });
            return JsonNode.Parse(ref reader) as JsonObject;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>Parses text as a JSON object; null when it isn't one.</summary>
    public static JsonObject? ParseObject(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return null;
        }
        try
        {
            return JsonNode.Parse(text, documentOptions: new JsonDocumentOptions { MaxDepth = 64 }) as JsonObject;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>A string member, or null when it's missing or not a string.</summary>
    public static string? Str(this JsonObject o, string key) =>
        o.TryGetPropertyValue(key, out var v) && v is JsonValue jv && jv.GetValueKind() == JsonValueKind.String
            ? jv.GetValue<string>()
            : null;

    /// <summary>
    /// An integer member, or null when it's missing, not a number, or has a fraction.
    /// Booleans are never integers (Python's <c>isinstance(True, int)</c> trap).
    /// </summary>
    public static long? Int(this JsonObject o, string key)
    {
        if (!o.TryGetPropertyValue(key, out var v) || v is not JsonValue jv || jv.GetValueKind() != JsonValueKind.Number)
        {
            return null;
        }
        return jv.TryGetValue<long>(out var l) ? l : null;
    }

    /// <summary>A number member, or null.</summary>
    public static double? Num(this JsonObject o, string key)
    {
        if (!o.TryGetPropertyValue(key, out var v) || v is not JsonValue jv || jv.GetValueKind() != JsonValueKind.Number)
        {
            return null;
        }
        return jv.TryGetValue<double>(out var d) && double.IsFinite(d) ? d : null;
    }

    /// <summary>A boolean member, or null.</summary>
    public static bool? Bool(this JsonObject o, string key) =>
        o.TryGetPropertyValue(key, out var v) && v is JsonValue jv &&
        jv.GetValueKind() is JsonValueKind.True or JsonValueKind.False
            ? jv.GetValue<bool>()
            : null;

    /// <summary>The strings of an array member (other items skipped); null when it isn't an array.</summary>
    public static List<string>? Strings(this JsonObject o, string key)
    {
        if (!o.TryGetPropertyValue(key, out var v) || v is not JsonArray a)
        {
            return null;
        }
        var list = new List<string>();
        foreach (var item in a)
        {
            if (item is JsonValue jv && jv.GetValueKind() == JsonValueKind.String)
            {
                list.Add(jv.GetValue<string>());
            }
        }
        return list;
    }

    /// <summary>An array of strings as a JSON array.</summary>
    public static JsonArray Array(IEnumerable<string> items)
    {
        var a = new JsonArray();
        foreach (var s in items)
        {
            a.Add(s);
        }
        return a;
    }
}
