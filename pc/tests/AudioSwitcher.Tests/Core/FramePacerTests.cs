using AudioSwitcher.Core.Audio;
using AudioSwitcher.Tests.Helpers;
using Microsoft.Extensions.Time.Testing;

namespace AudioSwitcher.Tests.Core;

public class FramePacerTests
{
    [Fact]
    public async Task 音が来なくても20msごとに無音のフレームを出す()
    {
        var time = new FakeTimeProvider();
        var ring = new SampleRingBuffer(StreamFormat.FrameSampleCount * 4);
        var pacer = new FramePacer(ring, time) { PreRoll = TimeSpan.Zero };
        var frames = new List<float[]>();
        using var cts = new CancellationTokenSource();

        var run = pacer.RunAsync(
            (frame, _) =>
            {
                lock (frames)
                {
                    frames.Add(frame.ToArray());
                }

                return ValueTask.CompletedTask;
            },
            cts.Token);

        // 0ms で1つ目、以後 20ms ごとに1つずつ。
        for (int i = 1; i <= 4; i++)
        {
            await Eventually.WaitUntilAsync(() => Count(frames) == i, $"{i} 個目のフレーム");
            time.Advance(StreamFormat.FrameDuration);
        }

        await Eventually.WaitUntilAsync(() => Count(frames) == 5, "5 個目のフレーム");
        cts.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => run);

        Assert.All(frames, f => Assert.All(f, s => Assert.Equal(0f, s)));
        Assert.Equal(StreamFormat.FrameSampleCount * 5, pacer.SilentSamples);
    }

    [Fact]
    public async Task 溜まった音があればそれを出し_足りない分だけ無音で埋める()
    {
        var time = new FakeTimeProvider();
        var ring = new SampleRingBuffer(StreamFormat.FrameSampleCount * 4);
        var half = Enumerable.Repeat(0.25f, StreamFormat.FrameSampleCount / 2).ToArray();
        ring.Write(half);
        var pacer = new FramePacer(ring, time) { PreRoll = TimeSpan.Zero };
        float[]? first = null;
        using var cts = new CancellationTokenSource();

        var run = pacer.RunAsync(
            (frame, _) =>
            {
                first ??= frame.ToArray();
                return ValueTask.CompletedTask;
            },
            cts.Token);

        await Eventually.WaitUntilAsync(() => first is not null, "1 個目のフレーム");
        cts.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => run);

        Assert.All(first![..half.Length], s => Assert.Equal(0.25f, s));
        Assert.All(first[half.Length..], s => Assert.Equal(0f, s));
        Assert.Equal(StreamFormat.FrameSampleCount / 2, pacer.SilentSamples);
    }

    [Fact]
    public async Task 最初のフレームは前置きの時間を待ってから出す()
    {
        var time = new FakeTimeProvider();
        var ring = new SampleRingBuffer(StreamFormat.FrameSampleCount * 4);
        var pacer = new FramePacer(ring, time) { PreRoll = TimeSpan.FromMilliseconds(60) };
        int count = 0;
        using var cts = new CancellationTokenSource();

        var run = pacer.RunAsync(
            (_, _) =>
            {
                Interlocked.Increment(ref count);
                return ValueTask.CompletedTask;
            },
            cts.Token);

        time.Advance(TimeSpan.FromMilliseconds(59));
        await Task.Delay(50);
        Assert.Equal(0, Volatile.Read(ref count));

        time.Advance(TimeSpan.FromMilliseconds(1));
        await Eventually.WaitUntilAsync(() => Volatile.Read(ref count) == 1, "前置きのあとの1個目");

        cts.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => run);
    }

    private static int Count(List<float[]> frames)
    {
        lock (frames)
        {
            return frames.Count;
        }
    }
}
