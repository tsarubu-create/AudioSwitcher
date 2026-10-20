using System.Security.Cryptography;

namespace AudioSwitcher.Server.Pairing;

/// <summary>ペアリングの数字と鍵を作る乱数の口。テストでは決まった値を返すものに差し替える。</summary>
public interface IRandomSource
{
    /// <summary>0 以上 <paramref name="maxExclusive"/> 未満の整数。</summary>
    int NextInt(int maxExclusive);

    byte[] NextBytes(int count);
}

/// <summary>暗号用の乱数（<see cref="RandomNumberGenerator"/>）。</summary>
public sealed class CryptoRandomSource : IRandomSource
{
    public int NextInt(int maxExclusive) => RandomNumberGenerator.GetInt32(maxExclusive);

    public byte[] NextBytes(int count) => RandomNumberGenerator.GetBytes(count);
}
