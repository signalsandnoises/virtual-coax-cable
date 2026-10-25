using System.Net;
using System.Net.Sockets;
using Vcx.Sender;

namespace Vcx.Tray;

/// <summary>Small settings window, built in code.</summary>
public sealed class SettingsForm : Form
{
    private const string AutoItem = "Automatic (first phone that answers)";
    private const string ManualItem = "Manual IP address";

    private readonly ComboBox _phone = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 260 };
    private readonly TextBox _ip = new() { Width = 260, PlaceholderText = "e.g. 192.168.137.50" };
    private readonly NumericUpDown _frames = new() { Minimum = 1, Maximum = 4800, Width = 80 };
    private readonly NumericUpDown _k = new() { Minimum = 0, Maximum = 8, Width = 80 };
    private readonly CheckBox _startWithWindows = new() { Text = "Start with Windows", AutoSize = true };
    private readonly CheckBox _streamOnLaunch = new() { Text = "Start streaming on launch", AutoSize = true };
    private readonly CheckBox _notifications = new() { Text = "Notifications (phone connected / lost)", AutoSize = true };
    private readonly AppConfig _original;

    public AppConfig Result { get; private set; }

    public SettingsForm(AppConfig cfg, IReadOnlyList<DiscoveredPhone> phones)
    {
        _original = cfg.Clone();
        Result = cfg.Clone();
        Text = "VCX sender settings";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        Padding = new Padding(12);
        ShowInTaskbar = true;
        TopMost = true;

        _phone.Items.Add(AutoItem);
        var names = phones.Select(p => p.Name).ToList();
        if (cfg.PhoneMode == PhoneMode.Device && cfg.PhoneName != null && !names.Contains(cfg.PhoneName)) names.Add(cfg.PhoneName);
        foreach (var n in names) _phone.Items.Add(n);
        _phone.Items.Add(ManualItem);
        _phone.SelectedItem = cfg.PhoneMode switch
        {
            PhoneMode.Device => cfg.PhoneName,
            PhoneMode.Manual => ManualItem,
            _ => AutoItem,
        };
        _ip.Text = cfg.PhoneMode == PhoneMode.Manual ? cfg.PhoneIp : "";
        _phone.SelectedIndexChanged += (_, _) => _ip.Enabled = (string?)_phone.SelectedItem == ManualItem;
        _ip.Enabled = (string?)_phone.SelectedItem == ManualItem;
        _frames.Value = cfg.Frames;
        _k.Value = cfg.Redundancy;
        _startWithWindows.Checked = cfg.StartWithWindows;
        _streamOnLaunch.Checked = cfg.StartStreamingOnLaunch;
        _notifications.Checked = cfg.Notifications;

        var grid = new TableLayoutPanel { ColumnCount = 2, AutoSize = true, Dock = DockStyle.Fill };
        void Row(string label, Control c)
        {
            grid.Controls.Add(new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(0, 6, 8, 0) });
            grid.Controls.Add(c);
        }
        Row("Phone", _phone);
        Row("Manual IP", _ip);
        Row("Frames per packet", _frames);
        grid.Controls.Add(new Label());
        grid.Controls.Add(new Label { Text = "120 = 2.5 ms at 48 kHz", AutoSize = true, ForeColor = SystemColors.GrayText });
        Row("Redundancy (K)", _k);
        grid.Controls.Add(new Label());
        grid.Controls.Add(_startWithWindows);
        grid.Controls.Add(new Label());
        grid.Controls.Add(_streamOnLaunch);
        grid.Controls.Add(new Label());
        grid.Controls.Add(_notifications);

        var ok = new Button { Text = "Save", DialogResult = DialogResult.OK, AutoSize = true };
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, AutoSize = true };
        var buttons = new FlowLayoutPanel { FlowDirection = FlowDirection.RightToLeft, AutoSize = true, Dock = DockStyle.Fill };
        buttons.Controls.Add(cancel);
        buttons.Controls.Add(ok);
        grid.Controls.Add(new Label());
        grid.Controls.Add(buttons);
        Controls.Add(grid);
        AcceptButton = ok;
        CancelButton = cancel;
        ok.Click += (_, _) => { if (!TryBuild()) DialogResult = DialogResult.None; };
    }

    private bool TryBuild()
    {
        var c = _original.Clone();
        string sel = (string?)_phone.SelectedItem ?? AutoItem;
        if (sel == ManualItem)
        {
            if (!TryParseIp(_ip.Text, out var ip))
            {
                MessageBox.Show(this, "Enter an IPv4 address, e.g. 192.168.137.50", Text, MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return false;
            }
            c.PhoneMode = PhoneMode.Manual;
            c.PhoneIp = ip.ToString();
            c.PhonePort = Protocol.AudioPort;
        }
        else if (sel == AutoItem)
        {
            c.PhoneMode = PhoneMode.Auto;
        }
        else
        {
            if (c.PhoneName != sel) c.PhoneIp = null; // learn the new phone's IP by discovery
            c.PhoneMode = PhoneMode.Device;
            c.PhoneName = sel;
        }
        c.Frames = (int)_frames.Value;
        c.Redundancy = (int)_k.Value;
        c.StartWithWindows = _startWithWindows.Checked;
        c.StartStreamingOnLaunch = _streamOnLaunch.Checked;
        c.Notifications = _notifications.Checked;
        Result = c.Normalize();
        return true;
    }

    public static bool TryParseIp(string? s, out IPAddress ip) =>
        IPAddress.TryParse((s ?? "").Trim(), out ip!) && ip.AddressFamily == AddressFamily.InterNetwork;
}

/// <summary>"Manual IP…" prompt.</summary>
public sealed class IpPrompt : Form
{
    private readonly TextBox _box = new() { Width = 220 };
    public IPAddress? Result { get; private set; }

    public IpPrompt(string? current)
    {
        Text = "Phone IP address";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        Padding = new Padding(12);
        TopMost = true;
        _box.Text = current ?? "";
        var ok = new Button { Text = "OK", DialogResult = DialogResult.OK, AutoSize = true };
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, AutoSize = true };
        var panel = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, Dock = DockStyle.Fill };
        panel.Controls.Add(new Label { Text = "Phone IP (shown in the receiver app):", AutoSize = true });
        panel.Controls.Add(_box);
        var row = new FlowLayoutPanel { AutoSize = true };
        row.Controls.Add(ok);
        row.Controls.Add(cancel);
        panel.Controls.Add(row);
        Controls.Add(panel);
        AcceptButton = ok;
        CancelButton = cancel;
        ok.Click += (_, _) =>
        {
            if (SettingsForm.TryParseIp(_box.Text, out var ip)) Result = ip;
            else
            {
                MessageBox.Show(this, "Enter an IPv4 address, e.g. 192.168.137.50", Text, MessageBoxButtons.OK, MessageBoxIcon.Warning);
                DialogResult = DialogResult.None;
            }
        };
    }
}
