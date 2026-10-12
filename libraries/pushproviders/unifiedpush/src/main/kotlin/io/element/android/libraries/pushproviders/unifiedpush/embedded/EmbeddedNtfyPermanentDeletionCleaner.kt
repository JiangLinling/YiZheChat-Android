/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.pushproviders.unifiedpush.embedded

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.pushproviders.unifiedpush.UnifiedPushStore
import io.element.android.libraries.pushstore.api.clientsecret.PushClientSecretStore
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.PermanentAccountLocalResourceCleaner

/** Removes only the deleted account's local push credentials after the server Push receipt is terminal. */
@ContributesIntoSet(AppScope::class)
class EmbeddedNtfyPermanentDeletionCleaner(
    @ApplicationContext private val context: Context,
    private val clientSecretStore: PushClientSecretStore,
    private val registrationStore: EmbeddedNtfyRegistrationStore,
    private val unifiedPushStore: UnifiedPushStore,
) : PermanentAccountLocalResourceCleaner {
    override suspend fun clear(scope: DeletionScope.Account): Boolean {
        val sessionId = UserId(scope.localSessionId)
        clientSecretStore.getSecret(sessionId)?.let { clientSecret ->
            // The Push authority receipt is already terminal at this point. Remove only local
            // account-scoped material and do not re-enter Matrix/push registration services.
            registrationStore.remove(clientSecret)
            unifiedPushStore.storeUpEndpoint(clientSecret, null)
            unifiedPushStore.storePushGateway(clientSecret, null)
        }
        unifiedPushStore.clearDistributorValue(sessionId)
        clientSecretStore.resetSecret(sessionId)
        if (registrationStore.getAll().isEmpty()) {
            EmbeddedNtfyConnectionService.stop(context)
        } else {
            EmbeddedNtfyConnectionService.start(context)
        }
        return true
    }
}
