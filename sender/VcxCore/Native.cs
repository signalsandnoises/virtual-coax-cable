using System.Diagnostics;
using System.Runtime.InteropServices;

namespace Vcx.Sender;

internal static class Native
{
    public const uint CREATE_WAITABLE_TIMER_HIGH_RESOLUTION = 0x00000002;
    public const uint TIMER_ALL_ACCESS = 0x001F0003;
    public const uint WAIT_OBJECT_0 = 0;
    public const uint WAIT_TIMEOUT = 0x102;
    public const uint WAIT_FAILED = 0xFFFFFFFF;

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern IntPtr CreateWaitableTimerExW(IntPtr lpTimerAttributes, string? lpTimerName, uint dwFlags, uint dwDesiredAccess);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool SetWaitableTimer(IntPtr hTimer, ref long pDueTime, int lPeriod, IntPtr pfnCompletionRoutine,
        IntPtr lpArgToCompletionRoutine, [MarshalAs(UnmanagedType.Bool)] bool fResume);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern uint WaitForMultipleObjects(uint nCount, IntPtr[] lpHandles, [MarshalAs(UnmanagedType.Bool)] bool bWaitAll, uint dwMilliseconds);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool CloseHandle(IntPtr h);

    [DllImport("avrt.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern IntPtr AvSetMmThreadCharacteristicsW(string taskName, ref uint taskIndex);

    [DllImport("avrt.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool AvSetMmThreadPriority(IntPtr avrtHandle, int priority);

    [DllImport("avrt.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool AvRevertMmThreadCharacteristics(IntPtr avrtHandle);

    public const int AVRT_PRIORITY_HIGH = 1;
}

/// <summary>Microsecond clock on QueryPerformanceCounter (Stopwatch).</summary>
public static class QpcClock
{
    private static readonly double UsPerTick = 1_000_000.0 / Stopwatch.Frequency;
    public static double NowUs() => Stopwatch.GetTimestamp() * UsPerTick;
    public static ulong NowUsInt() => (ulong)NowUs();
}

/// <summary>High-resolution waitable timer (CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, Win10 1803+).</summary>
internal sealed class HighResTimer : IDisposable
{
    public IntPtr Handle { get; }
    public bool IsHighResolution { get; }

    public HighResTimer()
    {
        Handle = Native.CreateWaitableTimerExW(IntPtr.Zero, null, Native.CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, Native.TIMER_ALL_ACCESS);
        IsHighResolution = Handle != IntPtr.Zero;
        if (Handle == IntPtr.Zero)
            Handle = Native.CreateWaitableTimerExW(IntPtr.Zero, null, 0, Native.TIMER_ALL_ACCESS);
        if (Handle == IntPtr.Zero)
            throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "CreateWaitableTimerExW failed");
    }

    /// <summary>Arms the timer to fire after <paramref name="us"/> microseconds (relative).</summary>
    public void ArmRelativeUs(double us)
    {
        long due = -Math.Max(1L, (long)(us * 10.0)); // negative = relative, 100 ns units
        if (!Native.SetWaitableTimer(Handle, ref due, 0, IntPtr.Zero, IntPtr.Zero, false))
            throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "SetWaitableTimer failed");
    }

    public void Dispose() => Native.CloseHandle(Handle);
}
