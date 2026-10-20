using System.Text;
using Microsoft.Extensions.Logging;

namespace AudioSwitcher.Tray.Logging;

/// <summary>ログを日ごとのファイルに書く。トレイ常駐で画面が無いので、何が起きたかはここで追う。</summary>
internal sealed class FileLoggerProvider : ILoggerProvider
{
    private readonly string _directory;
    private readonly TimeProvider _time;
    private readonly object _gate = new();

    public FileLoggerProvider(string directory, TimeProvider time)
    {
        _directory = directory;
        _time = time;
        Directory.CreateDirectory(directory);
    }

    public ILogger CreateLogger(string categoryName) => new FileLogger(this, categoryName);

    public void Dispose()
    {
    }

    private void Write(LogLevel level, string category, string message, Exception? exception)
    {
        var now = _time.GetLocalNow();
        var line = new StringBuilder()
            .Append(now.ToString("yyyy-MM-dd HH:mm:ss.fff"))
            .Append(" [").Append(level).Append("] ")
            .Append(category).Append(": ")
            .Append(message);
        if (exception is not null)
        {
            line.AppendLine().Append(exception);
        }

        line.AppendLine();
        var path = Path.Combine(_directory, $"AudioSwitcher-{now:yyyyMMdd}.log");
        lock (_gate)
        {
            try
            {
                File.AppendAllText(path, line.ToString());
            }
            catch (IOException ex)
            {
                // ログが書けないことをログには書けないので、デバッガの出力に残す。
                System.Diagnostics.Debug.WriteLine($"ログを書けなかった: {ex.Message}");
            }
        }
    }

    private sealed class FileLogger : ILogger
    {
        private readonly FileLoggerProvider _provider;
        private readonly string _category;

        public FileLogger(FileLoggerProvider provider, string category)
        {
            _provider = provider;
            _category = category;
        }

        public IDisposable? BeginScope<TState>(TState state)
            where TState : notnull => null;

        // ASP.NET Core は要求ごとに Information を出すので、Microsoft.* は Warning 以上だけを残す。
        public bool IsEnabled(LogLevel logLevel) =>
            logLevel >= (_category.StartsWith("Microsoft.", StringComparison.Ordinal) ? LogLevel.Warning : LogLevel.Information);

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel))
            {
                return;
            }

            _provider.Write(logLevel, _category, formatter(state, exception), exception);
        }
    }
}
