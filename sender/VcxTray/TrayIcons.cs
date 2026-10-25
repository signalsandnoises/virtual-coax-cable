using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using Vcx.Sender;

namespace Vcx.Tray;

/// <summary>Tray icons drawn in code: a colored disc per state, with sound arcs while streaming or "!" on error.</summary>
public sealed class TrayIcons : IDisposable
{
    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr hIcon);

    private readonly Dictionary<TrayState, (Icon icon, IntPtr handle)> _icons = new();

    public TrayIcons(int size)
    {
        foreach (TrayState s in Enum.GetValues<TrayState>()) _icons[s] = Draw(s, size);
    }

    public Icon this[TrayState s] => _icons[s].icon;

    public static Color ColorOf(TrayState s) => s switch
    {
        TrayState.Healthy => Color.FromArgb(46, 160, 67),
        TrayState.NoStats => Color.FromArgb(230, 160, 20),
        TrayState.Error => Color.FromArgb(210, 45, 45),
        _ => Color.FromArgb(128, 128, 128),
    };

    private static (Icon, IntPtr) Draw(TrayState state, int size)
    {
        using var bmp = new Bitmap(size, size);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            float pad = size / 16f;
            var disc = new RectangleF(pad, pad, size - 2 * pad, size - 2 * pad);
            using (var b = new SolidBrush(ColorOf(state))) g.FillEllipse(b, disc);
            float w = Math.Max(1.5f, size / 10f);
            using var pen = new Pen(Color.White, w) { StartCap = LineCap.Round, EndCap = LineCap.Round };
            float cx = size * 0.34f, cy = size / 2f;
            switch (state)
            {
                case TrayState.Error:
                    g.DrawLine(pen, size / 2f, size * 0.25f, size / 2f, size * 0.58f);
                    using (var wb = new SolidBrush(Color.White)) g.FillEllipse(wb, size / 2f - w * 0.75f, size * 0.70f, w * 1.5f, w * 1.5f);
                    break;
                case TrayState.Idle:
                    // "stopped": a small square
                    using (var wb = new SolidBrush(Color.White)) g.FillRectangle(wb, size * 0.34f, size * 0.34f, size * 0.32f, size * 0.32f);
                    break;
                default:
                    // a dot and two arcs (sound waves)
                    using (var wb = new SolidBrush(Color.White)) g.FillEllipse(wb, cx - w, cy - w, 2 * w, 2 * w);
                    for (int i = 1; i <= 2; i++)
                    {
                        float r = size * (0.16f + 0.15f * i);
                        g.DrawArc(pen, cx - r, cy - r, 2 * r, 2 * r, -45, 90);
                    }
                    break;
            }
        }
        IntPtr h = bmp.GetHicon();
        return (Icon.FromHandle(h), h);
    }

    public void Dispose()
    {
        foreach (var (icon, handle) in _icons.Values)
        {
            icon.Dispose();
            DestroyIcon(handle);
        }
        _icons.Clear();
    }
}
