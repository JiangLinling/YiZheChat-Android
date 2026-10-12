/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedRelayEntry
import org.junit.Assert.*
import org.junit.Test

class ProtectedRelayReducerTest {
    private val alice = UserId("@alice:test")
    private val bob = UserId("@bob:test")
    private val original = ProtectedRelayReducer.State(listOf(
        ProtectedRelayEntry("item-alice", alice, "Alice original"), ProtectedRelayEntry("item-bob", bob, "Bob original")))

    @Test fun `author edit replaces only that item and retains identity position and author`() {
        val actual = ProtectedRelayReducer.edit(original,"item-bob",bob,0,"Bob revised")
        assertEquals(2,actual.entries.size)
        assertEquals(original.entries[0],actual.entries[0])
        assertEquals(original.entries[1].copy(text="Bob revised",revision=1),actual.entries[1])
        assertEquals("Bob original",original.entries[1].text)
    }
    @Test fun `pending echo remains unsynchronized and cannot authorize another edit revision`() {
        val pending = ProtectedRelayReducer.edit(original,"item-alice",alice,0,"Pending",isConfirmed=false)
        assertFalse(pending.entries[0].isConfirmed)
        assertSame(pending,ProtectedRelayReducer.edit(pending,"item-alice",alice,1,"Too early"))
        val appended = ProtectedRelayReducer.append(original,"new",alice,"Pending append",isConfirmed=false)
        assertFalse(appended.entries.last().isConfirmed)
    }
    @Test fun `other actor missing item and unknown legacy author cannot edit`() {
        assertSame(original,ProtectedRelayReducer.edit(original,"item-alice",bob,0,"attack"))
        assertSame(original,ProtectedRelayReducer.edit(original,"missing",alice,0,"attack"))
        val legacy = ProtectedRelayReducer.State(listOf(ProtectedRelayEntry("legacy",null,"legacy")))
        assertSame(legacy,ProtectedRelayReducer.edit(legacy,"legacy",alice,0,"attack"))
    }
    @Test fun `stale replay concurrent revision and overflow cannot replace accepted edit`() {
        val revised = ProtectedRelayReducer.edit(original,"item-alice",alice,0,"accepted")
        assertSame(revised,ProtectedRelayReducer.edit(revised,"item-alice",alice,0,"stale"))
        assertSame(revised,ProtectedRelayReducer.edit(revised,"item-alice",alice,2,"future"))
        assertSame(revised,ProtectedRelayReducer.edit(revised,"item-alice",alice,-1,"negative"))
        val maximum = ProtectedRelayReducer.State(listOf(ProtectedRelayEntry("maximum",alice,"max",Long.MAX_VALUE)))
        assertSame(maximum,ProtectedRelayReducer.edit(maximum,"maximum",alice,Long.MAX_VALUE,"overflow"))
    }
    @Test fun `closed relay rejects append and edit even from the initiator`() {
        val closed = ProtectedRelayReducer.close(original,alice,alice)
        assertTrue(closed.closed)
        assertSame(closed,ProtectedRelayReducer.edit(closed,"item-alice",alice,0,"late"))
        assertSame(closed,ProtectedRelayReducer.append(closed,"new",alice,"late"))
        assertSame(original,ProtectedRelayReducer.close(original,bob,alice))
    }
    @Test fun `transport retry with same item identity does not append duplicates`() {
        val first = ProtectedRelayReducer.append(original,"item-new",bob,"new")
        assertEquals(3,first.entries.size)
        assertSame(first,ProtectedRelayReducer.append(first,"item-new",bob,"new"))
        assertSame(first,ProtectedRelayReducer.append(first,"item-new",alice,"replacement"))
    }
    @Test fun `invalid identity blank and oversized text cannot alter a relay`() {
        listOf("","bad id","x".repeat(513)).forEach { id ->
            assertSame(original,ProtectedRelayReducer.append(original,id,alice,"value"))
            assertSame(original,ProtectedRelayReducer.edit(original,id,alice,0,"value"))
        }
        listOf(" ","x".repeat(2_001)).forEach { text ->
            assertSame(original,ProtectedRelayReducer.append(original,"item-new",alice,text))
            assertSame(original,ProtectedRelayReducer.edit(original,"item-alice",alice,0,text))
        }
    }
}
