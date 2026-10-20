using AudioSwitcher.Server.Pairing;
using AudioSwitcher.Tests.Helpers;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Time.Testing;

namespace AudioSwitcher.Tests.Server;

public class PairingServiceTests
{
    private readonly FakeTimeProvider _time = new(new DateTimeOffset(2026, 10, 20, 0, 0, 0, TimeSpan.Zero));
    private readonly InMemoryPairingStore _store = new();

    [Fact]
    public void 候補は重ならない5つで_PCに出した数字がちょうど1つ入る()
    {
        for (int seed = 0; seed < 50; seed++)
        {
            using var pairing = Create(seed);
            var shown = new ShownCode(pairing);

            var result = pairing.Start();

            Assert.Equal(PairingStartStatus.Started, result.Status);
            Assert.Equal(PairingService.CandidateCount, result.Candidates.Distinct().Count());
            Assert.Single(result.Candidates, c => c == shown.Value);
            Assert.All(result.Candidates, c => Assert.Matches("^[0-9]{2}$", c));
        }
    }

    [Fact]
    public void 合えば鍵を返し_その鍵だけを認める_保存するのはハッシュだけ()
    {
        using var pairing = Create();
        var shown = new ShownCode(pairing);
        var start = pairing.Start();

        var result = pairing.Answer(start.PairingId, shown.Value!);

        Assert.Equal(PairingAnswerStatus.Paired, result.Status);
        Assert.True(pairing.IsPaired);
        Assert.True(pairing.IsAuthorized(result.Key));
        Assert.False(pairing.IsAuthorized(null));
        Assert.False(pairing.IsAuthorized("wrong-key"));
        Assert.False(pairing.IsAuthorized("@@not base64@@"));
        Assert.NotNull(_store.State.KeyHash);
        Assert.DoesNotContain(result.Key!, _store.State.KeyHash!);
        Assert.Null(shown.Value);
    }

    [Fact]
    public void 一回外れたら数字と候補を作り直し_外れた回数を残す()
    {
        using var pairing = Create();
        var shown = new ShownCode(pairing);
        var start = pairing.Start();
        var firstCode = shown.Value!;

        var result = pairing.Answer(start.PairingId, Wrong(start.Candidates, firstCode));

        Assert.Equal(PairingAnswerStatus.Wrong, result.Status);
        Assert.NotEqual(start.PairingId, result.PairingId);
        Assert.Single(result.Candidates, c => c == shown.Value);
        Assert.Equal(1, _store.State.ConsecutiveFailures);
        Assert.Equal(PairingAnswerStatus.Expired, pairing.Answer(start.PairingId, firstCode).Status);
    }

    [Fact]
    public void 続けて2回外れたら24時間受け付けず_起動し直しても続く()
    {
        using (var pairing = Create())
        {
            var shown = new ShownCode(pairing);
            var first = pairing.Start();
            var second = pairing.Answer(first.PairingId, Wrong(first.Candidates, shown.Value!));

            var result = pairing.Answer(second.PairingId, Wrong(second.Candidates, shown.Value!));

            Assert.Equal(PairingAnswerStatus.Locked, result.Status);
            Assert.Equal(_time.GetUtcNow() + PairingService.LockDuration, result.LockedUntil);
            Assert.Null(shown.Value);
        }

        // 起動し直したのと同じ（同じ保存先から読み直す）。
        using var restarted = Create();
        _time.Advance(PairingService.LockDuration - TimeSpan.FromMilliseconds(1));
        Assert.Equal(PairingStartStatus.Locked, restarted.Start().Status);

        _time.Advance(TimeSpan.FromMilliseconds(1));
        Assert.Equal(PairingStartStatus.Started, restarted.Start().Status);
    }

