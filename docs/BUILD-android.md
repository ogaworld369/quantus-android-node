# quantus-node を Android (aarch64-linux-android) 向けにビルドする

Quantus-Network/chain のノード本体 `quantus-node`（v1.0.5）を Android ARM64 向けにクロスビルドする手順。
2026-11-03 にこの手順で検証した結果を記録する。推測ではなく、実際に踏んだエラーと回避策だけを書く。

## 検証結果サマリ

| 段階 | 結果 |
|---|---|
| `cargo check -p quantus-node --target aarch64-linux-android`（Wasm ビルド無効） | ✅ EXIT 0（Phase 0、2026-11-03） |
| `cargo check -p quantus-node --target aarch64-linux-android`（Wasm ビルド有効、quantus_runtime.wasm 約3.1MB 生成） | ✅ EXIT 0（Phase 0、2026-11-03） |
| `cargo build -p quantus-node --target aarch64-linux-android`（最終リンク） | ✅ **成功**（2026-11-03）。未定義シンボル等のリンクエラーなし。成果物は後述 |

## 前提

- ホスト: Linux x86_64（検証環境: 4コア / RAM 7GB / ディスク空き 140GB 級）
- 対象ソース: Quantus-Network/chain（node v1.0.5 時点の main、depth 1 clone で取得）
- 所要時間の目安（検証環境）: 初回 `cargo check` は実質1時間超。ランタイム Wasm ビルド有効の check は追加で約22分。`cargo build`（最終リンクまで）は、check 済みの `target/` を再利用した条件で **10分46秒**（2026-11-03 実測、`-j2`）。クリーンビルドの所要時間は未計測で、これより大幅に長くなる見込み（CI ではキャッシュ前提で設計すること）。

## 1. ツールチェーンの用意

すべて任意のディレクトリ配下に閉じてよい。検証では `~/workspace/toolchain/` に置いた。

| 項目 | バージョン | 入手先・備考 |
|---|---|---|
| Rust | **1.93.0** | リポジトリの `rust-toolchain` が指定。rustup で入れ、`aarch64-linux-android` と `wasm32v1-none` の target を追加 |
| Android NDK | **r27d**（Pkg.Revision 27.3.13750724、clang 18.0.4） | 公式 `https://dl.google.com/android/repository/android-ndk-r27d-linux.zip`（663,956,036 bytes）。ダウンロードが途中で切れると zip が壊れるので、サイズを必ず確認してから展開する |
| protoc | **29.3** | protobuf 公式 GitHub Releases の linux-x86_64 zip。`PATH` 上の任意の場所に置き、後述の `PROTOC` で指す |

```sh
rustup toolchain install 1.93.0
rustup target add --toolchain 1.93.0 aarch64-linux-android wasm32v1-none
```

## 2. 依存ロックの更新（必須・これをしないと必ず止まる）

公式 `Cargo.lock` の **rustix 1.1.2 は Android でコンパイルできない**（libc バックエンドの thread モジュールが未リンクの `linux_raw_sys` を参照し、E0433 が大量に出る。sc-network / sc-executor-wasmtime / sc-client-db がすべてこれで止まる）。

```sh
cd chain
cargo update -p rustix   # 1.1.2 -> 1.1.6（linux-raw-sys 0.11.0->0.12.1、libc 0.2.175->0.2.191 も連動）
```

変更は `Cargo.lock` のみ（11行）。**コンパイル検証（Phase 0）の時点では**、ソースコードのパッチは不要だった。ただし実機で動かす現行ビルドでは、次節の2パッチが必須になる。

## 3. ソースコードのパッチ（出荷ビルドでは必須・2点）

コンパイル検証だけなら不要だが、実機で動くバイナリを作るには、リポジトリの `patches/` にある2点をビルド前に適用すること。**どちらか一方でも欠けたままビルドすると、実機では使い物にならない**（§5 の手順どおりに作っても、プルーニングが効かずアーカイブ保存を強制される／ネットワーク開始直後に panic して即死する。いずれも実機で踏んだ実績がある）。

### 3.1 プルーニングパッチ

