package com.music.zenith.data.tidal

import com.music.zenith.playback.StreamContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TidalManifestTest {

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun playback(mime: String, manifest: String, quality: String = "HI_RES_LOSSLESS", bits: Int? = 24) =
        TidalPlayback(1, quality, mime, b64(manifest), bits, 96000)

    /** Shaped like Tidal's HI_RES_LOSSLESS answer: absolute, tokenised segment URLs. */
    private val mpd = """<?xml version='1.0' encoding='UTF-8'?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-main:2011" type="static" mediaPresentationDuration="PT3M21S" minBufferTime="PT3.993S">
 <Period id="0">
  <AdaptationSet id="0" contentType="audio" mimeType="audio/mp4" segmentAlignment="true">
   <Representation id="FLAC,96000,24" codecs="flac" bandwidth="3000000" audioSamplingRate="96000">
    <SegmentTemplate timescale="96000" initialization="https://sp-ad-fa.audio.tidal.com/mediatracks/AAA/0.mp4?token=t" media="https://sp-ad-fa.audio.tidal.com/mediatracks/AAA/${'$'}Number${'$'}.mp4?token=t" startNumber="1">
     <SegmentTimeline><S d="383999" r="49"/></SegmentTimeline>
    </SegmentTemplate>
   </Representation>
  </AdaptationSet>
 </Period>
</MPD>"""

    @Test fun btsFlacAccepted() {
        val bts = """{"mimeType":"audio/flac","codecs":"flac","encryptionType":"NONE","urls":["https://lgf.audio.tidal.com/x.flac?token=t"]}"""
        val s = TidalManifest.toStream(playback("application/vnd.tidal.bts", bts, "LOSSLESS", 16), 201)
        assertNotNull(s)
        assertEquals("https://lgf.audio.tidal.com/x.flac?token=t", s!!.url)
        assertEquals("flac", s.format.codec)
        assertEquals(16, s.format.bitDepth)
        assertEquals(201, s.durationSec)
    }

    @Test fun btsEncryptedIsNull() {
        val bts = """{"codecs":"flac","encryptionType":"OLD_AES","urls":["https://x"]}"""
        assertNull(TidalManifest.toStream(playback("application/vnd.tidal.bts", bts)))
    }

    @Test fun btsLossyIsNull() {
        val bts = """{"codecs":"mp4a.40.2","encryptionType":"NONE","urls":["https://x"]}"""
        assertNull(TidalManifest.toStream(playback("application/vnd.tidal.bts", bts)))
    }

    @Test fun dashFlacBecomesDataUri() {
        val s = TidalManifest.toStream(playback("application/dash+xml", mpd))!!
        assertTrue(s.url.startsWith(TidalManifest.DASH_DATA_PREFIX))
        assertEquals(mpd, TidalManifest.mpdOf(s.url))
        assertEquals(24, s.format.bitDepth)
        assertEquals(96000, s.format.sampleRateHz)
        assertEquals("application/dash+xml", StreamContainer.manifestMimeOf(s.url))
    }

    @Test fun dashWithContentProtectionIsNull() {
        val protected = mpd.replace("<SegmentTemplate", "<ContentProtection schemeIdUri=\"urn:uuid:x\"/><SegmentTemplate")
        assertNull(TidalManifest.toStream(playback("application/dash+xml", protected)))
    }

    @Test fun eac3IsNull() {
        assertNull(TidalManifest.toStream(playback("application/dash+xml", mpd.replace("codecs=\"flac\"", "codecs=\"ec-3\""))))
        assertNull(TidalManifest.toStream(playback("application/vnd.something", "{}")))
    }

    @Test fun dashSegmentUrlsAreAbsolute() {
        Regex("""(initialization|media)="([^"]+)"""").findAll(mpd).forEach {
            assertTrue(it.groupValues[2], it.groupValues[2].startsWith("https://"))
        }
    }

    @Test fun mpdOfIgnoresOtherUrls() {
        assertNull(TidalManifest.mpdOf("https://example.com/a.mpd"))
    }
}
