namespace AudioSwitcher.Tests.Helpers;

internal static class Eventually
{
    /// <summary>
    /// 条件が満たされるまで待つ。偽の時計を進めたあと、続きの処理が別のスレッドで走り終わるのを待つのに使う。
    /// </summary>
    public static async Task WaitUntilAsync(Func<bool> condition, string description, int timeoutMs = 5000)
    {
        var deadline = Environment.TickCount64 + timeoutMs;
        while (!condition())
        {
            if (Environment.TickCount64 > deadline)
            {
                throw new TimeoutException($"{timeoutMs}ms 待っても満たされなかった: {description}");
            }

            await Task.Delay(5);
        }
    }
}
