using AudioSwitcher.Core.Encoding;
using AudioSwitcher.Server;
using AudioSwitcher.Tray.Audio;
using AudioSwitcher.Tray.Logging;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Logging;

namespace AudioSwitcher.Tray;

internal static class Program
{
    /// <summary>待ち受けのポートの既定。起動の引数 <c>--port 番号</c> で変えられる。</summary>
    private const int DefaultPort = 48100;

    [STAThread]
    private static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();

        var logDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AudioSwitcher", "logs");
        var logProvider = new FileLoggerProvider(logDirectory, TimeProvider.System);
        using var loggerFactory = LoggerFactory.Create(b => b.AddProvider(logProvider));
        var logger = loggerFactory.CreateLogger(typeof(Program));

        Application.ThreadException += (_, e) => logger.LogError(e.Exception, "画面のスレッドで想定外の例外");
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
            logger.LogCritical(e.ExceptionObject as Exception, "想定外の例外で終了する");

        int port = ParsePort(args) ?? DefaultPort;
        var service = new AudioStreamService(
            () => new WasapiLoopbackSource(loggerFactory.CreateLogger<WasapiLoopbackSource>()),
            new OggOpusEncoderFactory(),
            TimeProvider.System,
            loggerFactory.CreateLogger<AudioStreamService>());

        var app = StreamServer.Build(service, builder =>
        {
            builder.WebHost.ConfigureKestrel(k => k.ListenAnyIP(port));
            builder.Logging.ClearProviders();
            builder.Logging.AddProvider(logProvider);
        });

        try
        {
            app.StartAsync().GetAwaiter().GetResult();
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "ポート {Port} で待ち受けを始められなかった", port);
            MessageBox.Show(
                $"ポート {port} で待ち受けを始められませんでした。\n{ex.Message}",
                "AudioSwitcher",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            return;
        }

        logger.LogInformation("ポート {Port} で待ち受けを始めた", port);
        using (var tray = new TrayContext(service, port, logDirectory))
        {
            Application.Run(tray);
        }

        logger.LogInformation("終了する");
        app.StopAsync().GetAwaiter().GetResult();
        app.DisposeAsync().AsTask().GetAwaiter().GetResult();
    }

    private static int? ParsePort(string[] args)
    {
        int i = Array.IndexOf(args, "--port");
        if (i >= 0 && i + 1 < args.Length && int.TryParse(args[i + 1], out int port) && port is > 0 and < 65536)
        {
            return port;
        }

        return null;
    }
}
