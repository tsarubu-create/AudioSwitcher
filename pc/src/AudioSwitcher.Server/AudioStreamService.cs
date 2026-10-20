using System.Buffers;
using AudioSwitcher.Core.Audio;
using AudioSwitcher.Core.Encoding;
using Microsoft.Extensions.Logging;

namespace AudioSwitcher.Server;

/// <summary>ストリームが終わった理由。</summary>
public enum StreamEndReason
{
    /// <summary>受け手が切った。</summary>
    ClientDisconnected,

    /// <summary>送ったデータが決まった時間のうちにはけず、相手がいないと見なした。</summary>
    WriteTimedOut,

    /// <summary>音を取り続けられなくなった。</summary>
    SourceFaulted,

    /// <summary>PC 側のアプリが終わり始めた。</summary>
    ServerStopping,
}

/// <summary>
/// 音を取り、圧縮して、1本の接続へ流し続ける。同時に流すのは1本だけ（先勝ち）。
/// </summary>
/// <remarks>
/// 接続が来たら音を取り始め、切れたら止める（Android の再生がそのまま送り始めの合図になる）。
/// </remarks>
public sealed class AudioStreamService
{
    private readonly Func<IAudioSource> _sourceFactory;
    private readonly IAudioEncoderFactory _encoderFactory;
    private readonly TimeProvider _time;
    private readonly ILogger<AudioStreamService> _logger;
    private int _busy;

    public AudioStreamService(
        Func<IAudioSource> sourceFactory,
        IAudioEncoderFactory encoderFactory,
        TimeProvider time,
        ILogger<AudioStreamService> logger)
    {
        _sourceFactory = sourceFactory;
        _encoderFactory = encoderFactory;
        _time = time;
        _logger = logger;
    }

    /// <summary>1回の書き込みがこの時間のうちに終わらなければ、相手がいないと見なして閉じる。</summary>
    public TimeSpan WriteTimeout { get; init; } = TimeSpan.FromSeconds(5);

    /// <summary>送る前に溜めておける長さ。これを超えたら古い方から捨てる。</summary>
    public TimeSpan BufferCapacity { get; init; } = TimeSpan.FromMilliseconds(500);

    public string ContentType => _encoderFactory.ContentType;

    public bool IsStreaming => Volatile.Read(ref _busy) == 1;

    /// <summary>流し始めたとき（true）と終わったとき（false）に来る。どのスレッドから来るかは決まっていない。</summary>
    public event Action<bool>? StreamingChanged;

    /// <summary>流す権利を取る。すでに流していれば false（後から来た方を断る）。</summary>
    public bool TryAcquire()
    {
        if (Interlocked.CompareExchange(ref _busy, 1, 0) != 0)
        {
            return false;
        }

        StreamingChanged?.Invoke(true);
        return true;
    }

    public void Release()
    {
        if (Interlocked.Exchange(ref _busy, 0) == 1)
        {
            StreamingChanged?.Invoke(false);
        }
    }

    /// <summary>
    /// <paramref name="output"/> へ流し続け、終わった理由を返す。<see cref="TryAcquire"/> で権利を取ってから呼ぶ。
    /// </summary>
    /// <param name="serverStopping">
    /// PC 側のアプリが終わり始めた合図。終わりの無いストリームは自分では終わらないので、この合図ですぐに止める
    /// （待たずに止めないと、ASP.NET Core が処理中の要求の終わりを最大 30 秒待ち、その間も流し続けてしまう）。
    /// </param>
    public async Task<StreamEndReason> StreamAsync(Stream output, CancellationToken cancellationToken, CancellationToken serverStopping = default)
    {
        var ring = new SampleRingBuffer(StreamFormat.SampleCountOf(BufferCapacity));
        var encoder = _encoderFactory.Create();
        var pending = new ArrayBufferWriter<byte>();
        var pacer = new FramePacer(ring, _time);

        using var source = _sourceFactory();
        using var stop = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, serverStopping);
        Exception? fault = null;
        source.SamplesAvailable += samples => ring.Write(samples);
        source.Faulted += ex =>
        {
            fault = ex;
            stop.Cancel();
        };

        source.Start();
        _logger.LogInformation("音を取り始めた");
        try
        {
            encoder.WriteHeader(pending);
            await WriteAsync(output, pending, stop.Token).ConfigureAwait(false);

            await pacer.RunAsync(
                async (frame, token) =>
                {
                    encoder.Encode(frame.Span, pending);
                    if (pending.WrittenCount > 0)
                    {
                        await WriteAsync(output, pending, token).ConfigureAwait(false);
                    }
                },
                stop.Token).ConfigureAwait(false);

            throw new InvalidOperationException("RunAsync は取り消されるまで戻らないはず。");
        }
        catch (OperationCanceledException) when (fault is not null)
        {
            _logger.LogError(fault, "音を取り続けられなくなったため、ストリームを終える");
            return StreamEndReason.SourceFaulted;
        }
        catch (OperationCanceledException) when (serverStopping.IsCancellationRequested)
        {
            _logger.LogInformation("PC 側のアプリが終わり始めたので、ストリームを止める");
            return StreamEndReason.ServerStopping;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return StreamEndReason.ClientDisconnected;
        }
        catch (IOException ex)
        {
            // 受け手が切ると、取り消しより先に書き込みの失敗として届くことがある。
            _logger.LogInformation("書き込みに失敗したため、受け手が切ったと見なす: {Message}", ex.Message);
            return StreamEndReason.ClientDisconnected;
        }
        catch (TimeoutException)
        {
            _logger.LogWarning("送ったデータが {Timeout} のうちにはけないため、相手がいないと見なして閉じる", WriteTimeout);
            return StreamEndReason.WriteTimedOut;
        }
        finally
        {
            source.Stop();
            _logger.LogInformation(
                "音を取るのを止めた（無音で埋めた {Silent} サンプル、あふれて捨てた {Dropped} サンプル）",
                pacer.SilentSamples,
                ring.DroppedSamples);
        }
    }

    private async ValueTask WriteAsync(Stream output, ArrayBufferWriter<byte> pending, CancellationToken cancellationToken)
    {
        using var timeout = new CancellationTokenSource(WriteTimeout, _time);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, timeout.Token);
        try
        {
            await output.WriteAsync(pending.WrittenMemory, linked.Token).ConfigureAwait(false);
            await output.FlushAsync(linked.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (timeout.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            throw new TimeoutException($"書き込みが {WriteTimeout} のうちに終わらなかった。");
        }

        pending.ResetWrittenCount();
    }
}
