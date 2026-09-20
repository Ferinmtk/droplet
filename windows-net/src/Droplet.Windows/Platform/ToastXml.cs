using System.Globalization;
using System.Text;
using Droplet.Core.Platform;
using Droplet.Windows.Shell;

namespace Droplet.Windows.Platform;

/// <summary>
/// A notification as Windows' toast XML (learn.microsoft.com/windows/apps/design/shell/
/// tiles-and-notifications/toast-schema). Clicks and buttons use protocol activation:
/// https links, or <c>droplet:</c> links (<see cref="ActionLinks"/>). Pure, so it's tested
/// anywhere; a port of the Go app's <c>toastxml.go</c>.
/// </summary>
internal static class ToastXml
{
    /// <summary>The XML for <paramref name="n"/>.</summary>
    /// <param name="n">The notification.</param>
    /// <param name="key">The action key for droplet: links.</param>
    /// <param name="appLogo">A file path for the app logo, or null.</param>
    public static string Build(Notification n, string key, string? appLogo = null)
    {
        ArgumentNullException.ThrowIfNull(n);
        var x = new StringBuilder("<toast");
        if (n.Click is { } click)
        {
            x.Append(CultureInfo.InvariantCulture, $" activationType=\"protocol\" launch=\"{Esc(ActionLinks.ToUrl(click, key))}\"");
        }
        if (n.Urgent)
        {
            // stays up until answered; rings have their own, louder, looping sound
            x.Append(" scenario=\"incomingCall\"");
        }
        x.Append("><visual><binding template=\"ToastGeneric\">");
        x.Append(CultureInfo.InvariantCulture, $"<text hint-maxLines=\"1\">{Esc(Clip(n.Title, 120))}</text>");
        if (n.Body.Length > 0)
        {
            x.Append(CultureInfo.InvariantCulture, $"<text>{Esc(Clip(n.Body, 400))}</text>");
        }
        if (!string.IsNullOrEmpty(n.App))
        {
            x.Append(CultureInfo.InvariantCulture, $"<text placement=\"attribution\">{Esc(Clip(n.App, 60))}</text>");
        }
        if (!string.IsNullOrEmpty(appLogo))
        {
            x.Append(CultureInfo.InvariantCulture, $"<image placement=\"appLogoOverride\" hint-crop=\"none\" src=\"{Esc(new Uri(appLogo).AbsoluteUri)}\"/>");
        }
        x.Append("</binding></visual>");
        // one that stays up needs a way to dismiss it
        var buttons = n.Buttons.Count > 0 || !n.Urgent ? n.Buttons : [new NotificationAction("Dismiss", NotificationActionKind.OpenUrl)];
        if (buttons.Count > 0)
        {
            x.Append("<actions>");
            foreach (var b in buttons.Take(5))
            {
                if (b.Kind == NotificationActionKind.OpenUrl && b.Arg.Length == 0)
                {
                    x.Append(CultureInfo.InvariantCulture, $"<action content=\"{Esc(b.Label)}\" arguments=\"dismiss\" activationType=\"system\"/>");
                    continue;
                }
                x.Append(CultureInfo.InvariantCulture, $"<action content=\"{Esc(b.Label)}\" activationType=\"protocol\" arguments=\"{Esc(ActionLinks.ToUrl(b, key))}\"/>");
            }
            x.Append("</actions>");
        }
        if (n.Urgent)
        {
            x.Append("<audio silent=\"true\"/>");
        }
        x.Append("</toast>");
        return x.ToString();
    }

    /// <summary>A tag as Windows accepts it: at most 64 UTF-16 units (60 here, to be safe), cut between characters.</summary>
    public static string Tag(string? t)
    {
        if (string.IsNullOrEmpty(t))
        {
            return "";
        }
        var units = 0;
        var sb = new StringBuilder();
        foreach (var r in t.EnumerateRunes())
        {
            if (units + r.Utf16SequenceLength > 60)
            {
                break;
            }
            units += r.Utf16SequenceLength;
            sb.Append(r.ToString());
        }
        return sb.ToString();
    }

    static string Clip(string s, int n)
    {
        var runes = s.EnumerateRunes().ToList();
        return runes.Count <= n ? s : string.Concat(runes.Take(n - 1).Select(r => r.ToString())) + "…";
    }

    /// <summary>
    /// Text made safe as XML text or an attribute value. Characters XML can't carry at all
    /// (most control characters) are dropped: one of them would make Windows refuse the toast.
    /// </summary>
    public static string Esc(string? s)
    {
        var b = new StringBuilder((s ?? "").Length);
        foreach (var r in (s ?? "").EnumerateRunes())
        {
            switch (r.Value)
            {
                case '&':
                    b.Append("&amp;");
                    break;
                case '<':
                    b.Append("&lt;");
                    break;
                case '>':
                    b.Append("&gt;");
                    break;
                case '"':
                    b.Append("&quot;");
                    break;
                case '\'':
                    b.Append("&apos;");
                    break;
                case '\t' or '\n' or '\r':
                    b.Append((char)r.Value);
                    break;
                case < 0x20 or 0xFFFE or 0xFFFF:
                    break; // not allowed in XML 1.0
                default:
                    b.Append(r.ToString());
                    break;
            }
        }
        return b.ToString();
    }
}
