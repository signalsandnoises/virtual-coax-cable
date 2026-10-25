using System.Globalization;
using System.Text;

namespace Vcx.Sender;

/// <summary>
/// Thread-safe line logger: &lt;dir&gt;\&lt;prefix&gt;-yyyyMMdd-HHmmss.log, new file when the current one exceeds
/// <c>maxBytes</c>, oldest files deleted beyond <c>maxFiles</c>. Never throws to the caller.
/// </summary>
public sealed class RollingFileLog : IDisposable
{
    private readonly object _lock = new();
    private readonly string _dir;
    private readonly string _prefix;
    private readonly long _maxBytes;
    private readonly int _maxFiles;
    private StreamWriter? _w;
    private long _bytes;
    private int _seq;

    public string Directory => _dir;
    public string? CurrentFile { get; private set; }

    public RollingFileLog(string dir, string prefix = "vcx-tray", long maxBytes = 2_000_000, int maxFiles = 10)
    {
        _dir = dir;
        _prefix = prefix;
        _maxBytes = maxBytes;
        _maxFiles = Math.Max(1, maxFiles);
    }

    public static string DefaultDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "vcx", "logs");

    public void Write(string message)
    {
        string line = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff", CultureInfo.InvariantCulture) + "  " + message;
        lock (_lock)
        {
            try
            {
                if (_w == null || _bytes >= _maxBytes) Roll();
                _w!.WriteLine(line);
                _w.Flush();
                _bytes += Encoding.UTF8.GetByteCount(line) + 2;
            }
            catch
            {
                // logging must never take the app down
            }
        }
    }

    private void Roll()
    {
        _w?.Dispose();
        System.IO.Directory.CreateDirectory(_dir);
        string stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture);
        // Fixed-width names, so ordinal order = age: <prefix>-yyyyMMdd-HHmmss-NNN.log
        string path;
        do path = Path.Combine(_dir, $"{_prefix}-{stamp}-{_seq++ % 1000:D3}.log"); while (File.Exists(path));
        _w = new StreamWriter(new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.ReadWrite), new UTF8Encoding(false));
        _bytes = 0;
        CurrentFile = path;
        Prune();
    }

    private void Prune()
    {
        var files = new DirectoryInfo(_dir).GetFiles($"{_prefix}-*.log")
            .OrderByDescending(f => f.Name, StringComparer.Ordinal).ToList();
        foreach (var f in files.Skip(_maxFiles))
        {
            if (string.Equals(f.FullName, CurrentFile, StringComparison.OrdinalIgnoreCase)) continue;
            try { f.Delete(); } catch { /* in use or gone */ }
        }
    }

    public void Dispose()
    {
        lock (_lock)
        {
            _w?.Dispose();
            _w = null;
        }
    }
}
