using System.Net;
using AudioSwitcher.Core.Audio;
using AudioSwitcher.Core.Encoding;
using AudioSwitcher.Server;
using AudioSwitcher.Tests.Helpers;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Logging.Abstractions;

namespace AudioSwitcher.Tests.Server;

/// <summary>サイン波 → 圧縮 → HTTP → 復号 の往復を、実時間で確かめる。</summary>
public class StreamServerTests : IAsyncLifetime
{
    private const double Frequency = 1000;
    private AudioStreamService _service = null!;
    private WebApplication _app = null!;
    private HttpClient _client = null!;

    public async Task InitializeAsync()
    {
        _service = new AudioStreamService(
            () => new RealtimeSineSource(Frequency),
            new OggOpusEncoderFactory(),
            TimeProvider.System,
            NullLogger<AudioStreamService>.Instance);
        _app = StreamServer.Build(_service, b => b.WebHost.UseTestServer());
        await _app.StartAsync();
        _client = _app.GetTestClient();
    }

    public async Task DisposeAsync()
    {
        _client.Dispose();
        await _app.DisposeAsync();
    }

    [Fact]
    public async Task 流れてきたOggOpusを復号するとサイン波が戻る()
    {
        using var response = await _client.GetAsync(StreamServer.StreamPath, HttpCompletionOption.ResponseHeadersRead);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("audio/ogg", response.Content.Headers.ContentType?.MediaType);

        // 約1秒ぶん（50 パケット = 10 ページ）と見出しの2ページが届くまで読む。
        var received = await ReadPagesAsync(response, pageCount: 12, TimeSpan.FromSeconds(10));
        var decoded = AudioAnalysis.DecodeLeft(received.Skip(2).SelectMany(p => p.Packets));
        var steady = decoded.AsSpan(StreamFormat.SampleRate / 5);

        // 実時間で動くため、音の届き方のむらで無音が少し混じることがある（設計どおり無音で埋める）。
        // 0 をまたぐ回数で数えると無音の分だけ低く出るので、周波数ごとの強さで比べる。
        double tone = AudioAnalysis.TonePower(steady, Frequency);
        Assert.True(tone > 20 * AudioAnalysis.TonePower(steady, Frequency * 0.7), "1kHz が際立っていない（低い側）");
        Assert.True(tone > 20 * AudioAnalysis.TonePower(steady, Frequency * 1.5), "1kHz が際立っていない（高い側）");
        Assert.True(AudioAnalysis.Rms(steady) > 0.2, "音が無音に近い");
    }

    [Fact]
    public async Task 流している間の2本目は409で断り_1本目が切れたら受け付ける()
    {
        var first = await _client.GetAsync(StreamServer.StreamPath, HttpCompletionOption.ResponseHeadersRead);
        Assert.Equal(HttpStatusCode.OK, first.StatusCode);

        using (var second = await _client.GetAsync(StreamServer.StreamPath, HttpCompletionOption.ResponseHeadersRead))
        {
            Assert.Equal(HttpStatusCode.Conflict, second.StatusCode);
        }

        first.Dispose();
        await Eventually.WaitUntilAsync(() => !_service.IsStreaming, "1本目の終わり");

        using var third = await _client.GetAsync(StreamServer.StreamPath, HttpCompletionOption.ResponseHeadersRead);
        Assert.Equal(HttpStatusCode.OK, third.StatusCode);
    }

    private static async Task<List<OggPageInfo>> ReadPagesAsync(HttpResponseMessage response, int pageCount, TimeSpan timeout)
    {
        using var cts = new CancellationTokenSource(timeout);
        await using var body = await response.Content.ReadAsStreamAsync(cts.Token);
        var data = new MemoryStream();
        var buffer = new byte[4096];
        while (true)
        {
            var pages = OggReader.ReadPages(data.GetBuffer().AsSpan(0, (int)data.Length));
            if (pages.Count >= pageCount)
            {
                return pages;
            }

            int read = await body.ReadAsync(buffer, cts.Token);
            if (read == 0)
            {
                throw new EndOfStreamException("ストリームが途中で終わった。");
            }

            data.Write(buffer, 0, read);
        }
    }
}
