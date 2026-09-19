using Droplet.Windows.Interop;

namespace Droplet.Windows.Shell;

/// <summary>Whether droplet runs from its MSIX package (the Store, or a sideloaded package) or as a plain exe.</summary>
internal static class Packaging
{
    static readonly Lazy<bool> Packaged = new(() =>
    {
        uint length = 0;
        unsafe
        {
            var rc = Native.GetCurrentPackageFullName(ref length, null);
            return rc != Native.APPMODEL_ERROR_NO_PACKAGE;
        }
    });

    /// <summary>
    /// True inside a package: Start with Windows is then a StartupTask, the droplet: links
    /// and the firewall rules come from the manifest, and toasts use the package's identity.
    /// </summary>
    public static bool IsPackaged => Packaged.Value;
}
