namespace AudioSwitcher.Core.Audio;

/// <summary>
/// 配信する音の形。PC 側は取った音をこの形（48kHz・ステレオ・float のインターリーブ）にそろえてから圧縮する。
/// </summary>
public static class StreamFormat
{
    public const int SampleRate = 48_000;
    public const int Channels = 2;

    /// <summary>1フレームの1チャンネルあたりのサンプル数（20ms）。Opus のフレームの長さに合わせる。</summary>
    public const int FrameSamplesPerChannel = SampleRate / 50;

    /// <summary>1フレームの全チャンネルぶんのサンプル数。</summary>
    public const int FrameSampleCount = FrameSamplesPerChannel * Channels;

    public static readonly TimeSpan FrameDuration = TimeSpan.FromMilliseconds(20);

    /// <summary>時間の長さを、全チャンネルぶんのサンプル数に直す。</summary>
    public static int SampleCountOf(TimeSpan duration) =>
        (int)(duration.TotalSeconds * SampleRate) * Channels;
}
