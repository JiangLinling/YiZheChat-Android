/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

class LifecycleExternalTimingTest {
    @Test
    fun `server reported duration does not fabricate client timestamps or accept invalid duration`() {
        val lines = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += message
            }
        }
        Timber.plant(tree)
        try {
            LifecycleStageTiming.reportedDuration("kms_app", 12345, "trace", "epoch_release", "operation")
            assertTrue(lines.single().contains("phase=metric"))
            assertTrue(lines.single().contains("duration_us=12345 clock=server"))
            assertFalse(lines.single().contains("start_ns="))
            lines.clear()
            LifecycleStageTiming.reportedDuration("kms_app", -1, "trace", "epoch_release", "operation")
            LifecycleStageTiming.interval("kms_queue", 2, 1, "trace", "epoch_release", "operation")
            assertTrue(lines.isEmpty())
        } finally {
            Timber.uproot(tree)
        }
    }
}
