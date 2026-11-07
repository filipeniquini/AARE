package net.prototux.aare.car

import org.junit.Assert.assertTrue
import org.junit.Test

class DescribeCodecConfigTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun readsProfileAndLevelFromTheSps() {
        val config = bytes(0, 0, 0, 1, 0x67, 66, 0xC0, 31, 0xDA, 0, 0, 0, 1, 0x68, 0xCE)
        val text = CarSession.describeCodecConfig(config, 0, config.size)
        assertTrue(text, "profile 66 constraints c0 level 31" in text)
    }

    @Test
    fun honoursTheOffset() {
        val config = bytes(9, 9, 0, 0, 1, 0x67, 100, 0, 40, 0xDA)
        val text = CarSession.describeCodecConfig(config, 2, config.size - 2)
        assertTrue(text, "profile 100 constraints 00 level 40" in text)
    }

    @Test
    fun saysSoWhenThereIsNoSps() {
        val config = bytes(0, 0, 0, 1, 0x68, 0xCE, 0x38, 0x80)
        val text = CarSession.describeCodecConfig(config, 0, config.size)
        assertTrue(text, "no SPS" in text)
    }
}
