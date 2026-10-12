package com.echohome.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentParserTest {

    private fun search(query: String) = EchoIntent.MusicSearch(query)

    @Test fun searchArtistWithArticle() = assertEquals(search("Radiohead"), IntentParser.parse("metti i Radiohead"))

    @Test fun searchSongTitle() =
        assertEquals(search("Wish You Were Here"), IntentParser.parse("metti Wish You Were Here"))

    @Test fun searchKeepsSingularArticle() =
        assertEquals(search("La Vie en Rose"), IntentParser.parse("metti La Vie en Rose"))

    @Test fun searchVariants() {
        assertEquals(search("Vanilla Origin Album"), IntentParser.parse("metti su Vanilla Origin Album"))
        assertEquals(search("Queen"), IntentParser.parse("fai partire i Queen"))
        assertEquals(search("Daft Punk"), IntentParser.parse("suona Daft Punk"))
        assertEquals(search("Fabrizio De André"), IntentParser.parse("metti la musica di Fabrizio De André"))
        assertEquals(search("Pink Floyd"), IntentParser.parse("riproduci i Pink Floyd"))
    }

    @Test fun searchNormalizesCaseSpacesAndPunctuation() =
        assertEquals(search("Radiohead"), IntentParser.parse("  Metti   i Radiohead. "))

    @Test fun pause() {
        assertEquals(EchoIntent.MusicPause, IntentParser.parse("pausa"))
        assertEquals(EchoIntent.MusicPause, IntentParser.parse("metti in pausa"))
        assertEquals(EchoIntent.MusicPause, IntentParser.parse("Ferma la musica"))
        assertEquals(EchoIntent.MusicPause, IntentParser.parse("stop"))
    }

    @Test fun resume() {
        assertEquals(EchoIntent.MusicResume, IntentParser.parse("riprendi"))
        assertEquals(EchoIntent.MusicResume, IntentParser.parse("continua"))
    }

    @Test fun next() {
        assertEquals(EchoIntent.MusicNext, IntentParser.parse("prossima"))
        assertEquals(EchoIntent.MusicNext, IntentParser.parse("prossima canzone"))
        assertEquals(EchoIntent.MusicNext, IntentParser.parse("avanti"))
    }

    @Test fun previous() {
        assertEquals(EchoIntent.MusicPrevious, IntentParser.parse("precedente"))
        assertEquals(EchoIntent.MusicPrevious, IntentParser.parse("la precedente"))
        assertEquals(EchoIntent.MusicPrevious, IntentParser.parse("indietro"))
    }

    @Test fun unknownOrEmptyIsNull() {
        assertNull(IntentParser.parse(""))
        assertNull(IntentParser.parse("   "))
        assertNull(IntentParser.parse("che bella giornata"))
        assertNull(IntentParser.parse("Radiohead"))
        assertNull(IntentParser.parse("metti"))
    }

    @Test fun toleratesSlightlyMisheardVerbAndCommands() {
        assertEquals(search("Radiohead"), IntentParser.parse("meti i Radiohead"))
        assertEquals(search("Pink Floyd"), IntentParser.parse("metì Pink Floyd"))
        assertEquals(EchoIntent.MusicNext, IntentParser.parse("prossimo"))
        assertEquals(EchoIntent.MusicResume, IntentParser.parse("riprendo"))
        assertEquals(EchoIntent.MusicPrevious, IntentParser.parse("precedenti"))
    }

    @Test fun fuzzyDoesNotFireOnUnrelatedText() {
        assertNull(IntentParser.parse("Precremate"))
        assertNull(IntentParser.parse("di"))
        assertNull(IntentParser.parse("Radiohead"))
        assertNull(IntentParser.parse("la causa"))
        assertNull(IntentParser.parse("mettere"))
    }

    @Test fun volumeSet() {
        assertEquals(EchoIntent.VolumeSet(40), IntentParser.parse("volume 40"))
        assertEquals(EchoIntent.VolumeSet(40), IntentParser.parse("volume quaranta"))
        assertEquals(EchoIntent.VolumeSet(25), IntentParser.parse("metti il volume a venticinque"))
        assertEquals(EchoIntent.VolumeSet(70), IntentParser.parse("imposta il volume al 70%"))
        assertEquals(EchoIntent.VolumeSet(100), IntentParser.parse("volume al massimo"))
        assertEquals(EchoIntent.VolumeSet(0), IntentParser.parse("volume al minimo"))
        assertEquals(EchoIntent.VolumeSet(100), IntentParser.parse("volume 250")) // clamped
    }

    @Test fun volumeUpDown() {
        assertEquals(EchoIntent.VolumeUp, IntentParser.parse("alza il volume"))
        assertEquals(EchoIntent.VolumeUp, IntentParser.parse("più forte"))
        assertEquals(EchoIntent.VolumeDown, IntentParser.parse("abbassa il volume"))
        assertEquals(EchoIntent.VolumeDown, IntentParser.parse("più piano"))
    }

    @Test fun time() {
        assertEquals(EchoIntent.Time, IntentParser.parse("che ore sono"))
        assertEquals(EchoIntent.Time, IntentParser.parse("Che ora è?"))
        assertEquals(EchoIntent.Time, IntentParser.parse("dimmi l'ora"))
    }

    @Test fun numberWords() {
        assertEquals(0, IntentParser.parseNumber("zero"))
        assertEquals(13, IntentParser.parseNumber("tredici"))
        assertEquals(21, IntentParser.parseNumber("ventuno"))
        assertEquals(28, IntentParser.parseNumber("ventotto"))
        assertEquals(35, IntentParser.parseNumber("trentacinque"))
        assertEquals(100, IntentParser.parseNumber("cento"))
        assertNull(IntentParser.parseNumber("radiohead"))
    }

    @Test fun volumeAndTimeDoNotStealMusicSearches() {
        assertEquals(search("Radiohead"), IntentParser.parse("metti i Radiohead"))
        assertEquals(search("Volume Up"), IntentParser.parse("metti Volume Up"))
        assertNull(IntentParser.parse("volume radiohead"))
    }

    @Test fun timerCreate() {
        assertEquals(EchoIntent.TimerCreate(600), IntentParser.parse("timer dieci minuti"))
        assertEquals(EchoIntent.TimerCreate(600), IntentParser.parse("timer 10 minuti"))
        assertEquals(EchoIntent.TimerCreate(300), IntentParser.parse("imposta un timer di 5 minuti"))
        assertEquals(EchoIntent.TimerCreate(90), IntentParser.parse("metti un timer per un minuto e 30 secondi"))
        assertEquals(EchoIntent.TimerCreate(1800), IntentParser.parse("timer mezz'ora"))
        assertEquals(EchoIntent.TimerCreate(5400), IntentParser.parse("timer un'ora e mezza"))
        assertEquals(EchoIntent.TimerCreate(7200 + 30 * 60), IntentParser.parse("timer due ore e 30 minuti"))
        assertEquals(EchoIntent.TimerCreate(900), IntentParser.parse("timer un quarto d'ora"))
        assertEquals(EchoIntent.TimerCreate(45), IntentParser.parse("timer 45 secondi"))
    }

    @Test fun timerCancel() {
        assertEquals(EchoIntent.TimerCancel(), IntentParser.parse("annulla timer"))
        assertEquals(EchoIntent.TimerCancel(), IntentParser.parse("annulla il timer"))
        assertEquals(EchoIntent.TimerCancel(), IntentParser.parse("ferma il timer"))
        assertEquals(EchoIntent.TimerCancel(), IntentParser.parse("spegni la sveglia"))
    }

    @Test fun timerNeverBecomesMusicSearchOrPause() {
        assertNull(IntentParser.parse("timer"))
        assertNull(IntentParser.parse("timer pippo"))
        assertNull(IntentParser.parse("metti un timer"))
        assertNull(IntentParser.parse("timer 0 minuti"))
        assertNull(IntentParser.parse("timer 30 ore"))
        assertEquals(EchoIntent.MusicPause, IntentParser.parse("ferma la musica"))
    }

    @Test fun weather() {
        assertEquals(EchoIntent.WeatherNow, IntentParser.parse("che tempo fa"))
        assertEquals(EchoIntent.WeatherNow, IntentParser.parse("Che tempo fa oggi?"))
        assertEquals(EchoIntent.WeatherNow, IntentParser.parse("meteo"))
        assertEquals(EchoIntent.WeatherTomorrow, IntentParser.parse("che tempo farà domani"))
        assertEquals(EchoIntent.WeatherTomorrow, IntentParser.parse("che tempo fa domani?"))
        assertEquals(EchoIntent.WeatherTomorrow, IntentParser.parse("meteo di domani"))
        assertEquals(EchoIntent.WeatherTomorrow, IntentParser.parse("previsioni per domani"))
    }

    @Test fun weatherDoesNotStealMusic() {
        assertEquals(search("Meteo Is Cool"), IntentParser.parse("metti Meteo Is Cool"))
    }

    @Test
    fun quickWins() {
        assertEquals(EchoIntent.Date, IntentParser.parse("che giorno è oggi"))
        assertEquals(EchoIntent.Date, IntentParser.parse("Che data è?"))
        assertEquals(EchoIntent.TimerRemaining(), IntentParser.parse("quanto manca al timer"))
        assertEquals(EchoIntent.TimerRemaining(), IntentParser.parse("quanto manca"))
        assertEquals(EchoIntent.VolumeUnmute, IntentParser.parse("riattiva l'audio"))
        assertEquals(EchoIntent.VolumeUnmute, IntentParser.parse("riattiva audio"))
        assertEquals(EchoIntent.VolumeSet(0), IntentParser.parse("muto"))
        assertEquals(EchoIntent.MusicShuffle(true), IntentParser.parse("mischia"))
        assertEquals(EchoIntent.MusicShuffle(false), IntentParser.parse("disattiva la riproduzione casuale"))
        assertEquals(EchoIntent.MusicRepeat(true), IntentParser.parse("ripeti brano"))
        assertEquals(EchoIntent.MusicRepeat(false), IntentParser.parse("non ripetere"))
        assertEquals(EchoIntent.MusicSearch("Radiohead"), IntentParser.parse("metti Radiohead"))
    }

    @Test
    fun politenessAndAlbumForms() {
        assertEquals(EchoIntent.MusicSearch("Queen"), IntentParser.parse("per favore metti i Queen"))
        assertEquals(EchoIntent.MusicSearch("Queen"), IntentParser.parse("puoi mettere i Queen per piacere"))
        assertEquals(EchoIntent.MusicSearch("Pink Floyd"), IntentParser.parse("metti il disco dei Pink Floyd"))
        assertEquals(EchoIntent.MusicSearch("Rock Classics"), IntentParser.parse("metti la playlist Rock Classics"))
        assertEquals(EchoIntent.MusicSearch("Thriller"), IntentParser.parse("fammi sentire l'album Thriller"))
        assertEquals(EchoIntent.MusicPause, IntentParser.parse("ok pausa grazie"))
        assertEquals(EchoIntent.TimerCreate(300), IntentParser.parse("vorrei un timer di 5 minuti"))
        assertNull(IntentParser.parse("mettere"))
    }

    @Test
    fun namedTimers() {
        assertEquals(EchoIntent.TimerCreate(600, "pasta"), IntentParser.parse("timer pasta 10 minuti"))
        assertEquals(EchoIntent.TimerCreate(600, "pasta"), IntentParser.parse("imposta un timer per la pasta di 10 minuti"))
        assertEquals(EchoIntent.TimerCreate(480, "uova"), IntentParser.parse("metti un timer di 8 minuti per le uova"))
        assertEquals(EchoIntent.TimerCreate(5400, null), IntentParser.parse("timer un'ora e mezza"))
        assertEquals(EchoIntent.TimerCancel("pasta"), IntentParser.parse("annulla il timer della pasta"))
        assertEquals(EchoIntent.TimerCancel("pasta"), IntentParser.parse("annulla timer pasta"))
        assertEquals(EchoIntent.TimerCancel("uova"), IntentParser.parse("annulla il timer delle uova"))
        assertEquals(EchoIntent.TimerCancel(all = true), IntentParser.parse("annulla tutti i timer"))
        assertEquals(EchoIntent.TimerRemaining("pasta"), IntentParser.parse("quanto manca al timer della pasta"))
        assertEquals(EchoIntent.TimerRemaining(), IntentParser.parse("quanti timer ho"))
        assertEquals(EchoIntent.MusicPause, IntentParser.parse("ferma la musica"))
        assertEquals(true, IntentParser.labelMatches("pasta", "la pasta"))
        assertEquals(false, IntentParser.labelMatches("pasta", "uova"))
        assertEquals(false, IntentParser.labelMatches(null, "uova"))
    }

    @Test
    fun weatherWeek() {
        assertEquals(EchoIntent.WeatherWeek, IntentParser.parse("che tempo fa nei prossimi giorni"))
        assertEquals(EchoIntent.WeatherWeek, IntentParser.parse("meteo della settimana"))
        assertEquals(EchoIntent.WeatherWeek, IntentParser.parse("previsioni per la settimana"))
        assertEquals(EchoIntent.WeatherWeek, IntentParser.parse("che tempo farà questa settimana"))
        assertEquals(EchoIntent.WeatherTomorrow, IntentParser.parse("che tempo fa domani"))
        assertEquals(EchoIntent.WeatherNow, IntentParser.parse("che tempo fa"))
    }

    @Test fun weatherInOtherCities() {
        assertEquals(EchoIntent.WeatherIn("Roma", WeatherWhen.NOW), IntentParser.parse("che tempo fa a Roma"))
        assertEquals(EchoIntent.WeatherIn("Roma", WeatherWhen.NOW), IntentParser.parse("Meteo a Roma?"))
        assertEquals(EchoIntent.WeatherIn("Napoli", WeatherWhen.TOMORROW), IntentParser.parse("che tempo farà a Napoli domani"))
        assertEquals(EchoIntent.WeatherIn("Torino", WeatherWhen.WEEK), IntentParser.parse("previsioni per Torino nei prossimi giorni"))
        assertEquals(EchoIntent.WeatherIn("Reggio Emilia", WeatherWhen.NOW), IntentParser.parse("com'è il tempo a Reggio Emilia oggi"))
        // Home weather keeps its own intents.
        assertEquals(EchoIntent.WeatherTomorrow, IntentParser.parse("meteo di domani"))
        assertEquals(EchoIntent.WeatherWeek, IntentParser.parse("meteo della settimana"))
    }

    @Test fun volumeNudges() {
        assertEquals(EchoIntent.VolumeNudge(true), IntentParser.parse("un po' più forte"))
        assertEquals(EchoIntent.VolumeNudge(true), IntentParser.parse("alza un po' il volume"))
        assertEquals(EchoIntent.VolumeNudge(false), IntentParser.parse("abbassa un pochino"))
        assertEquals(EchoIntent.VolumeNudge(false), IntentParser.parse("un po' più piano"))
        assertEquals(EchoIntent.VolumeUp, IntentParser.parse("più forte"))
    }

    @Test fun wakeWordLeakingIntoTranscript() {
        assertEquals(EchoIntent.Time, IntentParser.parse("Hey Jarvis, che ore sono?"))
        assertEquals(EchoIntent.MusicSearch("Queen"), IntentParser.parse("Alexa, metti i Queen"))
        assertEquals(EchoIntent.TimerCreate(300), IntentParser.parse("Ehi Echo timer 5 minuti"))
        assertEquals(EchoIntent.MusicPause, IntentParser.parse("Jarvis pausa"))
    }

    @Test fun garbledVerbsAreRepaired() {
        assertEquals(EchoIntent.VolumeDown, IntentParser.parse("abbasa il volume"))
        assertEquals(EchoIntent.TimerCancel("pasta"), IntentParser.parse("anulla il timer della pasta"))
        assertEquals(EchoIntent.TimerCreate(600), IntentParser.parse("imposta un taimer di 10 minuti"))
        assertEquals(null, IntentParser.parse("lasciatemi cantare")) // not a command, no false repair
    }
}
