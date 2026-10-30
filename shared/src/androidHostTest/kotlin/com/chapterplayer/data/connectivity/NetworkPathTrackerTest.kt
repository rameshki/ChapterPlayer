package com.chapterplayer.data.connectivity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** iOS names its network from NWPathMonitor reports: the name must change exactly when the Plex choice should run again. */
class NetworkPathTrackerTest {

    private val wifi = NetworkPath(satisfied = true, interfaces = listOf("en0"))
    private val cellular = NetworkPath(satisfied = true, interfaces = listOf("pdp_ip0"))
    private val none = NetworkPath(satisfied = false, interfaces = emptyList())

    @Test
    fun `no usable path is no network`() {
        assertNull(NetworkPathTracker().update(none))
        assertNull(NetworkPathTracker().update(NetworkPath(satisfied = false, interfaces = listOf("en0"))))
    }

    @Test
    fun `the same path reported again keeps its name`() {
        val tracker = NetworkPathTracker()
        val first = tracker.update(wifi)
        assertEquals(first, tracker.update(wifi))
        // The order the interfaces come in does not matter.
        val both = NetworkPath(true, listOf("en0", "pdp_ip0"))
        val name = tracker.update(both)
        assertEquals(name, tracker.update(NetworkPath(true, listOf("pdp_ip0", "en0"))))
    }

    @Test
    fun `moving to other interfaces is another network`() {
        val tracker = NetworkPathTracker()
        val onWifi = tracker.update(wifi)
        val onCellular = tracker.update(cellular)
        assertNotEquals(onWifi, onCellular)
        assertNotEquals(onCellular, tracker.update(wifi))
    }

    @Test
    fun `coming back after no network is another network, even on the same interface`() {
        val tracker = NetworkPathTracker()
        val before = tracker.update(wifi)
        assertNull(tracker.update(none))
        val after = tracker.update(wifi)
        assertNotEquals(before, after)
    }

    @Test
    fun `a name holds no address, only interface names`() {
        val name = NetworkPathTracker().update(wifi)!!
        assertEquals("path-1:en0", name)
    }
}
