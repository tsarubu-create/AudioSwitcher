using System.Buffers;
using System.Buffers.Binary;
using System.Security.Cryptography;
using AudioSwitcher.Core.Audio;
using Concentus;
using Concentus.Enums;

namespace AudioSwitcher.Core.Encoding;

/// <summary>
/// 音を Opus で圧縮し、Ogg に入れる（RFC 7845）。終わりの無いストリームとして流し続ける。
/// </summary>
/// <remarks>
/// 既成の Ogg 書き出しはページが満杯になるまで溜めるため、20ms のパケットだと数秒ぶん遅れる。
/// ここでは <see cref="PacketsPerPage"/> 個（既定 5 個 = 100ms）ごとに1ページを書き、すぐ流せるようにする。
/// </remarks>
public sealed class OggOpusEncoder : IAudioEncoder
{
    public const int DefaultBitrate = 128_000;
    public const int DefaultPacketsPerPage = 5;
    private const int MaxPacketSize = 4000;

    private readonly IOpusEncoder _opus;
    private readonly uint _serialNumber;
    private readonly List<ReadOnlyMemory<byte>> _pending = new();
    private readonly byte[] _packetBuffer = new byte[MaxPacketSize];
    private uint _sequenceNumber;
    private long _samplesPerChannel;

    public OggOpusEncoder(uint serialNumber, int bitrate = DefaultBitrate, int packetsPerPage = DefaultPacketsPerPage)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(packetsPerPage);
        _serialNumber = serialNumber;
        PacketsPerPage = packetsPerPage;
        _opus = OpusCodecFactory.CreateEncoder(StreamFormat.SampleRate, StreamFormat.Channels, OpusApplication.OPUS_APPLICATION_AUDIO);
        _opus.Bitrate = bitrate;
    }

    public int PacketsPerPage { get; }

    /// <summary>復号の頭で捨てるサンプル数（圧縮器の先読みの分）。</summary>
    public int PreSkip => _opus.Lookahead;

    public void WriteHeader(IBufferWriter<byte> output)
    {
        var head = new byte[19];
        "OpusHead"u8.CopyTo(head);
        head[8] = 1; // 版
        head[9] = StreamFormat.Channels;
        BinaryPrimitives.WriteUInt16LittleEndian(head.AsSpan(10), (ushort)PreSkip);
        BinaryPrimitives.WriteUInt32LittleEndian(head.AsSpan(12), StreamFormat.SampleRate);
        BinaryPrimitives.WriteInt16LittleEndian(head.AsSpan(16), 0); // 出力の利得
        head[18] = 0; // チャンネルの割り当て（0 = モノラルかステレオ）
        OggPage.Write(output, _serialNumber, _sequenceNumber++, 0, OggPage.FlagBeginOfStream, [head]);

        var vendor = "AudioSwitcher"u8;
        var tags = new byte[8 + 4 + vendor.Length + 4];
        "OpusTags"u8.CopyTo(tags);
        BinaryPrimitives.WriteUInt32LittleEndian(tags.AsSpan(8), (uint)vendor.Length);
        vendor.CopyTo(tags.AsSpan(12));
        BinaryPrimitives.WriteUInt32LittleEndian(tags.AsSpan(12 + vendor.Length), 0); // コメントの数
        OggPage.Write(output, _serialNumber, _sequenceNumber++, 0, 0, [tags]);
    }

    public void Encode(ReadOnlySpan<float> frame, IBufferWriter<byte> output)
    {
        if (frame.Length != StreamFormat.FrameSampleCount)
        {
            throw new ArgumentException($"フレームの長さ {frame.Length} が {StreamFormat.FrameSampleCount} ではない。", nameof(frame));
        }

        int size = _opus.Encode(frame, StreamFormat.FrameSamplesPerChannel, _packetBuffer, _packetBuffer.Length);
        if (size < 0)
        {
            throw new InvalidOperationException($"Opus の圧縮に失敗した（{size}）。");
        }

        _pending.Add(_packetBuffer.AsSpan(0, size).ToArray());
        _samplesPerChannel += StreamFormat.FrameSamplesPerChannel;

        if (_pending.Count >= PacketsPerPage)
        {
            // granule は、このページの最後のパケットまでを復号したときのサンプル数（先読みの分を含む）。
            OggPage.Write(output, _serialNumber, _sequenceNumber++, _samplesPerChannel, 0, _pending);
            _pending.Clear();
        }
    }
}

/// <summary>接続ごとに <see cref="OggOpusEncoder"/> を作る。ストリームの通し番号は乱数から取る。</summary>
public sealed class OggOpusEncoderFactory : IAudioEncoderFactory
{
    private readonly Func<uint> _nextSerialNumber;

    public OggOpusEncoderFactory()
        : this(() => BinaryPrimitives.ReadUInt32LittleEndian(RandomNumberGenerator.GetBytes(4)))
    {
    }

    /// <param name="nextSerialNumber">ストリームの通し番号を返す口（テストで差し替える）。</param>
    public OggOpusEncoderFactory(Func<uint> nextSerialNumber)
    {
        _nextSerialNumber = nextSerialNumber;
    }

    public string ContentType => "audio/ogg";

    public IAudioEncoder Create() => new OggOpusEncoder(_nextSerialNumber());
}
