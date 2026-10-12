package io.element.android.libraries.push.impl.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.push.impl.PushDatabase
import io.element.android.libraries.push.impl.notifications.fixtures.aPushRequest
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class PendingPushObservationTest {
    @Test
    fun `committed pushes wake observer only in the requested account and age range`() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().context
        val driver = AndroidSqliteDriver(PushDatabase.Schema, context, null)
        try {
            val history = DefaultPushHistoryService(
                lazy { PushDatabase(driver) }, FakeSystemClock(), testCoroutineDispatchers(), context,
            )
            history.observePendingPushRequests(A_USER_ID, Instant.fromEpochMilliseconds(100)).test {
                assertThat(awaitItem()).isEmpty()
                val request = aPushRequest().copy(sessionId = A_USER_ID.value, pushDate = 101)
                history.enqueuePushRequest(request.copy(sessionId = "@other:test")).getOrThrow()
                assertThat(awaitItem()).isEmpty()
                history.enqueuePushRequest(request.copy(eventId = "old", pushDate = 99)).getOrThrow()
                assertThat(awaitItem()).isEmpty()
                history.enqueuePushRequest(request).getOrThrow()
                assertThat(awaitItem()).containsExactly(request)
                history.insertOrUpdatePushRequest(request.copy(status = 1)).getOrThrow()
                assertThat(awaitItem()).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            driver.close()
        }
    }
}
