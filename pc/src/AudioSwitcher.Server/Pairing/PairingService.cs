using System.Buffers.Text;
using System.Security.Cryptography;
using Microsoft.Extensions.Logging;

namespace AudioSwitcher.Server.Pairing;

public enum PairingStartStatus
{
    /// <summary>数字を出した。候補を返す。</summary>
    Started,

    /// <summary>ペアリング済みなので、新しいペアリングは受け付けない。</summary>
    AlreadyPaired,

    /// <summary>別の端末が答えを待っている。</summary>
    Busy,

    /// <summary>受け付けない状態（続けて2回外れた）。</summary>
    Locked,
}

public enum PairingAnswerStatus
{
    /// <summary>合った。鍵を返す。</summary>
    Paired,

    /// <summary>外れた。新しい数字を出し、新しい候補を返す。</summary>
    Wrong,

    /// <summary>続けて2回外れて、受け付けない状態になった（またはすでにその状態）。</summary>
    Locked,

    /// <summary>数字の期限が切れたか、知らないペアリング。</summary>
    Expired,
}

public sealed record PairingStartResult(
    PairingStartStatus Status,
    Guid PairingId,
    IReadOnlyList<string> Candidates,
    DateTimeOffset? LockedUntil);

public sealed record PairingAnswerResult(
    PairingAnswerStatus Status,
    string? Key,
    Guid PairingId,
    IReadOnlyList<string> Candidates,
    DateTimeOffset? LockedUntil);

/// <summary>
/// ペアリング（docs/構成.md の 2.3）の判断をすべて受け持つ。PC が2桁の数字を出し、端末は5つの候補から選ぶ。
/// 合えば接続の鍵を発行し、以後は鍵で繋ぐ。
/// </summary>
/// <remarks>
/// 2桁の数字と鍵はログに出さない。乱数・時刻・保存先は差し替えられる口から受け取る。
/// </remarks>
public sealed class PairingService : IDisposable
{
    public static readonly TimeSpan CodeLifetime = TimeSpan.FromMinutes(2);
    public static readonly TimeSpan LockDuration = TimeSpan.FromHours(24);
    public const int MaxConsecutiveFailures = 2;
    public const int CandidateCount = 5;
    private const int KeyByteCount = 32;

    private readonly IPairingStore _store;
    private readonly IRandomSource _random;
    private readonly TimeProvider _time;
    private readonly ILogger<PairingService> _logger;
    private readonly object _gate = new();
    private PairingState _state;
    private Pending? _pending;

    public PairingService(IPairingStore store, IRandomSource random, TimeProvider time, ILogger<PairingService> logger)
    {
        _store = store;
        _random = random;
        _time = time;
        _logger = logger;
        _state = store.Load();
    }

    /// <summary>PC に出す数字が変わったとき。null は「出さない」。どのスレッドから来るかは決まっていない。</summary>
    public event Action<string?>? CodeChanged;

    /// <summary>ペアリング済みか・受け付けない状態かが変わったとき。どのスレッドから来るかは決まっていない。</summary>
    public event Action? StateChanged;

    public bool IsPaired
    {
        get { lock (_gate) { return _state.KeyHash is not null; } }
    }

    /// <summary>受け付けない状態の期限。受け付けるなら null。</summary>
    public DateTimeOffset? LockedUntil
    {
        get { lock (_gate) { return LockedUntilLocked(); } }
    }

    public PairingStartResult Start()
    {
        PairingStartResult result;
        string? shownCode = null;
        lock (_gate)
        {
            if (LockedUntilLocked() is { } until)
            {
                _logger.LogInformation("受け付けない状態なので、ペアリングの開始を断った（期限 {Until:u}）", until);
                return new(PairingStartStatus.Locked, Guid.Empty, [], until);
            }

            if (_state.KeyHash is not null)
            {
                _logger.LogInformation("ペアリング済みなので、新しいペアリングの開始を断った");
                return new(PairingStartStatus.AlreadyPaired, Guid.Empty, [], null);
            }

            if (_pending is not null && !IsExpired(_pending))
            {
                _logger.LogInformation("別の端末が答えを待っているので、ペアリングの開始を断った");
                return new(PairingStartStatus.Busy, Guid.Empty, [], null);
            }

            var pending = NewPendingLocked();
            shownCode = pending.Code;
            result = new(PairingStartStatus.Started, pending.Id, pending.Candidates, null);
            _logger.LogInformation("ペアリングの数字を出した（{Lifetime} で無効になる）", CodeLifetime);
        }

        CodeChanged?.Invoke(shownCode);
        return result;
    }

