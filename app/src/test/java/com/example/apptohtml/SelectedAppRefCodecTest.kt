package com.example.apptohtml

import com.example.apptohtml.model.SelectedAppRef
import com.example.apptohtml.storage.SelectedAppRefCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SelectedAppRefCodecTest {
    @Test
    fun roundtrip_encode_decode_keeps_values() {
        val input = SelectedAppRef(
            packageName = "com.example.sample",
            appName = "Sample | App",
            launcherActivity = "com.example.sample.MainActivity",
            selectedAt = 123456789L,
        )

        val decoded = SelectedAppRefCodec.decode(SelectedAppRefCodec.encode(input))
        assertNotNull(decoded)
        assertEquals(input, decoded)
    }
}
