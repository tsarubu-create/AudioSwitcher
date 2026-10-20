using NAudio.Wave;

namespace AudioSwitcher.Tray.Audio;

/// <summary>3チャンネル以上（5.1ch など）の音から、前の左右の2チャンネルだけを取り出す。</summary>
internal sealed class FirstTwoChannelsSampleProvider : ISampleProvider
{
    private readonly ISampleProvider _source;
    private readonly int _sourceChannels;
    private float[] _sourceBuffer = [];

    public FirstTwoChannelsSampleProvider(ISampleProvider source)
    {
        _source = source;
        _sourceChannels = source.WaveFormat.Channels;
        WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(source.WaveFormat.SampleRate, 2);
    }

    public WaveFormat WaveFormat { get; }

    public int Read(Span<float> buffer)
    {
        int frames = buffer.Length / 2;
        int needed = frames * _sourceChannels;
        if (_sourceBuffer.Length < needed)
        {
            _sourceBuffer = new float[needed];
        }

        int read = _source.Read(_sourceBuffer.AsSpan(0, needed));
        int readFrames = read / _sourceChannels;
        for (int i = 0; i < readFrames; i++)
        {
            buffer[i * 2] = _sourceBuffer[i * _sourceChannels];
            buffer[i * 2 + 1] = _sourceBuffer[i * _sourceChannels + 1];
        }

        return readFrames * 2;
    }
}