上流は全ノードにアーカイブ保存を強制する作りで、CLI の `--state-pruning` / `--blocks-pruning` 指定がコード側で無効化される。`patches/pruning-patch.diff`（`node/src/command.rs` への1ファイルの差分）を適用すると、数値を指定したときだけその指定が尊重されるようになる（未指定・`archive` 等を指定した場合は従来どおりの強制が維持される）。

```sh
cd chain
patch -p1 < ../patches/pruning-patch.diff   # patches/ へのパスは配置に合わせて読み替える
```

### 3.2 DNS フォールバック（ベンダーパッチ）

ノードをアプリから素のプロセスとして実行すると、litep2p の DNS 解決（hickory-resolver）が未初期化の ndk-context を呼んで `android context was not initialized` で panic する（ブートノードが全て /dns/ アドレスのため、ネットワーク開始直後に必ず踏む）。`patches/hickory-ndk-fallback-patch.diff` の冒頭に書かれた再現手順どおりに適用すること。要点は次の3つ:

1. crates.io の ndk-context 0.1.1 と hickory-resolver 0.26.1 を `chain/vendor/` へコピーする
2. パッチ内の3ファイル分の差分を vendor 側へ適用する
3. `chain/Cargo.toml` に `[patch.crates-io]` エントリ（パッチ末尾のハンク）を追加し、この2クレートを vendor 版へ差し替える（`Cargo.lock` は cargo が更新する）

適用後の DNS 解決は、JNI（Android コンテキストがある場合のみ）→ システムプロパティ `net.dns1`〜`net.dns4` → 公開 DNS（1.1.1.1 / 8.8.8.8）の順にフォールバックし、どの経路でも panic しない。

## 4. 環境変数（必須の一式）

以下の内容を `android-env.sh` として保存し、ビルド前に `source` するのが確実。
NDK のパスは自分の配置に合わせて読み替えること。

```sh
export RUSTUP_HOME="$HOME/workspace/toolchain/rustup"   # rustup を閉じた場所に置いた場合
export CARGO_HOME="$HOME/workspace/toolchain/cargo"
export PATH="$HOME/workspace/toolchain/cargo/bin:$HOME/workspace/toolchain/bin:$PATH"

NDK="$HOME/workspace/toolchain/android-ndk"
LLVM="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

# リンカ / C ツールチェーン（API レベル 35 で検証）
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LLVM/bin/aarch64-linux-android35-clang"
export CC_aarch64_linux_android="$LLVM/bin/aarch64-linux-android35-clang"
export CXX_aarch64_linux_android="$LLVM/bin/aarch64-linux-android35-clang++"
export AR_aarch64_linux_android="$LLVM/bin/llvm-ar"

# litep2p の build.rs（prost-build）がホストの protoc を要求する
export PROTOC="$HOME/workspace/toolchain/bin/protoc"

# bindgen（librocksdb-sys）が libclang を要求する。NDK 同梱のものを使う。
# LIBCLANG_PATH だけでは不十分で、ビルドスクリプト実行時のロード用に LD_LIBRARY_PATH も要る
export LIBCLANG_PATH="$LLVM/musl/lib"
export LD_LIBRARY_PATH="$LLVM/musl/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# bindgen に Android のヘッダを見せる（無いと rocksdb/c.h の解析で 'stdbool.h' file not found）
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--target=aarch64-linux-android35 --sysroot=$LLVM/sysroot -resource-dir $LLVM/lib/clang/18"
```

## 5. ビルド

```sh
cd chain
source ../android-env.sh

# 型チェックだけなら（リンクはしない）
cargo check -p quantus-node --target aarch64-linux-android

# 実バイナリ（最終リンクまで）
cargo build -p quantus-node --target aarch64-linux-android
# 成果物: target/aarch64-linux-android/debug/quantus-node
```

### ビルド結果（2026-11-03 実測）

