using System.ComponentModel;
using System.Diagnostics;
using Droplet.Core.Mesh;
using Droplet.Core.Platform;
using Microsoft.Extensions.Logging;

namespace Droplet.Windows.Platform;

/// <summary>
/// Opens web links in the default browser (docs/mesh.md §9.11): a link from your own device
/// when it arrives, or any link when its Open button is clicked. Only http and https, checked
/// again here, so nothing else (a file, a script, an app's own scheme) is ever started.
/// </summary>
internal sealed class BrowserService(ILogger log) : IBrowser
{
    public bool Open(string url)
    {
        string link;
        try
        {
            link = Links.CheckUrl(url);
        }
        catch (ArgumentException)
        {
            return false;
        }
        try
        {
            using var _ = Process.Start(new ProcessStartInfo(link) { UseShellExecute = true });
            return true;
        }
        catch (Exception e) when (e is Win32Exception or InvalidOperationException)
        {
            log.LogWarning("open a link: {Error}", e.Message);
            return false;
        }
    }
}
