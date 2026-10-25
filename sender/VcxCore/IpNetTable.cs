using System.Net;
using System.Runtime.InteropServices;

namespace Vcx.Sender;

/// <summary>Reads the IPv4 ARP table (GetIpNetTable, iphlpapi; no admin needed).</summary>
internal static class IpNetTable
{
    private const int ErrorInsufficientBuffer = 122;
    private const int TypeInvalid = 2;
    // MIB_IPNETROW: dwIndex, dwPhysAddrLen, bPhysAddr[8], dwAddr, dwType
    private const int RowSize = 24;
    private const int AddrOffset = 16;
    private const int TypeOffset = 20;

    [DllImport("iphlpapi.dll")]
    private static extern int GetIpNetTable(IntPtr table, ref int size, bool order);

    public static List<IPAddress> Read()
    {
        var result = new List<IPAddress>();
        if (!OperatingSystem.IsWindows()) return result;
        int size = 0;
        if (GetIpNetTable(IntPtr.Zero, ref size, false) != ErrorInsufficientBuffer || size <= 0) return result;
        IntPtr buf = Marshal.AllocHGlobal(size);
        try
        {
            if (GetIpNetTable(buf, ref size, false) != 0) return result;
            int n = Marshal.ReadInt32(buf);
            for (int i = 0; i < n; i++)
            {
                IntPtr row = buf + 4 + i * RowSize;
                if (Marshal.ReadInt32(row, TypeOffset) == TypeInvalid) continue;
                var a = new IPAddress(BitConverter.GetBytes(Marshal.ReadInt32(row, AddrOffset)));
                var b = a.GetAddressBytes();
                if (b[0] >= 224) continue; // multicast / broadcast
                result.Add(a);
            }
        }
        finally { Marshal.FreeHGlobal(buf); }
        return result;
    }
}
