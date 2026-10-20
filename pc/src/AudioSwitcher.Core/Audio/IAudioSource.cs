namespace AudioSwitcher.Core.Audio;

/// <summary>取った音を受け取る。中身は <see cref="StreamFormat"/> の形（48kHz・ステレオ・float のインターリーブ）。</summary>
public delegate void SamplesAvailableHandler(ReadOnlySpan<float> samples);

/// <summary>
/// 音の取り出し口。本番は WASAPI ループバック、テストではサイン波などに差し替える。
/// </summary>
/// <remarks>
/// 何も鳴っていない間は <see cref="SamplesAvailable"/> が来なくてよい（無音で埋めるのは受け手の仕事）。
/// </remarks>
public interface IAudioSource : IDisposable
{
    event SamplesAvailableHandler? SamplesAvailable;

    /// <summary>音を取り続けられなくなったときに一度だけ来る。</summary>
    event Action<Exception>? Faulted;

    void Start();

    void Stop();
}
