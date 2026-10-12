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

    @Test fun weekReplies() {
        val days = listOf(
            DayForecast("2026-10-12", 11, 21, 3, 0), // Monday = today
            DayForecast("2026-10-13", 12, 20, 3, 0),
            DayForecast("2026-10-14", 10, 18, 61, 80),
            DayForecast("2026-10-15", 9, 19, 0, 0),
        )
        val f = Forecast(17, 3, 11, 21, 0, 12, 20, 3, IntArray(24), days)
        assertEquals("mercoledì", Replies.weekday("2026-10-14"))
        assertEquals(
            "Domani coperto, tra 12 e 20 gradi. Mercoledì pioggia, tra 10 e 18 gradi. Giovedì sereno, tra 9 e 19 gradi.",
            Replies.weatherWeek(f),
        )
        assertEquals(listOf("Domani  12° / 20°  coperto", "Mer     10° / 18°  pioggia", "Gio      9° / 19°  sereno"), Replies.weatherWeekRows(f))
        assertEquals(2, WeatherParser.parse(sample).days.size)
    }

    @Test fun citiesAreFoundIgnoringCaseAccentsAndApostrophes() {
        assertEquals("Roma", Cities.find("roma")?.name)
        assertEquals("L'Aquila", Cities.find("L'Aquila")?.name)
        assertEquals("L'Aquila", Cities.find("laquila")?.name)
        assertEquals("Reggio Emilia", Cities.find("reggio  emilia")?.name)
        assertEquals(null, Cities.find("Pizzighettone"))
    }

    @Test fun iconsAndCityPhrases() {
        assertEquals("☀️", Replies.icon(0))
        assertEquals("🌙", Replies.icon(0, night = true))
        assertEquals("⛈️", Replies.icon(95))
        assertEquals("A Roma", Replies.inCity("Roma"))
        assertEquals("Ad Ancona", Replies.inCity("Ancona"))
    }

    @Test fun requestUrl() {
        val url = WeatherClient.url(41.903, 12.496)
        assertEquals(true, url.contains("latitude=41.903&longitude=12.496&"))
        assertEquals(true, url.contains("timezone=Europe%2FRome"))
    }
}
