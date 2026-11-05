# 一般公開までの手順（リリース準備チェックリスト）

作成: 2026-11-03。本人の決定により、一般公開（GitHub Release での APK 配布）は以下のゲートをすべて通してから行う。通過していない項目がある状態では公開しない。

現状: 最新版は v0.1.3（versionCode 23）を 2026-11-05 23:29 に GitHub Release で公開済み（Latest。v0.1.2 は同日 22:29、v0.1.1 は同日 13:53、v0.1.0 は 2026-11-04 22:50 に公開）。v0.1.3 は vc22 全セクション監査（中3件・低17件）のうち中3件と実害系の低への対応版で、実機での上書き更新と先端復帰、停止→即開始、フッターの v0.1.3 表示も確認済み。APK（quantus-node-v0.1.3.apk、55,700,181 bytes）の SHA-256 は `dff1df99e5fa75f253477dcae1e46c04a2cf2672c4411c88b8207cdf639680e0` で、公開ページのアセット digest と照合済み。v0.1.2 は ChatGPT 外部監査（v0.1.1 対象、全11件）の対応版で、実機での上書き更新と先端復帰、開始直後の停止→即開始（監査の中心所見だった起動取消し競合の修正箇所）も確認済み（APK の SHA-256 は `7c196901d0160a6035bf931eb8bc69fbbf13e4ea17c9510e2891be7ac0548b7f`）。v0.1.1 は vc20 全監査の低重要度所見への修正版で、実機での上書き更新と先端復帰も確認済み。リリース版での実機フル同期（2026-11-05、#349,518）とテレメトリ掲載も確認済み。DB 容量の初回実測は同期完了時点で合計1.30GB。残るのは Gate 1 の定量評価（DB 容量の頭打ち・電池・発熱）と Gate 4 のライセンス同梱など、Phase 4 側の項目。

## Gate 1: 実機検証（Pixel 9a / GrapheneOS）

- [x] MVP APK がインストールでき、ノードが起動する（2026-11-03、versionCode 4 で確認）
- [x] ブロック高が実際に増加し、同期が進む（同日、ジェネシスから先端 #341592 まで完走を確認）
- [ ] 数時間の連続稼働で落ちない（Foreground Service の常駐が維持される）
- [ ] プルーニング済み DB の容量が頭打ちになる傾向を確認する
- [ ] 電池消費と発熱が日常使いと両立する範囲か
- [ ] アプリ再起動・端末再起動のあと、同じプルーニング指定で正常に復帰する（モード不一致エラーにならない）
- [x] 停止操作でノードが正常に止まる（2026-11-03、停止→開始の実施を確認。反復と端末再起動後は継続確認）

判定: このゲートが全体の土台。1つでも失敗したら、原因を直してから次へ進む。

## Gate 2: リリースビルド

- [x] `cargo build --release` の Android 向けビルドが完走する（確認済み。fat LTO はビルド機のメモリ不足で不採用、thin LTO は実験の結果サイズ減に寄与せず不採用 — 設計書「その5」参照）
- [x] release バイナリで APK を組み直し、サイズと同梱物を確認する（strip 後 約53.4MB、テスト配布版 versionCode 4〜10 で実施済み（vc10 は依存関係のセキュリティ更新を反映した再ビルド版、strip 後 53,411,080 bytes））
- [x] バージョン番号を確定する（初回公開は v0.1.0。2026-11-04、versionCode 19 / versionName 0.1.0 に確定）
- [ ] 【版上げのたび】build-manual.sh（aapt2 の --version-code / --version-name）と build.gradle の versionCode / versionName に加えて、StringsTable の FOOTER_INFO（全9言語）の版数を新しい versionName に揃え、docs/I18N.md の「All strings」表も StringsTable から再生成して揃える。確認は機械的に: (1) 現在の versionName が FOOTER_INFO の全9言語に現れること、(2) 再生成した表と docs/I18N.md の全セル（49キー × 9言語 = 441セル）が一致すること（diff ゼロ）。旧 versionName の grep は補助に留める — 直前版でない版数（2版前など）の残留は「旧版数」の grep では原理的に捕捉できない（vc22 / v0.1.2 でフッターが v0.1.1 のまま出荷された DOC-1 と、vc23 / v0.1.3 で I18N.md のフッター行が v0.1.1 のまま残った C23-1 の再発防止 — 全セクション監査）

## Gate 3: 署名

- [x] 配布専用のリリース鍵を生成する（debug 鍵は使わない）（2026-11-04 実施。詳細は後述「署名鍵の置き場所と正式署名ビルドの手順」）
- [x] 鍵のバックアップを本人が安全に保持する。**この鍵を失うと、同じアプリとして更新版を二度と配れなくなる**（バックアップメモと本人保管用コピーを用意済み。パスワードの転記保管は本人の実施事項）
- [x] keystore はリポジトリ・GitHub に絶対に入れない（リポジトリ外の `~/workspace/release-signing/` に保管し、混入がないことを git status と grep で確認済み）
- [x] リリース鍵で署名した APK を検証し、SHA-256 チェックサムを Release に併記する（当初用意した versionCode 19 の APK の SHA-256 は `b01269297b8eb5d8a64a7b9629a5a9ebf782a56f2826760bf1eebcbbc884351d`。実際に公開したのはスクロール修正を入れた versionCode 20 で、その SHA-256 は `ef1ef477d6102333f7a5c2deaee9e512eee7a98b6282cd0c167dbd4d1d003583`。apksigner verify と証明書フィンガープリントの照合で確認済みで、Release 本文にも併記した）

