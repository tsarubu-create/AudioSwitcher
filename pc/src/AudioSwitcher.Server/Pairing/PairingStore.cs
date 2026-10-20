using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace AudioSwitcher.Server.Pairing;

/// <summary>
/// ペアリングの保存する状態。鍵そのものではなく、鍵の SHA-256 のハッシュ（Base64）を持つ。
/// </summary>
/// <param name="KeyHash">ペアリング済みの鍵のハッシュ。ペアリングしていなければ null。</param>
/// <param name="ConsecutiveFailures">続けて外れた回数。正解したら 0 に戻す。</param>
/// <param name="LockedUntilUtc">ペアリングを受け付けない期限（UTC）。受け付けるなら null。</param>
public sealed record PairingState(string? KeyHash, int ConsecutiveFailures, DateTimeOffset? LockedUntilUtc)
{
    public static readonly PairingState Empty = new(null, 0, null);
}

/// <summary>ペアリングの状態の保存先。テストではメモリ上のものに差し替える。</summary>
public interface IPairingStore
{
    PairingState Load();

    void Save(PairingState state);
}

/// <summary>ペアリングの状態を JSON のファイルに保存する。PC アプリを起動し直しても続くようにするため。</summary>
public sealed class FilePairingStore : IPairingStore
{
    private readonly string _path;
    private readonly ILogger<FilePairingStore> _logger;

    public FilePairingStore(string path, ILogger<FilePairingStore> logger)
    {
        _path = path;
        _logger = logger;
    }

    public PairingState Load()
    {
        if (!File.Exists(_path))
        {
            return PairingState.Empty;
        }

        try
        {
            return JsonSerializer.Deserialize<PairingState>(File.ReadAllText(_path)) ?? PairingState.Empty;
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            _logger.LogError(ex, "ペアリングの状態（{Path}）を読めなかったので、ペアリングしていない状態として扱う", _path);
            return PairingState.Empty;
        }
    }

    public void Save(PairingState state)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        // 書きかけで落ちても壊れないよう、別のファイルに書いてから置き換える。
        var temp = _path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(state));
        File.Move(temp, _path, overwrite: true);
    }
}
