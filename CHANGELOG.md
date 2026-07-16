# 更新日志 / Changelog

本文件记录项目各版本的重要变更。

This file documents notable changes for each project version.

## Unreleased

尚无变更。

No changes yet.

## 0.3.0 (Debug) - 2026-07-16

### 中文

- 新增后置摄像头视频发布和可选麦克风音频
- 支持屏幕共享横竖屏切换

### English

- Add rear-camera video publishing with optional microphone audio
- Support portrait and landscape transitions during screen sharing

## 0.2.2 (Debug) - 2026-07-03

### 中文

- 重构发布端 source/pipeline
- 拆分编码实现和屏幕采集实现

### English

- Refactor the publish source/pipeline
- Split encoder and screen capture implementations

## 0.2.1 (Debug) - 2026-07-01

### 中文

- 修复屏幕发布进入后台一段时间后断开的问题

### English

- Fix screen publishing stopping after the app stays in the background

## 0.2.0 (Debug) - 2026-06-29

### 中文

- 使用 Compose Material 3 重构主界面，发布与订阅入口分离
- 支持发布 Android 系统音频，使用 Opus 进行音频编码

### English

- Rebuild the main UI with Compose Material 3 and separate publish and subscribe entry points
- Support publishing Android system audio with Opus encoding

## 0.1.0 (Debug) - 2026-06-21

### 初始化 / Init

#### 中文

- 支持在 Android 上订阅和播放 MoQ broadcast
- 使用 Android `MediaCodec` 进行视频解码
- 将 Android 屏幕编码为 H.264 并发布

#### English

- Subscribe to and play MoQ broadcasts on Android
- Decode video with Android `MediaCodec`
- Encode and publish the Android screen as H.264
