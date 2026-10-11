package com.echohome.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RepliesTest {
    @Test fun time() {
        assertEquals("Sono le 14 e 42.", Replies.time(14, 42))
        assertEquals("Sono le 9 e 5.", Replies.time(9, 5))
        assertEquals("Sono le 21 in punto.", Replies.time(21, 0))
        assertEquals("È l'una e 10.", Replies.time(1, 10))
        assertEquals("È l'una.", Replies.time(1, 0))
        assertEquals("È mezzanotte.", Replies.time(0, 0))
        assertEquals("È mezzanotte e 15.", Replies.time(0, 15))
        assertEquals("È mezzogiorno.", Replies.time(12, 0))
        assertEquals("È mezzogiorno e 30.", Replies.time(12, 30))
    }

    @Test fun volume() {
        assertEquals("Volume al 40 per cento.", Replies.volume(40))
        assertEquals("Volume al massimo.", Replies.volume(100))
        assertEquals("Volume azzerato.", Replies.volume(0))
    }

    @Test fun timers() {
        assertEquals("Timer impostato per 10 minuti.", Replies.timerSet(600))
        assertEquals("Timer impostato per 1 ora e 30 minuti.", Replies.timerSet(5400))
        assertEquals("Timer impostato per 1 minuto e 30 secondi.", Replies.timerSet(90))
        assertEquals("Timer impostato per 45 secondi.", Replies.timerSet(45))
    }
}
