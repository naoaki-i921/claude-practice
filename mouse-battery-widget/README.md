# Mouse Battery Widget

Logitech(Logicool) のワイヤレスゲーミングマウスのバッテリー残量を、**タスクバーの通知領域（システムトレイ）に数値で常時表示**する Windows 常駐アプリです。残量が少なくなると **警告音**（全画面ゲーム中でも鳴る）と **トースト通知** を出します。

G HUB は不要です。マウス（または Lightspeed レシーバー）と **HID++ プロトコルで直接通信** して残量を取得します。

- 言語: Java（Swing / AWT SystemTray）
- HID アクセス: [hid4java](https://github.com/gary-rowe/hid4java)（hidapi 同梱）
- ウィンドウなし。トレイアイコンのみ

---

## 動作確認

この開発環境で以下を確認済みです。

```
マウス     : PRO X 2  (G PRO X SUPERLIGHT 2)
レシーバー : Logitech USB Receiver  (VID 046D / PID C54D)
取得       : HID++ feature 0x1004 (unifiedBattery) → 35% / 放電中
```

他の Logitech ゲーミングマウス（G Pro Wireless, G Pro X Superlight, G502 Lightspeed など）でも、
HID++ feature `0x1000` または `0x1004` に対応していれば動作します。USB ケーブル直結でも取得できます。

---

## 必要なもの

- **JDK 17 以上**（`java` / `javac` に PATH が通っていること）
  - 確認: `java -version`
  - 未導入なら [Adoptium Temurin](https://adoptium.net/) などを導入

---

## ビルド

PowerShell で:

```powershell
cd path\to\mouse-battery-widget
powershell -ExecutionPolicy Bypass -File build.ps1
```

初回は依存 JAR を `lib\` にダウンロードし、`dist\` に成果物を作ります。

| ファイル | 用途 |
|---|---|
| `dist\MouseBatteryWidget.jar` | 依存込みの実行可能 JAR |
| `dist\MouseBatteryWidget.vbs` | コンソール窓なしで常駐起動するランチャ |

Maven は不要です（`build.ps1` が `javac` / `jar` を直接呼びます）。

---

## 実行

### 常駐起動（通常はこちら）

```
dist\MouseBatteryWidget.vbs をダブルクリック
```

タスクバー右端にバッテリー残量の数値アイコンが出ます。

### 診断モード（トラブル時）

```powershell
java -jar dist\MouseBatteryWidget.jar --debug
```

接続中の HID デバイス一覧と、バッテリー取得の通信ログを表示して終了します。
うまく動かないときはこの出力を確認してください。

### スタートアップに登録（PC 起動時に自動実行）

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1 -InstallStartup
```

これで次の処理を行います。

1. `%LOCALAPPDATA%\MouseBatteryWidget\` に jar と小さなランチャ exe をコピー
   （リポジトリが WSL 上にあるとログオン直後は共有が使えないことがあるため、
   Windows 側のローカルにコピーして起動を確実にする）
2. レジストリ `HKCU\...\CurrentVersion\Run` に値 **`MouseBatteryWidget`** を追加
   （中身は `"<コピー先>\MouseBatteryWidget.exe"`）
3. その場で 1 度起動

**タスクマネージャー →「スタートアップ アプリ」に `MouseBatteryWidget` という名前・
発行元で表示されます。** ここから一時的に無効化することもできます。

> Windows 11 のタスクマネージャーは登録名ではなく「起動する exe の説明」を表示するため、
> `javaw.exe` を直接登録すると "javaw" と出てしまう。そこで `src/launcher/Launcher.cs`
> をビルドした約 5KB のスタブ exe（説明 = MouseBatteryWidget）を噛ませている。
> `csc.exe`（.NET Framework 同梱、通常どの Windows にもある）が無い場合は
> 自動的に javaw 直接登録にフォールバックする。

**コードを変更したら `build.ps1 -InstallStartup` を再実行**してコピーを更新してください。

解除:

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1 -RemoveStartup
```

---

## トレイアイコンの見かた

| 表示 | 意味 |
|---|---|
| 緑の数字 | 残量 26% 以上 |
| 橙の数字 | 残量 11〜25% |
| 赤の数字 | 残量 10% 以下 |
| 水色の数字 ＋ 右上の点 | 充電中 |
| `OK` | 満充電 |
| 灰色の × | マウス未検出（スリープ中・電源オフなど） |

マウスにカーソルを合わせると `PRO X 2 : 35% (放電中)` のようなツールチップが出ます。

---

## トレイ右クリックメニュー

| 項目 | 説明 |
|---|---|
| 今すぐ更新 | すぐに残量を取り直す（ダブルクリックでも可） |
| 低残量で警告音 | ON/OFF |
| 低残量でトースト通知 | ON/OFF |
| 「残りわずか」しきい値 | 既定 15%。ここを下回ると 1 回通知 |
| 「危険」しきい値 | 既定 7%。ここを下回るとより強い通知 |
| 警告音 › 音声ファイルを選択 | 好きな音に変更（**WAV / AIFF / AU**。MP3 非対応） |
| 警告音 › 組み込み音に戻す | 既定のビープ音に戻す |
| 警告音 › 音量 | 20〜100%（選ぶとテスト再生） |
| 警告音 › テスト再生 | 通知と音を確認 |
| 設定ファイルの場所 | パスをトースト表示 |
| 終了 | 常駐を終了 |

### 警告音を任意の音にする

「警告音 › 音声ファイルを選択...」で `.wav` などを指定すると、以降その音が鳴ります
（ファイルパスは設定に保存。ファイルは移動・削除しないでください）。
MP3 を使いたい場合は、あらかじめ WAV に変換してください
（例: `ffmpeg -i sound.mp3 sound.wav`）。

通知は「健全 → 残りわずか → 危険」と悪化したときだけ鳴ります。充電を開始するとリセットされます。

---

## 設定ファイル

`%APPDATA%\MouseBatteryWidget\config.properties`

```properties
lowThreshold=15
criticalThreshold=7
soundEnabled=true
soundVolume=0.8
soundFile=
notificationsEnabled=true
pollSeconds=60
```

メニューからの変更はここに保存されます。直接編集する場合はアプリを終了してから。

---

## 全画面ゲームでの挙動

| ゲームの表示モード | トレイアイコン | トースト通知 | 警告音 |
|---|---|---|---|
| ウィンドウ / ボーダーレス全画面 | タスクバーを出せば見える | 集中モードOFF なら出る | 鳴る |
| 排他的全画面（真の全画面） | ゲーム中は隠れる（Alt+Tab で見える） | 出ない（通知センターに溜まる） | **鳴る** |

- **警告音はどのモードでも鳴ります**（オーディオ出力は全画面や集中モードの影響を受けないため）。
  排他的全画面のゲーム中に残量を知る手段はこれです。
- 排他的全画面へのオーバーレイ描画（RivaTuner や Discord のような表示）は、ゲームプロセスへの
  DLL 注入が必要で、競技系タイトルではアンチチートに検知される恐れがあるため実装していません。

### トースト通知を全画面（ボーダーレス）でも出したい場合

Windows 11 は既定で「ゲーム中・全画面時」に集中モードへ自動切替し、通知を抑制します。
`設定 > システム > 通知 > 通知を自動的にオンにする` を開き、

- 「ゲームをプレイしているとき」
- 「アプリを全画面モードで使用しているとき」

のルールを OFF にすると、ボーダーレス全画面でもトーストが表示されます（排他的全画面は不可）。

---

## トラブルシューティング

**トレイアイコンが灰色の × のまま**
- `java -jar dist\MouseBatteryWidget.jar --debug` を実行
- `Attached HID devices` に `VID=046D ... <- Logitech HID++` の行があるか確認
- マウスがスリープしている場合は動かしてから再度 `今すぐ更新`

**`--debug` で `no data` になる**
- マウスが `0x1000` / `0x1004` 以外の方式（古い `0x1001` バッテリー電圧のみなど）の可能性
- `--debug` の通信ログ（`> ...` / `< ...` の行）を控えておくと対応を追加できます

**G HUB と同時に使える？**
- 使えます。HID の入力レポートは複数ハンドルに配信されるため共存できます。

**残量が 10% きざみでしか変わらない**
- 一部のマウスは HID++ が段階値しか返しません（アプリ側では丸めていません）。

**警告音が鳴らない**
- メニューの「警告音をテスト」で確認
- ごく一部のゲーム/オーディオ設定が音声を排他モードで掴むと他アプリの音が出ません（既定は共有モード）

---

## 仕組み（概要）

1. `hidapi` で `VID=0x046D` かつ usage page `0xFF00` 以上の HID コレクション（HID++ インターフェース）を列挙。
   20 バイトの long レポートを扱う `usage 0x0002` を優先して開く。
2. `root` feature (`0x0000`) に問い合わせて `0x1000` / `0x1004` / `0x0005` の feature index を解決。
   - 応答が featureId を含まないため、呼び出しごとに 4bit ソフトウェア ID を回して古い応答を除外。
3. バッテリー feature を呼んで残量（%）と充電状態を取得。`0x0005` でマウス名も取得。
4. 60 秒ごとに更新。成功した「デバイスインデックス / feature」をキャッシュし、以降は 1 往復で取得。

---

## 単独 exe 化（任意）

JRE 同梱の配布物を作る場合、JDK の `jpackage` を使えます。

```powershell
jpackage `
  --type app-image `
  --name MouseBatteryWidget `
  --input dist `
  --main-jar MouseBatteryWidget.jar `
  --main-class com.example.mousebattery.App
```

`MouseBatteryWidget\MouseBatteryWidget.exe` が生成されます（コンソールなしの GUI exe）。

---

## ライセンス / 謝辞

- HID アクセス: [hid4java](https://github.com/gary-rowe/hid4java)（MIT）
- HID++ プロトコルは [Solaar](https://github.com/pwr-Solaar/Solaar) / [libratbag](https://github.com/libratbag/libratbag) の公開情報を参考にしています。
