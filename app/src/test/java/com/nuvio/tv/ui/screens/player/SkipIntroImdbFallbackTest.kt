package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkipIntroImdbFallbackTest {

    @Test
    fun `uses the imdb id carried by the video id`() {
        assertEquals(
            "tt0944947",
            episodeSkipImdbId(videoId = "tt0944947:1:2", contentId = "tt0944947", metaImdbId = "tt9999999"),
        )
    }

    @Test
    fun `falls back to an imdb content id when the video id is tmdb`() {
        assertEquals(
            "tt0944947",
            episodeSkipImdbId(videoId = "tmdb:1399:1:2", contentId = "tt0944947", metaImdbId = null),
        )
    }

    @Test
    fun `falls back to the metadata imdb id for tmdb ids`() {
        assertEquals(
            "tt0944947",
            episodeSkipImdbId(videoId = "tmdb:1399:1:2", contentId = "tmdb:1399", metaImdbId = "tt0944947"),
        )
    }

    @Test
    fun `returns null when no id is an imdb id`() {
        assertNull(episodeSkipImdbId(videoId = "tmdb:1399:1:2", contentId = "tmdb:1399", metaImdbId = null))
        assertNull(episodeSkipImdbId(videoId = null, contentId = null, metaImdbId = "1399"))
    }
}
