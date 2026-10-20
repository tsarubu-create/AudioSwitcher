using System.Buffers;
using System.Buffers.Binary;
using AudioSwitcher.Core.Audio;
using AudioSwitcher.Core.Encoding;
using AudioSwitcher.Tests.Helpers;

namespace AudioSwitcher.Tests.Core;

public class OggOpusEncoderTests
{
    [Fact]
    public void CRCはOggの決まりどおりに計算する()
    {
        // CRC-32（多項式 0x04C11DB7、初期値 0、反転なし、最後の XOR なし）で "123456789" を計算した値。
        Assert.Equal(0x89A1897Fu, OggPage.ComputeCrc("123456789"u8));
    }

    [Fact]
    public void 長さが255のパケットは長さ0の区切りを足して終わりを示す()
    {
        var output = new ArrayBufferWriter<byte>();
        OggPage.Write(output, 1, 0, 0, 0, [new byte[255], new byte[3]]);

        var page = OggReader.ReadPages(output.WrittenSpan).Single();

        Assert.Equal([255, 3], page.Packets.Select(p => p.Length));
    }

    [Fact]
    public void 見出しはOpusHeadとOpusTagsの2ページで_最初のページに始まりの印を付ける()
    {
        var encoder = new OggOpusEncoder(serialNumber: 0x1234);
        var output = new ArrayBufferWriter<byte>();

        encoder.WriteHeader(output);
        var pages = OggReader.ReadPages(output.WrittenSpan);

        Assert.Equal(2, pages.Count);
        Assert.Equal(OggPage.FlagBeginOfStream, pages[0].Flags);
        Assert.Equal(0, pages[1].Flags);
        Assert.All(pages, p => Assert.Equal(0x1234u, p.SerialNumber));
        Assert.Equal([0u, 1u], pages.Select(p => p.SequenceNumber));

        var head = pages[0].Packets.Single();
        Assert.Equal("OpusHead"u8.ToArray(), head[..8]);
        Assert.Equal(1, head[8]);
        Assert.Equal(StreamFormat.Channels, head[9]);
        Assert.Equal(encoder.PreSkip, BinaryPrimitives.ReadUInt16LittleEndian(head.AsSpan(10)));
        Assert.Equal((uint)StreamFormat.SampleRate, BinaryPrimitives.ReadUInt32LittleEndian(head.AsSpan(12)));
        Assert.Equal("OpusTags"u8.ToArray(), pages[1].Packets.Single()[..8]);
    }

    [Fact]
    public void パケット5つごとに1ページを書き_granuleは復号したサンプル数になる()
    {
        var encoder = new OggOpusEncoder(serialNumber: 7);
        var output = new ArrayBufferWriter<byte>();
        encoder.WriteHeader(output);
        output.ResetWrittenCount();

        var frame = AudioAnalysis.Sine(440, StreamFormat.FrameSamplesPerChannel);
        for (int i = 0; i < 4; i++)
        {
            encoder.Encode(frame, output);
        }

        Assert.Equal(0, output.WrittenCount);

        for (int i = 0; i < 6; i++)
        {
            encoder.Encode(frame, output);
        }

        var pages = OggReader.ReadPages(output.WrittenSpan);
        Assert.Equal(2, pages.Count);
        Assert.All(pages, p => Assert.Equal(5, p.Packets.Count));
        Assert.Equal([960L * 5, 960L * 10], pages.Select(p => p.GranulePosition));
        Assert.Equal([2u, 3u], pages.Select(p => p.SequenceNumber));
    }

    [Fact]
    public void 圧縮して復号するとサイン波の周波数と大きさが戻る()
    {
        const double frequency = 1000;
        var encoder = new OggOpusEncoder(serialNumber: 1);
        var output = new ArrayBufferWriter<byte>();
        encoder.WriteHeader(output);

        int frames = StreamFormat.SampleRate; // 1秒
        var sine = AudioAnalysis.Sine(frequency, frames);
        for (int i = 0; i < sine.Length; i += StreamFormat.FrameSampleCount)
        {
            encoder.Encode(sine.AsSpan(i, StreamFormat.FrameSampleCount), output);
        }

        var pages = OggReader.ReadPages(output.WrittenSpan);
        var decoded = AudioAnalysis.DecodeLeft(pages.Skip(2).SelectMany(p => p.Packets));
        // 頭（先読みの分と立ち上がり）を除いて比べる。
        var steady = decoded.AsSpan(StreamFormat.SampleRate / 10);

        Assert.InRange(AudioAnalysis.EstimateFrequency(steady), frequency * 0.98, frequency * 1.02);
        Assert.InRange(AudioAnalysis.Rms(steady), AudioAnalysis.Rms(sine) * 0.8, AudioAnalysis.Rms(sine) * 1.2);
    }
}
