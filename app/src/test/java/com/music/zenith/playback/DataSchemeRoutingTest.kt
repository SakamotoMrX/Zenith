package com.music.zenith.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSchemeRoutingTest {
    @Test fun onlyDataUrisAreRoutedInline() {
        assertTrue(DataSchemeRouting.isDataScheme("data:application/dash+xml;base64,AAA"))
        assertTrue(DataSchemeRouting.isDataScheme("DATA:text/plain,x"))
        assertFalse(DataSchemeRouting.isDataScheme("https://sp-ad-fa.audio.tidal.com/a.mp4"))
        assertFalse(DataSchemeRouting.isDataScheme("zenith://source?s=a&t=b"))
        assertFalse(DataSchemeRouting.isDataScheme("smb://host/share/a.flac"))
        assertFalse(DataSchemeRouting.isDataScheme("dat"))
    }
}
