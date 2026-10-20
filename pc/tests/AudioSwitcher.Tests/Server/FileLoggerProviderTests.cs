using AudioSwitcher.Server.Logging;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Time.Testing;

namespace AudioSwitcher.Tests.Server;

public sealed class FileLoggerProviderTests : IDisposable
{
    private readonly FakeTimeProvider _time = new(new DateTimeOffset(2026, 10, 20, 12, 0, 0, TimeSpan.Zero));
    private readonly FlakyWriter _writer = new();
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "AudioSwitcherTests", Guid.NewGuid().ToString("N"));

    [Fact]
    public void 書けるときは1行ずつ足す()
    {
        var logger = CreateLogger(out _);

        logger.LogInformation("一行目");
        logger.LogInformation("二行目");

        Assert.Collection(
            _writer.Lines,
            l => Assert.Contains("一行目", l),
            l => Assert.Contains("二行目", l));
    }

    [Theory]
    [InlineData(typeof(IOException))]
    [InlineData(typeof(UnauthorizedAccessException))]
    public void 書けないときは例外を外へ出さず_理由を一度だけ知らせる(Type exceptionType)
    {
        var logger = CreateLogger(out var provider);
        var notices = new List<LogWriteFailure?>();
        provider.WriteFailureChanged += notices.Add;
        _writer.FailWith = (Exception)Activator.CreateInstance(exceptionType, "詳しい理由")!;

        logger.LogInformation("書けない一行");
        logger.LogInformation("書けない二行目");

        Assert.Empty(_writer.Lines);
        Assert.Single(notices);
        Assert.Equal("詳しい理由", notices[0]!.Detail);
        Assert.Equal(notices[0], provider.CurrentFailure);
    }

    [Fact]
    public void 画面に出す理由は_エラーの番号で短い言葉に置き換える()
    {
        Assert.Equal("書き込みの権限が無い", FileLoggerProvider.Summarize(new UnauthorizedAccessException("長い文章")));
        Assert.Equal("ほかのプログラムが使用中", FileLoggerProvider.Summarize(new IOException("長い文章", unchecked((int)0x80070020))));
        Assert.Equal("ディスクの空きが無い", FileLoggerProvider.Summarize(new IOException("長い文章", unchecked((int)0x80070070))));
        Assert.Equal("書き込みに失敗した", FileLoggerProvider.Summarize(new IOException("長い文章")));
    }

    [Fact]
    public void 書けるようになったら区切りの行のあとに_溜めていた行を元の順に書き出す()
    {
        var logger = CreateLogger(out var provider);
        var notices = new List<LogWriteFailure?>();
        provider.WriteFailureChanged += notices.Add;
        _writer.FailWith = new IOException("使用中", unchecked((int)0x80070020));
        logger.LogInformation("溜まる一行目");
        _time.Advance(TimeSpan.FromSeconds(1));
        logger.LogInformation("溜まる二行目");

        _writer.FailWith = null;
        _time.Advance(TimeSpan.FromSeconds(1));
        logger.LogInformation("回復後の一行");

        Assert.Collection(
            _writer.Lines,
            l =>
            {
                Assert.Contains("[Warning]", l);
                Assert.Contains("から 2 行が書けなかった（ほかのプログラムが使用中: 使用中）", l);
                Assert.Contains("溜めていた 2 行", l);
                Assert.Contains("捨てた行は 0 行", l);
            },
            l => Assert.Contains("溜まる一行目", l),
            l => Assert.Contains("溜まる二行目", l),
            l => Assert.Contains("回復後の一行", l));
        Assert.Equal([new LogWriteFailure("ほかのプログラムが使用中", "使用中"), null], notices);
        Assert.Null(provider.CurrentFailure);
    }

    [Fact]
    public void 溜める行があふれたら古い方から捨てて数える()
    {
        var logger = CreateLogger(out _);
        _writer.FailWith = new IOException("使用中");
        for (int i = 0; i < FileLoggerProvider.MaxPendingLines + 5; i++)
        {
            logger.LogInformation("行 {Number}", i);
        }

        _writer.FailWith = null;
        logger.LogInformation("回復");

        Assert.Contains($"から {FileLoggerProvider.MaxPendingLines + 5} 行が書けなかった", _writer.Lines[0]);
        Assert.Contains("捨てた行は 5 行", _writer.Lines[0]);
        Assert.Contains("行 5", _writer.Lines[1]);
        Assert.Equal(FileLoggerProvider.MaxPendingLines + 2, _writer.Lines.Count);
    }

    [Fact]
    public void 二つのプロセスが同じファイルに同時に書いても_すべての行が残る()
    {
        // 別々のプロセスの代わりに、別々のプロバイダ（別々にファイルを開く）を同時に動かす。
        using var first = new FileLoggerProvider(_directory, _time);
        using var second = new FileLoggerProvider(_directory, _time);
        var a = first.CreateLogger("A");
        var b = second.CreateLogger("B");
        const int count = 300;

        Parallel.Invoke(
            () => { for (int i = 0; i < count; i++) { a.LogInformation("A {Number}", i); } },
            () => { for (int i = 0; i < count; i++) { b.LogInformation("B {Number}", i); } });

        var lines = File.ReadAllLines(Directory.GetFiles(_directory).Single());
        Assert.Equal(count * 2, lines.Length);
        Assert.Null(first.CurrentFailure);
        Assert.Null(second.CurrentFailure);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }

    private ILogger CreateLogger(out FileLoggerProvider provider)
    {
        provider = new FileLoggerProvider(_directory, _time, _writer);
        return provider.CreateLogger("テスト");
    }

    /// <summary>失敗させられる書き込みの口。書けた行を覚えておく。</summary>
    private sealed class FlakyWriter : ILogFileWriter
    {
        public Exception? FailWith { get; set; }

        public List<string> Lines { get; } = new();

        public void Append(string path, string text)
        {
            if (FailWith is not null)
            {
                throw FailWith;
            }

            Lines.Add(text);
        }
    }
}
