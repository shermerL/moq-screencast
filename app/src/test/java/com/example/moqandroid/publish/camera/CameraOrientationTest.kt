package com.example.moqandroid.publish.camera

import com.example.moqandroid.publish.encoder.H264ProfilePreference
import com.example.moqandroid.publish.encoder.VideoEncoderPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun encoderUsesCameraSurfaceDimensionsWithoutApplicationRotation() {
        val config = CameraPublishConfig(
            cameraId = "0",
            width = 1280,
            height = 720,
            frameRate = 30,
            sensorOrientation = 90,
        )
        val encoder = config.encoderConfig(
            encoderPolicy = VideoEncoderPolicy.Default,
            h264ProfilePreference = H264ProfilePreference.High,
        )

        assertEquals(1280, encoder.width)
        assertEquals(720, encoder.height)
    }
}
