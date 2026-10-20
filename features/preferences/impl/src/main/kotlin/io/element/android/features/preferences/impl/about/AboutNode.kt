/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.about

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.libraries.di.SessionScope
import io.element.android.security.dataprotection.api.PreparedAppUpdate

@ContributesNode(SessionScope::class)
@AssistedInject
class AboutNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val presenter: AboutPresenter,
) : Node(buildContext, plugins = plugins) {
    interface Callback : Plugin {
        fun navigateToOssLicenses()
    }

    private fun installUpdate(activity: Activity, prepared: PreparedAppUpdate): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", prepared.apkFile)
        activity.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            }
        )
        return true
    }

    @Composable
    override fun View(modifier: Modifier) {
        val activity = requireNotNull(LocalActivity.current)
        val state = presenter.present()
        val preparedUpdate = state.preparedUpdate.dataOrNull()
        var installRequested by rememberSaveable { mutableStateOf(false) }
        var installPermissionOpened by rememberSaveable { mutableStateOf(false) }
        LifecycleResumeEffect(preparedUpdate?.apkFile?.absolutePath, installRequested) {
            if (installRequested && preparedUpdate != null) {
                val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()
                if (allowed) {
                    installRequested = false
                    installUpdate(activity, preparedUpdate)
                } else if (!installPermissionOpened) {
                    // One permission visit per explicit install tap. A denied/cancelled
                    // return must leave About usable instead of reopening settings.
                    installPermissionOpened = true
                    installUpdate(activity, preparedUpdate)
                } else {
                    installRequested = false
                }
            }
            onPauseOrDispose { }
        }
        AboutView(
            state = state,
            onBackClick = ::navigateUp,
            onInstallUpdate = {
                installPermissionOpened = false
                installRequested = true
            },
            modifier = modifier
        )
    }
}
