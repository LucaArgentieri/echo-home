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

/**
 * Open-Meteo (no account, no API key). The only thing sent is the fixed coordinates of
 * San Giuliano Milanese: nothing about the user or the device.
 */
object WeatherClient {
    const val CITY = "San Giuliano Milanese"
    private const val URL_TEXT = "https://api.open-meteo.com/v1/forecast?latitude=45.396&longitude=9.291" +
        "&current=temperature_2m,weather_code&hourly=precipitation_probability" +
        "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max" +
        "&timezone=Europe%2FRome&forecast_days=6"

    /** Blocking: call from a background thread. Throws on network or format problems. */
    fun fetch(): Forecast {
        val connection = URL(URL_TEXT).openConnection() as HttpURLConnection
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
