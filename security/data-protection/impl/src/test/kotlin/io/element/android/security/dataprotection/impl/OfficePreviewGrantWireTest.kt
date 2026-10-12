/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class OfficePreviewGrantWireTest {
    @Test
    fun `strict grant decoding preserves the signed lifecycle operation identity`() {
        val operationId = "69ef43da-497d-4bcf-9f4a-a4b39137055e"
        val grant = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
        }.decodeFromString(
            OfficePreviewGrantWire.serializer(),
            """
                {
                  "schema_version": 1,
                  "purpose": "office-preview-upload",
                  "operation_id": "$operationId",
                  "grant_id": "7PLwm-_aimF9Dvzl3mXEKZWGTpKrPXaP",
                  "installation_id": "17c8b1e3019d6499883da7bb1f397eab7c9bb5e309123db97bcc2db82751531a",
                  "account_id_hash": "${"a".repeat(64)}",
                  "file_sha256": "${"b".repeat(64)}",
                  "file_size_bytes": 37100,
                  "file_extension": "docx",
                  "content_scope": "ORDINARY_CHAT_ATTACHMENT",
                  "issued_at_epoch_millis": 1789078424143,
                  "expires_at_epoch_millis": 1789078484143,
                  "preview_expires_at_epoch_millis": 1789082024143
                }
            """.trimIndent(),
        )

        assertThat(grant.operationId).isEqualTo(operationId)
    }
}
