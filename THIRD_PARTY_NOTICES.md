# Third-party references

## NTE-Auto-Sign

Public account-login protocol compatibility was adapted with reference to [Candy-QAQ/NTE-Auto-Sign](https://github.com/Candy-QAQ/NTE-Auto-Sign/tree/38d77e1f7dc784f2baeb49c41cd4352deadf2b24), commit `38d77e1f7dc784f2baeb49c41cd4352deadf2b24`, MIT license, Copyright (c) 2023 xxyz30. The full upstream license is retained in `android/app/src/main/assets/NOTICE-NTE-Auto-Sign.txt` and packaged with the APK. No third-party device fingerprint, captcha solver or cloud execution code is included from that reference.

## Backdrop (Kyant0/AndroidLiquidGlass) — library and catalog components (v0.7.8)

The standard Android 13+ animated dock directly depends on `io.github.kyant0:backdrop:1.0.0` (Apache-2.0, Copyright 2025 Kyant). Its `LiquidBottomTabs`, `LiquidBottomTab` and gesture/highlight helpers are migrated from the upstream catalog at fixed commit `eada9a8600142c1bd6a5c5fc143d06f599cdfaef` (tag `1.0.0`) under `android/app/src/main/java/com/glasssign/app/ui/upstream/`. Source headers retain attribution and identify app-specific changes. The application adapter supplies theme colors, height, navigation and accessibility semantics. The actual combined page/tab capture, refraction, shadows and spring drag remain upstream implementations. Full Apache-2.0 license is packaged at `assets/licenses/backdrop-apache-2.0.txt`.

The catalog's original continuous-curvature shape dependency, `io.github.kyant0:capsule:2.1.1`, is also directly consumed (Apache-2.0, Kyant). Its full license text is the same Apache-2.0 text packaged above; the capsule attribution is included in NOTICE-backdrop.txt. No replacement capsule geometry is hand-written.

NexioSchedule's AGPL application sources were inspected to identify this upstream pipeline; they are not incorporated. The app remains an Android Compose application and does not import Nexio's business logic or miuix fork.

## Backdrop — historical fallback optics (v0.7.6)

The liquid-glass optics of the floating dock ([LiquidDock.kt](./android/app/src/main/java/com/glasssign/app/ui/LiquidDock.kt)) are a **modified adaptation** of [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass/tree/65ab177e90e5c1d8c62e70cf7755841982da65f6) (`io.github.kyant0:backdrop:2.0.1`), branch `kmp`, pinned commit `65ab177e90e5c1d8c62e70cf7755841982da65f6`, **Apache License 2.0, Copyright 2025 Kyant**.

Adapted from upstream `backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt`: `RoundedRectSDF`, `RoundedRectRefractionShaderString`, `DefaultHighlightShaderString`, `AmbientHighlightShaderString`. Reimplemented over this app's own Android AGSL `RuntimeShader` and its existing recorded-page `GraphicsLayer`. This describes the legacy fallback introduced in v0.7.6, before the direct library and catalog integration above. The exact list of historical changes is in `android/app/src/main/assets/NOTICE-backdrop.txt`; the full Apache-2.0 text is packaged at `android/app/src/main/assets/licenses/backdrop-apache-2.0.txt`. Rationale and license analysis: [docs/glass-research-v076.md](./docs/glass-research-v076.md).

Neither NexioSchedule (AGPL-3.0) nor FlClash (GPL-3.0) application code contributed to this adaptation.

## FlClash

[chen08209/FlClash](https://github.com/chen08209/FlClash/tree/7fd417fb9a9a277c6a9339a8ac87f907df7c25f7), GPL-3.0, was consulted for theme feature organization. Hoshiori uses an independent native Compose implementation; no FlClash Dart source, images or shaders were copied. The mapping and explicit differences are documented in `docs/frontend-flclash-20261102.md`.

## MiyoQian

The public [Marchen-orz/MiyoQian constants](https://github.com/Marchen-orz/MiyoQian/blob/master/miyouqian/constants.py) were consulted for the Honkai Impact 3 account game business identifier and check-in activity routes. Hoshiori's adapter implementation is its own; no captcha solving code was imported. Public service endpoints may change independently of this client.

## Runtime dependencies

AndroidX, Jetpack Compose, WorkManager and lifecycle components (Apache-2.0); Kotlin and coroutines (Apache-2.0); OkHttp / Okio (Apache-2.0). Respective copyrights and licenses remain with their authors. Build dependencies are declared under `android/` and are not vendored here.

This summary is not a complete resolved transitive-dependency license inventory. Test-only dependencies are not evidence that their code is shipped in the APK. Packaging excludes some META-INF license marker files; the release process must verify the notices required by the actual runtime artifacts rather than treating this summary as a compliance certificate.

### Public Suffix List data carried by OkHttp

The APK retains `okhttp3/internal/publicsuffix/NOTICE`. It identifies `publicsuffixes.gz` as compiled from [The Public Suffix List](https://publicsuffix.org/list/public_suffix_list.dat), subject to [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/). This data has a separate license from OkHttp's Apache-2.0 code. Hoshiori does not modify that bundled list. The upstream notice and its source/license links must remain in redistributions; this does not relicense all Hoshiori code under MPL.

## Optional external application: LingQian

The plugin page links to [Starlevin/LingQian-Android](https://github.com/Starlevin/LingQian-Android) and installs its separate APK only after system confirmation. Hoshiori does not include that APK or its source, and does not pass account credentials to it. As checked on 2026-11-05, that public repository and its v0.1.0-alpha tag do not declare their own license in a standard LICENSE/COPYING/NOTICE file. References to other MIT projects are not a license grant for LingQian itself. Its reuse and redistribution permissions remain unverified; no MIT or GPL designation is inferred here.

## Asset provenance and scope

The two raster assets, `hoshiori_mascot_v076.webp` and `hoshiori_starfield_v076.webp`, were produced using the built-in imagegen service and resized/encoded for this app. The prompts and processing notes are in [docs/brand-v076.md](docs/brand-v076.md). The specific generation-model revision was not recorded. The app mark and launcher foreground are project vector artwork; navigation/settings glyphs are cached ImageVector paths in `HoshioriBrand.kt`.

These provenance records do not establish exclusive copyright, trademark clearance, or absence of similarity to third-party works. The root MIT notice does not authorize third-party game logos, characters or service names, nor does it replace provider terms. No new proprietary font is bundled by the current appearance settings; they select system font families.
