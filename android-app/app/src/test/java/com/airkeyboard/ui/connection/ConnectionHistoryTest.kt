package com.airkeyboard.ui.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionHistoryTest {
    private fun pc(host: String, port: Int = 1978, time: Long = 0) = RecentPc("PC-$host", host, port, time)

    @Test
    fun newestFirst() {
        val list = ConnectionHistory.added(ConnectionHistory.added(emptyList(), pc("a")), pc("b"))
        assertEquals(listOf("b", "a"), list.map { it.host })
    }

    @Test
    fun reconnectMovesToTopWithoutDuplicate() {
        val list = listOf(pc("b"), pc("a", time = 1))
        val after = ConnectionHistory.added(list, pc("a", time = 2))
        assertEquals(listOf("a", "b"), after.map { it.host })
        assertEquals(2L, after[0].lastUsed)
    }

    @Test
    fun sameHostOtherPortIsSeparate() {
        val list = ConnectionHistory.added(listOf(pc("a")), pc("a", port = 2000))
        assertEquals(2, list.size)
    }

    @Test
    fun keepsAtMostTen() {
        var list = emptyList<RecentPc>()
        for (i in 1..12) list = ConnectionHistory.added(list, pc("h$i"))
        assertEquals(ConnectionHistory.MAX, list.size)
        assertEquals("h12", list.first().host)
        assertEquals("h3", list.last().host)
    }

    @Test
    fun removeByHostAndPort() {
        val list = listOf(pc("a"), pc("a", port = 2000), pc("b"))
        assertEquals(listOf(1978, 2000), ConnectionHistory.removed(list, pc("b")).map { it.port })
        assertEquals(listOf("a", "b"), ConnectionHistory.removed(list, pc("a", port = 2000)).map { it.host })
    }
}
