/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.preferences.impl.store

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.SessionId
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeFalse
import org.junit.rules.TemporaryFolder

class VoicePlayedPreferencesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `played marker survives store recreation stays account scoped and clears with account preferences`() = runTest {
        // Android DataStore FileStorage uses POSIX rename-overwrite. The host Windows JVM cannot model it.
        // Persistence on Windows development hosts must be verified on Android, not by changing storage behavior.
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns temporaryFolder.root
        val account = SessionId("@voice-a:example.org")
        val event = "\$voice-played-event"
        fun scope() = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val firstScope = scope()
        val first = DefaultSessionPreferencesStore(context, account, firstScope)
        assertThat(first.hasVoiceMessagePlayed(event).first()).isFalse()
        first.markVoiceMessagePlayed(event)
        assertThat(first.hasVoiceMessagePlayed(event).first()).isTrue()
        firstScope.coroutineContext.job.cancelAndJoin()
        assertThat(DefaultSessionPreferencesStore.storeFile(context, account).readBytes().decodeToString()).doesNotContain(event)

        val secondScope = scope()
        val second = DefaultSessionPreferencesStore(context, account, secondScope)
        val other = DefaultSessionPreferencesStore(context, SessionId("@voice-b:example.org"), secondScope)
        assertThat(second.hasVoiceMessagePlayed(event).first()).isTrue()
        assertThat(second.hasVoiceMessagePlayed("\$other-event").first()).isFalse()
        assertThat(other.hasVoiceMessagePlayed(event).first()).isFalse()
        secondScope.coroutineContext.job.cancelAndJoin()
        second.clear()
        val thirdScope = scope()
        try {
            assertThat(DefaultSessionPreferencesStore(context, account, thirdScope).hasVoiceMessagePlayed(event).first()).isFalse()
        } finally {
            thirdScope.coroutineContext.job.cancelAndJoin()
        }
    }
}
