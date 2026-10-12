package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.CallIntentConsensus
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.test.room.aRoomInfo
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomInfoWithRtcTest {
    @Test fun `suspended RTC observer never blocks native membership changes`() = runTest {
        val base = MutableStateFlow(aRoomInfo())
        val values = mutableListOf<io.element.android.libraries.matrix.api.room.RoomInfo>()
        backgroundScope.launch { roomInfoWithRtc(base) { flow { awaitCancellation() } }.collect(values::add) }
        runCurrent()
        assertThat(values.last().currentUserMembership).isEqualTo(CurrentUserMembership.JOINED)
        base.value = base.value.copy(currentUserMembership = CurrentUserMembership.LEFT)
        runCurrent()
        assertThat(values.last().currentUserMembership).isEqualTo(CurrentUserMembership.LEFT)
    }

    @Test fun `RTC exception retry never cancels base membership subscription`() = runTest {
        val base = MutableStateFlow(aRoomInfo())
        val values = mutableListOf<io.element.android.libraries.matrix.api.room.RoomInfo>()
        backgroundScope.launch { roomInfoWithRtc(base) { flow { error("optional RTC unavailable") } }.collect(values::add) }
        runCurrent()
        assertThat(values.last().currentUserMembership).isEqualTo(CurrentUserMembership.JOINED)
        base.value = base.value.copy(currentUserMembership = CurrentUserMembership.LEFT)
        runCurrent()
        assertThat(values.last().currentUserMembership).isEqualTo(CurrentUserMembership.LEFT)
    }

    @Test fun `left cancels RTC and clears call fields while preserving actual SDK state`() = runTest {
        val base = MutableStateFlow(aRoomInfo())
        var cancelled = false
        val values = mutableListOf<io.element.android.libraries.matrix.api.room.RoomInfo>()
        backgroundScope.launch { roomInfoWithRtc(base) { flow {
            try {
                emit(RtcRoomCallSnapshot(listOf(UserId("@peer:test")), CallIntentConsensus.None))
                awaitCancellation()
            } finally { cancelled = true }
        } }.collect(values::add) }
        runCurrent()
        assertThat(values.last().hasRoomCall).isTrue()
        base.value = base.value.copy(currentUserMembership = CurrentUserMembership.LEFT)
        runCurrent()
        assertThat(cancelled).isTrue()
        assertThat(values.last().hasRoomCall).isFalse()
        assertThat(values.last().currentUserMembership).isEqualTo(CurrentUserMembership.LEFT)
    }
}
