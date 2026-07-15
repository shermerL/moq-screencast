package com.example.moqandroid.catalog

import android.media.AudioFormat
import android.media.MediaFormat
import com.example.moqandroid.media.AvcConfig
import com.example.moqandroid.media.codec.CodecSupport
import com.example.moqandroid.media.parseAvcConfig
import java.nio.ByteBuffer
import uniffi.moq.MoqAudio
import uniffi.moq.MoqAudioDecoderOutput
import uniffi.moq.MoqAudioFormat
import uniffi.moq.MoqCatalog
import uniffi.moq.MoqDimensions
import uniffi.moq.MoqVideo

enum class CodecPreference(
    val label: String,
    val preferredMime: String?,
) {
    Auto("Auto", null),
    H264("H.264", "video/avc"),
    H265("H.265", "video/hevc"),
    VP9("VP9", "video/x-vnd.on2.vp9"),
    AV1("AV1", "video/av01");
}

data class PlayableTrack(
    val name: String,
    val video: MoqVideo,
    val mime: String,
    val avcConfig: AvcConfig?,
    val codecPreference: Int,
)

data class PlayableVideoInfo(
    val broadcastName: String,
    val trackName: String,
    val codec: String,
    val mime: String,
    val preference: CodecPreference,
    val audioTrackName: String?,
    val audioDescription: String?,
    val displayWidth: Int?,
    val displayHeight: Int?,
) {
    fun describe(): String {
        val audio = audioDescription?.let { "\naudio=$it" } ?: "\naudio=none"
        val display = if (displayWidth != null && displayHeight != null) {
            "\ndisplay=${displayWidth}x$displayHeight"
        } else {
            "\ndisplay=unknown"
        }
        return "track=$trackName codec=$codec mime=$mime preferred=${preference.label}$audio$display"
    }
}

fun MoqCatalog.displayWidthFor(video: MoqVideo): Int? {
    return display?.width?.toInt()
        ?: video.coded?.width?.toInt()
        ?: video.displayRatio?.width?.toInt()
}

fun MoqCatalog.displayHeightFor(video: MoqVideo): Int? {
    return display?.height?.toInt()
        ?: video.coded?.height?.toInt()
        ?: video.displayRatio?.height?.toInt()
}

data class PlayableAudioTrack(
    val name: String,
    val audio: MoqAudio,
    val sampleRate: Int,
    val channelCount: Int,
    val channelMask: Int,
) {
    val bytesPerSampleFrame: Int = channelCount * 2
    val bytesPerSecond: Int = sampleRate * channelCount * 2

    fun describe(): String {
        return "$name codec=${audio.codec} ${sampleRate}Hz ${channelCount}ch"
    }
}

fun MoqAudio.toPlayableTrack(name: String): PlayableAudioTrack? {
    if (codec != "opus") return null
    val sampleRate = sampleRate.toInt()
    val channelCount = channelCount.toInt()
    val channelMask = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> return null
    }
    return PlayableAudioTrack(name, this, sampleRate, channelCount, channelMask)
}

fun PlayableAudioTrack.decoderOutput(): MoqAudioDecoderOutput {
    return MoqAudioDecoderOutput(
        format = MoqAudioFormat.S16,
        sampleRate = sampleRate.toUInt(),
        channels = channelCount.toUInt(),
        latencyMaxMs = 250uL,
    )
}

fun MoqVideo.toPlayableTrack(name: String): PlayableTrack? {
    val mime = runCatching { codec.toMimeType() }.getOrNull() ?: return null
    val avcConfig = if (mime == "video/avc") description?.parseAvcConfig() else null
    return if (CodecSupport.hasDecoderFor(mediaFormat(mime, avcConfig))) {
        PlayableTrack(name, this, mime, avcConfig, mime.codecPreference())
    } else {
        null
    }
}

fun PlayableTrack.preferenceRank(preference: CodecPreference): Int {
    val selected = preference.preferredMime
    if (selected != null && mime == selected) return 0

    val base = mime.codecPreference()
    return if (selected == null) base else base + 10
}

fun MoqCatalog.describe(broadcastName: String): String {
    return buildString {
        appendLine("MoQ catalog broadcast=$broadcastName")
        appendLine("display=${display.describe()} rotation=${rotation ?: "none"} flip=${flip ?: "none"}")
        appendLine("video tracks=${video.size}")
        video.entries.forEachIndexed { index, (name, video) ->
            val mime = runCatching { video.codec.toMimeType() }.getOrNull() ?: "unsupported"
            val playable = video.toPlayableTrack(name) != null
            appendLine(
                "  [$index] name=$name codec=${video.codec} mime=$mime playable=$playable " +
                    "coded=${video.coded.describe()} displayRatio=${video.displayRatio.describe()} " +
                    "framerate=${video.framerate ?: "none"} bitrate=${video.bitrate ?: "none"} container=${video.container}",
            )
        }
        appendLine("audio tracks=${audio.size}")
        audio.entries.forEachIndexed { index, (name, audio) ->
            appendLine("  [$index] name=$name codec=${audio.codec} descriptionBytes=${audio.description?.size ?: 0}")
        }
    }.trimEnd()
}

fun MoqVideo.mediaFormat(
    mime: String,
    avcConfig: AvcConfig?,
    adaptiveMaxDimension: Int? = null,
    lowLatency: Boolean = false,
): MediaFormat {
    val width = coded?.width?.toInt() ?: 1280
    val height = coded?.height?.toInt() ?: 720
    val codecDescription = description
    return MediaFormat.createVideoFormat(mime, width, height).apply {
        if (mime == "video/avc" && avcConfig != null) {
            setByteBuffer("csd-0", ByteBuffer.wrap(avcConfig.sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(avcConfig.pps))
        } else if (codecDescription != null) {
            setByteBuffer("csd-0", ByteBuffer.wrap(codecDescription))
        }
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
        adaptiveMaxDimension?.takeIf { it > 0 }?.let { maxDimension ->
            setInteger(MediaFormat.KEY_MAX_WIDTH, maxDimension)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, maxDimension)
        }
        if (lowLatency && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
    }
}

private fun MoqDimensions?.describe(): String {
    return if (this == null) "none" else "${width}x${height}"
}

private fun String.codecPreference(): Int = when (this) {
    "video/avc" -> 0
    "video/hevc" -> 1
    "video/x-vnd.on2.vp9" -> 2
    "video/av01" -> 3
    else -> 100
}

private fun String.toMimeType(): String = when {
    startsWith("avc1") || startsWith("avc3") -> "video/avc"
    startsWith("hvc1") || startsWith("hev1") -> "video/hevc"
    startsWith("vp09") -> "video/x-vnd.on2.vp9"
    startsWith("av01") -> "video/av01"
    else -> error("unsupported video codec: $this")
}
