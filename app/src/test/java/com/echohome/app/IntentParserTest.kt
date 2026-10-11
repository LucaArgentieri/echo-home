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
        assertNull(IntentParser.parse("che tempo fa"))
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
}
