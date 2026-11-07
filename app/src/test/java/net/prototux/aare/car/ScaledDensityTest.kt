package net.prototux.aare.car

import net.prototux.aare.car.CarSession.Companion.scaledDensity
import org.junit.Assert.assertEquals
import org.junit.Test

class ScaledDensityTest {
    @Test fun `100 percent keeps what the head unit asks for`() {
        assertEquals(160, scaledDensity(160, 100))
    }

    @Test fun `a smaller interface means a lower density`() {
        assertEquals(144, scaledDensity(160, 90))
        assertEquals(128, scaledDensity(160, 80))
        assertEquals(112, scaledDensity(160, 70))
    }

    @Test fun `out of range values stay within 70 to 100 percent`() {
        assertEquals(112, scaledDensity(160, 10))
        assertEquals(160, scaledDensity(160, 500))
    }
}
