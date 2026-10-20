using AudioSwitcher.Core.Audio;

namespace AudioSwitcher.Tests.Helpers;

/// <summary>テストから音や失敗を手で流し込む取り出し口。</summary>
internal sealed class ManualAudioSource : IAudioSource
{
    public event SamplesAvailableHandler? SamplesAvailable;

    public event Action<Exception>? Faulted;

    public int StartCount { get; private set; }

    public int StopCount { get; private set; }

    public bool Disposed { get; private set; }

    public void Push(ReadOnlySpan<float> samples) => SamplesAvailable?.Invoke(samples);

    public void Fail(Exception ex) => Faulted?.Invoke(ex);

    public void Start() => StartCount++;

    public void Stop() => StopCount++;

    public void Dispose() => Disposed = true;
}

/// <summary>実時間で 10ms ごとにサイン波を流す取り出し口（HTTP を通すテスト用）。</summary>
internal sealed class RealtimeSineSource : IAudioSource
{
    private readonly double _frequency;
    private CancellationTokenSource? _cts;
    private Task? _loop;

    public RealtimeSineSource(double frequency)
    {
        _frequency = frequency;
    }

    public event SamplesAvailableHandler? SamplesAvailable;

    public event Action<Exception>? Faulted;

    public void Start()
    {
        _cts = new CancellationTokenSource();
        var token = _cts.Token;
        _loop = Task.Run(async () =>
        {
            const int chunkFrames = StreamFormat.SampleRate / 100;
            long frame = 0;
            using var timer = new PeriodicTimer(TimeSpan.FromMilliseconds(10));
            try
            {
                while (await timer.WaitForNextTickAsync(token))
                {
                    SamplesAvailable?.Invoke(AudioAnalysis.Sine(_frequency, chunkFrames, startFrame: frame));
                    frame += chunkFrames;
                }
            }
            catch (OperationCanceledException)
            {
                // Stop で止めた。
            }
            catch (Exception ex)
            {
                Faulted?.Invoke(ex);
            }
        });
    }

    public void Stop()
    {
        _cts?.Cancel();
        _loop?.Wait(TimeSpan.FromSeconds(1));
    }

    public void Dispose()
    {
        Stop();
        _cts?.Dispose();
    }
}
