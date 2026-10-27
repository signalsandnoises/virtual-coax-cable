using System.Reflection;
using System.Runtime.InteropServices;

namespace Vcx.Tray;

/// <summary>
/// Balloon/toast without the notification sound (NIIF_NOSOUND), which NotifyIcon.ShowBalloonTip cannot
/// ask for. Needs NotifyIcon's private window handle and icon id (WinForms on .NET 8); when those are
/// not found, <see cref="TryShow"/> returns false and the caller falls back to ShowBalloonTip.
/// </summary>
internal static class SilentBalloon
{
    private const uint NIM_MODIFY = 1;
    private const uint NIF_INFO = 0x10;
    private const uint NIIF_INFO = 0x1;
    private const uint NIIF_NOSOUND = 0x10;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NOTIFYICONDATA
    {
        public int cbSize;
        public IntPtr hWnd;
        public int uID;
        public uint uFlags;
        public int uCallbackMessage;
        public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string szTip;
        public int dwState;
        public int dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string szInfo;
        public int uTimeoutOrVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string szInfoTitle;
        public uint dwInfoFlags;
        public Guid guidItem;
        public IntPtr hBalloonIcon;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern bool Shell_NotifyIcon(uint dwMessage, ref NOTIFYICONDATA lpData);

    private static readonly FieldInfo? WindowField =
        typeof(NotifyIcon).GetField("_window", BindingFlags.NonPublic | BindingFlags.Instance);
    private static readonly FieldInfo? IdField =
        typeof(NotifyIcon).GetField("_id", BindingFlags.NonPublic | BindingFlags.Instance);

    public static bool TryShow(NotifyIcon icon, int timeoutMs, string title, string text)
    {
        if (WindowField?.GetValue(icon) is not NativeWindow window || window.Handle == IntPtr.Zero) return false;
        if (IdField?.GetValue(icon) is not uint id) return false;
        var data = new NOTIFYICONDATA
        {
            cbSize = Marshal.SizeOf<NOTIFYICONDATA>(),
            hWnd = window.Handle,
            uID = (int)id,
            uFlags = NIF_INFO,
            szTip = "",
            szInfo = text,
            uTimeoutOrVersion = timeoutMs,
            szInfoTitle = title,
            dwInfoFlags = NIIF_INFO | NIIF_NOSOUND,
        };
        return Shell_NotifyIcon(NIM_MODIFY, ref data);
    }
}
