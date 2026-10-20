using AudioSwitcher.Core.Encoding;
using AudioSwitcher.Server;
using AudioSwitcher.Tests.Helpers;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Time.Testing;

namespace AudioSwitcher.Tests.Server;

public class AudioStreamServiceTests
{
    [Fact]
    public void 流している間は2本目の権利を取れない_先勝ち()
    {
        var service = CreateService(new ManualAudioSource(), TimeProvider.System);

        Assert.True(service.TryAcquire());
        Assert.False(service.TryAcquire());

        service.Release();
        Assert.True(service.TryAcquire());
    }

    [Fact]
    public async Task 受け手が受け取らず書き込みが終わらなければ_決まった時間で見切る()
    {
        var time = new FakeTimeProvider();
        var source = new ManualAudioSource();
        var service = CreateService(source, time);
        var output = new NeverCompletingStream();

        var stream = service.StreamAsync(output, CancellationToken.None);
        await Eventually.WaitUntilAsync(() => output.WriteStarted, "最初の書き込み");
        time.Advance(service.WriteTimeout - TimeSpan.FromMilliseconds(1));
        await Task.Delay(50);
        Assert.False(stream.IsCompleted);

        time.Advance(TimeSpan.FromMilliseconds(1));
        var reason = await stream.WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal(StreamEndReason.WriteTimedOut, reason);
        Assert.Equal(1, source.StopCount);
        Assert.True(source.Disposed);
    }

    [Fact]
    public async Task 音を取り続けられなくなったら終える()
    {
        var source = new ManualAudioSource();
        var service = CreateService(source, new FakeTimeProvider());
        var output = new MemoryStream();

        var stream = service.StreamAsync(output, CancellationToken.None);
        await Eventually.WaitUntilAsync(() => output.Length > 0, "見出しの書き込み");
        source.Fail(new InvalidOperationException("機器が外れた"));
        var reason = await stream.WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal(StreamEndReason.SourceFaulted, reason);
        Assert.Equal(1, source.StopCount);
    }

    [Fact]
    public async Task 受け手が切ったら音を取るのを止める()
    {
        var source = new ManualAudioSource();
        var service = CreateService(source, new FakeTimeProvider());
        using var cts = new CancellationTokenSource();

        var stream = service.StreamAsync(new MemoryStream(), cts.Token);
        await Eventually.WaitUntilAsync(() => source.StartCount == 1, "音を取り始める");
        cts.Cancel();
        var reason = await stream.WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal(StreamEndReason.ClientDisconnected, reason);
        Assert.Equal(1, source.StopCount);
    }

    private static AudioStreamService CreateService(ManualAudioSource source, TimeProvider time) =>
        new(() => source, new OggOpusEncoderFactory(() => 1), time, NullLogger<AudioStreamService>.Instance);

    /// <summary>書き込みが取り消されるまで終わらない（受け取らない相手の代わり）。</summary>
    private sealed class NeverCompletingStream : Stream
    {
        private volatile bool _writeStarted;

        public bool WriteStarted => _writeStarted;

        public override bool CanRead => false;

        public override bool CanSeek => false;

        public override bool CanWrite => true;

        public override long Length => throw new NotSupportedException();

        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
        {
            _writeStarted = true;
            await Task.Delay(Timeout.Infinite, cancellationToken);
        }

        public override void Flush()
        {
        }

        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
