# Building quantus-node for Android (aarch64-linux-android)

How to cross-build the `quantus-node` node binary (v1.0.5) from Quantus-Network/chain for Android ARM64.
This records the results verified with this procedure on 2026-11-03. It documents only errors actually hit and their workarounds, not speculation.

## Verification summary

| Stage | Result |
|---|---|
| `cargo check -p quantus-node --target aarch64-linux-android` (Wasm build disabled) | ✅ EXIT 0 (Phase 0, 2026-11-03) |
| `cargo check -p quantus-node --target aarch64-linux-android` (Wasm build enabled, quantus_runtime.wasm of about 3.1 MB generated) | ✅ EXIT 0 (Phase 0, 2026-11-03) |
| `cargo build -p quantus-node --target aarch64-linux-android` (final link) | ✅ **Succeeded** (2026-11-03). No link errors such as undefined symbols. The artifact is described below |

## Prerequisites

- Host: Linux x86_64 (verification environment: 4 cores / 7 GB RAM / about 140 GB free disk)
- Target source: Quantus-Network/chain (main at node v1.0.5, fetched with a depth-1 clone)
- Rough time required (verification environment): the first `cargo check` took over an hour in practice. The check with the runtime Wasm build enabled took about 22 additional minutes. `cargo build` (through the final link) took **10 minutes 46 seconds** (measured 2026-11-03, `-j2`), reusing the already-checked `target/`. A clean build has not been timed and is expected to take substantially longer (design CI around caching).

## 1. Preparing the toolchain

Everything can live under an arbitrary directory. The verification used `~/workspace/toolchain/`.

| Item | Version | Source / notes |
|---|---|---|
| Rust | **1.93.0** | Specified by the repository's `rust-toolchain`. Install with rustup and add the `aarch64-linux-android` and `wasm32v1-none` targets |
| Android NDK | **r27d** (Pkg.Revision 27.3.13750724, clang 18.0.4) | Official `https://dl.google.com/android/repository/android-ndk-r27d-linux.zip` (663,956,036 bytes). An interrupted download corrupts the zip, so always verify the size before extracting |
| protoc | **29.3** | The linux-x86_64 zip from the official protobuf GitHub Releases. Place it anywhere on `PATH` and point `PROTOC` below at it |

```sh
rustup toolchain install 1.93.0
rustup target add --toolchain 1.93.0 aarch64-linux-android wasm32v1-none
```

## 2. Updating the dependency lock (required; the build always stops without this)

