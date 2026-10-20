package com.airkeyboard.ui.bookmarks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkStoreTest {
    @Test
    fun longUrlIsTooLong() {
        assertTrue(BookmarkStore.isUrlTooLong("https://example.com/" + "a".repeat(2000)))
    }

    @Test
    fun shortUrlFits() {
        assertFalse(BookmarkStore.isUrlTooLong("https://example.com"))
    }
}