    [Fact]
    public void 正解したら外れた回数を0に戻す()
    {
        using var pairing = Create();
        var shown = new ShownCode(pairing);
        var first = pairing.Start();
        var second = pairing.Answer(first.PairingId, Wrong(first.Candidates, shown.Value!));

        var result = pairing.Answer(second.PairingId, shown.Value!);

        Assert.Equal(PairingAnswerStatus.Paired, result.Status);
        Assert.Equal(0, _store.State.ConsecutiveFailures);
    }

    [Fact]
    public void 数字は2分で無効になり_窓を閉じる_期限切れは外れに数えない()
    {
        using var pairing = Create();
        var shown = new ShownCode(pairing);
        var start = pairing.Start();
        var code = shown.Value!;

        _time.Advance(PairingService.CodeLifetime - TimeSpan.FromMilliseconds(1));
        Assert.Equal(code, shown.Value);

        _time.Advance(TimeSpan.FromMilliseconds(1));
        Assert.Null(shown.Value);
        Assert.Equal(PairingAnswerStatus.Expired, pairing.Answer(start.PairingId, code).Status);
        Assert.Equal(0, _store.State.ConsecutiveFailures);
    }

    [Fact]
    public void 期限の直前に届いた正解は受け付ける()
    {
        using var pairing = Create();
        var shown = new ShownCode(pairing);
        var start = pairing.Start();

        _time.Advance(PairingService.CodeLifetime - TimeSpan.FromMilliseconds(1));

        Assert.Equal(PairingAnswerStatus.Paired, pairing.Answer(start.PairingId, shown.Value!).Status);
    }

    [Fact]
    public void 答えを待っている間の別の開始は断り_期限が切れたら受け付ける()
    {
        using var pairing = Create();
        pairing.Start();

        Assert.Equal(PairingStartStatus.Busy, pairing.Start().Status);

        _time.Advance(PairingService.CodeLifetime);
        Assert.Equal(PairingStartStatus.Started, pairing.Start().Status);
    }

    [Fact]
    public void 知らないペアリングの答えは_今の待ちを取り消さない()
    {
        using var pairing = Create();
        var shown = new ShownCode(pairing);
        var start = pairing.Start();

        Assert.Equal(PairingAnswerStatus.Expired, pairing.Answer(Guid.NewGuid(), "00").Status);

        Assert.Equal(PairingAnswerStatus.Paired, pairing.Answer(start.PairingId, shown.Value!).Status);
    }

    [Fact]
    public void ペアリング済みの間は新しく始められず_解除すると古い鍵を認めない()
    {
        using var pairing = Create();
        var key = Pair(pairing);

        Assert.Equal(PairingStartStatus.AlreadyPaired, pairing.Start().Status);

        pairing.Unpair();

        Assert.False(pairing.IsPaired);
        Assert.False(pairing.IsAuthorized(key));
        Assert.Equal(PairingStartStatus.Started, pairing.Start().Status);
    }

    [Fact]
    public void 受け付けない状態は手で解ける()
    {
        using var pairing = Create();
        var shown = new ShownCode(pairing);
        var first = pairing.Start();
        var second = pairing.Answer(first.PairingId, Wrong(first.Candidates, shown.Value!));
        pairing.Answer(second.PairingId, Wrong(second.Candidates, shown.Value!));

        pairing.ClearLock();

        Assert.Null(pairing.LockedUntil);
        Assert.Equal(PairingStartStatus.Started, pairing.Start().Status);
    }

    [Fact]
    public void 起動し直しても鍵で繋がる()
    {
        string key;
        using (var pairing = Create())
        {
            key = Pair(pairing);
        }

        using var restarted = Create();

        Assert.True(restarted.IsAuthorized(key));
    }

    internal static string Pair(PairingService pairing)
    {
        var shown = new ShownCode(pairing);
        var start = pairing.Start();
        return pairing.Answer(start.PairingId, shown.Value!).Key!;
    }

    private static string Wrong(IReadOnlyList<string> candidates, string code) => candidates.First(c => c != code);

    private PairingService Create(int seed = 1) =>
        new(_store, new SeededRandomSource(seed), _time, NullLogger<PairingService>.Instance);
}
