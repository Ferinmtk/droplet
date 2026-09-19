using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;

namespace Droplet.Windows.Shell;

/// <summary>A .lnk file to create: Explorer's Send To entries and the Start menu entry.</summary>
internal sealed record Shortcut(string Path, string Target, string Arguments = "", string Description = "", string Icon = "", string? AppId = null);

/// <summary>Writes .lnk files with the shell's own IShellLink, optionally stamped with an AppUserModelID.</summary>
internal static class ShellLink
{
    [ComImport]
    [Guid("00021401-0000-0000-C000-000000000046")]
    sealed class CShellLink;

    [ComImport]
    [Guid("000214F9-0000-0000-C000-000000000046")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IShellLinkW
    {
        [PreserveSig] int GetPath(nint file, int max, nint findData, uint flags);
        [PreserveSig] int GetIDList(out nint idl);
        [PreserveSig] int SetIDList(nint idl);
        [PreserveSig] int GetDescription(nint name, int max);
        [PreserveSig] int SetDescription([MarshalAs(UnmanagedType.LPWStr)] string name);
        [PreserveSig] int GetWorkingDirectory(nint dir, int max);
        [PreserveSig] int SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string dir);
        [PreserveSig] int GetArguments(nint args, int max);
        [PreserveSig] int SetArguments([MarshalAs(UnmanagedType.LPWStr)] string args);
        [PreserveSig] int GetHotkey(out ushort hotkey);
        [PreserveSig] int SetHotkey(ushort hotkey);
        [PreserveSig] int GetShowCmd(out int show);
        [PreserveSig] int SetShowCmd(int show);
        [PreserveSig] int GetIconLocation(nint path, int max, out int icon);
        [PreserveSig] int SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string path, int icon);
        [PreserveSig] int SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string path, uint reserved);
        [PreserveSig] int Resolve(nint wnd, uint flags);
        [PreserveSig] int SetPath([MarshalAs(UnmanagedType.LPWStr)] string file);
    }

    [ComImport]
    [Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPropertyStore
    {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int GetAt(uint index, out PropertyKey key);
        [PreserveSig] int GetValue(ref PropertyKey key, nint value);
        [PreserveSig] int SetValue(ref PropertyKey key, ref PropVariant value);
        [PreserveSig] int Commit();
    }

    [StructLayout(LayoutKind.Sequential)]
    struct PropertyKey
    {
        public Guid Fmtid;
        public uint Pid;
    }

    /// <summary>PROPVARIANT holding a VT_LPWSTR.</summary>
    [StructLayout(LayoutKind.Sequential)]
    struct PropVariant
    {
        public ushort Vt;
        public ushort Reserved1, Reserved2, Reserved3;
        public nint Value;
        public nint Padding;
    }

    // PKEY_AppUserModel_ID
    static readonly Guid AppUserModelFmtid = new("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3");
    const ushort VtLpwstr = 31;

    /// <summary>Creates or replaces the shortcut.</summary>
    public static void Save(Shortcut s)
    {
        ArgumentNullException.ThrowIfNull(s);
        object link = new CShellLink();
        try
        {
            var sl = (IShellLinkW)link;
            Marshal.ThrowExceptionForHR(sl.SetPath(s.Target));
            Marshal.ThrowExceptionForHR(sl.SetArguments(s.Arguments));
            Marshal.ThrowExceptionForHR(sl.SetDescription(s.Description));
            var dir = System.IO.Path.GetDirectoryName(s.Target);
            if (!string.IsNullOrEmpty(dir))
            {
                Marshal.ThrowExceptionForHR(sl.SetWorkingDirectory(dir));
            }
            if (s.Icon.Length > 0)
            {
                Marshal.ThrowExceptionForHR(sl.SetIconLocation(s.Icon, 0));
            }
            if (s.AppId is { } id)
            {
                var store = (IPropertyStore)link;
                var key = new PropertyKey { Fmtid = AppUserModelFmtid, Pid = 5 };
                var value = new PropVariant { Vt = VtLpwstr, Value = Marshal.StringToCoTaskMemUni(id) };
                try
                {
                    Marshal.ThrowExceptionForHR(store.SetValue(ref key, ref value));
                    Marshal.ThrowExceptionForHR(store.Commit());
                }
                finally
                {
                    Marshal.FreeCoTaskMem(value.Value);
                }
            }
            System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(s.Path)!);
            ((IPersistFile)link).Save(s.Path, true);
        }
        finally
        {
            Marshal.ReleaseComObject(link);
        }
    }
}
