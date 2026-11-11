package dev.etran.bedreplay.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldLayoutTest {
    @Test
    fun markersOfOtherPluginsAreNeverCopiedIntoAReplayWorld() {
        // MapLoader deletes any world folder carrying its marker that is not in its queue
        assertTrue(WorldLayout.skip(".maploader-runtime"))
        assertTrue(WorldLayout.skip(".maploader-setup"))
        assertTrue(WorldLayout.skip(".multigroup-runtime"))
        assertTrue(WorldLayout.skip("uid.dat"))
        assertTrue(WorldLayout.skip("session.lock"))
        assertTrue(WorldLayout.skip("data/session.lock"))
    }

    @Test
    fun mapDataIsCopied() {
        assertFalse(WorldLayout.skip("region/r.0.0.mca"))
        assertFalse(WorldLayout.skip("level.dat"))
        assertFalse(WorldLayout.skip("entities/r.0.0.mca"))
    }
}
