using System.Runtime.InteropServices;
using AudioSwitcher.Server.Pairing;
using Microsoft.Extensions.Logging;

namespace AudioSwitcher.Tray;

/// <summary>
/// ペアリングの数字を、画面の右下に白基調の小さなポップアップで出す（docs/構成.md の 2.3）。
/// </summary>
/// <remarks>
/// 通知のように扱う: 出すときに入力の焦点を奪わず、タスクバーや Alt+Tab にも出さない。
/// 残り時間は、数字を出したときから単調な時計で数える（外れて数字が変わったら、また 2:00 から）。
/// × で閉じても、ペアリングは続く。
/// 置き場所は、出す前と、出したあと（裏の窓ができたあと）にもう一度決め、置いた位置をログに残す
/// （ほかの画面の真ん中に出たことがあり、原因を突き止められていないため。docs/構成.md の 2.3）。
/// </remarks>
internal sealed class PairingCodeForm : Form
{
    private const int ScreenMargin = 16;
    private const int WsExToolWindow = 0x00000080;
    private const int WsExTopmost = 0x00000008;
    private const int WsExNoActivate = 0x08000000;
    private const int DwmwaWindowCornerPreference = 33;
    private const int DwmwcpRound = 2;

    // 白基調の配色。
    private static readonly Color Surface = Color.White;
    private static readonly Color Outline = Color.FromArgb(0xD0, 0xD7, 0xDE);
    private static readonly Color TextPrimary = Color.FromArgb(0x1F, 0x23, 0x28);
    private static readonly Color TextSecondary = Color.FromArgb(0x57, 0x60, 0x6A);
    private static readonly Color Accent = Color.FromArgb(0x09, 0x69, 0xDA);
    private static readonly Color HoverFill = Color.FromArgb(0xEE, 0xF1, 0xF4);

    private readonly TimeProvider _time;
    private readonly ILogger _logger;
    private readonly Label _code;
    private readonly Label _remaining;
    private readonly System.Windows.Forms.Timer _tick;
    private long _shownAt;

    /// <param name="icon">窓のアイコン。持ち主は呼び出し側で、この窓は片付けない。</param>
    /// <param name="logger">置いた位置を書くログ。</param>
    public PairingCodeForm(Icon icon, ILogger logger)
        : this(icon, logger, TimeProvider.System)
    {
    }

    /// <param name="time">残り時間を数える時計（単調な時刻を使う）。</param>
    public PairingCodeForm(Icon icon, ILogger logger, TimeProvider time)
    {
        _time = time;
        _logger = logger;
        Text = "AudioSwitcher - ペアリング";
        Icon = icon;
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoScaleDimensions = new SizeF(96F, 96F);
        BackColor = Surface;
        ClientSize = new Size(300, 168);

        var title = new Label
        {
            Text = "AudioSwitcher  ペアリング",
            Font = new Font("Yu Gothic UI", 9F),
            ForeColor = TextSecondary,
            AutoSize = true,
            Location = new Point(16, 14),
        };
        var close = new Button
        {
            Text = "✕",
            Font = new Font("Segoe UI", 9F),
            ForeColor = TextSecondary,
            BackColor = Surface,
            FlatStyle = FlatStyle.Flat,
            Size = new Size(28, 28),
            Location = new Point(300 - 28 - 8, 8),
            TabStop = false,
            Cursor = Cursors.Hand,
            AccessibleName = "閉じる",
        };
        close.FlatAppearance.BorderSize = 0;
        close.FlatAppearance.MouseOverBackColor = HoverFill;
        close.Click += (_, _) => Hide();

        _code = new Label
        {
            Font = new Font("Segoe UI Semibold", 36F),
            ForeColor = TextPrimary,
            AutoSize = false,
            Size = new Size(140, 64),
            Location = new Point(12, 38),
            TextAlign = ContentAlignment.MiddleLeft,
        };
        var hint = new Label
        {
            Text = "端末で、同じ数字を選んでください",
            Font = new Font("Yu Gothic UI", 10F),
            ForeColor = TextPrimary,
            AutoSize = true,
            Location = new Point(16, 106),
        };
        _remaining = new Label
        {
            Font = new Font("Yu Gothic UI", 9F),
            ForeColor = Accent,
            AutoSize = true,
            Location = new Point(16, 134),
        };
        Controls.AddRange([title, close, _code, hint, _remaining]);

        _tick = new System.Windows.Forms.Timer { Interval = 250 };
        _tick.Tick += (_, _) => UpdateRemaining();
    }

    protected override bool ShowWithoutActivation => true;

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            cp.ExStyle |= WsExToolWindow | WsExNoActivate | WsExTopmost;
            return cp;
        }
    }

    public void ShowCode(string code)
    {
        _code.Text = code;
        _shownAt = _time.GetTimestamp();
        UpdateRemaining();
        PlaceAtBottomRight("出す前");
        if (!Visible)
        {
            Show();
            // 裏の窓を作るときに位置が変わっても、右下に戻す。
            PlaceAtBottomRight("出したあと");
        }

        _tick.Start();
    }

    /// <summary>残り時間を「分:秒」にする。端数は切り上げる（「0:00」は期限が切れたときだけ）。</summary>
    internal static string FormatRemaining(TimeSpan left)
    {
        int seconds = (int)Math.Ceiling(Math.Max(0, left.TotalSeconds));
        return $"{seconds / 60}:{seconds % 60:00}";
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        // Windows 11 の丸い角にする。古い Windows では効かずに失敗の値が返るだけで、見た目が角張るほかに支障は無い。
        int preference = DwmwcpRound;
        _ = DwmSetWindowAttribute(Handle, DwmwaWindowCornerPreference, ref preference, sizeof(int));
    }

    protected override void OnVisibleChanged(EventArgs e)
    {
        base.OnVisibleChanged(e);
        if (!Visible)
        {
            _tick.Stop();
        }
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        using var pen = new Pen(Outline);
        e.Graphics.DrawRectangle(pen, 0, 0, ClientSize.Width - 1, ClientSize.Height - 1);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _tick.Dispose();
        }

        base.Dispose(disposing);
    }

    private void UpdateRemaining()
    {
        var left = PairingService.CodeLifetime - _time.GetElapsedTime(_shownAt);
        _remaining.Text = $"残り {FormatRemaining(left)}";
    }

    /// <summary>タスクバーを除いた作業領域の右下に置く（通知が出る場所と同じ）。置いた位置をログに残す。</summary>
    private void PlaceAtBottomRight(string when)
    {
        var screen = Screen.PrimaryScreen ?? Screen.AllScreens[0];
        var area = screen.WorkingArea;
        var target = new Point(area.Right - Width - ScreenMargin, area.Bottom - Height - ScreenMargin);
        Location = target;
        _logger.LogInformation(
            "ペアリングの数字の窓を置いた（{When}）: 狙い {Target}、実際 {Actual}、置いた画面 {Screen} の作業領域 {Area}、画面の並び {Screens}",
            when,
            target,
            Bounds,
            screen.DeviceName,
            area,
            string.Join("、", Screen.AllScreens.Select(s => $"{s.DeviceName}{(s.Primary ? "（主）" : "")} {s.Bounds}")));
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
}
