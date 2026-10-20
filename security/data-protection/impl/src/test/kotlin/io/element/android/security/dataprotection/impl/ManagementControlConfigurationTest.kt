package io.element.android.security.dataprotection.impl

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class ManagementControlConfigurationTest {
    @Test
    fun `formal channel keeps unified contact and room authority enabled`() {
        assertThat(configuration("internal", "internal").isNextDeployment).isTrue()
    }

    @Test
    fun `legacy internal channel does not silently enable another protocol`() {
        assertThat(configuration(null, "internal").isNextDeployment).isFalse()
    }

    @Test
    fun `existing Next metadata retains its original protocol`() {
        assertThat(configuration(null, "next-internal").isNextDeployment).isTrue()
    }

    private fun configuration(deployment: String?, channel: String): ManagementControlConfiguration {
        val bundle = mockk<Bundle> {
            every { getString(any()) } answers {
                when (firstArg<String>()) {
                    ManagementControlConfiguration.METADATA_DEPLOYMENT_ID -> deployment
                    ManagementControlConfiguration.METADATA_UPDATE_CHANNEL -> channel
                    else -> null
                }
            }
        }
        val info = mockk<ApplicationInfo>().apply { metaData = bundle }
        val manager = mockk<PackageManager> {
            every { getApplicationInfo(any<String>(), any<Int>()) } returns info
            every { getApplicationInfo(any<String>(), any<PackageManager.ApplicationInfoFlags>()) } returns info
        }
        val context = mockk<Context> {
            every { packageName } returns "com.yizhe.chat.internal"
            every { packageManager } returns manager
        }
        return ManagementControlConfiguration(context)
    }
}
