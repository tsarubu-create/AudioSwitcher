using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using AudioSwitcher.Core.Audio;
using AudioSwitcher.Core.Encoding;
using AudioSwitcher.Server;
using AudioSwitcher.Server.Pairing;
using AudioSwitcher.Tests.Helpers;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Logging.Abstractions;

namespace AudioSwitcher.Tests.Server;

/// <summary>サイン波 → 圧縮 → HTTP → 復号 の往復と、HTTP を通したペアリングを、実時間で確かめる。</summary>
public class StreamServerTests : IAsyncLifetime
{
    private const double Frequency = 1000;
    private AudioStreamService _service = null!;
    private PairingService _pairing = null!;
    private ShownCode _shown = null!;
    private WebApplication _app = null!;
    private HttpClient _client = null!;

    public async Task InitializeAsync()
    {
        _service = new AudioStreamService(
            () => new RealtimeSineSource(Frequency),
            new OggOpusEncoderFactory(),
            TimeProvider.System,
            NullLogger<AudioStreamService>.Instance);
        _pairing = new PairingService(new InMemoryPairingStore(), new SeededRandomSource(), TimeProvider.System, NullLogger<PairingService>.Instance);
        _shown = new ShownCode(_pairing);
        _app = StreamServer.Build(_service, _pairing, b => b.WebHost.UseTestServer());
        await _app.StartAsync();
        _client = _app.GetTestClient();
    }

    public async Task DisposeAsync()
    {
        _client.Dispose();
        await _app.DisposeAsync();
        _pairing.Dispose();
    }

    [Fact]
    public async Task 流れてきたOggOpusを復号するとサイン波が戻る()
    {
        var key = PairingServiceTests.Pair(_pairing);
        using var response = await GetStreamAsync(key);

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
        var key = PairingServiceTests.Pair(_pairing);
        var first = await GetStreamAsync(key);
        Assert.Equal(HttpStatusCode.OK, first.StatusCode);

        using (var second = await GetStreamAsync(key))
        {
            Assert.Equal(HttpStatusCode.Conflict, second.StatusCode);
        }

        first.Dispose();
        await Eventually.WaitUntilAsync(() => !_service.IsStreaming, "1本目の終わり");

        using var third = await GetStreamAsync(key);
        Assert.Equal(HttpStatusCode.OK, third.StatusCode);
    }

    [Fact]
    public async Task 鍵が無いか認めない鍵なら401で断り_音を取り始めない()
    {
        PairingServiceTests.Pair(_pairing);

        using var none = await GetStreamAsync(null);
        using var wrong = await GetStreamAsync("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        Assert.Equal(HttpStatusCode.Unauthorized, none.StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, wrong.StatusCode);
        Assert.False(_service.IsStreaming);
    }

    [Fact]
    public async Task HTTPでペアリングして得た鍵で流せる_外れは400で新しい候補を返す()
    {
        using var start = await _client.PostAsync(PairingEndpoints.StartPath, null);
        Assert.Equal(HttpStatusCode.OK, start.StatusCode);
        var started = await start.Content.ReadFromJsonAsync<JsonElement>();
        var candidates = started.GetProperty("candidates").EnumerateArray().Select(c => c.GetString()!).ToList();
        Assert.Equal(5, candidates.Count);

        using var wrong = await _client.PostAsJsonAsync(PairingEndpoints.AnswerPath, new
        {
            pairingId = started.GetProperty("pairingId").GetGuid(),
            answer = candidates.First(c => c != _shown.Value),
        });
        Assert.Equal(HttpStatusCode.BadRequest, wrong.StatusCode);
        var retry = await wrong.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Contains(_shown.Value, retry.GetProperty("candidates").EnumerateArray().Select(c => c.GetString()));

        using var right = await _client.PostAsJsonAsync(PairingEndpoints.AnswerPath, new
        {
            pairingId = retry.GetProperty("pairingId").GetGuid(),
            answer = _shown.Value,
        });
        Assert.Equal(HttpStatusCode.OK, right.StatusCode);
        var key = (await right.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("key").GetString();

        using var stream = await GetStreamAsync(key);
        Assert.Equal(HttpStatusCode.OK, stream.StatusCode);

        using var again = await _client.PostAsync(PairingEndpoints.StartPath, null);
        Assert.Equal(HttpStatusCode.Conflict, again.StatusCode);
        Assert.Equal("paired", (await again.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("reason").GetString());
    }

    [Fact]
    public async Task 続けて2回外れたら423で受け付けない()
    {
        var first = _pairing.Start();
        var second = _pairing.Answer(first.PairingId, first.Candidates.First(c => c != _shown.Value));

        using var locked = await _client.PostAsJsonAsync(PairingEndpoints.AnswerPath, new
        {
            pairingId = second.PairingId,
            answer = second.Candidates.First(c => c != _shown.Value),
        });
        using var start = await _client.PostAsync(PairingEndpoints.StartPath, null);

        Assert.Equal((HttpStatusCode)423, locked.StatusCode);
        Assert.Equal((HttpStatusCode)423, start.StatusCode);
        Assert.True((await start.Content.ReadFromJsonAsync<JsonElement>()).TryGetProperty("lockedUntil", out _));
    }

    private Task<HttpResponseMessage> GetStreamAsync(string? key)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, StreamServer.StreamPath);
        if (key is not null)
        {
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        }

        return _client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead);
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
