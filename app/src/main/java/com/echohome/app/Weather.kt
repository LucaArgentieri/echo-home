package com.echohome.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One day of the daily forecast; [date] is "2026-10-12". */
class DayForecast(val date: String, val min: Int, val max: Int, val code: Int, val rainMax: Int)

/** What the voice answers need from one Open-Meteo call. Temperatures are rounded to whole degrees. */
class Forecast(
    val nowTemp: Int,
    val nowCode: Int,
    val todayMin: Int,
    val todayMax: Int,
    val todayRainMax: Int,
    val tomorrowMin: Int,
    val tomorrowMax: Int,
    val tomorrowCode: Int,
    /** Rain probability (%) for each hour 0..23 of tomorrow. */
    val tomorrowRainByHour: IntArray,
    /** Today first, then the following days. */
    val days: List<DayForecast> = emptyList(),
)

object WeatherParser {
    fun parse(json: String): Forecast {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val hourly = root.getJSONObject("hourly")

        val tomorrowDate = daily.getJSONArray("time").getString(1)
        val times = hourly.getJSONArray("time")
        val rain = hourly.getJSONArray("precipitation_probability")
        val byHour = IntArray(24)
        for (i in 0 until times.length()) {
            val t = times.getString(i) // "2026-10-12T15:00"
            if (t.startsWith(tomorrowDate)) byHour[t.substring(11, 13).toInt()] = rain.optInt(i, 0)
        }
        fun day(name: String, index: Int) = Math.round(daily.getJSONArray(name).getDouble(index)).toInt()
        return Forecast(
            nowTemp = Math.round(current.getDouble("temperature_2m")).toInt(),
            nowCode = current.getInt("weather_code"),
            todayMin = day("temperature_2m_min", 0),
            todayMax = day("temperature_2m_max", 0),
            todayRainMax = daily.getJSONArray("precipitation_probability_max").optInt(0, 0),
            tomorrowMin = day("temperature_2m_min", 1),
            tomorrowMax = day("temperature_2m_max", 1),
            tomorrowCode = daily.getJSONArray("weather_code").getInt(1),
            tomorrowRainByHour = byHour,
            days = (0 until daily.getJSONArray("time").length()).map { i ->
                DayForecast(
                    date = daily.getJSONArray("time").getString(i),
                    min = day("temperature_2m_min", i),
                    max = day("temperature_2m_max", i),
                    code = daily.getJSONArray("weather_code").getInt(i),
                    rainMax = daily.getJSONArray("precipitation_probability_max").optInt(i, 0),
                )
            },
        )
    }
}

/** A place that can be asked by voice ("che tempo fa a Roma"). Coordinates are fixed here, never looked up online. */
class City(val name: String, val lat: Double, val lon: Double, vararg val aliases: String)

object Cities {
    private val all = listOf(
        City("Milano", 45.464, 9.190),
        City("Roma", 41.903, 12.496),
        City("Torino", 45.070, 7.687),
        City("Genova", 44.406, 8.934),
        City("Bologna", 44.494, 11.343),
        City("Firenze", 43.770, 11.256),
        City("Napoli", 40.852, 14.268),
        City("Venezia", 45.441, 12.316),
        City("Verona", 45.438, 10.992),
        City("Padova", 45.407, 11.877),
        City("Trieste", 45.650, 13.777),
        City("Bari", 41.118, 16.872),
        City("Palermo", 38.116, 13.361),
        City("Catania", 37.502, 15.087),
        City("Cagliari", 39.223, 9.122),
        City("Perugia", 43.112, 12.389),
        City("Ancona", 43.617, 13.516),
        City("Pescara", 42.465, 14.214),
        City("L'Aquila", 42.350, 13.399, "laquila", "aquila"),
        City("Bergamo", 45.698, 9.677),
        City("Brescia", 45.541, 10.212),
        City("Monza", 45.584, 9.274),
        City("Pavia", 45.185, 9.155),
        City("Lodi", 45.314, 9.503),
        City("Como", 45.808, 9.085),
        City("Parma", 44.801, 10.328),
        City("Modena", 44.647, 10.926),
        City("Reggio Emilia", 44.699, 10.631),
        City("Ravenna", 44.418, 12.203),
        City("Rimini", 44.060, 12.568),
        City("Trento", 46.067, 11.121),
        City("Bolzano", 46.498, 11.355),
        City("Aosta", 45.737, 7.320),
        City("Udine", 46.063, 13.235),
        City("Pisa", 43.716, 10.401),
        City("Livorno", 43.552, 10.309),
        City("Siena", 43.318, 11.331),
        City("Lecce", 40.352, 18.174),
        City("Reggio Calabria", 38.110, 15.661),
        City("Messina", 38.193, 15.554),
        City("Sassari", 40.727, 8.560),
        City("San Giuliano Milanese", 45.396, 9.291, "san giuliano"),
    )

    private fun key(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace("'", "").replace(Regex("[^a-z ]"), "").trim().replace(Regex("\\s+"), " ")

    /** Case, accents and apostrophes do not matter. Null when the place is not in the list. */
    fun find(text: String): City? {
        val k = key(text)
        return all.firstOrNull { c -> key(c.name) == k || c.aliases.any { key(it) == k } }
    }
}

/**
 * Open-Meteo (no account, no API key). The only thing sent is a pair of coordinates (home by
 * default, or a city from [Cities]): never the spoken text, nothing about the user or the device.
 */
object WeatherClient {
    const val CITY = "San Giuliano Milanese"
    private const val HOME_LAT = 45.396
    private const val HOME_LON = 9.291
    private const val URL_TEXT = "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s" +
        "&current=temperature_2m,weather_code&hourly=precipitation_probability" +
        "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max" +
        "&timezone=Europe%%2FRome&forecast_days=6"

    internal fun url(lat: Double, lon: Double) = URL_TEXT.format(java.util.Locale.US, lat, lon)

    /** Blocking: call from a background thread. Throws on network or format problems. */
    fun fetch(lat: Double = HOME_LAT, lon: Double = HOME_LON): Forecast {
        val connection = URL(url(lat, lon)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            if (connection.responseCode != 200) throw java.io.IOException("HTTP ${connection.responseCode}")
            return WeatherParser.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
