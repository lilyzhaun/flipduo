# FlipDuo

[简体中文](README.zh-CN.md)

FlipDuo is an LSPosed module and companion settings app for the cover screens of Xiaomi MIX Flip devices.

This repository contains the production source and build files for **0.6.143 (154)**. The app's existing interface text is preserved in its original language.

## Features

- Configurable cover-screen navigation controls, status indicators, and notification/activity displays.
- Black or live blurred backgrounds beside supported apps.
- Per-app fullscreen options and cover-screen recent-task customization.
- Optional full-width notifications and framed cover-screen screenshots.
- Named local appearance presets and per-user selection of an enabled cover-screen keyboard.
- Selectable quick restart for Screenshot, Sogou Input Method for Xiaomi, FlipHome, and System UI, using root access only after confirmation.
- Repository access and a choice of Lanzou Cloud or the latest GitHub Release from the About page.

Features depend on the device's firmware and the enabled module scopes. Fullscreen content can extend behind the physical cameras.

## Compatibility

The compatibility targets are MIX Flip (`ruyi`) on HyperOS 3 and MIX Flip 2 (`bixi`) on HyperOS 3 or 4. Firmware-specific behavior requires verification on the corresponding device; these targets are not a guarantee that every feature works on every firmware.

This version has user acceptance on MIX Flip 2 / HyperOS 4. First-generation HyperOS 3 support is based on ROM interface checks, and MIX Flip 2 / HyperOS 3 still requires independent firmware and device verification. Live blur remains experimental and can vary by video content and firmware.

Framed screenshots support the verified Screenshot 1.6.3.44 pipeline while retaining the older modern and legacy paths. Unknown future Screenshot builds may require additional compatibility work. Quick restart sends process termination requests; the system restarts hosts as needed, and a sent request does not by itself confirm that the module has loaded.

WeType-specific adaptation is not included. Existing saved WeType choices are preserved but delegated to the system or other modules after the updated framework policy loads. Updating the APK alone does not replace hooks already loaded in host processes or revoke existing framework scopes.

Running the module requires a compatible rooted device and an LSPosed implementation supporting libxposed API 101 or newer. The companion app uses libxposed service API 102, with capability checks for features that require newer framework support.

## Build

Requirements:

- JDK 17 or a later version supported by the bundled Gradle version.
- Android SDK Platform 37 (Android 17), Android SDK Command-line Tools, and the build tools required by the Android Gradle plugin.
- Access to Google Maven, Maven Central, and the Gradle distribution server for the first build.

Set `ANDROID_HOME` to your Android SDK directory, or create an untracked `local.properties` containing `sdk.dir=<your-sdk-directory>`.

The Gradle wrapper pins Gradle 9.6.1 and verifies its distribution checksum. Dependency versions are defined in the Gradle build files.

`lint.xml` narrowly exempts intentional non-SDK reflection in the listed LSPosed host integration classes from `BlockedPrivateApi`; other lint checks remain enabled.

```sh
./gradlew :app:assembleRelease --no-daemon --max-workers=1
```

Output:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

The release APK is unsigned. Sign it locally with your own key before installation. Existing installations signed with a different key cannot be updated in place with your build.

For a locally debug-signed development APK:

```sh
./gradlew :app:assembleDebug --no-daemon --max-workers=1
```

The public source distribution does not include private signing material, test suites, device dumps, or development logs. They are not required to build the application.

## Source layout

| Path | Contents |
| --- | --- |
| `app/src/main/` | Companion app, Android resources, manifest, and bundled license notices |
| `native-hook/src/` | Java module implementation and shared runtime code |
| `native-hook/resources/` | Module entry points and scope declarations |
| `gradle/wrapper/` | Official Gradle wrapper and pinned distribution configuration |
| `licenses/` | Additional license texts |

## Source releases

Use the source tag matching the APK version you received. The tag for this version is [`v0.6.143`](https://github.com/lilyzhaun/flipduo/tree/v0.6.143). The signed APK is available from the [matching release](https://github.com/lilyzhaun/flipduo/releases/tag/v0.6.143); earlier source tags remain available.

The public build configuration omits private instrumentation-test dependencies and local SDK paths. It contains the production sources needed to build this version, rather than a byte-for-byte reproduction of a publisher-signed APK.

## License and attribution

FlipDuo contains GPL-3.0-only code and AGPL-3.0-only adaptations from [parallelcc/MixFlipMod](https://github.com/parallelcc/MixFlipMod) and [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler). The GPL and AGPL portions retain their respective licenses; their combination is permitted under section 13 of each license. See [LICENSE](LICENSE), [AGPL-3.0-only](licenses/AGPL-3.0-only.txt), and [NOTICE.md](NOTICE.md) for details and upstream attribution.

Redistribution of covered binaries must satisfy the applicable corresponding-source and notice requirements. This includes the modified application, not only links to its upstream projects.
