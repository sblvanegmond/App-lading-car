package nl.adempauze

import nl.adempauze.service.Interceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterceptorTest {
    private val yt = "com.google.android.youtube"

    @Test
    fun gekozenAppZonderPasKrijgtPauze() {
        val i = Interceptor()
        assertTrue(i.onForeground(yt, guardedAndActive = true, passValidUntil = 0, now = 10_000))
    }

    @Test
    fun andereAppKrijgtGeenPauze() {
        val i = Interceptor()
        assertFalse(i.onForeground("com.android.calculator", guardedAndActive = false, passValidUntil = 0, now = 10_000))
    }

    @Test
    fun geldigePasLaatAppDoor() {
        val i = Interceptor()
        assertFalse(i.onForeground(yt, true, passValidUntil = 20_000, now = 10_000))
        assertTrue(i.onForeground(yt, true, passValidUntil = 20_000, now = 20_001))
    }

    @Test
    fun meerdereVenstersKortNaElkaarGevenEenPauze() {
        val i = Interceptor()
        assertTrue(i.onForeground(yt, true, 0, 10_000))
        assertFalse(i.onForeground(yt, true, 0, 10_400))
        assertFalse(i.onForeground(yt, true, 0, 11_200))
        assertTrue(i.onForeground(yt, true, 0, 13_000))
    }

    @Test
    fun naNeeLatenKomtDePauzeNietMeteenTerug() {
        val i = Interceptor()
        assertTrue(i.onForeground(yt, true, 0, 10_000))
        i.onDismissed(yt, now = 12_000)
        assertFalse(i.onForeground(yt, true, 0, 12_500))
        assertTrue(i.onForeground(yt, true, 0, 14_000))
    }

    @Test
    fun voorgrondWordtBijgehouden() {
        val i = Interceptor()
        i.onForeground(yt, true, 99_999, 10_000)
        assertEquals(yt, i.foreground)
        i.onForeground("com.sec.android.app.launcher", false, 0, 11_000)
        assertEquals("com.sec.android.app.launcher", i.foreground)
        i.onOpened(yt)
        assertEquals(yt, i.foreground)
    }
}
