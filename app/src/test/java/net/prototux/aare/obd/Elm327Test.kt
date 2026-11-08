package net.prototux.aare.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327Test {
    @Test
    fun formulas() {
        assertEquals(2_000f, Elm327.value(Elm327.RPM, intArrayOf(0x1F, 0x40))!!, 0.01f)
        assertEquals(92f, Elm327.value(Elm327.COOLANT, intArrayOf(132))!!, 0.01f)
        assertEquals(14.1f, Elm327.value(Elm327.BATTERY, intArrayOf(0x37, 0x14))!!, 0.01f)
        assertEquals(100f, Elm327.value(Elm327.FUEL, intArrayOf(255))!!, 0.01f)
        assertNull("a cut rpm answer is no number", Elm327.value(Elm327.RPM, intArrayOf(0x1F)))
    }

    @Test
    fun answersWithNoiseAndTwoModules() {
        val answer = "SEARCHING...\r410C1F40\r410C1F80\r"
        assertEquals(2, Elm327.allAnswers(answer, Elm327.RPM).size)
        assertEquals(0x1F40, Elm327.bytes(answer, Elm327.RPM)!!.let { it[0] * 256 + it[1] })
        assertNull(Elm327.bytes("NODATA", Elm327.RPM))
    }

    @Test
    fun supportedBitmap() {
        // 0x0C and 0x0D: bits 12 and 13 of the first block.
        val pids = Elm327.supported(mapOf(0x00 to "4100" + "%08X".format((1L shl (32 - 0x0C)) or (1L shl (32 - 0x0D)))))
        assertEquals(setOf(0x0C, 0x0D), pids)
    }

    @Test
    fun thePretendCarDrives() {
        var now = 0L
        val link = FakeObdLink(clock = { now }, delayMs = 0)
        val supported = Elm327.prepare(link)
        assertTrue(Elm327.READ.all { it in supported })
        val poller = ObdPoller(link, supported, Trip())
        now = 18_000 // cruising at 100 km/h
        val r = poller.readRound(now)
        assertEquals(100f, r.speed!!, 0.5f)
        assertEquals(2_300f, r.rpm!!, 1f)
        assertEquals(92f, r.coolant!!, 0.5f)
        assertEquals(62f, r.fuel!!, 0.5f)
        assertNotNull(r.economy)
        assertNotNull(r.boost)
    }

    @Test
    fun rangeFromTheTripOrTheLastOne() {
        val trip = Trip(savedAverage = 10f)
        assertEquals("the last drive's average until this one counts", 260f, trip.rangeKm(50f)!!, 0.1f)
        // 30 minutes at 60 km/h and 12 km/l: 30 km on 2.5 l.
        var t = 1L
        trip.add(ObdReadings(speed = 60f, economy = 12f, rpm = 2000f), t)
        repeat(360) { t += 5_000; trip.add(ObdReadings(speed = 60f, economy = 12f, rpm = 2000f), t) }
        assertEquals(12f, trip.average()!!, 0.01f)
        assertEquals(312f, trip.rangeKm(50f)!!, 0.5f)
        assertNull(Trip().rangeKm(50f))
    }
}
