/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments.preview

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediapickers.api.MediaPickOperation
import io.element.android.libraries.mediapickers.api.MediaPickSource
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GallerySelectionAuthorityTest {
    private val account = "a".repeat(64)
    private val device = "b".repeat(64)
    private val room = "c".repeat(64)

    private suspend fun selection(coordinator: FakeAppOperationalStateCoordinator): MediaPickOperation {
        val authority = coordinator.start(lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_14,
            trigger = "draft_change",
            accountIdHash = account,
            matrixDeviceIdHash = device,
            roomIdHash = room,
            deadlineEpochMillis = System.currentTimeMillis() + 60_000,
        ))
        return MediaPickOperation(
            authority.operationId,
            MediaPickSource.GALLERY,
            room,
            authority.createdAtEpochMillis,
            authority.deadlineEpochMillis
        )
    }

    @Test
    fun `only current durable gallery operation can add selected sources`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val operation = selection(coordinator)
        assertThat(isAuthoritativeGallerySelection(coordinator, operation, room, account, device)).isTrue()
    }

    @Test
    fun `another room account device or forged epoch cannot claim a selection`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val operation = selection(coordinator)
        assertThat(isAuthoritativeGallerySelection(coordinator, operation, "d".repeat(64), account, device)).isFalse()
        assertThat(isAuthoritativeGallerySelection(coordinator, operation, room, "d".repeat(64), device)).isFalse()
        assertThat(isAuthoritativeGallerySelection(coordinator, operation, room, account, "d".repeat(64))).isFalse()
        assertThat(isAuthoritativeGallerySelection(
            coordinator,
            operation.copy(createdAtEpochMillis = operation.createdAtEpochMillis + 1),
            room,
            account,
            device
        )).isFalse()
        assertThat(isAuthoritativeGallerySelection(
            coordinator,
            operation.copy(deadlineEpochMillis = operation.deadlineEpochMillis + 1),
            room,
            account,
            device
        )).isFalse()
    }

    @Test
    fun `expired missing or camera authority fails closed`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val operation = selection(coordinator)
        assertThat(isAuthoritativeGallerySelection(
            coordinator,
            operation,
            room,
            account,
            device,
            nowEpochMillis = operation.deadlineEpochMillis
        )).isFalse()
        assertThat(isAuthoritativeGallerySelection(FakeAppOperationalStateCoordinator(), operation, room, account, device)).isFalse()
        assertThat(isAuthoritativeGallerySelection(coordinator, operation.copy(source = MediaPickSource.CAMERA_PHOTO), room, account, device)).isFalse()
    }
}
