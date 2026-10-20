using System.Buffers;
using System.Buffers.Binary;

namespace AudioSwitcher.Core.Encoding;

/// <summary>Ogg のページを書く（RFC 3533）。</summary>
public static class OggPage
{
    public const byte FlagContinued = 0x01;
    public const byte FlagBeginOfStream = 0x02;
    public const byte FlagEndOfStream = 0x04;

    private const int HeaderSize = 27;
    private const int MaxSegments = 255;

    private static readonly uint[] CrcTable = BuildCrcTable();

    /// <summary>まとまったパケットを1ページとして書く。パケットはページをまたがない。</summary>
    public static void Write(
        IBufferWriter<byte> output,
        uint serialNumber,
        uint sequenceNumber,
        long granulePosition,
        byte flags,
        IReadOnlyList<ReadOnlyMemory<byte>> packets)
    {
        int segmentCount = 0;
        int bodySize = 0;
        foreach (var packet in packets)
        {
            // 255 ちょうどの倍数のパケットは、終わりを示す長さ 0 の区切りを足す。
            segmentCount += packet.Length / 255 + 1;
            bodySize += packet.Length;
        }

        if (segmentCount > MaxSegments)
        {
            throw new ArgumentException($"1ページの区切りが {segmentCount} 個で、上限 {MaxSegments} を超える。", nameof(packets));
        }

        int pageSize = HeaderSize + segmentCount + bodySize;
        var page = new byte[pageSize];
        var span = page.AsSpan();

        "OggS"u8.CopyTo(span);
        span[4] = 0; // 版
        span[5] = flags;
        BinaryPrimitives.WriteInt64LittleEndian(span[6..], granulePosition);
        BinaryPrimitives.WriteUInt32LittleEndian(span[14..], serialNumber);
        BinaryPrimitives.WriteUInt32LittleEndian(span[18..], sequenceNumber);
        // span[22..26] は CRC。0 のまま計算して、あとで書き込む。
        span[26] = (byte)segmentCount;

        int segmentPos = HeaderSize;
        int bodyPos = HeaderSize + segmentCount;
        foreach (var packet in packets)
        {
            int remaining = packet.Length;
            while (remaining >= 255)
            {
                span[segmentPos++] = 255;
                remaining -= 255;
            }

            span[segmentPos++] = (byte)remaining;
            packet.Span.CopyTo(span[bodyPos..]);
            bodyPos += packet.Length;
        }

        BinaryPrimitives.WriteUInt32LittleEndian(span[22..], ComputeCrc(span));
        output.Write(span);
    }

    /// <summary>Ogg の CRC-32（多項式 0x04C11DB7、反転なし、初期値 0）。</summary>
    public static uint ComputeCrc(ReadOnlySpan<byte> data)
    {
        uint crc = 0;
        foreach (byte b in data)
        {
            crc = (crc << 8) ^ CrcTable[(byte)(crc >> 24) ^ b];
        }

        return crc;
    }

    private static uint[] BuildCrcTable()
    {
        var table = new uint[256];
        for (uint i = 0; i < 256; i++)
        {
            uint r = i << 24;
            for (int bit = 0; bit < 8; bit++)
            {
                r = (r & 0x8000_0000) != 0 ? (r << 1) ^ 0x04C1_1DB7 : r << 1;
            }

            table[i] = r;
        }

        return table;
    }
}
