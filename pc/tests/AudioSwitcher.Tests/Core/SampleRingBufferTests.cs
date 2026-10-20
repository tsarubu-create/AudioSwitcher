using AudioSwitcher.Core.Audio;

namespace AudioSwitcher.Tests.Core;

public class SampleRingBufferTests
{
    [Fact]
    public void 書いた順に読める()
    {
        var ring = new SampleRingBuffer(8);
        ring.Write([1, 2, 3, 4]);

        var read = new float[6];
        int n = ring.Read(read);

        Assert.Equal(4, n);
        Assert.Equal([1, 2, 3, 4], read[..4]);
        Assert.Equal(0, ring.Count);
    }

    [Fact]
    public void 端をまたいで書いても順番どおりに読める()
    {
        var ring = new SampleRingBuffer(6);
        ring.Write([1, 2, 3, 4]);
        ring.Read(new float[4]);
        ring.Write([5, 6, 7, 8]);

        var read = new float[4];
        ring.Read(read);

        Assert.Equal([5, 6, 7, 8], read);
    }

    [Fact]
    public void あふれたら古い方を左右の組ごと捨てる()
    {
        var ring = new SampleRingBuffer(6);
        ring.Write([1, 2, 3, 4]);
        ring.Write([5, 6, 7, 8]);

        var read = new float[6];
        int n = ring.Read(read);

        Assert.Equal(6, n);
        Assert.Equal([3, 4, 5, 6, 7, 8], read);
        Assert.Equal(2, ring.DroppedSamples);
    }

    [Fact]
    public void 容量より多く一度に来たら新しい方の末尾だけを残す()
    {
        var ring = new SampleRingBuffer(4);
        ring.Write([1, 2]);
        ring.Write([3, 4, 5, 6, 7, 8]);

        var read = new float[4];
        ring.Read(read);

        Assert.Equal([5, 6, 7, 8], read);
        Assert.Equal(4, ring.DroppedSamples);
    }

    [Fact]
    public void 左右の組にならない数は書けない()
    {
        var ring = new SampleRingBuffer(4);

        Assert.Throws<ArgumentException>(() => ring.Write([1, 2, 3]));
    }
}
