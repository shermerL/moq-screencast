# MoQ ScreenCast

English | [简体中文](README.md)

[![Android CI](https://github.com/shermerL/moq-screencast/actions/workflows/android.yml/badge.svg)](https://github.com/shermerL/moq-screencast/actions/workflows/android.yml)

A native Android sample project for learning and evaluating [Media over QUIC](https://moq.dev/). The app can subscribe to and play a broadcast through a MoQ relay. It can also capture an Android screen, encode it as H.264, and publish it to a specified broadcast.

This project is built on the MoQ implementation and Kotlin/Android bindings provided by [moq-dev/moq](https://github.com/moq-dev/moq). Thanks to Luke Curley and all `moq-dev` contributors for their continued work on the MoQ protocol implementation, native bindings, and open source ecosystem.

> This project is still experimental and is intended for protocol learning and functional evaluation.

## Features

- Configure and persist a MoQ relay URL
- Enter a broadcast name and subscribe to it
- Decode and play video with the Android `MediaCodec` hardware decoder
- Encode the Android screen as H.264 with `MediaCodec` and publish it

## Requirements

- Android Studio
- JDK 17
- Android SDK 33
- Android 10 (API 29) or later
- An accessible MoQ relay (you need to deploy a `moq-relay` service)

## Verified Environment

The following version combination has been verified for publishing and subscribing interoperability across Android, the Web, and the relay server.

### Android Dependencies

The following dependencies are packaged directly into the Android APK:

| Component | Version | Purpose |
| --- | --- | --- |
| `dev.moq:moq` | `0.2.18` | MoQ, Hang, and UniFFI bindings for Android |
| `kotlinx-coroutines-android` | `1.9.0` | Coroutine support for Android |

### Web Interoperability Dependencies

| Component | Version | Purpose |
| --- | --- | --- |
| `@moq/watch` | `0.2.14` | Subscribe to broadcasts on the Web |
| `@moq/publish` | `0.2.10` | Publish broadcasts from the Web |
| `@moq/hang` | `0.2.7` | Hang catalog and media container support for the Web |
| `@moq/net` | `0.1.2` | MoQ networking layer for the Web |

These packages are used for Web interoperability testing. They are not direct dependencies of the Android APK.

### Relay Server

| Item | Verified configuration |
| --- | --- |
| Deployment | Docker |
| Docker image | `kixelated/moq-relay` |
| Protocol version | `moq-lite-03` |

This project currently stays on the verified configuration. If a newer version is confirmed to work correctly, an upgrade will be considered after compatibility testing is complete.

## Build

After cloning the repository, open it with Android Studio and wait for Gradle synchronization. You can also build it from the command line.

macOS or Linux:

```bash
./gradlew :app:assembleDebug
```

Windows:

```bat
gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Launch the app.
2. Enter a relay URL, for example `https://relay.example.com/anon`.
3. Enter a broadcast name, for example `screen.hang`.
4. Select `Subscribe` to subscribe and play, or select `Publish screen` to publish the current device screen.
5. When publishing the screen, grant the notification and screen capture permissions requested by Android.

The relay URL is stored only in the app's own `SharedPreferences`. The repository does not contain a default server address, access token, or certificate fingerprint.

## Project Structure

The underlying MoQ networking functionality is provided by `dev.moq:moq`. This dependency includes the Rust implementation, UniFFI-generated Kotlin interfaces, and Android native dynamic libraries. The application layer does not need to call JNI directly.

## Permissions

- `INTERNET`: Connect to a MoQ relay
- `FOREGROUND_SERVICE`: Keep screen publishing active in a foreground service
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`: Declare the screen capture service type
- `POST_NOTIFICATIONS`: Display the foreground service notification while publishing

## Current Limitations

- Screen publishing supports H.264 video only and does not currently publish audio
- Codec negotiation is not currently supported
- Automated tests and release signing have not been added

## Related Projects

- [moq-dev/moq](https://github.com/moq-dev/moq)
- [MoQ official website](https://moq.dev/)

## Upstream Dependency and Attribution

This project uses the Kotlin/Android bindings provided by `moq-dev/moq`:

- Maven coordinates: `dev.moq:moq:0.2.18`
- Source: [github.com/moq-dev/moq](https://github.com/moq-dev/moq)
- Authors: Luke Curley and other MoQ contributors
- License: [Apache License 2.0](https://github.com/moq-dev/moq/blob/main/LICENSE-APACHE) or [MIT License](https://github.com/moq-dev/moq/blob/main/LICENSE-MIT)

The `dev.moq:moq` package includes the Rust implementation, UniFFI-generated Kotlin interfaces, and Android native dynamic libraries. This project is just an independently developed Android sample.

When redistributing this project's source code or APK, retain the license and copyright notices for both this project and `moq-dev/moq`.
