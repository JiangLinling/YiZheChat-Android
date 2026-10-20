/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.auth

import android.util.Log
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.libraries.androidutils.crypto.ClientSecret
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.mapFailure
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.auth.AuthenticationException
import io.element.android.libraries.matrix.api.auth.ElementClassicSession
import io.element.android.libraries.matrix.api.auth.MatrixAuthenticationService
import io.element.android.libraries.matrix.api.auth.MatrixHomeServerDetails
import io.element.android.libraries.matrix.api.auth.OAuthDetails
import io.element.android.libraries.matrix.api.auth.OAuthPrompt
import io.element.android.libraries.matrix.api.auth.SessionRestorationException
import io.element.android.libraries.matrix.api.auth.external.ExternalSession
import io.element.android.libraries.matrix.api.auth.qrlogin.MatrixQrCodeLoginData
import io.element.android.libraries.matrix.api.auth.qrlogin.QrCodeLoginStep
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.libraries.matrix.impl.ClientBuilderSlidingSync
import io.element.android.libraries.matrix.impl.RustMatrixClientFactory
import io.element.android.libraries.matrix.impl.auth.qrlogin.QrErrorMapper
import io.element.android.libraries.matrix.impl.auth.qrlogin.SdkQrCodeLoginData
import io.element.android.libraries.matrix.impl.auth.qrlogin.toStep
import io.element.android.libraries.matrix.impl.exception.mapClientException
import io.element.android.libraries.matrix.impl.keys.SecretGenerator
import io.element.android.libraries.matrix.impl.mapper.toSessionData
import io.element.android.libraries.matrix.impl.paths.SessionPaths
import io.element.android.libraries.matrix.impl.paths.SessionPathsFactory
import io.element.android.libraries.matrix.impl.toSession
import io.element.android.libraries.sessionstorage.api.LoginType
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.HumanQrLoginException
import org.matrix.rustcomponents.sdk.QrCodeData
import org.matrix.rustcomponents.sdk.QrCodeDecodeException
import org.matrix.rustcomponents.sdk.QrLoginProgress
import org.matrix.rustcomponents.sdk.QrLoginProgressListener
import org.matrix.rustcomponents.sdk.SecretsBundleWithUserId
import timber.log.Timber
import uniffi.matrix_sdk.OAuthAuthorizationData
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class RustMatrixAuthenticationService(
    private val sessionPathsFactory: SessionPathsFactory,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val sessionStore: SessionStore,
    private val rustMatrixClientFactory: RustMatrixClientFactory,
    private val secretGenerator: SecretGenerator,
    private val oAuthConfigurationProvider: OAuthConfigurationProvider,
    private val enterpriseService: EnterpriseService,
) : MatrixAuthenticationService {
    // Any existing Element Classic session that we want to try to import secrets from during login.
    private var elementClassicSession: ElementClassicSession? = null

    // Passphrase which will be used for new sessions. Existing sessions will use the passphrase
    // stored in the SessionData.
    private val pendingKey by lazy { getDatabaseKey() }

    // Need to keep a copy of the current session path to eventually delete it.
    // Ideally it would be possible to get the sessionPath from the Client to avoid doing this.
    private var sessionPaths: SessionPaths? = null
    private var currentClient: Client? = null
    private val failedSessionRestorations = ConcurrentHashMap.newKeySet<String>()

    private val newMatrixClientObservers = mutableListOf<(MatrixClient) -> Unit>()
    override fun listenToNewMatrixClients(lambda: (MatrixClient) -> Unit) {
        newMatrixClientObservers.add(lambda)
    }

    private fun rotateSessionPath(): SessionPaths {
        sessionPaths?.deleteRecursively()
        return sessionPathsFactory.create()
            .also { sessionPaths = it }
    }

    override suspend fun restoreSession(sessionId: SessionId): Result<MatrixClient> = withContext(coroutineDispatchers.io) {
        var attemptedRestorableSession = false
        runCatchingExceptions {
            val sessionData = sessionStore.getSession(sessionId.value)
            if (sessionData != null) {
                if (sessionData.isTokenValid) {
                    requireConfiguredNextHomeserver(sessionData.homeserverUrl)
                    // A null key makes the Rust SDK SQLite state store (including room drafts)
                    // plaintext. There is no safe in-place migration API, so fail closed and
                    // require a new login that creates a fresh encrypted store.
                    if (sessionData.passphrase.isNullOrBlank()) {
                        sessionStore.updateData(sessionData.copy(isTokenValid = false))
                        throw IllegalStateException(
                            "This legacy session has no Matrix database key and cannot be opened in high-security mode."
                        )
                    }
                    attemptedRestorableSession = true
                    rustMatrixClientFactory.create(sessionData)
                } else {
                    throw SessionRestorationException.InvalidToken()
                }
            } else {
                throw SessionRestorationException.MissingSession(sessionId)
            }
        }.onSuccess {
            failedSessionRestorations.remove(sessionId.value)
        }.onFailure {
            if (attemptedRestorableSession) {
                failedSessionRestorations.add(sessionId.value)
            }
        }.mapFailure { failure ->
            failure.mapClientException()
        }
    }

    private fun getDatabaseKey(): ClientSecret {
        Timber.d("New sessions will be encrypted with a raw key")
        return secretGenerator.generateKey()
    }

    override suspend fun setHomeserver(homeserver: String): Result<MatrixHomeServerDetails> =
        withContext(coroutineDispatchers.io) {
            val emptySessionPath = rotateSessionPath()
            runCatchingExceptions {
                val configuredHomeserver = requireConfiguredNextHomeserver(homeserver)
                val client = makeClient(sessionPaths = emptySessionPath) {
                    serverNameOrHomeserverUrl(configuredHomeserver)
                }

                currentClient = client
                client.homeserverLoginDetails().map().also {
                    requireConfiguredNextHomeserver(it.url)
                }
            }.onFailure {
                clear()
            }.mapFailure { failure ->
                Timber.e(failure, "Failed to set homeserver to $homeserver")
                failure.mapAuthenticationException()
            }
        }

    override suspend fun login(username: String, password: String): Result<SessionId> =
        withContext(coroutineDispatchers.io) {
            runCatchingExceptions {
                val client = currentClient ?: error("You need to call `setHomeserver()` first")
                val currentSessionPaths = sessionPaths ?: error("You need to call `setHomeserver()` first")
                // The Rust SDK can invoke saveSessionInKeychain during login. Snapshot the
                // pre-login state so that callback cannot turn an invalid legacy row back
                // into an apparent duplicate before we decide whether to replace it.
                val sessionsBeforeLogin = sessionStore.getAllSessions()
                client.login(
                    username = username,
                    password = password,
                    initialDeviceName = YIZHE_ANDROID_DEVICE_NAME,
                    deviceId = null,
                )
                // Ensure that the user is not already logged in with the same account
                ensureNotAlreadyLoggedIn(client, sessionsBeforeLogin)
                tryToImportSecretForElementClassicSession(client)
                val sessionData = client.session()
                    .toSessionData(
                        isTokenValid = true,
                        loginType = LoginType.PASSWORD,
                        passphrase = pendingKey.formattedAsString(),
                        sessionPaths = currentSessionPaths,
                    )
                requireConfiguredNextHomeserver(sessionData.homeserverUrl)
                val matrixClient = loginFinalizationStage("client-create") {
                    rustMatrixClientFactory.create(client)
                }
                loginFinalizationStage("observer-notification") {
                    newMatrixClientObservers.forEach { it.invoke(matrixClient) }
                }
                loginFinalizationStage("session-persistence") {
                    persistAuthenticatedSession(sessionData)
                }

                // Clean up the strong reference held here since it's no longer necessary
                currentClient = null

                SessionId(sessionData.userId)
            }.mapFailure { failure ->
                Timber.e(failure, "Failed to login")
                failure.mapAuthenticationException()
            }
        }

    private suspend fun tryToImportSecretForElementClassicSession(client: Client) {
        elementClassicSession
            ?.takeIf {
                // Note: the SDK will also do this check
                it.userId.value == client.userId()
            }
            ?.let {
                val secrets = it.secrets
                val roomKeysVersion = it.roomKeysVersion
                if (secrets == null || roomKeysVersion == null) {
                    Timber.d("No secrets or roomKeysVersion found for Element Classic session ${it.userId}, skipping import")
                } else {
                    Timber.d("Trying to import secrets for Element Classic session ${it.userId}")
                    runCatchingExceptions {
                        SecretsBundleWithUserId.fromStr(
                            userId = it.userId.value,
                            bundle = secrets,
                            backupInfo = roomKeysVersion,
                        ).use { secretsBundle ->
                            client.encryption().importSecretsBundle(secretsBundle)
                        }
                    }.onFailure { failure ->
                        Timber.e(failure, "Failed to import secrets for Element Classic session ${it.userId}")
                    }
                }
            }
    }

    override fun doSecretsContainBackupKey(
        userId: UserId,
        secrets: String,
        backupInfo: String,
    ): Boolean {
        return try {
            SecretsBundleWithUserId.fromStr(
                userId = userId.value,
                bundle = secrets,
                backupInfo = backupInfo,
            ).use { secretsBundle ->
                secretsBundle.containsBackupKey()
            }
        } catch (failure: Exception) {
            Timber.e(failure, "Failed to parse secrets for Element Classic session $userId")
            false
        }
    }

    override suspend fun importCreatedSession(externalSession: ExternalSession): Result<SessionId> =
        withContext(coroutineDispatchers.io) {
            runCatchingExceptions {
                val client = currentClient ?: error("You need to call `setHomeserver()` first")
                val currentSessionPaths = sessionPaths ?: error("You need to call `setHomeserver()` first")
                val sessionData = externalSession.toSessionData(
                    isTokenValid = true,
                    loginType = LoginType.PASSWORD,
                    passphrase = pendingKey.formattedAsString(),
                    sessionPaths = currentSessionPaths,
                )
                requireConfiguredNextHomeserver(sessionData.homeserverUrl)

                // We restore the client using the just retrieved session data
                client.restoreSession(sessionData.toSession())
                val matrixClient = rustMatrixClientFactory.create(client)

                // We wait for the verification state to be known
                matrixClient.waitForKnownVerificationState()

                // And once it's ready we share it and save the actual session data
                newMatrixClientObservers.forEach { it.invoke(matrixClient) }
                sessionStore.addSession(rustMatrixClientFactory.protectSessionDataForStorage(sessionData))

                // Clean up the strong reference held here since it's no longer necessary
                currentClient = null

                SessionId(sessionData.userId)
            }
        }

    private var pendingOAuthAuthorizationData: OAuthAuthorizationData? = null

    override suspend fun getOAuthUrl(
        prompt: OAuthPrompt,
        loginHint: String?,
    ): Result<OAuthDetails> {
        return withContext(coroutineDispatchers.io) {
            runCatchingExceptions {
                val client = currentClient ?: error("You need to call `setHomeserver()` first")
                val oAuthAuthorizationData = client.urlForOauth(
                    oauthConfiguration = oAuthConfigurationProvider.get(),
                    prompt = prompt.toRustPrompt(),
                    loginHint = loginHint,
                    // If we want to restore a previous session for which we have encryption keys, we can pass the deviceId here. At the moment, we don't
                    deviceId = null,
                    additionalScopes = emptyList(),
                )
                val url = oAuthAuthorizationData.loginUrl()
                    .let {
                        enterpriseService.tweakMasUrl(
                            url = it,
                            homeserver = client.server() ?: client.homeserver(),
                        )
                    }
                pendingOAuthAuthorizationData = oAuthAuthorizationData
                OAuthDetails(url)
            }.mapFailure { failure ->
                Timber.e(failure, "Failed to get OAuth URL")
                failure.mapAuthenticationException()
            }
        }
    }

    override suspend fun cancelOAuthLogin(): Result<Unit> {
        return withContext(coroutineDispatchers.io) {
            runCatchingExceptions {
                pendingOAuthAuthorizationData?.use {
                    currentClient?.abortOauthAuth(it)
                }
                pendingOAuthAuthorizationData = null
            }.mapFailure { failure ->
                Timber.e(failure, "Failed to cancel OAuth login")
                failure.mapAuthenticationException()
            }
        }
    }

    override fun setElementClassicSession(session: ElementClassicSession?) {
        elementClassicSession = session
    }

    /**
     * callbackUrl should be the `url` from `OAuthAction` (with all the parameters).
     */
    override suspend fun loginWithOAuth(callbackUrl: String): Result<SessionId> {
        return withContext(coroutineDispatchers.io) {
            runCatchingExceptions {
                val client = currentClient ?: error("You need to call `setHomeserver()` first")
                val currentSessionPaths = sessionPaths ?: error("You need to call `setHomeserver()` first")
                val sessionsBeforeLogin = sessionStore.getAllSessions()
                client.loginWithOauthCallback(
                    callbackUrl = callbackUrl,
                )
                // Free the pending data since we won't use it to abort the flow anymore
                pendingOAuthAuthorizationData?.close()
                pendingOAuthAuthorizationData = null
                // Ensure that the user is not already logged in with the same account
                ensureNotAlreadyLoggedIn(client, sessionsBeforeLogin)
                tryToImportSecretForElementClassicSession(client)
                val sessionData = client.session().toSessionData(
                    isTokenValid = true,
                    loginType = LoginType.OIDC,
                    passphrase = pendingKey.formattedAsString(),
                    sessionPaths = currentSessionPaths,
                )
                requireConfiguredNextHomeserver(sessionData.homeserverUrl)
                val matrixClient = rustMatrixClientFactory.create(client)
                matrixClient.waitForKnownVerificationState()

                newMatrixClientObservers.forEach { it.invoke(matrixClient) }
                persistAuthenticatedSession(sessionData)

                // Clean up the strong reference held here since it's no longer necessary
                currentClient = null

                SessionId(sessionData.userId)
            }.mapFailure { failure ->
                Timber.e(failure, "Failed to login with OAuth")
                failure.mapAuthenticationException()
            }
        }
    }

    @Throws(AuthenticationException.AccountAlreadyLoggedIn::class)
    private suspend fun ensureNotAlreadyLoggedIn(client: Client, sessionsBeforeLogin: List<SessionData>) {
        val newUserId = client.userId()
        val existingSession = sessionsBeforeLogin.firstOrNull { it.userId == newUserId }
        val existingSessionCanBeRestored = existingSession?.isTokenValid == true &&
            !existingSession.passphrase.isNullOrBlank() &&
            existingSession.userId !in failedSessionRestorations
        if (existingSessionCanBeRestored) {
            // Sign out the client, ignoring any error
            runCatchingExceptions {
                client.logout()
            }
            throw AuthenticationException.AccountAlreadyLoggedIn(newUserId)
        }
        // A soft-logged-out account must be re-authenticated in place. Rejecting
        // it as a duplicate leaves the user trapped on the login screen while
        // the unusable local row remains present.
    }

    private suspend fun persistAuthenticatedSession(sessionData: SessionData) {
        val protectedSession = rustMatrixClientFactory.protectSessionDataForStorage(sessionData)
        // A successful explicit login owns a new Matrix session. Ordinary SDK updates
        // deliberately cannot replace a device or revive an invalidated login.
        // Install the authenticated session atomically, without an intermediate logout.
        sessionStore.storeAuthenticatedSession(protectedSession)
        failedSessionRestorations.remove(sessionData.userId)
    }

    /**
     * Emits a deliberately redacted Release diagnostic for failures that happen after the
     * homeserver has accepted the credentials. Never include account or session material here.
     */
    private suspend fun <T> loginFinalizationStage(stage: String, block: suspend () -> T): T = try {
        block()
    } catch (failure: Throwable) {
        Log.e(LOGIN_DIAGNOSTIC_TAG, "Login finalization failed at $stage: ${failure.javaClass.name}")
        throw failure
    }

    override suspend fun loginWithQrCode(qrCodeData: MatrixQrCodeLoginData, progress: (QrCodeLoginStep) -> Unit) =
        withContext(coroutineDispatchers.io) {
            val sdkQrCodeLoginData = (qrCodeData as SdkQrCodeLoginData).rustQrCodeData
            val emptySessionPaths = rotateSessionPath()
            val oAuthConfiguration = oAuthConfigurationProvider.get()
            val progressListener = object : QrLoginProgressListener {
                override fun onUpdate(state: QrLoginProgress) {
                    Timber.d("QR Code login progress: $state")
                    progress(state.toStep())
                }
            }
            runCatchingExceptions {
                val client = makeQrCodeLoginClient(
                    sessionPaths = emptySessionPaths,
                    qrCodeData = sdkQrCodeLoginData,
                )
                val sessionsBeforeLogin = sessionStore.getAllSessions()
                client.newLoginWithQrCodeHandler(
                    oauthConfiguration = oAuthConfiguration,
                ).use {
                    it.scan(
                        qrCodeData = qrCodeData.rustQrCodeData,
                        progressListener = progressListener,
                    )
                }
                // Ensure that the user is not already logged in with the same account
                ensureNotAlreadyLoggedIn(client, sessionsBeforeLogin)
                val sessionData = client.session()
                    .toSessionData(
                        isTokenValid = true,
                        loginType = LoginType.QR,
                        passphrase = pendingKey.formattedAsString(),
                        sessionPaths = emptySessionPaths,
                    )
                requireConfiguredNextHomeserver(sessionData.homeserverUrl)
                val matrixClient = rustMatrixClientFactory.create(client)
                newMatrixClientObservers.forEach { it.invoke(matrixClient) }
                persistAuthenticatedSession(sessionData)

                // Clean up the strong reference held here since it's no longer necessary
                currentClient = null

                SessionId(sessionData.userId)
            }.mapFailure {
                when (it) {
                    is QrCodeDecodeException -> QrErrorMapper.map(it)
                    is HumanQrLoginException -> QrErrorMapper.map(it)
                    else -> it
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                Timber.e(throwable, "Failed to login with QR code")
            }
        }

    private suspend fun makeClient(
        sessionPaths: SessionPaths,
        config: suspend ClientBuilder.() -> ClientBuilder,
    ): Client {
        Timber.d("Creating client with simplified sliding sync")
        return rustMatrixClientFactory
            .buildClient(
                sessionPaths = sessionPaths,
                clientSecret = pendingKey,
                slidingSyncType = ClientBuilderSlidingSync.Discovered,
                config = config,
            )
    }

    private suspend fun makeQrCodeLoginClient(
        sessionPaths: SessionPaths,
        qrCodeData: QrCodeData,
    ): Client {
        Timber.d("Creating client for QR Code login with simplified sliding sync")
        // The 2025 version of MSC4108 provides baseUrl; the 2024 version has null baseUrl and uses
        // serverName instead, which can be null or malformed. We only enforce presence/non-blankness
        // here and rely on serverNameOrHomeserverUrl()/the Rust builder layer to validate structure.
        val baseUrlOrServerName = qrCodeData.baseUrl() ?: qrCodeData.serverName()

        if (baseUrlOrServerName == null) {
            // With the 2024 version of MSC4108 we treat the absence of serverName as meaning that
            // the other device is not signed in.
            Timber.e("The QR code is from a device that is not yet signed in")
            throw HumanQrLoginException.OtherDeviceNotSignedIn()
        }

        if (baseUrlOrServerName.isBlank()) {
            Timber.e("The QR code contains an empty base URL or server name, which is invalid")
            throw HumanQrLoginException.Unknown()
        }

        val configuredHomeserver = requireConfiguredNextHomeserver(
            candidate = baseUrlOrServerName,
            allowServerName = qrCodeData.baseUrl() == null,
        )

        return rustMatrixClientFactory
            .buildClient(
                sessionPaths = sessionPaths,
                clientSecret = pendingKey,
                slidingSyncType = ClientBuilderSlidingSync.Discovered,
            ) {
                serverNameOrHomeserverUrl(configuredHomeserver)
            }
    }

    private fun requireConfiguredNextHomeserver(candidate: String, allowServerName: Boolean = false): String =
        enforceNextHomeserverRoute(
            candidate = candidate,
            configuredHomeserver = NextDeploymentConfig.matrixHomeserverUrl,
            isNextDeployment = NextDeploymentConfig.isNextDeployment,
            allowServerName = allowServerName,
        )

    private fun clear() {
        currentClient?.let {
            rustMatrixClientFactory.discardPendingClient(it)
            it.close()
        }
        currentClient = null
    }

    private companion object {
        const val LOGIN_DIAGNOSTIC_TAG = "YiZheAuth"
    }

    private suspend fun MatrixClient.waitForKnownVerificationState() {
        withTimeoutOrNull(10.seconds) {
            Timber.d("Waiting for a known verification status...")
            val status = sessionVerificationService.sessionVerifiedStatus.first { it != SessionVerifiedStatus.Unknown }
            Timber.d("Finished waiting for a known verification status: $status")
        } ?: Timber.w("Timed out waiting for a known verification status")
    }
}

internal const val YIZHE_ANDROID_DEVICE_NAME = "乙者 Android"

/**
 * Locks every Next authentication and restoration route to the build-time homeserver.
 * Returning the configured URL also prevents a differently-spelled accepted input from being
 * passed to discovery. Stable builds preserve their existing user-selectable behavior.
 */
internal fun enforceNextHomeserverRoute(
    candidate: String,
    configuredHomeserver: String,
    isNextDeployment: Boolean,
    allowServerName: Boolean = false,
): String {
    if (!isNextDeployment) return candidate

    val configured = parseLockedHomeserver(configuredHomeserver)
    val candidateUri = runCatching { URI(candidate) }.getOrNull()
    val candidateIsServerName = candidateUri?.scheme == null
    val candidateRoute = if (candidateIsServerName && allowServerName) {
        val serverName = runCatching { URI("matrix://$candidate") }.getOrNull()
        check(
            configured.path == "/" &&
                serverName?.host != null &&
                serverName.userInfo == null && serverName.path.isEmpty() &&
                serverName.query == null && serverName.fragment == null
        ) { NEXT_HOMESERVER_REJECTION }
        LockedHomeserver(
            host = requireNotNull(serverName.host).lowercase(Locale.US),
            port = normalizedHttpsPort(serverName.port),
            path = "/",
        )
    } else {
        parseLockedHomeserver(candidate)
    }
    check(candidateRoute == configured) { NEXT_HOMESERVER_REJECTION }
    return configuredHomeserver.trimEnd('/')
}

private data class LockedHomeserver(
    val host: String,
    val port: Int,
    val path: String,
)

private fun parseLockedHomeserver(value: String): LockedHomeserver {
    val uri = runCatching { URI(value) }.getOrNull()
    check(
        uri?.scheme?.lowercase(Locale.US) == "https" && uri.host != null &&
            uri.userInfo == null && uri.query == null && uri.fragment == null
    ) { NEXT_HOMESERVER_REJECTION }
    val normalizedPath = requireNotNull(uri).normalize().rawPath.orEmpty().trimEnd('/').ifEmpty { "/" }
    check(uri.rawPath.orEmpty().trimEnd('/').ifEmpty { "/" } == normalizedPath) { NEXT_HOMESERVER_REJECTION }
    return LockedHomeserver(
        host = requireNotNull(uri.host).lowercase(Locale.US),
        port = normalizedHttpsPort(uri.port),
        path = normalizedPath,
    )
}

private fun normalizedHttpsPort(port: Int): Int = if (port == -1) 443 else port

private const val NEXT_HOMESERVER_REJECTION = "Homeserver is outside the configured Next deployment."
