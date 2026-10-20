using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using AudioSwitcher.Server;
using AudioSwitcher.Server.Pairing;

namespace AudioSwitcher.Tray;

/// <summary>タスクトレイのアイコンとメニュー。画面はこれだけ。</summary>
internal sealed class TrayContext : ApplicationContext
{
    private readonly AudioStreamService _service;
    private readonly PairingService _pairing;
    private readonly NotifyIcon _icon;
    private readonly Icon _streamingIcon = LoadIcon("app");
    private readonly Icon _idleIcon = LoadIcon("idle");
    private readonly ToolStripMenuItem _status;
    private readonly ToolStripMenuItem _pairingStatus;
    private readonly ToolStripMenuItem _unpair;
    private readonly ToolStripMenuItem _clearLock;
    private readonly WindowsFormsSynchronizationContext _ui = new();
    private PairingCodeForm? _codeForm;

    public TrayContext(AudioStreamService service, PairingService pairing, int port, string logDirectory)
    {
        _service = service;
        _pairing = pairing;
        _status = new ToolStripMenuItem { Enabled = false };
        _pairingStatus = new ToolStripMenuItem { Enabled = false };
        _unpair = new ToolStripMenuItem("ペアリングを解除", null, (_, _) => ConfirmUnpair());
        _clearLock = new ToolStripMenuItem("受け付けない状態を解く", null, (_, _) => _pairing.ClearLock());

        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem("AudioSwitcher（試作）") { Enabled = false });
        menu.Items.Add(_status);
        menu.Items.Add(new ToolStripSeparator());
        foreach (var address in LocalAddresses(port))
        {
            menu.Items.Add($"{address} をコピー（端末の接続先）", null, (_, _) => Clipboard.SetText(address));
        }

        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(_pairingStatus);
        menu.Items.Add(_unpair);
        menu.Items.Add(_clearLock);

        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("ログのフォルダを開く", null, (_, _) => Process.Start("explorer.exe", logDirectory));
        menu.Items.Add("終了", null, (_, _) => ExitThread());

        _icon = new NotifyIcon
        {
            Icon = _idleIcon,
            ContextMenuStrip = menu,
            Visible = true,
        };

        // 受け付けない状態の期限は時間で明けるので、メニューを開くたびに出し直す。
        menu.Opening += (_, _) => UpdatePairingMenu();

        UpdateStatus(service.IsStreaming);
        UpdatePairingMenu();
        service.StreamingChanged += OnStreamingChanged;
        pairing.StateChanged += OnPairingStateChanged;
        pairing.CodeChanged += OnCodeChanged;
    }

    protected override void ExitThreadCore()
    {
        _service.StreamingChanged -= OnStreamingChanged;
        _pairing.StateChanged -= OnPairingStateChanged;
        _pairing.CodeChanged -= OnCodeChanged;
        _codeForm?.Dispose();
        _icon.Visible = false;
        _icon.Dispose();
        _streamingIcon.Dispose();
        _idleIcon.Dispose();
        base.ExitThreadCore();
    }

    private void OnStreamingChanged(bool streaming) => _ui.Post(_ => UpdateStatus(streaming), null);

    private void OnPairingStateChanged() => _ui.Post(_ => UpdatePairingMenu(), null);

    private void OnCodeChanged(string? code) => _ui.Post(_ => ShowCode(code), null);

    private void ShowCode(string? code)
    {
        if (code is null)
        {
            _codeForm?.Hide();
            return;
        }

        _codeForm ??= new PairingCodeForm();
        _codeForm.ShowCode(code);
    }

    private void UpdatePairingMenu()
    {
        var lockedUntil = _pairing.LockedUntil;
        _pairingStatus.Text = _pairing.IsPaired ? "ペアリング: 済み" : "ペアリング: まだ";
        _unpair.Enabled = _pairing.IsPaired;
        _clearLock.Enabled = lockedUntil is not null;
        _clearLock.Text = lockedUntil is { } until
            ? $"受け付けない状態を解く（{until.ToLocalTime():M/d HH:mm} まで）"
            : "受け付けない状態を解く";
    }

    private void ConfirmUnpair()
    {
        var answer = MessageBox.Show(
            "ペアリングを解除すると、今の端末の鍵では繋げなくなります。解除しますか？",
            "AudioSwitcher",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Question);
        if (answer == DialogResult.Yes)
        {
            _pairing.Unpair();
        }
    }

    private void UpdateStatus(bool streaming)
    {
        var text = streaming ? "送信中" : "待ち受け中";
        _status.Text = $"状態: {text}";
        _icon.Text = $"AudioSwitcher - {text}";
        // 送信中は青、待ち受け中は灰色。
        _icon.Icon = streaming ? _streamingIcon : _idleIcon;
    }

    /// <summary>埋め込んだ .ico から、トレイの寸法（拡大率に応じて 16〜24px）に合う絵を取り出す。</summary>
    private static Icon LoadIcon(string name)
    {
        using var stream = typeof(TrayContext).Assembly.GetManifestResourceStream($"AudioSwitcher.Icons.{name}.ico")
            ?? throw new InvalidOperationException($"アイコン {name}.ico が埋め込まれていない。");
        return new Icon(stream, SystemInformation.SmallIconSize);
    }

    /// <summary>この PC の、同じ Wi-Fi から届きそうな IPv4 のアドレスごとの「IP:ポート」。</summary>
    private static IEnumerable<string> LocalAddresses(int port) =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up
                && n.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel))
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
            .Select(a => $"{a.Address}:{port}")
            .Distinct();
}
