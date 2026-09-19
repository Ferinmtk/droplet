using System.Text.Json;
using Droplet.Core.Common;

namespace Droplet.Core.Config;

/// <summary>
/// Where the app keeps its files. Everything is per user. Tests and portable use pass
/// their own root; <see cref="Default"/> is <c>%LOCALAPPDATA%\droplet</c> on Windows
/// (<c>~/.local/share/droplet</c> elsewhere), or <c>DROPLET_HOME</c> when set.
/// </summary>
public sealed record AppPaths(string Root)
{
    /// <summary>The settings file.</summary>
    public string ConfigFile => Path.Combine(Root, "config.json");

    /// <summary>The mesh's own configuration: trust list, and the identity on Linux.</summary>
    public string MeshConfigDir => Path.Combine(Root, "mesh");

    /// <summary>The mesh's data: outbox, chat, received files' record.</summary>
    public string MeshDataDir => Path.Combine(Root, "mesh", "data");

    /// <summary>The log.</summary>
    public string LogFile => Path.Combine(Root, "droplet.log");

    /// <summary>The default location.</summary>
    public static AppPaths Default()
    {
        var env = Environment.GetEnvironmentVariable("DROPLET_HOME");
        if (!string.IsNullOrWhiteSpace(env))
        {
            return new AppPaths(env);
        }
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData, Environment.SpecialFolderOption.Create);
        return new AppPaths(Path.Combine(local, "droplet"));
    }

    /// <summary>
    /// The user's download folder for droplet: <c>%USERPROFILE%\Downloads\droplet</c>
    /// (<c>~/Downloads/droplet</c> elsewhere). 3b may prefer the Downloads known folder.
    /// </summary>
    public static string DefaultDownloadDir()
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return string.IsNullOrEmpty(home) ? "droplet-downloads" : Path.Combine(home, "Downloads", "droplet");
    }
}

/// <summary>
/// Loads and saves one config file, and serialises access to it. <see cref="Get"/>
/// hands out copies, so a caller can't change the stored config by accident; changes
/// go through <see cref="Update"/>, which saves them atomically.
/// </summary>
public sealed class ConfigStore
{
    static readonly JsonSerializerOptions Options = new(Json.Indented)
    {
        // a field from a newer version is kept by nobody, but must not fail the load
        UnmappedMemberHandling = System.Text.Json.Serialization.JsonUnmappedMemberHandling.Skip,
    };

    readonly Lock gate = new();
    AppConfig current;

    ConfigStore(string path, AppConfig config)
    {
        FilePath = path;
        current = config;
    }

    /// <summary>The file.</summary>
    public string FilePath { get; }

    /// <summary>Raised after every saved change, with a copy of the new config.</summary>
    public event Action<AppConfig>? Changed;

    /// <summary>
    /// Opens the config at <paramref name="paths"/>. A first run imports the Go app's
    /// config when there is one (<paramref name="goConfigFile"/>, default
    /// <see cref="GoConfigImport.DefaultPath"/>; pass "" to skip).
    /// </summary>
    public static ConfigStore Open(AppPaths paths, string? goConfigFile = null)
    {
        ArgumentNullException.ThrowIfNull(paths);
        var file = paths.ConfigFile;
        if (!File.Exists(file))
        {
            var go = goConfigFile ?? GoConfigImport.DefaultPath();
            if (!string.IsNullOrEmpty(go) && GoConfigImport.TryRead(go) is { } imported)
            {
                imported.ImportedFrom = go;
                var store = new ConfigStore(file, Normalize(imported));
                store.Save(store.current);
                return store;
            }
        }
        return OpenFile(file);
    }

    /// <summary>Opens a config file; a missing file means defaults (not saved until changed).</summary>
    public static ConfigStore OpenFile(string file) => new(file, Load(file));

    /// <summary>A fresh config for a first run.</summary>
    public static AppConfig Defaults() => Normalize(new AppConfig());

    static AppConfig Load(string file)
    {
        string text;
        try
        {
            text = File.ReadAllText(file);
        }
        catch (FileNotFoundException)
        {
            return Defaults();
        }
        catch (DirectoryNotFoundException)
        {
            return Defaults();
        }
        var cfg = JsonSerializer.Deserialize<AppConfig>(text, Options) ?? throw new InvalidDataException($"{file} is empty");
        return Normalize(cfg);
    }

    /// <summary>Fills in what a config file may lack.</summary>
    internal static AppConfig Normalize(AppConfig cfg)
    {
        cfg.HubUrl ??= "";
        if (string.IsNullOrEmpty(cfg.DownloadDir))
        {
            cfg.DownloadDir = AppPaths.DefaultDownloadDir();
        }
        cfg.ChatSeen ??= [];
        cfg.InboxSeen ??= [];
        cfg.Mesh ??= new MeshSettings();
        if (cfg.Hub is not null)
        {
            cfg.Hub.Lan ??= [];
        }
        if (string.IsNullOrEmpty(cfg.ActionKey))
        {
            cfg.ActionKey = Hex.Random(16);
        }
        return cfg;
    }

    /// <summary>Whether the config has been saved yet.</summary>
    public bool Exists => File.Exists(FilePath);

    /// <summary>A copy of the current config.</summary>
    public AppConfig Get()
    {
        lock (gate)
        {
            return Clone(current);
        }
    }

    /// <summary>Applies <paramref name="change"/> to a copy, saves it, and makes it current.</summary>
    public AppConfig Update(Action<AppConfig> change)
    {
        ArgumentNullException.ThrowIfNull(change);
        AppConfig next;
        lock (gate)
        {
            next = Clone(current);
            change(next);
            Save(next);
            current = next;
        }
        var copy = Clone(next);
        Changed?.Invoke(copy);
        return copy;
    }

    /// <summary>Re-reads the file (another process may have changed it).</summary>
    public void Reload()
    {
        var cfg = Load(FilePath);
        lock (gate)
        {
            current = cfg;
        }
    }

    void Save(AppConfig cfg) => AtomicFile.Write(FilePath, JsonSerializer.SerializeToUtf8Bytes(cfg, Options));

    static AppConfig Clone(AppConfig cfg) =>
        JsonSerializer.Deserialize<AppConfig>(JsonSerializer.SerializeToUtf8Bytes(cfg, Options), Options)!;
}
