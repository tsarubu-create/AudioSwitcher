using AudioSwitcher.Core.Audio;
using Microsoft.Extensions.Logging;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace AudioSwitcher.Tray.Audio;

/// <summary>
/// 既定の出力機器に出ている音を WASAPI ループバックで取り、<see cref="StreamFormat"/> の形にそろえて渡す。
/// 既定の出力機器が変わったら取り直す。
/// </summary>
internal sealed class WasapiLoopbackSource : IAudioSource
{
    private readonly ILogger<WasapiLoopbackSource> _logger;
    private readonly object _gate = new();
    private readonly MMDeviceEnumerator _enumerator = new();
    private MMDeviceNotificationClient? _notifications;
    private Pipeline? _pipeline;
    private bool _running;
    private bool _faulted;

    public WasapiLoopbackSource(ILogger<WasapiLoopbackSource> logger)
    {
        _logger = logger;
    }

    public event SamplesAvailableHandler? SamplesAvailable;

    public event Action<Exception>? Faulted;

    public void Start()
    {
        lock (_gate)
        {
            if (_running)
            {
                return;
            }

            _running = true;
            // false: 通知は Windows の音声のスレッドで来る（画面のスレッドに回さない）。中では止まらない処理だけをする。
            _notifications = _enumerator.CreateNotificationClient(false);
            _notifications.DefaultDeviceChanged += OnDefaultDeviceChanged;
            StartCaptureLocked();
        }
    }

    public void Stop()
    {
        lock (_gate)
        {
            if (!_running)
            {
                return;
            }

            _running = false;
            _notifications?.Dispose();
            _notifications = null;
            StopCaptureLocked();
        }
    }

    public void Dispose()
    {
        Stop();
        _enumerator.Dispose();
    }

    private void StartCaptureLocked()
    {
        // NAudio 3 では古い形式の扱い（新しい WasapiRecorderBuilder を勧めている）だが、まだ使える。
        // 試作では情報の多いこちらを使い、乗り換えは別の変更で行う。
#pragma warning disable CS0618
        var capture = new WasapiLoopbackCapture();
#pragma warning restore CS0618
        var pipeline = new Pipeline(capture);
        capture.DataAvailable += (_, e) => OnDataAvailable(pipeline, e);
        capture.RecordingStopped += (_, e) => OnRecordingStopped(pipeline, e);
        _pipeline = pipeline;
        capture.StartRecording();
        _logger.LogInformation("ループバックを始めた（取った形: {Format}）", capture.WaveFormat);
    }

    private void StopCaptureLocked()
    {
        var pipeline = _pipeline;
        _pipeline = null;
        // 後片付けは RecordingStopped で行う（キャプチャのスレッドが止まってから捨てるため）。
        pipeline?.Capture.StopRecording();
    }

    private void OnDataAvailable(Pipeline pipeline, WaveInEventArgs e)
    {
        if (!ReferenceEquals(pipeline, Volatile.Read(ref _pipeline)))
        {
            return;
        }

        pipeline.Buffered.AddSamples(e.Buffer, 0, e.BytesRecorded);
        int read;
        while ((read = pipeline.Converted.Read(pipeline.ReadBuffer)) > 0)
        {
            SamplesAvailable?.Invoke(pipeline.ReadBuffer.AsSpan(0, read));
        }
    }

    private void OnRecordingStopped(Pipeline pipeline, StoppedEventArgs e)
    {
        pipeline.Capture.Dispose();
        if (e.Exception is null)
        {
            return;
        }

        _logger.LogError(e.Exception, "ループバックが止まった");
        if (ReferenceEquals(pipeline, Volatile.Read(ref _pipeline)))
        {
            RaiseFaulted(e.Exception);
        }
    }

    private void OnDefaultDeviceChanged(object? sender, DefaultDeviceChangedEventArgs e)
    {
        if (e.Flow != DataFlow.Render || e.Role != Role.Multimedia)
        {
            return;
        }

        // 通知のスレッドの中で取り直すと固まることがあるので、別のスレッドで行う。
        ThreadPool.QueueUserWorkItem(_ =>
        {
            try
            {
                lock (_gate)
                {
                    if (!_running)
                    {
                        return;
                    }

                    _logger.LogInformation("既定の出力機器が変わったので取り直す");
                    StopCaptureLocked();
                    StartCaptureLocked();
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "既定の出力機器が変わったあと、取り直せなかった");
                RaiseFaulted(ex);
            }
        });
    }

    private void RaiseFaulted(Exception ex)
    {
        lock (_gate)
        {
            if (_faulted)
            {
                return;
            }

            _faulted = true;
        }

        Faulted?.Invoke(ex);
    }

    /// <summary>1回のキャプチャぶんの道具一式。取り直すたびに作り直す。</summary>
#pragma warning disable CS0618 // WasapiLoopbackCapture を使う理由は StartCaptureLocked を参照。
    private sealed class Pipeline
    {
        public Pipeline(WasapiLoopbackCapture capture)
        {
            Capture = capture;
            // WASAPI は WAVE_FORMAT_EXTENSIBLE で返すので、NAudio が扱える普通の形に読み替える（中身の並びは同じ）。
            var format = capture.WaveFormat.AsStandardWaveFormat();
            Buffered = new BufferedWaveProvider(format, TimeSpan.FromSeconds(1))
            {
                ReadFully = false,
                DiscardOnBufferOverflow = true,
            };
            Converted = ToStreamFormat(Buffered.ToSampleProvider(), format);
        }

        public WasapiLoopbackCapture Capture { get; }
#pragma warning restore CS0618

        public BufferedWaveProvider Buffered { get; }

        public ISampleProvider Converted { get; }

        public float[] ReadBuffer { get; } = new float[StreamFormat.SampleCountOf(TimeSpan.FromMilliseconds(100))];

        private static ISampleProvider ToStreamFormat(ISampleProvider source, WaveFormat format)
        {
            var provider = source;
            if (format.Channels == 1)
            {
                provider = provider.ToStereo();
            }
            else if (format.Channels > 2)
            {
                provider = new FirstTwoChannelsSampleProvider(provider);
            }

            if (format.SampleRate != StreamFormat.SampleRate)
            {
                provider = new WdlResamplingSampleProvider(provider, StreamFormat.SampleRate);
            }

            return provider;
        }
    }
}
