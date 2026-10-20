using AudioSwitcher.Server.Pairing;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;

namespace AudioSwitcher.Server;

/// <summary>ペアリングの受け口（docs/構成.md の 2.3「通信の取り決め」）。</summary>
public static class PairingEndpoints
{
    public const string StartPath = "/pair/start";
    public const string AnswerPath = "/pair/answer";

    public static IEndpointRouteBuilder MapPairing(this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapPost(StartPath, (PairingService pairing) =>
        {
            var result = pairing.Start();
            return result.Status switch
            {
                PairingStartStatus.Started => Results.Ok(new { pairingId = result.PairingId, candidates = result.Candidates }),
                PairingStartStatus.AlreadyPaired => Results.Conflict(new { reason = "paired" }),
                PairingStartStatus.Busy => Results.Conflict(new { reason = "busy" }),
                PairingStartStatus.Locked => Results.Json(new { lockedUntil = result.LockedUntil }, statusCode: StatusCodes.Status423Locked),
                _ => throw new InvalidOperationException($"知らない結果 {result.Status}"),
            };
        });

        endpoints.MapPost(AnswerPath, (AnswerRequest request, PairingService pairing) =>
        {
            var result = pairing.Answer(request.PairingId, request.Answer ?? string.Empty);
            return result.Status switch
            {
                PairingAnswerStatus.Paired => Results.Ok(new { key = result.Key }),
                PairingAnswerStatus.Wrong => Results.BadRequest(new { pairingId = result.PairingId, candidates = result.Candidates }),
                PairingAnswerStatus.Locked => Results.Json(new { lockedUntil = result.LockedUntil }, statusCode: StatusCodes.Status423Locked),
                PairingAnswerStatus.Expired => Results.StatusCode(StatusCodes.Status410Gone),
                _ => throw new InvalidOperationException($"知らない結果 {result.Status}"),
            };
        });

        return endpoints;
    }

    /// <summary><c>Authorization: Bearer &lt;鍵&gt;</c> から鍵を取り出す。無ければ null。</summary>
    public static string? BearerKey(HttpRequest request)
    {
        const string prefix = "Bearer ";
        string? header = request.Headers.Authorization;
        return header is not null && header.StartsWith(prefix, StringComparison.Ordinal) ? header[prefix.Length..].Trim() : null;
    }

    public sealed record AnswerRequest(Guid PairingId, string? Answer);
}
