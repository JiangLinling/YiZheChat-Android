/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediapickers.impl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediapickers.api.MediaPickOperation
import io.element.android.libraries.mediapickers.api.MediaPickSource
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaPickOperationJournalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `persisted correlation survives a new journal instance`() {
        val operation = anOperation()

        val result = journal().persist(operation, nowEpochMillis = 1_500L)

        assertThat(result).isEqualTo(MediaPickJournalWriteResult.STORED)
        assertThat(journal().read(MediaPickSource.GALLERY)).isEqualTo(operation)
    }

    @Test
    fun `active operation cannot be silently replaced`() {
        val first = anOperation(operationId = "operation-00000001")
        val second = anOperation(operationId = "operation-00000002")
        journal().persist(first, nowEpochMillis = 1_500L)

        val result = journal().persist(second, nowEpochMillis = 1_999L)

        assertThat(result).isEqualTo(MediaPickJournalWriteResult.ACTIVE_OPERATION_EXISTS)
        assertThat(journal().read(MediaPickSource.GALLERY)).isEqualTo(first)
    }

    @Test
    fun `expired correlation can be replaced`() {
        val first = anOperation(operationId = "operation-00000001")
        val second = anOperation(operationId = "operation-00000002", createdAtEpochMillis = 2_000L, deadlineEpochMillis = 3_000L)
        journal().persist(first, nowEpochMillis = 1_500L)

        val result = journal().persist(second, nowEpochMillis = 2_000L)

        assertThat(result).isEqualTo(MediaPickJournalWriteResult.STORED)
        assertThat(journal().read(MediaPickSource.GALLERY)).isEqualTo(second)
    }

    @Test
    fun `completion clears only the matching operation`() {
        val gallery = anOperation(operationId = "operation-00000001")
        val camera = anOperation(operationId = "operation-00000002", source = MediaPickSource.CAMERA_PHOTO)
        journal().persist(gallery, nowEpochMillis = 1_500L)
        journal().persist(camera, nowEpochMillis = 1_500L)

        assertThat(journal().clear(gallery.operationId)).isTrue()

        assertThat(journal().read(MediaPickSource.GALLERY)).isNull()
        assertThat(journal().read(MediaPickSource.CAMERA_PHOTO)).isEqualTo(camera)
    }

    @Test
    fun `malformed correlation is discarded`() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("pending_gallery", "not-an-operation")
            .commit()

        assertThat(journal().read(MediaPickSource.GALLERY)).isNull()
        assertThat(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .contains("pending_gallery")
        ).isFalse()
    }

    private fun journal() = SharedPreferencesMediaPickOperationJournal(context)

    @Test
    fun `native cancellation survives correlation removal and process recreation`() {
        val operation = anOperation(source = MediaPickSource.CAMERA_PHOTO)
        journal().persist(operation, 1_100L)
        val fact = journal().completeCancellation(operation, 1_600L)
        assertThat(fact).isNotNull()
        assertThat(journal().read(operation.source)).isNull()
        assertThat(journal().pendingCancellations()).containsExactly(fact)
        // A later retry cannot change the original observed completion time.
        assertThat(journal().completeCancellation(operation, 1_900L)).isEqualTo(fact)
        assertThat(journal().acknowledgeCancellation(checkNotNull(fact).copy(completedAtEpochMillis = 1_700L))).isFalse()
        assertThat(journal().pendingCancellations()).containsExactly(fact)
        assertThat(journal().acknowledgeCancellation(fact)).isTrue()
        assertThat(journal().pendingCancellations()).isEmpty()
    }

    @Test
    fun `absence or changed correlation cannot manufacture a cancellation fact`() {
        val operation = anOperation()
        assertThat(journal().completeCancellation(operation, 1_600L)).isNull()
        journal().persist(operation, 1_100L)
        assertThat(journal().completeCancellation(operation.copy(roomIdHash = "b".repeat(64)), 1_600L)).isNull()
        assertThat(journal().completeCancellation(operation, 999L)).isNull()
        assertThat(journal().read(operation.source)).isEqualTo(operation)
        assertThat(journal().pendingCancellations()).isEmpty()
    }

    @Test
    fun `acknowledging one cancellation preserves another source and active picker`() {
        val first = anOperation()
        journal().persist(first, 1_100L)
        val fact = checkNotNull(journal().completeCancellation(first, 1_200L))
        val second = anOperation(operationId = "operation-00000002", source = MediaPickSource.CAMERA_PHOTO)
        journal().persist(second, 1_100L)
        val other = checkNotNull(journal().completeCancellation(second, 1_300L))
        val active = anOperation(operationId = "operation-00000003")
        journal().persist(active, 1_400L)
        assertThat(journal().acknowledgeCancellation(fact)).isTrue()
        assertThat(journal().pendingCancellations()).containsExactly(other)
        assertThat(journal().read(active.source)).isEqualTo(active)
    }

    @Test
    fun `full cancellation journal preserves existing facts and unresolved correlation`() {
        repeat(32) { index ->
            val operation = anOperation(operationId = "operation-${index.toString().padStart(8, '0')}")
            journal().persist(operation, 1_100L)
            assertThat(journal().completeCancellation(operation, 1_200L + index)).isNotNull()
        }
        val active = anOperation(operationId = "operation-00000032")
        journal().persist(active, 1_400L)
        assertThat(journal().completeCancellation(active, 1_500L)).isNull()
        assertThat(journal().pendingCancellations()).hasSize(32)
        assertThat(journal().read(active.source)).isEqualTo(active)
    }

    private fun anOperation(
        operationId: String = "operation-00000001",
        source: MediaPickSource = MediaPickSource.GALLERY,
        createdAtEpochMillis: Long = 1_000L,
        deadlineEpochMillis: Long = 2_000L,
    ) = MediaPickOperation(
        operationId = operationId,
        source = source,
        roomIdHash = "a".repeat(64),
        createdAtEpochMillis = createdAtEpochMillis,
        deadlineEpochMillis = deadlineEpochMillis,
    )

    private companion object {
        const val PREFERENCES_NAME = "media_pick_operation_bridge_v1"
    }
}
