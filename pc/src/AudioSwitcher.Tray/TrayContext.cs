using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using AudioSwitcher.Server;

namespace AudioSwitcher.Tray;

/// <summary>タスクトレイのアイコンとメニュー。画面はこれだけ。</summary>
internal sealed class TrayContext : ApplicationContext
{
    private readonly AudioStreamService _service;
    private readonly NotifyIcon _icon;
    private readonly ToolStripMenuItem _status;
    private readonly WindowsFormsSynchronizationContext _ui = new();

    public TrayContext(AudioStreamService service, int port, string logDirectory)
    {
        _service = service;
        _status = new ToolStripMenuItem { Enabled = false };

        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem("AudioSwitcher（試作）") { Enabled = false });
        menu.Items.Add(_status);
        menu.Items.Add(new ToolStripSeparator());
        foreach (var url in StreamUrls(port))
        {
            menu.Items.Add($"{url} をコピー", null, (_, _) => Clipboard.SetText(url));
        }

        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("ログのフォルダを開く", null, (_, _) => Process.Start("explorer.exe", logDirectory));
        menu.Items.Add("終了", null, (_, _) => ExitThread());

        _icon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            ContextMenuStrip = menu,
            Visible = true,
        };

        UpdateStatus(service.IsStreaming);
        service.StreamingChanged += OnStreamingChanged;
    }

    protected override void ExitThreadCore()
    {
        _service.StreamingChanged -= OnStreamingChanged;
        _icon.Visible = false;
        _icon.Dispose();
        base.ExitThreadCore();
    }

    private void OnStreamingChanged(bool streaming) => _ui.Post(_ => UpdateStatus(streaming), null);

    private void UpdateStatus(bool streaming)
    {
        var text = streaming ? "送信中" : "待ち受け中";
        _status.Text = $"状態: {text}";
        _icon.Text = $"AudioSwitcher - {text}";
    }

    /// <summary>この PC の、同じ Wi-Fi から届きそうな IPv4 のアドレスごとの URL。</summary>
    private static IEnumerable<string> StreamUrls(int port) =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up
                && n.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel))
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
            .Select(a => $"http://{a.Address}:{port}{StreamServer.StreamPath}")
            .Distinct();
}
