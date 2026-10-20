namespace AudioSwitcher.Core.Audio;

/// <summary>
/// 取った音を、送り出すまで溜めておく輪の形のバッファ。書く側（キャプチャのスレッド）と読む側（送るスレッド）が別でよい。
/// </summary>
/// <remarks>
/// あふれたら古い方から捨てる（遅れを溜め込まないため）。捨てる数は <c>alignment</c>（チャンネル数）の倍数にそろえ、
/// 左右の並びがずれないようにする。
/// </remarks>
public sealed class SampleRingBuffer
{
    private readonly float[] _buffer;
    private readonly int _alignment;
    private readonly object _gate = new();
    private int _start;
    private int _count;
    private long _droppedSamples;

    public SampleRingBuffer(int capacity, int alignment = StreamFormat.Channels)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(alignment);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(capacity);
        if (capacity % alignment != 0)
        {
            throw new ArgumentException($"容量 {capacity} が {alignment} の倍数ではない。", nameof(capacity));
        }

        _buffer = new float[capacity];
        _alignment = alignment;
    }

    public int Capacity => _buffer.Length;

    public int Count
    {
        get { lock (_gate) { return _count; } }
    }

    /// <summary>あふれて捨てたサンプルの数の合計。</summary>
    public long DroppedSamples
    {
        get { lock (_gate) { return _droppedSamples; } }
    }

    public void Write(ReadOnlySpan<float> samples)
    {
        if (samples.Length % _alignment != 0)
        {
            throw new ArgumentException($"書く数 {samples.Length} が {_alignment} の倍数ではない。", nameof(samples));
        }

        lock (_gate)
        {
            if (samples.Length >= _buffer.Length)
            {
                // 一度に容量より多く来たら、溜めていた分も含めて新しい方の末尾だけを残す。
                _droppedSamples += _count + samples.Length - _buffer.Length;
                samples = samples[^_buffer.Length..];
                _start = 0;
                _count = 0;
            }

            int overflow = _count + samples.Length - _buffer.Length;
            if (overflow > 0)
            {
                // 容量も書く数も alignment の倍数なので、overflow も倍数になる。
                _start = (_start + overflow) % _buffer.Length;
                _count -= overflow;
                _droppedSamples += overflow;
            }

            int end = (_start + _count) % _buffer.Length;
            int first = Math.Min(samples.Length, _buffer.Length - end);
            samples[..first].CopyTo(_buffer.AsSpan(end));
            samples[first..].CopyTo(_buffer);
            _count += samples.Length;
        }
    }

    /// <summary>溜まっている分から読めるだけ読む。読んだ数を返す（足りなければ <paramref name="destination"/> より少ない）。</summary>
    public int Read(Span<float> destination)
    {
        lock (_gate)
        {
            int n = Math.Min(destination.Length, _count);
            n -= n % _alignment;
            int first = Math.Min(n, _buffer.Length - _start);
            _buffer.AsSpan(_start, first).CopyTo(destination);
            _buffer.AsSpan(0, n - first).CopyTo(destination[first..]);
            _start = (_start + n) % _buffer.Length;
            _count -= n;
            return n;
        }
    }
}