## Gate 4: ライセンスと表記

確認済みの事実: 上流 chain リポジトリのルート LICENSE は MIT 表記（Copyright 2025 Quantus Network）。一方でノードの crate メタデータは Apache-2.0 を宣言しており、表記に揺れがある。依存 crate にはそれぞれのライセンスがある。

- [ ] 上流の LICENSE を APK / リポジトリに同梱し、出典を明記する
- [ ] 第三者ライセンスの一覧を用意する（依存 crate 分）
- [x] アプリ自体のライセンスを最終決定する（2026-11-04、本人が Apache-2.0 で確定。LICENSE ファイルを設置済み）
- [ ] README に利用条件を書く: 対応環境（Android 12 以降・arm64）、必要な空き容量の目安、初回同期にかかる時間と通信量の注意（Wi-Fi 推奨）
- [ ] 既知の制限を明記する（プルーニング済みノードでは古い履歴の照会ができないこと等）
- [x] SECURITY.md の文言を現行の状態に合わせて整理する（2026-11-04、公開前の表現に修正済み）

## Gate 5: 監査の残件クローズ

- [ ] 実回線での DB 容量実測（Gate 1 と兼ねる）
- [x] fast 同期が実際に成立するか → コード精査の結果、機構的障壁2件により不成立と確定（2026-11-03、設計書「その5」参照）。追わない
- [ ] 2026-11-03 監査の未検証項目のうち、公開のブロッカーになるものがないか再確認する

## Gate 6: 公開作業

- [x] GitHub Release を作成する（タグ v0.1.0、APK とチェックサムを添付）（2026-11-04 22:50 に公開済み。https://github.com/ogaworld369/quantus-android-node/releases/tag/v0.1.0。本人の依頼で助手がブラウザ経由で代行作成）
- [x] リリースノートを用意する: 概要、要件、既知の制限、フィードバック方法（Issue テンプレートへの導線）（v0.1.0 の完成文を用意し、公開時に本文へ掲載済み）
- [ ] コミュニティ向け告知文の下書きを用意する（過剰な宣伝にせず、テスト版であることを正直に書く）
- [x] v0.1.2（versionCode 22）を公開する（2026-11-05 22:29 に公開済み・Latest。https://github.com/ogaworld369/quantus-android-node/releases/tag/v0.1.2。ChatGPT 外部監査の全11件への対応版で、リリースノートも用意し公開時に本文へ掲載済み。APK の SHA-256 は `7c196901d0160a6035bf931eb8bc69fbbf13e4ea17c9510e2891be7ac0548b7f`（55,700,181 bytes）で公開ページのアセット digest と一致を確認。実機では v0.1.1 への上書き→先端復帰と開始直後の停止→即開始を確認済み）
- [x] v0.1.3（versionCode 23）を公開する（2026-11-05 23:29 に公開済み・Latest。https://github.com/ogaworld369/quantus-android-node/releases/tag/v0.1.3。vc22 全セクション監査の中3件＋実害系の低への対応版で、リリースノートも用意し公開時に本文へ掲載済み。APK の SHA-256 は `dff1df99e5fa75f253477dcae1e46c04a2cf2672c4411c88b8207cdf639680e0`（55,700,181 bytes）で公開ページのアセット digest と一致を確認。実機では v0.1.2 への上書き→先端復帰と停止→即開始、フッターの v0.1.3 表示を確認済み）

## 署名鍵の置き場所と正式署名ビルドの手順

（2026-11-04、Gate 3 実施時に追記）

### 鍵の置き場所（リポジトリの外）

- キーストア: `~/workspace/release-signing/quantus-release.jks`（権限 600）
- パスワードファイル: `~/workspace/release-signing/keystore-password.txt`（権限 600）
  - パスワードの実値はこの手順書を含め、リポジトリ内のどこにも書かない
- エイリアス: `quantus-release`
- 証明書 SHA-256 フィンガープリント（公開情報）:
  `65:BB:1A:5F:EF:27:D7:0B:E1:DD:12:FC:12:CE:34:84:B4:C8:48:FC:EC:A9:2F:88:98:CD:96:CB:27:84:F6:FF`
- 本人保管用にキーストアのコピーとバックアップメモを別途渡してある
  （ゴール側ファイル置き場。リポジトリではない）

### 正式署名ビルド

```sh
cd app
QUANTUS_SIGNING=release bash build-manual.sh
# 成果物: app/build/outputs/apk/release/quantus-node-release.apk
```

- 環境変数を付けなければ従来どおり debug 署名（開発用）
- 署名時、スクリプトはパスワードファイルから値を環境変数へ読み込み、
  apksigner へ `env:` 経由で渡す。パスワードがスクリプト内・コマンド
  ライン・ビルドログに現れることはない
- 置き場所を変える場合は `QUANTUS_RELEASE_KS` /
  `QUANTUS_RELEASE_KS_PASS_FILE` / `QUANTUS_RELEASE_ALIAS` で上書きできる
- 確認: `apksigner verify --print-certs` の証明書 SHA-256 が上記
  フィンガープリントと一致すること、`aapt2 dump badging` で
  versionCode / versionName と権限（4つ）を確認すること

## 公開のあと（Phase 4 として分離）

- F-Droid 申請、他 Android 機種での検証、量子ウォッチの外部情報の実装、release ビルドの CI 化と再現可能ビルド
