/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class LifecycleJournalDecoderCompatibilityTest {
    private val preferences = mockk<SharedPreferences>()
    private val context = mockk<Context>()
    private val retained = mutableMapOf<String, Set<String>>()

    init {
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.getStringSet(any(), any()) } answers { retained[firstArg<String>()]?.toSet().orEmpty() }
    }

    @Test
    fun `legacy receipts retain escaping quoted time unknown fields and last duplicate value`() {
        val encoded = """{"operation":"original-receipt-0001","component":"ANDROID_SERVICE","phase":"CLIENT_APPLIED",""" +
            """"result":"old","reference":"line\n\"quoted\"","occurredAt":"123456","extra":{"ignored":[1,true]},"result":"APPLIED"}"""
        retained[RECEIPTS] = setOf(encoded)

        val fact = SharedPreferencesLifecycleOperationIdStore(context).pendingClientReceipts().single()

        assertThat(fact.operationId).isEqualTo("original-receipt-0001")
        assertThat(fact.component).isEqualTo(LifecycleComponent.ANDROID_SERVICE)
        assertThat(fact.phase).isEqualTo(LifecyclePhase.CLIENT_APPLIED)
        assertThat(fact.resultCode).isEqualTo("APPLIED")
        assertThat(fact.receiptReference).isEqualTo("line\n\"quoted\"")
        assertThat(fact.occurredAtEpochMillis).isEqualTo(123456L)
        assertThat(retained[RECEIPTS]).containsExactly(encoded)
        verify(exactly = 0) { preferences.edit() }
    }

    @Test
    fun `legacy primitive content is retained without turning it into new authority`() {
        retained[RECEIPTS] = setOf(
            """{"operation":"original-receipt-0001","component":"ANDROID_SERVICE","phase":"CLIENT_APPLIED","result":true,"reference":1234,"occurredAt":123456}"""
        )

        val fact = SharedPreferencesLifecycleOperationIdStore(context).pendingClientReceipts().single()

        assertThat(fact.resultCode).isEqualTo("true")
        assertThat(fact.receiptReference).isEqualTo("1234")
        verify(exactly = 0) { preferences.edit() }
    }

    @Test
    fun `legacy pending draft restores exact subjects deadline and original escaped idempotency`() {
        val encoded = """{"operation":"original-draft-0001","idempotency":"original-idempotency\n\"key\"","account":"${"a".repeat(
            64
        )}","device":"${"b".repeat(
            64
        )}","room":"${"c".repeat(64)}","deadline":"100000","installation":"installation-original-0001","extra":[{"ignored":true}]}"""
        retained[DRAFTS] = setOf(encoded)

        val entry = SharedPreferencesLifecycleOperationIdStore(context).pendingDraftStarts().single()

        assertThat(entry.request.operationId).isEqualTo("original-draft-0001")
        assertThat(entry.request.idempotencyKey).isEqualTo("original-idempotency\n\"key\"")
        assertThat(entry.request.accountIdHash).isEqualTo("a".repeat(64))
        assertThat(entry.request.matrixDeviceIdHash).isEqualTo("b".repeat(64))
        assertThat(entry.request.roomIdHash).isEqualTo("c".repeat(64))
        assertThat(entry.request.deadlineEpochMillis).isEqualTo(100000L)
        assertThat(entry.installationId).isEqualTo("installation-original-0001")
        assertThat(retained[DRAFTS]).containsExactly(encoded)
        verify(exactly = 0) { preferences.edit() }
    }

    @Test
    fun `a malformed retained record fails the read and never removes the original batch`() {
        val valid = """{"operation":"original-receipt-0001","component":"ANDROID_SERVICE","phase":"CLIENT_APPLIED",""" +
            """"result":"APPLIED","reference":"original-reference","occurredAt":123456}"""
        val invalid = valid.replace("\"occurredAt\":123456", "\"occurredAt\":{}")
        retained[RECEIPTS] = linkedSetOf(valid, invalid)
        retained[DRAFTS] = setOf("""{"operation":"missing-original-fields"}""")
        val store = SharedPreferencesLifecycleOperationIdStore(context)

        assertThat(runCatching { store.pendingClientReceipts() }.isFailure).isTrue()
        assertThat(runCatching { store.pendingDraftStarts() }.isFailure).isTrue()
        assertThat(retained[RECEIPTS]).containsExactly(valid, invalid)
        assertThat(retained[DRAFTS]).containsExactly("""{"operation":"missing-original-fields"}""")
        verify(exactly = 0) { preferences.edit() }
    }

    private companion object {
        const val RECEIPTS = "pending_client_receipts_v1"
        const val DRAFTS = "pending_lc14_draft_starts_v1"
    }
}
