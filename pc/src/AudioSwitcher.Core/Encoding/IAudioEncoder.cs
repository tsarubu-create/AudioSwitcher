using System.Buffers;

namespace AudioSwitcher.Core.Encoding;

/// <summary>1本のストリームぶんの圧縮器。接続ごとに新しく作る（受け手の復号器は先頭の見出しから始めるため）。</summary>
public interface IAudioEncoder
{
    /// <summary>ストリームの先頭に置く見出しを書く。</summary>
    void WriteHeader(IBufferWriter<byte> output);

    /// <summary>
    /// 1フレーム（<see cref="Audio.StreamFormat.FrameSampleCount"/> 個）を圧縮する。
    /// 中で溜めてから書くことがあるので、呼ぶたびに書かれるとは限らない。
    /// </summary>
    void Encode(ReadOnlySpan<float> frame, IBufferWriter<byte> output);
}

/// <summary>圧縮器を接続ごとに作る口。</summary>
public interface IAudioEncoderFactory
{
    /// <summary>HTTP で返す Content-Type。</summary>
    string ContentType { get; }

    IAudioEncoder Create();
}
