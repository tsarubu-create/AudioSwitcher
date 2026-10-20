namespace AudioSwitcher.Core.Audio;

/// <summary>
/// 決まった間隔（20ms）でバッファから1フレームずつ取り出す。足りない分は無音で埋める。
/// </summary>
/// <remarks>
/// WASAPI ループバックは何も鳴っていない間は音を返さない。そのまま送ると HTTP のストリームが止まり、
/// 受け手（ExoPlayer）はバッファが尽きて切ってしまう。時計で刻んで無音を送り続け、ストリームを生かしておく。
/// 時間は <see cref="TimeProvider"/> の単調な時刻（<see cref="TimeProvider.GetTimestamp"/>）で計る。
/// </remarks>
public sealed class FramePacer
{
    private readonly SampleRingBuffer _buffer;
    private readonly TimeProvider _time;
    private long _silentSamples;

    public FramePacer(SampleRingBuffer buffer, TimeProvider time)
    {
        _buffer = buffer;
        _time = time;
    }

    /// <summary>最初のフレームを出す前に待つ時間。キャプチャの届き方のむらを吸収する。</summary>
    public TimeSpan PreRoll { get; init; } = TimeSpan.FromMilliseconds(60);

    /// <summary>これより遅れたら、間のフレームを飛ばして今に追いつく。</summary>
    public TimeSpan MaxLag { get; init; } = TimeSpan.FromMilliseconds(200);

    /// <summary>無音で埋めたサンプルの数の合計。</summary>
    public long SilentSamples => Interlocked.Read(ref _silentSamples);

    /// <summary>取り消されるまでフレームを出し続ける。<paramref name="onFrame"/> に渡す領域は次の呼び出しで上書きする。</summary>
    public async Task RunAsync(Func<ReadOnlyMemory<float>, CancellationToken, ValueTask> onFrame, CancellationToken cancellationToken)
    {
        var frame = new float[StreamFormat.FrameSampleCount];
        var period = StreamFormat.FrameDuration;
        long start = _time.GetTimestamp();
        long index = 0;

        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();

            var due = PreRoll + period * index;
            var elapsed = _time.GetElapsedTime(start);
            if (elapsed < due)
            {
                await Task.Delay(due - elapsed, _time, cancellationToken).ConfigureAwait(false);
            }
            else if (elapsed - due > MaxLag)
            {
                index = (long)((elapsed - PreRoll) / period);
            }

            int read = _buffer.Read(frame);
            if (read < frame.Length)
            {
                frame.AsSpan(read).Clear();
                Interlocked.Add(ref _silentSamples, frame.Length - read);
            }

            await onFrame(frame, cancellationToken).ConfigureAwait(false);
            index++;
        }
    }
}
