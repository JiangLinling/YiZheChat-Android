package io.element.android.features.home.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.home.impl.roomlist.RoomListContentState
import io.element.android.features.home.impl.roomlist.SecurityBannerState
import io.element.android.features.home.impl.roomlist.aRoomListState
import io.element.android.features.home.impl.roomlist.anEmptyContentState
import org.junit.Test

class HomeInitialContentTest {
    @Test fun `skeleton stays behind curtain and genuine empty account can enter`() {
        assertThat(homeInitialContentReady(aHomeState(roomListState = aRoomListState(
            contentState = RoomListContentState.Skeleton(16))))).isFalse()
        assertThat(homeInitialContentReady(aHomeState(roomListState = aRoomListState(
            contentState = anEmptyContentState(SecurityBannerState.None))))).isTrue()
    }
    @Test fun `real room list and restored settings do not block initial entry`() {
        assertThat(homeInitialContentReady(aHomeState())).isTrue()
        assertThat(homeInitialContentReady(aHomeState(currentHomeNavigationBarItem = HomeNavigationBarItem.Me,
            roomListState = aRoomListState(contentState = RoomListContentState.Skeleton(16))))).isTrue()
    }
}
