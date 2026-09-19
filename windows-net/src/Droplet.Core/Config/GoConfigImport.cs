using System.Text.Json;
using System.Text.Json.Nodes;

namespace Droplet.Core.Config;

/// <summary>
/// Reads the Go app's <c>%APPDATA%\droplet\config.json</c> (windows/internal/config),
/// so an existing install keeps its hub, token, pinned certificate and settings. The
/// field names are the same; what the Go app did on load is repeated here:
/// <list type="bullet">
/// <item>a config from before Send To was a setting keeps the Send To entries it
/// already had (on for any PC that was let in to a hub);</item>
/// <item>a config that knows no hub at all, which the Go app pointed at its built-in
/// default, gets none: the new app finds its hub on the LAN or is told it.</item>
/// </list>
/// The Go file is only read, never changed, so the Go app keeps working until it's removed.
/// </summary>
public static class GoConfigImport
{
    /// <summary>
    /// Where the Go app keeps it: <c>DROPLET_CONFIG_DIR</c>, else <c>%APPDATA%\droplet</c>
    /// (Go's <c>os.UserConfigDir</c>, <c>~/.config</c> on Linux).
    /// </summary>
    public static string DefaultPath()
    {
        var dir = Environment.GetEnvironmentVariable("DROPLET_CONFIG_DIR");
        if (string.IsNullOrEmpty(dir))
        {
            var appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            if (string.IsNullOrEmpty(appData))
            {
                return "";
            }
            dir = Path.Combine(appData, "droplet");
        }
        return Path.Combine(dir, "config.json");
    }

    /// <summary>The Go app's config as this app's, or null when there is none or it can't be read.</summary>
    public static AppConfig? TryRead(string path)
    {
        string text;
        try
        {
            text = File.ReadAllText(path);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null;
        }
        AppConfig? cfg;
        JsonObject? keys;
        try
        {
            keys = JsonNode.Parse(text) as JsonObject;
            cfg = JsonSerializer.Deserialize<AppConfig>(text);
        }
        catch (JsonException)
        {
            return null;
        }
        if (cfg is null || keys is null)
        {
            return null;
        }
        if (!keys.ContainsKey("send_to"))
        {
            cfg.SendTo = !string.IsNullOrEmpty(cfg.DeviceToken);
        }
        // the Go app has no mesh: this one starts with the defaults
        cfg.Mesh = new MeshSettings();
        return ConfigStore.Normalize(cfg);
    }
}
