using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace AudioSwitcher.Server;

/// <summary>配信の HTTP サーバを組み立てる。</summary>
public static class StreamServer
{
    public const string StreamPath = "/stream";

    /// <param name="configure">待ち受けの口やログの出し先を決める（本番は Kestrel、テストは TestServer）。</param>
    public static WebApplication Build(AudioStreamService service, Action<WebApplicationBuilder> configure)
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Services.AddSingleton(service);
        configure(builder);

        var app = builder.Build();
        app.MapAudioStream();
        return app;
    }

    public static IEndpointRouteBuilder MapAudioStream(this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapGet(StreamPath, HandleStreamAsync);
        return endpoints;
    }

    private static async Task HandleStreamAsync(HttpContext context, AudioStreamService service, ILoggerFactory loggerFactory)
    {
        var logger = loggerFactory.CreateLogger(typeof(StreamServer));
        var remote = context.Connection.RemoteIpAddress;

        if (!service.TryAcquire())
        {
            logger.LogInformation("流している最中のため、{Remote} からの接続を断った", remote);
            context.Response.StatusCode = StatusCodes.Status409Conflict;
            return;
        }

        try
        {
            logger.LogInformation("{Remote} へ流し始める", remote);
            context.Response.ContentType = service.ContentType;
            context.Response.Headers.CacheControl = "no-store";
            context.Features.Get<IHttpResponseBodyFeature>()?.DisableBuffering();
            await context.Response.StartAsync(context.RequestAborted);

            var reason = await service.StreamAsync(context.Response.Body, context.RequestAborted);
            logger.LogInformation("{Remote} へのストリームを終えた（{Reason}）", remote, reason);
            if (reason != StreamEndReason.ClientDisconnected)
            {
                // 受け手に「途中で切れた」と分かるよう、正しく終わらせずに接続を切る。
                context.Abort();
            }
        }
        finally
        {
            service.Release();
        }
    }
}
