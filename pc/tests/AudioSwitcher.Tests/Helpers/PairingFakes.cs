using AudioSwitcher.Server.Pairing;

namespace AudioSwitcher.Tests.Helpers;

/// <summary>決まった種から乱数を出す（テストのたびに同じ並びになる）。</summary>
internal sealed class SeededRandomSource : IRandomSource
{
    private readonly Random _random;

    public SeededRandomSource(int seed = 1)
    {
        _random = new Random(seed);
    }

    public int NextInt(int maxExclusive) => _random.Next(maxExclusive);

    public byte[] NextBytes(int count)
    {
        var bytes = new byte[count];
        _random.NextBytes(bytes);
        return bytes;
    }
}

/// <summary>メモリ上だけに持つ保存先。保存した回数も数える。</summary>
internal sealed class InMemoryPairingStore : IPairingStore
{
    public InMemoryPairingStore(PairingState? initial = null)
    {
        State = initial ?? PairingState.Empty;
    }

    public PairingState State { get; private set; }

    public PairingState Load() => State;

    public void Save(PairingState state) => State = state;
}

/// <summary>PC に出ている数字を、<see cref="PairingService.CodeChanged"/> から拾っておく。</summary>
internal sealed class ShownCode
{
    public ShownCode(PairingService pairing)
    {
        pairing.CodeChanged += code => Value = code;
    }

    public string? Value { get; private set; }
}