The **rustix 1.1.2 in the official `Cargo.lock` does not compile on Android** (the libc backend's thread module references an unlinked `linux_raw_sys`, producing a flood of E0433 errors. sc-network / sc-executor-wasmtime / sc-client-db all stop on this).

```sh
cd chain
cargo update -p rustix   # 1.1.2 -> 1.1.6 (linux-raw-sys 0.11.0->0.12.1, libc 0.2.175->0.2.191 follow along)
```

The change is limited to `Cargo.lock` (11 lines). **At the compile-verification (Phase 0) stage**, no source code patches were needed. However, the current build that runs on a real device requires the two patches in the next section.

## 3. Source code patches (required for the shipping build; three of them)

Not needed for compile verification alone, but to produce a binary that works on a real device, apply the three patches in the repository's `patches/` before building. **If either one is missing, the result is unusable on a real device** (even when built exactly per the procedure in §5, pruning does not take effect and archive storage is forced / the binary panics and dies immediately right after networking starts. Both were actually hit on a real device).

### 3.1 Pruning patch

Upstream is built to force archive storage on all nodes, and the CLI's `--state-pruning` / `--blocks-pruning` options are disabled in code. Applying `patches/pruning-patch.diff` (a one-file diff to `node/src/command.rs`) makes the setting respected only when a numeric value is given (when unspecified, or when `archive` etc. is specified, the existing forced behavior is kept).

```sh
cd chain
patch -p1 < ../patches/pruning-patch.diff   # adjust the path to patches/ to match your layout
```

### 3.2 DNS fallback (vendor patch)

When the node is run from the app as a plain process, litep2p's DNS resolution (hickory-resolver) calls an uninitialized ndk-context and panics with `android context was not initialized` (all bootnodes use /dns/ addresses, so this is always hit right after networking starts). Apply the patch following the reproduction steps written at the top of `patches/hickory-ndk-fallback-patch.diff`. The three key points:

1. Copy crates.io's ndk-context 0.1.1 and hickory-resolver 0.26.1 into `chain/vendor/`
2. Apply the diffs for the three files in the patch to the vendor copies
3. Add a `[patch.crates-io]` entry to `chain/Cargo.toml` (the hunk at the end of the patch) to replace these two crates with the vendored versions (`Cargo.lock` is updated by cargo)

After the patch, DNS resolution falls back in the order JNI (only when an Android context exists) → system properties `net.dns1` through `net.dns4` → public DNS (1.1.1.1 / 8.8.8.8), and never panics on any path.

### 3.3 Wasmtime pooling patch

The default instantiation strategy (PoolingCopyOnWrite) makes wasmtime reserve its whole memory pool with a single mmap of 64 instances × (4 GiB max memory + guard) = 258 GiB of virtual address space. Some Android devices refuse that reservation (mmap ENOMEM, logged as `failed to create memory pool mapping: mmap failed to reserve 0x4082000000 bytes`), after which no runtime can be created and block verification fails permanently; this was reported by a user on a Xiaomi device whose node stalled at block 16,038 while the network was at ~402k (2026-11-14). Applying `patches/wasmtime-pooling-patch.diff` (a one-file diff to `client/executor-wasmtime/src/runtime.rs`) makes every runtime creation recreate instances on demand instead of pooling them (copy-on-write memory images are kept). The patch sits in the executor's config construction, the one place all runtime creation in the process funnels through, because executors are also built with default semantics outside the node service (the chain spec's genesis config builder is one such path; a service-level override alone does not cover it). Verified on the host by running the node with a 16 GiB virtual-address-space limit (`ulimit -v`): unpatched it aborts at startup with the exact reported error; patched it starts fully and creates runtimes with no pool mapping.

```sh
cd chain
patch -p1 < ../patches/wasmtime-pooling-patch.diff   # adjust the path to patches/ to match your layout
```

## 4. Environment variables (the required set)

Save the following as `android-env.sh` and `source` it before building to be safe.
Adjust the NDK path to your own layout.

```sh
export RUSTUP_HOME="$HOME/workspace/toolchain/rustup"   # when rustup is kept in a self-contained location
export CARGO_HOME="$HOME/workspace/toolchain/cargo"
export PATH="$HOME/workspace/toolchain/cargo/bin:$HOME/workspace/toolchain/bin:$PATH"

NDK="$HOME/workspace/toolchain/android-ndk"
LLVM="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

# Linker / C toolchain (verified at API level 35)
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LLVM/bin/aarch64-linux-android35-clang"
export CC_aarch64_linux_android="$LLVM/bin/aarch64-linux-android35-clang"
export CXX_aarch64_linux_android="$LLVM/bin/aarch64-linux-android35-clang++"
export AR_aarch64_linux_android="$LLVM/bin/llvm-ar"

# litep2p's build.rs (prost-build) requires the host protoc
export PROTOC="$HOME/workspace/toolchain/bin/protoc"

# bindgen (librocksdb-sys) requires libclang. Use the one bundled with the NDK.
# LIBCLANG_PATH alone is not enough; LD_LIBRARY_PATH is also needed for loading when build scripts run
export LIBCLANG_PATH="$LLVM/musl/lib"
export LD_LIBRARY_PATH="$LLVM/musl/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# Show bindgen the Android headers (without this, parsing rocksdb/c.h fails with 'stdbool.h' file not found)
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--target=aarch64-linux-android35 --sysroot=$LLVM/sysroot -resource-dir $LLVM/lib/clang/18"
```

## 5. Building

```sh
cd chain
source ../android-env.sh

# For a type check only (no linking)
cargo check -p quantus-node --target aarch64-linux-android

# The real binary (through the final link)
cargo build -p quantus-node --target aarch64-linux-android
# Artifact: target/aarch64-linux-android/debug/quantus-node
```

### Build result (measured 2026-11-03)

