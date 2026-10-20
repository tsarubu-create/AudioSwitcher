using AudioSwitcher.Core.Audio;
using Concentus;

namespace AudioSwitcher.Tests.Helpers;

internal static class AudioAnalysis
{
    /// <summary>ステレオのサイン波を作る（左右とも同じ）。<paramref name="startFrame"/> から続きを作れる。</summary>
    public static float[] Sine(double frequency, int frames, float amplitude = 0.5f, long startFrame = 0)
    {
        var samples = new float[frames * StreamFormat.Channels];
        for (int i = 0; i < frames; i++)
        {
            float v = amplitude * (float)Math.Sin(2 * Math.PI * frequency * (startFrame + i) / StreamFormat.SampleRate);
            samples[i * 2] = v;
            samples[i * 2 + 1] = v;
        }

        return samples;
    }

    /// <summary>Ogg/Opus のパケット（見出しの2つを除いたもの）を復号して、左チャンネルだけを返す。</summary>
    public static float[] DecodeLeft(IEnumerable<byte[]> audioPackets)
    {
        var decoder = OpusCodecFactory.CreateDecoder(StreamFormat.SampleRate, StreamFormat.Channels);
        var frame = new float[StreamFormat.FrameSampleCount];
        var left = new List<float>();
        foreach (var packet in audioPackets)
        {
            int frames = decoder.Decode(packet, frame, StreamFormat.FrameSamplesPerChannel, false);
            for (int i = 0; i < frames; i++)
            {
                left.Add(frame[i * 2]);
            }
        }

        return left.ToArray();
    }

    public static double Rms(ReadOnlySpan<float> samples)
    {
        double sum = 0;
        foreach (float s in samples)
        {
            sum += s * s;
        }

        return samples.Length == 0 ? 0 : Math.Sqrt(sum / samples.Length);
    }

    /// <summary>
    /// ある周波数の強さ（Goertzel 法）。途中に無音が混じっても、含まれる周波数の比べ合いは崩れにくい。
    /// </summary>
    public static double TonePower(ReadOnlySpan<float> mono, double frequency)
    {
        double coeff = 2 * Math.Cos(2 * Math.PI * frequency / StreamFormat.SampleRate);
        double s1 = 0, s2 = 0;
        foreach (float x in mono)
        {
            double s0 = x + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        return (s1 * s1 + s2 * s2 - coeff * s1 * s2) / mono.Length;
    }

    /// <summary>上向きに 0 をまたいだ回数から周波数を見積もる。</summary>
    public static double EstimateFrequency(ReadOnlySpan<float> mono)
    {
        int crossings = 0;
        for (int i = 1; i < mono.Length; i++)
        {
            if (mono[i - 1] < 0 && mono[i] >= 0)
            {
                crossings++;
            }
        }

        return crossings * (double)StreamFormat.SampleRate / mono.Length;
    }
}
