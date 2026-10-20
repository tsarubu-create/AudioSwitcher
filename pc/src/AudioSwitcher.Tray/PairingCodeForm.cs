namespace AudioSwitcher.Tray;

/// <summary>ペアリングの数字を、画面の手前に大きく出す小さな窓。</summary>
internal sealed class PairingCodeForm : Form
{
    private readonly Label _code;

    public PairingCodeForm()
    {
        Text = "AudioSwitcher - ペアリング";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        ShowInTaskbar = true;
        TopMost = true;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(360, 220);

        _code = new Label
        {
            Dock = DockStyle.Fill,
            Font = new Font(FontFamily.GenericSansSerif, 72, FontStyle.Bold),
            TextAlign = ContentAlignment.MiddleCenter,
        };
        var hint = new Label
        {
            Dock = DockStyle.Bottom,
            Height = 56,
            Text = "端末で、この数字と同じものを選んでください。\n2分で無効になります。",
            TextAlign = ContentAlignment.MiddleCenter,
        };
        Controls.Add(_code);
        Controls.Add(hint);
    }

    public void ShowCode(string code)
    {
        _code.Text = code;
        if (!Visible)
        {
            Show();
        }

        Activate();
    }
}
