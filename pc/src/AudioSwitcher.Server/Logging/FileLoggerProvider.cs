using System.Text;
using Microsoft.Extensions.Logging;

namespace AudioSwitcher.Server.Logging;

/// <summary>ログのファイルへの追記の口。テストでは失敗させられるものに差し替える。</summary>
internal interface ILogFileWriter
{
    /// <summary>ファイルの末尾に足す。書けなければ <see cref="IOException"/> か <see cref="UnauthorizedAccessException"/> を投げる。</summary>
    void Append(string path, string text);
}

/// <summary>
/// ファイルの末尾に足す。ほかのプロセス（2つ目に起動した AudioSwitcher など）も同じファイルに書くことがある。
/// </summary>
/// <remarks>
/// <see cref="FileMode.Append"/> は「開いたときの末尾」に書くだけで、本当の追記ではない。2つのプロセスが
/// 同時に開くと同じ位置に書き、片方の行が消える。そこで、同じユーザーのプロセスの間で共有する名前付きの
/// ミューテックスで、開いて書いて閉じるまでを1つずつ順に行う。
/// </remarks>
internal sealed class SharedFileWriter : ILogFileWriter, IDisposable
{
    private static readonly UTF8Encoding Utf8 = new(encoderShouldEmitUTF8Identifier: false);
    private static readonly TimeSpan LockTimeout = TimeSpan.FromSeconds(1);
    private readonly Mutex _mutex = new(initiallyOwned: false, @"Local\AudioSwitcher.LogFile");

    public void Append(string path, string text)
    {
        var bytes = Utf8.GetBytes(text);
        try
        {
            if (!_mutex.WaitOne(LockTimeout))
            {
                throw new IOException($"ほかのプロセスが {LockTimeout.TotalSeconds} 秒たっても書き終えない");
            }
        }
        catch (AbandonedMutexException)
        {
            // 持っていたプロセスが書く途中で落ちた。鍵はこちらに移っているので、そのまま書く。
        }

        try
        {
            using var stream = new FileStream(path, FileMode.Append, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete);
            stream.Write(bytes);
        }
        finally
        {
            _mutex.ReleaseMutex();
        }
    }

    public void Dispose() => _mutex.Dispose();
}

/// <summary>
/// ログを日ごとのファイルに書く。トレイ常駐で画面が無いので、何が起きたかはここで追う。
/// </summary>
/// <remarks>
/// 書けないときは黙らない（全体ルール: 例外を握り潰さない）。
/// <list type="bullet">
/// <item>書けなかった行は直近 <see cref="MaxPendingLines"/> 行までメモリに溜め、書けるようになったらまとめて書き出す。</item>
/// <item>書けるようになったとき、いつから何行書けなかったか・なぜか・何行捨てたかを、区切りの行として残す。</item>
/// <item>書けない間は <see cref="WriteFailureChanged"/> で知らせる（トレイのメニューに出す）。</item>
/// </list>
/// </remarks>
/// <summary>ログが書けない理由。</summary>
/// <param name="Summary">画面に出す短い理由。</param>
/// <param name="Detail">Windows の例外の文章（パスを含む）。</param>
internal sealed record LogWriteFailure(string Summary, string Detail);

internal sealed class FileLoggerProvider : ILoggerProvider
{
    public const int MaxPendingLines = 1000;
    private const string Category = "AudioSwitcher.Logging";

    private readonly string _directory;
    private readonly TimeProvider _time;
    private readonly ILogFileWriter _writer;
    private readonly bool _ownsWriter;
    private readonly object _gate = new();
    private readonly Queue<(string Path, string Text)> _pending = new();
    private Failure? _failure;

    public FileLoggerProvider(string directory, TimeProvider time)
        : this(directory, time, new SharedFileWriter())
    {
        _ownsWriter = true;
    }

    public FileLoggerProvider(string directory, TimeProvider time, ILogFileWriter writer)
    {
        _directory = directory;
        _time = time;
        _writer = writer;
        try
        {
            Directory.CreateDirectory(directory);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // ここではまだ知らせる先が無い。最初に書こうとしたときに失敗として扱われ、知らせる。
            System.Diagnostics.Debug.WriteLine($"ログのフォルダを作れなかった: {ex.Message}");
        }
    }

    /// <summary>
    /// 書けなくなったとき（理由）と、また書けるようになったとき（null）に来る。どのスレッドから来るかは決まっていない。
    /// </summary>
    public event Action<LogWriteFailure?>? WriteFailureChanged;

