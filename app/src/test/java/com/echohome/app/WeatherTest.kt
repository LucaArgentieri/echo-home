package com.echohome.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherTest {
    private val sample = javaClass.getResource("/open-meteo-sample.json")!!.readText()

    @Test fun parsesRealOpenMeteoResponse() {
        val f = WeatherParser.parse(sample)
        assertEquals(17, f.nowTemp)
        assertEquals(3, f.nowCode)
        assertEquals(11, f.todayMin)
        assertEquals(21, f.todayMax)
        assertEquals(12, f.tomorrowMin)
        assertEquals(20, f.tomorrowMax)
        assertEquals(3, f.tomorrowCode)
        assertEquals(24, f.tomorrowRainByHour.size)
        assertEquals(3, f.tomorrowRainByHour.max()) // from the sample: at most 3% tomorrow
    }

    @Test fun rainSummaryRanges() {
        val h = IntArray(24)
        assertEquals("Nessuna pioggia prevista.", Replies.rainSummary(h))
        h[10] = 25
        assertEquals("Probabilità di pioggia bassa, al massimo 25 per cento.", Replies.rainSummary(h))
        for (i in 15..18) h[i] = 40 + (i - 15) * 10 // 40,50,60,70
        assertEquals("Pioggia probabile dalle 15 alle 19, fino al 70 per cento.", Replies.rainSummary(h))
        h[7] = 50
        assertEquals("Pioggia probabile alle 7 e dalle 15 alle 19, fino al 70 per cento.", Replies.rainSummary(h))
    }

    @Test fun repliesAndTable() {
        val f = WeatherParser.parse(sample)
        assertEquals("Domani minima 12 gradi, massima 20, coperto. Nessuna pioggia prevista.", Replies.weatherTomorrow(f))
        assertEquals("Adesso ci sono 17 gradi, coperto. Oggi massima 21, minima 11.", Replies.weatherNow(f))
        assertEquals("rovesci", Replies.condition(81))
        val rows = Replies.rainTable(f.tomorrowRainByHour).lines()
        assertEquals(4, rows.size)
        assertEquals("00:", rows[0].take(3))
    }
}
