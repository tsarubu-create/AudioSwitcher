# AudioSwitcher

Windows 11 の PC で鳴っている音を、同じ Wi-Fi にいる Android 端末へ転送して聴くための自分用アプリ。

- PC 側: C# / .NET 10（タスクトレイ常駐）
- Android 側: Kotlin / Jetpack Compose / Media3

構成と決めごとは [docs/構成.md](docs/構成.md) を参照。

## PC 側（試作）

### 要るもの

- Windows 11
- .NET 10 のランタイム（Desktop と ASP.NET Core）。.NET 10 の SDK が入っていれば足りる。

### 動かし方

1. `AudioSwitcher.exe` を起動する。タスクトレイにアイコンが出る。
2. 初回は Windows のファイアウォールの許可を求める画面が出る。同じ Wi-Fi の Android から繋ぐには「プライベート ネットワーク」を許可する。
   - Wi-Fi のネットワークの種類が「パブリック」になっていると止められやすい。「設定 → ネットワークとインターネット → Wi-Fi → 繋いでいるネットワーク」で「プライベート ネットワーク」にする。
3. トレイのアイコンを右クリックすると、状態（待ち受け中／送信中）と、この PC の URL（`http://<IP>:48100/stream`）が出る。押すと URL をコピーする。
4. 受け手がその URL を開くと、音を取り始めて流す。受け手が切ると止める。
5. 終えるときは、トレイのメニューの「終了」。

- ポートは起動の引数で変えられる: `AudioSwitcher.exe --port 48200`
- ログ: `%LOCALAPPDATA%\AudioSwitcher\logs`（トレイの「ログのフォルダを開く」）

### 試作の制約

- ペアリング（[docs/構成.md](docs/構成.md) の 2.3）はまだ無い。**同じ Wi-Fi の誰でも URL を開けば聴ける**ので、試すときだけ起動する。
- Android アプリはまだ無い。

### 開発

```
cd pc
dotnet build
dotnet test
```
