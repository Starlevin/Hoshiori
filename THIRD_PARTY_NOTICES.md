# Third-party references

## NTE-Auto-Sign

Public account-login protocol compatibility was adapted with reference to [Candy-QAQ/NTE-Auto-Sign](https://github.com/Candy-QAQ/NTE-Auto-Sign/tree/38d77e1f7dc784f2baeb49c41cd4352deadf2b24), commit `38d77e1f7dc784f2baeb49c41cd4352deadf2b24`, MIT license, Copyright (c) 2023 xxyz30. The full upstream license is retained in `android/app/src/main/assets/NOTICE-NTE-Auto-Sign.txt` and packaged with the APK. No third-party device fingerprint, captcha solver or cloud execution code is included from that reference.

## FlClash

[chen08209/FlClash](https://github.com/chen08209/FlClash/tree/7fd417fb9a9a277c6a9339a8ac87f907df7c25f7), GPL-3.0, was consulted for theme feature organization. Hoshiori uses an independent native Compose implementation; no FlClash Dart source, images or shaders were copied. The mapping and explicit differences are documented in `docs/frontend-flclash-20261102.md`.

## MiyoQian

The public [Marchen-orz/MiyoQian constants](https://github.com/Marchen-orz/MiyoQian/blob/master/miyouqian/constants.py) were consulted for the Honkai Impact 3 account game business identifier and check-in activity routes. Hoshiori's adapter implementation is its own; no captcha solving code was imported. Public service endpoints may change independently of this client.

## Runtime dependencies

AndroidX, Jetpack Compose, WorkManager and lifecycle components (Apache-2.0); Kotlin and coroutines (Apache-2.0); OkHttp / Okio (Apache-2.0). Respective copyrights and licenses remain with their authors. Build dependencies are declared under `android/` and are not vendored here.