    /// <summary>今、書けていない理由。書けていれば null。</summary>
    public LogWriteFailure? CurrentFailure
    {
        get { lock (_gate) { return _failure is null ? null : new LogWriteFailure(_failure.Summary, _failure.Reason); } }
    }

    /// <summary>
    /// 画面に出す短い理由。Windows の例外の文章は長く（パスを含み）、OS の言語で変わるので、エラーの番号で見分けて置き換える。
    /// </summary>
    public static string Summarize(Exception exception) => exception switch
    {
        UnauthorizedAccessException => "書き込みの権限が無い",
        IOException io when (io.HResult & 0xFFFF) is ErrorSharingViolation or ErrorLockViolation => "ほかのプログラムが使用中",
        IOException io when (io.HResult & 0xFFFF) is ErrorDiskFull or ErrorHandleDiskFull => "ディスクの空きが無い",
        _ => "書き込みに失敗した",
    };

    private const int ErrorSharingViolation = 0x20;
    private const int ErrorLockViolation = 0x21;
    private const int ErrorHandleDiskFull = 0x27;
    private const int ErrorDiskFull = 0x70;

    public ILogger CreateLogger(string categoryName) => new FileLogger(this, categoryName);

    public void Dispose()
    {
        if (_ownsWriter)
        {
            (_writer as IDisposable)?.Dispose();
        }
    }

    private void Write(LogLevel level, string category, string message, Exception? exception)
    {
        var now = _time.GetLocalNow();
        var entry = (Path: PathFor(now), Text: FormatLine(now, level, category, message, exception));

        LogWriteFailure? notify = null;
        bool recovered = false;
        lock (_gate)
        {
            try
            {
                if (_failure is { } failure)
                {
                    // 先に区切りの行を書き、溜めていた行を元の時刻のまま並べる。
                    var summary = FormatLine(
                        now,
                        LogLevel.Warning,
                        Category,
                        $"{failure.Since:yyyy-MM-dd HH:mm:ss} から {failure.FailedLines} 行が書けなかった（{failure.Summary}: {failure.Reason}）。" +
                        $"溜めていた {_pending.Count} 行を、このあとに書き出す。あふれて捨てた行は {failure.DroppedLines} 行。",
                        null);
                    _writer.Append(entry.Path, summary);
                    while (_pending.Count > 0)
                    {
                        var pending = _pending.Peek();
                        _writer.Append(pending.Path, pending.Text);
                        _pending.Dequeue();
                    }

                    _failure = null;
                    recovered = true;
                }

                _writer.Append(entry.Path, entry.Text);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // 書けなかった行は捨てずに溜める。あふれたら古い方から捨てて数える。
                _pending.Enqueue(entry);
                int dropped = 0;
                while (_pending.Count > MaxPendingLines)
                {
                    _pending.Dequeue();
                    dropped++;
                }

                if (_failure is null)
                {
                    _failure = new Failure(now, Summarize(ex), ex.Message);
                    notify = new LogWriteFailure(_failure.Summary, _failure.Reason);
                }

                _failure.FailedLines++;
                _failure.DroppedLines += dropped;
                _failure.Summary = Summarize(ex);
                _failure.Reason = ex.Message;
                recovered = false;
            }
        }

        if (notify is not null)
        {
            WriteFailureChanged?.Invoke(notify);
        }
        else if (recovered)
        {
            WriteFailureChanged?.Invoke(null);
        }
    }

    private string PathFor(DateTimeOffset now) => Path.Combine(_directory, $"AudioSwitcher-{now:yyyyMMdd}.log");

    private static string FormatLine(DateTimeOffset now, LogLevel level, string category, string message, Exception? exception)
    {
        var line = new StringBuilder()
            .Append(now.ToString("yyyy-MM-dd HH:mm:ss.fff"))
            .Append(" [").Append(level).Append("] ")
            .Append(category).Append(": ")
            .Append(message);
        if (exception is not null)
        {
            line.AppendLine().Append(exception);
        }

        return line.AppendLine().ToString();
    }

    private sealed class Failure
    {
        public Failure(DateTimeOffset since, string summary, string reason)
        {
            Since = since;
            Summary = summary;
            Reason = reason;
        }

        public DateTimeOffset Since { get; }

        public string Summary { get; set; }

        public string Reason { get; set; }

        public int FailedLines { get; set; }

        public int DroppedLines { get; set; }
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