- The commands are as above, with parallelism limited to `-j2` only (because the environment has 7 GB RAM and no swap). No debuginfo reduction or profile changes were needed. Memory use just before linking was about 6.1 GB / 7.9 GB.
- Artifact: `target/aarch64-linux-android/debug/quantus-node`, **1,072,804,072 bytes**.
  `file` output: `ELF 64-bit LSB pie executable, ARM aarch64, dynamically linked, interpreter /system/bin/linker64, with debug_info, not stripped`.
  Most of the size is debug information; an `llvm-strip`ped copy was 122,601,984 bytes (about 117 MB).
- NEEDED libraries: `libstdc++.so` / `libc++_shared.so` / `libdl.so` / `libm.so` / `libc.so`.
  **Note: `libc++_shared.so` must be bundled on the app side** (use the NDK's `toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so`). It is not a blocker for linking itself, but is required when running on a real device.

## 6. Known notes

- **The host-side build is heavy.** As a build dependency of the runtime, `wasm-opt-sys` (all of Binaryen's C++) is built on the host. In addition, pallet-wormhole's build.rs runs ZK circuit binary generation (plonky2) on the host in the release profile, which took about 20 minutes per run in the verification environment (4 cores). This runs repeatedly on every build (for the host and for Wasm), so artifact cache design matters in CI.
- **Limit parallelism on machines with little RAM.** The verification environment had 7 GB RAM and no swap, and `cargo build` completed through linking with `-j2` (peak use about 6.1 GB). Adding swap may be rejected by permissions depending on the environment (on this VM, swapon itself was Operation not permitted).
- **The native code for RocksDB and ring is actually compiled with the NDK clang** (generation of `librocksdb.a` / `libsnappy.a` / `libring_core_*.a` confirmed). jemalloc (tikv-jemalloc-sys) is not in the node's default dependency graph, so Android jemalloc problems do not occur in the default build.
- **wasmtime / cranelift passed through at the compile stage**, but runtime memory allocation behavior (pooling allocator etc.) is a separate question from compilation. Real-device execution is unverified (next section).

## 7. Unverified (beyond this point is not proof that it "works")

Linking passed, but the following remain unverified. **Link success is not proof that it works on a real device.**

- Startup on a real device (bionic): bundled loading of `libc++_shared.so`, wasmtime memory allocation, RocksDB mmap and file locks, SELinux / W^X, and background restrictions are unknown until actually run.
- Clean-build time (without `target/`).
- Whether `[profile.production]` (a separate profile including LTO) builds.
- (Appended 2026-11-11) Real-device startup was verified the same day, 2026-11-03: the node started on a Pixel 9a / GrapheneOS, completed a full mainnet sync, and has since shipped in public releases through v3.0 (see docs/DESIGN.md, parts 4–5, and docs/RELEASE.md). The clean-build timing above remains unmeasured, as recorded.

### Release profile verification result (appended 2026-11-03)

`cargo build --release -p quantus-node --target aarch64-linux-android -j2` (android-env.sh, Wasm enabled) **ran to completion on the first attempt as-is**. No profile changes or additional patches were needed, and there was no OOM.

- Time required: about 96 minutes (measured with the debug `target/` cache present)
- Artifact: 77,885,328 bytes unstripped → **53,403,896 bytes** after strip (43.6% of the debug build). NEEDED is identical to the debug build, and the need to bundle `libc++_shared.so` is unchanged
- Note that the above figures are from the first release build before the §3.2 vendor patch was applied. The rebuild with the patch applied shipped at 53,408,792 bytes after strip (through versionCode 9), and **the binary currently bundled is a rebuild reflecting dependency security updates, at 77,892,888 bytes unstripped → 53,411,080 bytes after strip** (versionCode 10)
- Correction of a premise: upstream's `[profile.release]` is opt-level=3 only and **does not include LTO** (LTO is in the separate `[profile.production]` profile). The initial concern that "release overflows link memory with LTO" did not occur with this profile
- Memory reality: MemAvailable during the build dropped to a minimum of about 49 MB but was not exhausted. The largest consumer was not the final link but pallet-wormhole's build script inside the Wasm build (ZK circuit generation, RSS about 4.96 GB)
