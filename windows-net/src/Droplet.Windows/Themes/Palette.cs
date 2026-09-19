using System.Windows;
using System.Windows.Media;
using Microsoft.Win32;

namespace Droplet.Windows.Themes;

/// <summary>
/// droplet's colours, from the web app (templates/index.html): a teal accent, aqua on dark
/// and deep teal on light. They replace Fluent's accent (which would otherwise be the
/// system's), except in high contrast, where Windows' colours win.
/// </summary>
internal static class Palette
{
    static readonly (string Accent, string OnAccent, string Dim, string Light1, string Light2, string Light3, string Dark1, string Dark2, string Dark3) Dark =
        ("#4DE8D4", "#032824", "#7D9DA0", "#71EDDD", "#98F2E7", "#C0F7F0", "#2FC9B6", "#1FA595", "#0F7F72");

    static readonly (string Accent, string OnAccent, string Dim, string Light1, string Light2, string Light3, string Dark1, string Dark2, string Dark3) Light =
        ("#0A7D84", "#FFFFFF", "#5E7478", "#0E929A", "#2AA9B0", "#5CC2C7", "#086A70", "#06565B", "#044246");

    /// <summary>Whether Windows' apps are in dark mode.</summary>
    public static bool SystemDark()
    {
        using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        return k?.GetValue("AppsUseLightTheme") is int v && v == 0;
    }

    /// <summary>Sets the colours for the current mode, and again whenever Windows' mode changes.</summary>
    public static void Follow(Application app)
    {
        ArgumentNullException.ThrowIfNull(app);
        Apply(app);
        SystemEvents.UserPreferenceChanged += (_, e) =>
        {
            if (e.Category is UserPreferenceCategory.General or UserPreferenceCategory.Color or UserPreferenceCategory.Accessibility)
            {
                app.Dispatcher.BeginInvoke(() => Apply(app));
            }
        };
    }

    static Color C(string hex) => (Color)ColorConverter.ConvertFromString(hex);

    static SolidColorBrush B(Color c, byte alpha = 255)
    {
        var b = new SolidColorBrush(Color.FromArgb(alpha, c.R, c.G, c.B));
        b.Freeze();
        return b;
    }

    static void Apply(Application app)
    {
        var r = app.Resources;
        if (SystemParameters.HighContrast)
        {
            r["DropletAccentBrush"] = SystemColors.HighlightBrush;
            r["DropletOnAccentBrush"] = SystemColors.HighlightTextBrush;
            r["DropletDimBrush"] = SystemColors.GrayTextBrush;
            return;
        }
        var p = SystemDark() ? Dark : Light;
        var accent = C(p.Accent);
        r["DropletAccentBrush"] = B(accent);
        r["DropletOnAccentBrush"] = B(C(p.OnAccent));
        r["DropletAccentSoftBrush"] = B(accent, 0x22);
        r["DropletDimBrush"] = B(C(p.Dim));
        // Fluent's accent keys, so its own controls (checked boxes, focus, selection) match
        r["SystemAccentColor"] = accent;
        r["SystemAccentColorLight1"] = C(p.Light1);
        r["SystemAccentColorLight2"] = C(p.Light2);
        r["SystemAccentColorLight3"] = C(p.Light3);
        r["SystemAccentColorDark1"] = C(p.Dark1);
        r["SystemAccentColorDark2"] = C(p.Dark2);
        r["SystemAccentColorDark3"] = C(p.Dark3);
        var isDark = p == Dark;
        r["AccentFillColorDefaultBrush"] = B(isDark ? C(p.Light2) : C(p.Dark1));
        r["AccentFillColorSecondaryBrush"] = B(isDark ? C(p.Light2) : C(p.Dark1), 0xE6);
        r["AccentFillColorTertiaryBrush"] = B(isDark ? C(p.Light2) : C(p.Dark1), 0xCC);
        r["AccentTextFillColorPrimaryBrush"] = B(isDark ? C(p.Light3) : C(p.Dark2));
        r["AccentTextFillColorSecondaryBrush"] = B(isDark ? C(p.Light3) : C(p.Dark3));
        r["AccentTextFillColorTertiaryBrush"] = B(isDark ? C(p.Light2) : C(p.Dark1));
        r["TextOnAccentFillColorPrimaryBrush"] = B(isDark ? C("#000000") : C("#FFFFFF"));
    }
}
