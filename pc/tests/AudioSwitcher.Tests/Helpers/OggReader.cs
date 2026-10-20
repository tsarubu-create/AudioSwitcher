using System.Buffers.Binary;
using AudioSwitcher.Core.Encoding;

namespace AudioSwitcher.Tests.Helpers;

/// <summary>テスト用に Ogg のページを読み戻す。CRC も確かめる。</summary>
internal sealed record OggPageInfo(byte Flags, long GranulePosition, uint SerialNumber, uint SequenceNumber, IReadOnlyList<byte[]> Packets);

internal static class OggReader
{
    /// <summary>
    /// 先頭から読めるだけのページを読む。末尾の書きかけのページは読まない。
    /// パケットはページをまたがない前提（<see cref="OggPage"/> の書き方）。
    /// </summary>
    public static List<OggPageInfo> ReadPages(ReadOnlySpan<byte> data)
    {
        var pages = new List<OggPageInfo>();
        int pos = 0;
        while (data.Length - pos >= 27)
        {
            var header = data[pos..];
            if (!header[..4].SequenceEqual("OggS"u8))
            {
                throw new InvalidDataException($"{pos} バイト目にページの印が無い。");
            }

            int segmentCount = header[26];
            if (header.Length < 27 + segmentCount)
            {
                break;
            }

            var segments = header.Slice(27, segmentCount);
            int bodySize = 0;
            foreach (byte s in segments)
            {
                bodySize += s;
            }

            int pageSize = 27 + segmentCount + bodySize;
            if (header.Length < pageSize)
            {
                break;
            }

            var page = header[..pageSize].ToArray();
            uint storedCrc = BinaryPrimitives.ReadUInt32LittleEndian(page.AsSpan(22));
            page.AsSpan(22, 4).Clear();
            if (OggPage.ComputeCrc(page) != storedCrc)
            {
                throw new InvalidDataException($"{pos} バイト目のページの CRC が合わない。");
            }

            var packets = new List<byte[]>();
            var current = new List<byte>();
            int bodyPos = 27 + segmentCount;
            foreach (byte s in segments)
            {
                current.AddRange(page.AsSpan(bodyPos, s).ToArray());
                bodyPos += s;
                if (s < 255)
                {
                    packets.Add(current.ToArray());
                    current.Clear();
                }
            }

            pages.Add(new OggPageInfo(
                page[5],
                BinaryPrimitives.ReadInt64LittleEndian(page.AsSpan(6)),
                BinaryPrimitives.ReadUInt32LittleEndian(page.AsSpan(14)),
                BinaryPrimitives.ReadUInt32LittleEndian(page.AsSpan(18)),
                packets));
            pos += pageSize;
        }

        return pages;
    }
}
