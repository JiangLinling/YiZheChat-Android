/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class LifecycleClientReceiptPersistenceTest {
    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private var committed = emptySet<String>()
    private var staged = emptySet<String>()
    private var allowCommit = true

    init {
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.getStringSet(any(), any()) } answers { committed.toSet() }
        every { preferences.edit() } returns editor
        every { editor.putStringSet(any(), any()) } answers {
            staged = secondArg<Set<String>>().toSet()
            editor
        }
        every { editor.commit() } answers {
            if (allowCommit) committed = staged
            allowCommit
        }
    }

    private fun receipt() = LifecycleClientReceipt(
        "lc13-persisted-receipt-0001",
        LifecycleComponent.ANDROID_SERVICE,
        LifecyclePhase.CLIENT_APPLIED,
        "ANDROID_SERVICE_APPLIED",
        "android-service:lc13-persisted-receipt-0001",
        123456L,
    )

    @Test
    fun `fresh store restores exact receipt metadata after a committed write`() {
        val before = listOf(receipt())
        SharedPreferencesLifecycleOperationIdStore(context).savePendingClientReceipts(before)
        assertThat(SharedPreferencesLifecycleOperationIdStore(context).pendingClientReceipts()).containsExactlyElementsIn(before)
        SharedPreferencesLifecycleOperationIdStore(context).savePendingClientReceipts(emptyList())
        assertThat(SharedPreferencesLifecycleOperationIdStore(context).pendingClientReceipts()).isEmpty()
    }

    @Test
    fun `failed commit is reported and does not replace the durable intent`() {
        val store = SharedPreferencesLifecycleOperationIdStore(context)
        store.savePendingClientReceipts(listOf(receipt()))
        allowCommit = false
        assertThat(runCatching { store.savePendingClientReceipts(emptyList()) }.isFailure).isTrue()
        assertThat(SharedPreferencesLifecycleOperationIdStore(context).pendingClientReceipts()).containsExactly(receipt())
    }

    @Test
    fun `corrupt metadata is never converted into an applied receipt`() {
        committed = setOf("not-valid-metadata")
        assertThat(runCatching { SharedPreferencesLifecycleOperationIdStore(context).pendingClientReceipts() }.isFailure).isTrue()
    }
}