- コマンドは上記の通りで、並列数のみ `-j2` に絞った（RAM 7GB・スワップなし環境のため）。debuginfo 削減やプロファイル変更は不要だった。リンク直前のメモリ使用は約6.1GB / 7.9GB。
- 成果物: `target/aarch64-linux-android/debug/quantus-node`、**1,072,804,072 bytes**。
  `file` の結果: `ELF 64-bit LSB pie executable, ARM aarch64, dynamically linked, interpreter /system/bin/linker64, with debug_info, not stripped`。
  サイズの大半は debug 情報で、`llvm-strip` したコピーは 122,601,984 bytes（約117MB）だった。
- NEEDED ライブラリ: `libstdc++.so` / `libc++_shared.so` / `libdl.so` / `libm.so` / `libc.so`。
  **注意: `libc++_shared.so` はアプリ側で同梱が必要**（NDK の `toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so` を使う）。リンク自体のブロッカーではないが、実機で動かす段階で必須になる。

## 6. 既知の注意点

- **ホスト側ビルドが重い。** ランタイムの build 依存として `wasm-opt-sys`（Binaryen の C++ 全体）をホストでビルドする。さらに pallet-wormhole の build.rs が ZK 回路バイナリ生成（plonky2）をホスト・release プロファイルで回し、検証環境（4コア）では1回あたり約20分かかった。これはビルドのたび（ホスト向けと Wasm 向け）に繰り返し走るため、CI では成果物キャッシュの設計が重要になる。
- **RAM の少ないマシンでは並列数を絞る。** 検証環境は RAM 7GB・スワップなしで、`cargo build` は `-j2` で実施しリンクまで完走した（ピーク約6.1GB使用）。スワップの追加は環境によっては権限で弾かれる（この VM では swapon 自体が Operation not permitted だった）。
- **RocksDB と ring のネイティブコードは NDK clang で実コンパイルされる**（`librocksdb.a` / `libsnappy.a` / `libring_core_*.a` の生成を確認済み）。jemalloc（tikv-jemalloc-sys）はノードの既定依存グラフに含まれないので、Android での jemalloc 問題は既定ビルドでは発生しない。
- **wasmtime / cranelift はコンパイル段階では素通りした**が、実行時のメモリ確保（pooling allocator 等）の挙動はコンパイルとは別問題。実機実行は未検証（次節）。

## 7. 未検証（ここから先は「動く」ことの証明にはならない）

リンクは通ったが、以下は依然として未検証。**リンク成功は「実機で動く」ことの証明ではない。**

- 実機（bionic）での起動: `libc++_shared.so` の同梱ロード、wasmtime のメモリ確保、RocksDB の mmap・ファイルロック、SELinux / W^X、バックグラウンド制限は実行してみるまで分からない。
- クリーンビルド（`target/` なし）での所要時間。
- `[profile.production]`（LTO を含む別プロファイル）のビルド可否。

### release プロファイルの検証結果（2026-11-03 追記）

`cargo build --release -p quantus-node --target aarch64-linux-android -j2`（android-env.sh、Wasm 有効）が**初回試行のまま完走**した。プロファイル変更・追加パッチは不要で、OOM もなし。

- 所要時間: 約96分（debug の `target/` キャッシュがある状態での計測）
- 成果物: 未 strip 77,885,328 bytes → strip 後 **53,403,896 bytes**（debug 版の 43.6%）。NEEDED は debug 版と同一で、`libc++_shared.so` の同梱が必要な点も不変
- なお上記は §3.2 のベンダーパッチ適用前の初回 release ビルドの数値。**出荷バイナリはパッチ適用後の再ビルド版で、未 strip 77,890,168 bytes → strip 後 53,408,792 bytes**（同梱中のバイナリはこちら）
- 前提の訂正: 上流の `[profile.release]` は opt-level=3 のみで **LTO を含まない**（LTO は別プロファイル `[profile.production]` 側）。「release は LTO でリンクメモリが溢れる」という当初の懸念は、このプロファイルでは発生しなかった
- メモリの実態: ビルド中の MemAvailable は最小約49MB まで落ちたが枯渇には至らなかった。最大の消費者は最終リンクではなく、Wasm ビルド内の pallet-wormhole ビルドスクリプト（ZK 回路生成、RSS 約4.96GB）
