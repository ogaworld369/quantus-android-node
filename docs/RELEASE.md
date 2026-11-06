# Steps to Public Release (Release Preparation Checklist)

Created: 2026-11-03. By the owner's decision, a public release (APK distribution via GitHub Release) is performed only after passing all of the following gates. Nothing is published while any item is unpassed.

Current status: The latest published release is v0.2.1 (versionCode 27), published as a GitHub Release on 2026-11-06. v0.2.0 (versionCode 26, the UI redesign release) was published on 2026-11-06 16:48 (the tag target is main commit 0459e9e7). v0.1.5 (versionCode 25) was published on 2026-11-06, v0.1.4 on 2026-11-05 23:59, v0.1.3 the same day at 23:29, v0.1.2 the same day at 22:29, v0.1.1 the same day at 13:53, and v0.1.0 on 2026-11-04 22:50. The records below in this document concern the published releases. v0.2.1 is a maintenance release addressing the two verified findings of an external audit of v0.2.0 (one medium, one low): spawned node processes are now also tracked in a process-wide registry, so a child whose on-disk pid record could not be written is still found and waited on by a service destroyed and recreated within the same app process (the cross-process recovery via the pid record, the spawn-intent marker and the orphan scan is unchanged), and the display name passes through a single normalization shared by the editor, the stored value and the launch, so the confirmed name and the launched name can no longer differ. There are no changes to the node launch arguments, the DB, permissions, or the bundled node binary (the vc10 build). The fixes are verified on the JVM harness (4,550 checks passed, 0 failed); on-device reproduction of the pid-record-failure sequence has not been performed. The SHA-256 of the APK (quantus-node-v0.2.1.apk, 55,733,030 bytes) is `9f1ce2da422a11d2355572ffa113e18bf02bb18a6b8f743d1c28bea96e6db46c`. v0.2.0 is the UI redesign release that restructured the single scrolling page into four screens (Home / Node details / Track record / Settings), including failure classification display with a reason and retry path, dialog editing of the display name with validation (if it exceeds 64 code points after sanitization, it is refused rather than truncated), per-item display of unapplied settings and a check link on the home screen, per-language plural forms, and the color change to the official Quantus colors (quantus.com's void #0E0E0E, surface #181818, flare #FF6B35, content #E8E6E0; Synced uses the brand green #4ADE80). Along with this, 242 non-English UI strings that had been mangled into "?" in the shipped build were restored verbatim from the table committed at vc24 (with a regression check added), and the Quantum Watch placeholder display — which had shown only a "not available yet" note — was removed. There are no changes to the node launch arguments, the DB, permissions, or the bundled node binary (the vc10 build). The pre-release full-section audit found zero critical, high, or medium findings (7 low, all addressed). An in-place update on a real device and the preservation of settings and sync data were confirmed. The SHA-256 of the APK (quantus-node-v0.2.0.apk, 55,733,030 bytes) is `ec13212cf4e4a6e9597b7779591966e094eff30fc12454030a08535442e218ee`, verified against the asset digest on the release page. v0.1.5 addressed all 11 verified findings (5 medium, 6 low) from the external audits of v0.1.4 (3 rounds) and the 8 remaining items (all low, none deferred) found in the re-audit of the fixed version; an in-place update on a real device and data preservation (user data of 1.21GB maintained) were confirmed. The SHA-256 of the APK (quantus-node-v0.1.5.apk, 55,700,262 bytes) is `22ab1fe1faa9e07598a3b13f24dd7a299c8bef403af655239a7fe25029e9ec2e`, verified against the asset digest on the release page. v0.1.4 addressed all 5 findings of the vc23 full-section audit (3 new low and 2 carried over, none deferred); an in-place update on a real device, return to the tip, stop → immediate start, and the footer's v0.1.4 display were confirmed. The SHA-256 of the APK (quantus-node-v0.1.4.apk, 55,700,181 bytes) is `f662112bc213b0ae5f9be724b5421badf39730652381ca0ae3dc22e8b1ec74b2`, verified against the asset digest on the release page. v0.1.3 addressed the 3 medium findings and the harmful low findings of the vc22 full-section audit (3 medium, 17 low); an in-place update on a real device, return to the tip, stop → immediate start, and the footer's v0.1.3 display were confirmed. The SHA-256 of the APK (quantus-node-v0.1.3.apk, 55,700,181 bytes) is `dff1df99e5fa75f253477dcae1e46c04a2cf2672c4411c88b8207cdf639680e0`, verified against the asset digest on the release page. v0.1.2 addressed all 11 findings of the external audit of v0.1.1; an in-place update on a real device, return to the tip, and stop → immediate start right after starting (the fixed location of the startup-cancellation race that was the audit's central finding) were confirmed (the APK's SHA-256 is `7c196901d0160a6035bf931eb8bc69fbbf13e4ea17c9510e2891be7ac0548b7f`). v0.1.1 fixed the low-severity findings of the full vc20 audit; an in-place update on a real device and return to the tip were confirmed. A real-device full sync on the release build (2026-11-05, #349,518) and the telemetry listing were also confirmed. The first DB-size measurement was 1.30GB in total at sync completion. What remains are Phase 4-side items such as Gate 1's quantitative evaluation (the DB-size ceiling, battery, heat) and Gate 4's license bundling.

## Gate 1: Real-device verification (Pixel 9a / GrapheneOS)

- [x] The MVP APK installs and the node starts (confirmed 2026-11-03 with versionCode 4)
- [x] The block height actually increases and syncing progresses (confirmed the same day, running to completion from genesis to the tip #341592)
- [ ] It does not crash over several hours of continuous operation (Foreground Service residency is maintained)
- [ ] Confirm the tendency for the pruned DB size to level off
- [ ] Whether battery consumption and heat are in a range compatible with daily use
- [ ] After an app restart or device reboot, it recovers normally with the same pruning settings (does not become a mode-mismatch error)
- [x] The node stops normally with the stop operation (confirmed 2026-11-03, stop → start performed. Repetition and after device reboot remain under continued confirmation)

Judgment: this gate is the foundation of everything. If even one item fails, fix the cause before moving on.

## Gate 2: Release build

- [x] The Android release build of `cargo build --release` runs to completion (confirmed. fat LTO was not adopted due to insufficient memory on the build machine; thin LTO was not adopted after the experiment showed it did not contribute to size reduction — see the design document's "part 5")
- [x] Rebuild the APK with the release binary and confirm the size and bundled contents (about 53.4MB after strip; performed on the test distribution builds versionCode 4–10 (vc10 is the rebuild reflecting the dependency security updates, 53,411,080 bytes after strip))
- [x] Settle the version number (the first public release is v0.1.0. Settled on 2026-11-04 as versionCode 19 / versionName 0.1.0)
- [ ] [At every version bump] Align build-manual.sh (aapt2's --version-code / --version-name) and build.gradle's versionCode / versionName to the new version, and regenerate docs/I18N.md's "All strings" table from StringsTable to match. Confirmation is mechanical: (1) the in-app version display (at the bottom of Settings; generated from the build's versionName, and no version number is embedded in the string table. The FOOTER_INFO key itself was abolished in vc26), (2) the regenerated table and all cells of docs/I18N.md (151 keys × 9 languages = 1359 cells) agree (zero diff). A grep for the old versionName is only an aid — a grep for "old versions" in principle cannot catch leftovers of versions that are not the immediately preceding one (two versions back, etc.) (recurrence prevention for DOC-1, where in vc22 / v0.1.2 the footer shipped still showing v0.1.1, and C23-1, where in vc23 / v0.1.3 the footer row of I18N.md remained at v0.1.1 — full-section audit)
- [ ] [At every version bump] Search all tracked text files for the old versionName and old versionCode, and classify each hit one by one. Historical records (the CHANGELOG, past release records, audit records) may remain, but statements in the present tense must point to the new version. Examples of targets: the status line at the top of all 9 USAGE files, SECURITY.md's "current release", the spec summary at the top of app/README.md (versionName / versionCode). The footer wording (FOOTER_INFO) and the docs/I18N.md generated table are mechanically confirmed in the item above, so they are not duplicated here (recurrence prevention for the fact that, at the post-release full check of vc24 / v0.1.4, descriptions stating v0.1.3 as current remained in all 9 USAGE files, SECURITY.md, and app/README.md)

## Gate 3: Signing

- [x] Generate a release-dedicated release key (do not use the debug key) (performed 2026-11-04. Details in "Where the signing key lives and the official signed-build procedure" below)
- [x] The owner keeps a backup of the key safely. **If this key is lost, updated versions can never be distributed as the same app again** (a backup memo and a copy for the owner's keeping have been prepared. Transcribing and storing the password is the owner's own task)
- [x] Never put the keystore in the repository or on GitHub (stored outside the repository in `~/workspace/release-signing/`, and confirmed free of contamination via git status and grep)
- [x] Verify the APK signed with the release key and include the SHA-256 checksum with the Release (the SHA-256 of the versionCode 19 APK prepared initially was `b01269297b8eb5d8a64a7b9629a5a9ebf782a56f2826760bf1eebcbbc884351d`. What was actually published was versionCode 20, which included a scroll fix, and its SHA-256 is `ef1ef477d6102333f7a5c2deaee9e512eee7a98b6282cd0c167dbd4d1d003583`. Confirmed by apksigner verify and certificate fingerprint matching, and included in the Release body as well)

## Gate 4: Licensing and wording

Confirmed facts: the root LICENSE of the upstream chain repository states MIT (Copyright 2025 Quantus Network). On the other hand, the node's crate metadata declares Apache-2.0, and the wording wavers. Each dependency crate has its own license.

- [ ] Bundle the upstream LICENSE in the APK / repository and state the source
- [ ] Prepare a list of third-party licenses (for the dependency crates)
- [x] Make the final decision on the app's own license (settled by the owner as Apache-2.0 on 2026-11-04. A LICENSE file is in place)
- [ ] Write the terms of use in the README: the supported environment (Android 12 or later, arm64), a guide for the required free space, and cautions about the time and traffic volume the initial sync takes (Wi-Fi recommended)
- [ ] State the known limitations (e.g., that on a pruned node, queries of old history are not possible)
- [x] Organize SECURITY.md's wording to match the current state (revised to pre-publication wording on 2026-11-04)

## Gate 5: Closing the audit's remaining items

- [ ] Real-connection DB-size measurement (doubles as Gate 1)
- [x] Whether fast sync actually works → as a result of the close code reading, settled as not viable due to 2 mechanism-level barriers (2026-11-03, see the design document's "part 5"). Will not be pursued
- [ ] Reconfirm that among the unverified items of the 2026-11-03 audit, none is a blocker for publication

## Gate 6: Publication work

- [x] Create a GitHub Release (tag v0.1.0, with the APK and checksum attached) (published 2026-11-04 22:50. https://github.com/ogaworld369/quantus-android-node/releases/tag/v0.1.0. Created by the assistant via the browser, at the owner's request)
- [x] Prepare release notes: summary, requirements, known limitations, how to give feedback (guidance to the Issue templates) (the completed text for v0.1.0 was prepared and published in the body at release time)
- [ ] Prepare a draft of the community announcement text (without excessive promotion, honestly stating that it is a test build) (a standing item performed at each version, not an item to be deleted when completed: for v0.1.1 through v0.1.4, drafts were prepared and presented to the owner each time. Whether to post is the owner's judgment)
- [ ] [Standing wording rule for public material] In GitHub-facing public material (Release bodies, the README's release records, new entries in CHANGELOG and SECURITY.md, etc.), when writing about audits, do not name AI products or write "AI audit"; write only "audit" / "external audit" (in English: audit / external review). A rule from the owner's instruction on 2026-11-06, applied from new entries onward; for the v0.1.5 records it was applied retroactively under the owner's additional instruction (like the announcement item, this is not an item to be deleted when completed)
- [ ] [Standing language rule for public material] All GitHub-facing text (release notes, documentation, commit messages) is written in English. (A rule from the owner's instruction on 2026-11-06. Like the announcement item, this is a standing item, not one to be deleted when completed)
- [x] Publish v0.1.2 (versionCode 22) (published 2026-11-05 22:29, Latest. https://github.com/ogaworld369/quantus-android-node/releases/tag/v0.1.2. The release addressing all 11 findings of the external audit; release notes were also prepared and published in the body at release time. The APK's SHA-256 is `7c196901d0160a6035bf931eb8bc69fbbf13e4ea17c9510e2891be7ac0548b7f` (55,700,181 bytes), confirmed to match the asset digest on the release page. On a real device, in-place update over v0.1.1 → return to the tip and stop → immediate start right after starting were confirmed)
- [x] Publish v0.1.3 (versionCode 23) (published 2026-11-05 23:29, Latest. https://github.com/ogaworld369/quantus-android-node/releases/tag/v0.1.3. The release addressing the 3 medium findings and the harmful low findings of the vc22 full-section audit; release notes were also prepared and published in the body at release time. The APK's SHA-256 is `dff1df99e5fa75f253477dcae1e46c04a2cf2672c4411c88b8207cdf639680e0` (55,700,181 bytes), confirmed to match the asset digest on the release page. On a real device, in-place update over v0.1.2 → return to the tip, stop → immediate start, and the footer's v0.1.3 display were confirmed)
- [x] Publish v0.1.4 (versionCode 24) (published 2026-11-05 23:59, Latest. https://github.com/ogaworld369/quantus-android-node/releases/tag/v0.1.4. The tag target is main commit 67e39b4c. The release addressing all 5 findings of the vc23 full-section audit (3 new low + 2 carried over), with none deferred; release notes were also prepared and published in the body at release time. The APK's SHA-256 is `f662112bc213b0ae5f9be724b5421badf39730652381ca0ae3dc22e8b1ec74b2` (55,700,181 bytes), confirmed to match the asset digest on the release page. On a real device, in-place update over v0.1.3 → return to the tip, stop → immediate start, and the footer's v0.1.4 display were confirmed)
- [x] Publish v0.1.5 (versionCode 25) (published 2026-11-06, Latest. https://github.com/ogaworld369/quantus-android-node/releases/tag/v0.1.5. The tag target is main commit c27959ec. The release addressing all 11 verified findings (5 medium, 6 low) of the external audits of v0.1.4 (3 rounds) and the 8 remaining items (all low) found in the re-audit of the fixed version, with none deferred; release notes were also prepared and published in the body at release time. The pre-release full GitHub check required 0 fixes. The APK's SHA-256 is `22ab1fe1faa9e07598a3b13f24dd7a299c8bef403af655239a7fe25029e9ec2e` (55,700,262 bytes), confirmed to match the asset digest on the release page. On a real device, an in-place install over v0.1.4 and data preservation (user data 1.21GB) were confirmed)
- [x] Publish v0.2.0 (versionCode 26) (published 2026-11-06 16:48, Latest. https://github.com/ogaworld369/quantus-android-node/releases/tag/v0.2.0. The tag target is main commit 0459e9e7. The UI redesign release restructuring the single scrolling page into four screens (Home / Node details / Track record / Settings), including failure classification display, dialog editing of the display name with validation, per-item display of unapplied settings, per-language plural forms, the color change to the official Quantus colors, and the restoration of 242 non-English UI strings that had been mangled into "?" in the shipped build (restored verbatim from the table committed at vc24, with a regression check added), and the removal of the Quantum Watch placeholder display. No changes to the node launch arguments, the DB, permissions, or the bundled node binary (the vc10 build). The pre-release full-section audit found zero critical, high, or medium findings (7 low, all addressed). Release notes were also prepared and published in the body at release time. The APK's SHA-256 is `ec13212cf4e4a6e9597b7779591966e094eff30fc12454030a08535442e218ee` (55,733,030 bytes), confirmed to match the asset digest on the release page. On a real device, an in-place install over v0.1.5 and the preservation of settings and sync data were confirmed)

## Where the signing key lives and the official signed-build procedure

(Added 2026-11-04, when Gate 3 was performed)

### Where the key lives (outside the repository)

- Keystore: `~/workspace/release-signing/quantus-release.jks` (permissions 600)
- Password file: `~/workspace/release-signing/keystore-password.txt` (permissions 600)
  - The password's actual value is written nowhere in the repository, including this procedure document
- Alias: `quantus-release`
- Certificate SHA-256 fingerprint (public information):
  `65:BB:1A:5F:EF:27:D7:0B:E1:DD:12:FC:12:CE:34:84:B4:C8:48:FC:EC:A9:2F:88:98:CD:96:CB:27:84:F6:FF`
- A copy of the keystore and a backup memo have been handed over separately for the owner's keeping
  (the goal-side file area. Not the repository)

### The official signed build

```sh
cd app
QUANTUS_SIGNING=release bash build-manual.sh
# Artifact: app/build/outputs/apk/release/quantus-node-release.apk
```

- Without the environment variable, it is a debug signature (for development), as before
- At signing time, the script reads the value from the password file into an environment variable,
  and passes it to apksigner via `env:`. The password never appears in the script, the
  command line, or the build log
- The location can be overridden with `QUANTUS_RELEASE_KS` /
  `QUANTUS_RELEASE_KS_PASS_FILE` / `QUANTUS_RELEASE_ALIAS`
- Checks: that the certificate SHA-256 from `apksigner verify --print-certs` matches the
  fingerprint above, and that versionCode / versionName and the permissions (4) are confirmed
  with `aapt2 dump badging`

## After publication (separated as Phase 4)

- The F-Droid application, verification on other Android devices, CI-ization of release builds and reproducible builds
