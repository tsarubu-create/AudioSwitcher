using AudioSwitcher.Server.Pairing;
using Microsoft.Extensions.Logging.Abstractions;

namespace AudioSwitcher.Tests.Server;

public sealed class FilePairingStoreTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "AudioSwitcherTests", Guid.NewGuid().ToString("N"));

    [Fact]
    public void 保存した状態を読み戻せる()
    {
        var path = Path.Combine(_directory, "pairing.json");
        var state = new PairingState("aGFzaA==", 1, new DateTimeOffset(2026, 10, 21, 3, 4, 5, TimeSpan.Zero));

        new FilePairingStore(path, NullLogger<FilePairingStore>.Instance).Save(state);

        Assert.Equal(state, new FilePairingStore(path, NullLogger<FilePairingStore>.Instance).Load());
        Assert.False(File.Exists(path + ".tmp"));
    }

    [Fact]
    public void ファイルが無ければペアリングしていない状態()
    {
        var store = new FilePairingStore(Path.Combine(_directory, "none.json"), NullLogger<FilePairingStore>.Instance);

        Assert.Equal(PairingState.Empty, store.Load());
    }

    [Fact]
    public void 壊れたファイルはペアリングしていない状態として読む()
    {
        Directory.CreateDirectory(_directory);
        var path = Path.Combine(_directory, "broken.json");
        File.WriteAllText(path, "{ これは JSON ではない");

        Assert.Equal(PairingState.Empty, new FilePairingStore(path, NullLogger<FilePairingStore>.Instance).Load());
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }
}