    public PairingAnswerResult Answer(Guid pairingId, string answer)
    {
        PairingAnswerResult result;
        string? shownCode;
        bool stateChanged;
        lock (_gate)
        {
            if (LockedUntilLocked() is { } lockedUntil)
            {
                return new(PairingAnswerStatus.Locked, null, Guid.Empty, [], lockedUntil);
            }

            if (_pending is null || _pending.Id != pairingId)
            {
                // 今の待ちは別の端末のものかもしれないので、触らない。
                return new(PairingAnswerStatus.Expired, null, Guid.Empty, [], null);
            }

            if (IsExpired(_pending))
            {
                ClearPendingLocked();
                result = new(PairingAnswerStatus.Expired, null, Guid.Empty, [], null);
                shownCode = null;
                stateChanged = false;
            }
            else if (answer == _pending.Code)
            {
                var keyBytes = _random.NextBytes(KeyByteCount);
                var key = Base64Url.EncodeToString(keyBytes);
                _state = _state with
                {
                    KeyHash = Convert.ToBase64String(SHA256.HashData(keyBytes)),
                    ConsecutiveFailures = 0,
                    LockedUntilUtc = null,
                };
                _store.Save(_state);
                ClearPendingLocked();
                _logger.LogInformation("ペアリングした");
                result = new(PairingAnswerStatus.Paired, key, Guid.Empty, [], null);
                shownCode = null;
                stateChanged = true;
            }
            else
            {
                int failures = _state.ConsecutiveFailures + 1;
                ClearPendingLocked();
                if (failures >= MaxConsecutiveFailures)
                {
                    var until = _time.GetUtcNow() + LockDuration;
                    // 期限が明けたら、また2回まで試せるように回数は 0 に戻す。
                    _state = _state with { ConsecutiveFailures = 0, LockedUntilUtc = until };
                    _store.Save(_state);
                    _logger.LogWarning("続けて {Failures} 回外れたので、ペアリングを受け付けない状態にした（期限 {Until:u}）", failures, until);
                    result = new(PairingAnswerStatus.Locked, null, Guid.Empty, [], until);
                    shownCode = null;
                    stateChanged = true;
                }
                else
                {
                    _state = _state with { ConsecutiveFailures = failures };
                    _store.Save(_state);
                    var pending = NewPendingLocked();
                    _logger.LogInformation("外れた（続けて {Failures} 回）ので、数字を作り直した", failures);
                    result = new(PairingAnswerStatus.Wrong, null, pending.Id, pending.Candidates, null);
                    shownCode = pending.Code;
                    stateChanged = false;
                }
            }
        }

        CodeChanged?.Invoke(shownCode);
        if (stateChanged)
        {
            StateChanged?.Invoke();
        }

        return result;
    }

    /// <summary>届いた鍵がペアリング済みの鍵と同じか。比べるのにかかる時間から一致の長さを推測されないように比べる。</summary>
    public bool IsAuthorized(string? key)
    {
        if (string.IsNullOrEmpty(key))
        {
            return false;
        }

        byte[] keyBytes;
        try
        {
            keyBytes = Base64Url.DecodeFromChars(key);
        }
        catch (FormatException)
        {
            return false;
        }

        string? storedHash;
        lock (_gate)
        {
            storedHash = _state.KeyHash;
        }

        return storedHash is not null
            && CryptographicOperations.FixedTimeEquals(SHA256.HashData(keyBytes), Convert.FromBase64String(storedHash));
    }

    /// <summary>ペアリングを解除する。古い鍵では繋げなくなる。</summary>
    public void Unpair()
    {
        lock (_gate)
        {
            _state = _state with { KeyHash = null };
            _store.Save(_state);
            _logger.LogInformation("ペアリングを解除した");
        }

        StateChanged?.Invoke();
    }

    /// <summary>受け付けない状態を解く（持ち主が選び間違えたとき）。</summary>
    public void ClearLock()
    {
        lock (_gate)
        {
            _state = _state with { ConsecutiveFailures = 0, LockedUntilUtc = null };
            _store.Save(_state);
            _logger.LogInformation("受け付けない状態を解いた");
        }

        StateChanged?.Invoke();
    }

    public void Dispose()
    {
        lock (_gate)
        {
            ClearPendingLocked();
        }
    }

    private DateTimeOffset? LockedUntilLocked() =>
        _state.LockedUntilUtc is { } until && until > _time.GetUtcNow() ? until : null;

    private bool IsExpired(Pending pending) => _time.GetElapsedTime(pending.StartedAt) >= CodeLifetime;

    private Pending NewPendingLocked()
    {
        ClearPendingLocked();
        var code = _random.NextInt(100);
        var candidates = new List<int>(CandidateCount) { code };
        while (candidates.Count < CandidateCount)
        {
            int decoy = _random.NextInt(100);
            if (!candidates.Contains(decoy))
            {
                candidates.Add(decoy);
            }
        }

        // 正解の位置も乱数で決める（Fisher–Yates で並べ替える）。
        for (int i = candidates.Count - 1; i > 0; i--)
        {
            int j = _random.NextInt(i + 1);
            (candidates[i], candidates[j]) = (candidates[j], candidates[i]);
        }

        var id = Guid.NewGuid();
        var timer = _time.CreateTimer(_ => OnCodeExpired(id), null, CodeLifetime, Timeout.InfiniteTimeSpan);
        _pending = new Pending(id, code.ToString("00"), candidates.Select(c => c.ToString("00")).ToArray(), _time.GetTimestamp(), timer);
        return _pending;
    }

    private void ClearPendingLocked()
    {
        _pending?.Timer.Dispose();
        _pending = null;
    }

    private void OnCodeExpired(Guid id)
    {
        lock (_gate)
        {
            if (_pending?.Id != id)
            {
                return;
            }

            ClearPendingLocked();
            _logger.LogInformation("ペアリングの数字の期限が切れた");
        }

        CodeChanged?.Invoke(null);
    }

    private sealed record Pending(Guid Id, string Code, IReadOnlyList<string> Candidates, long StartedAt, ITimer Timer);
}
