/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.product

import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import dev.zacsweers.metro.Inject
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.mediaviewer.impl.segmented.continuous.ContinuousVideoPlaybackOwner
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.timeline.item.event.SegmentedVideoCandidate
import io.element.android.libraries.matrix.api.timeline.item.event.SegmentedVideoResource
import io.element.android.libraries.mediaviewer.impl.segmented.player.CoordinatorSegmentedVideoReplacementOwner
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.LocalAuthenticatedSegmentBridge
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.ProviderBackedLocalSegmentLeaseIssuer
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedAuthorizedResourceHandle
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedAuthorizedResourceProvider
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedAuthorizedResourceRequest
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedDecoderLifecycle
import io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedSeekReplacementCoordinator
import io.element.android.libraries.mediaviewer.impl.segmented.player.Media3SegmentedVideoPlayerSink
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoPlayerCloseReceipt
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoPlayerController
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoAdvanceStart
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoPlayerSource
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoSeekStart
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoTimelineSnapshot
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMatrixMediaLocatorPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaAuthorizationDecisionPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaAuthorizationGatePrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaManifestPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaManifestValidatorPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaReadContextPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaResourceKindPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaResourcePrototype
import io.element.android.security.contentprotection.api.SegmentedMatrixEncryptedFile
import io.element.android.security.contentprotection.api.SegmentedProtectedMediaAcquisitionRequest
import io.element.android.security.contentprotection.api.SegmentedProtectedMediaLocator
import io.element.android.security.contentprotection.api.SegmentedProtectedMediaResource
import io.element.android.security.contentprotection.api.SegmentedProtectedMediaResourceAcquirer
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

@Inject
class SegmentedVideoPlaybackOwnerFactory(
    private val acquirer: SegmentedProtectedMediaResourceAcquirer,
    private val dispatchers: CoroutineDispatchers,
    private val systemClock: SystemClock,
    private val featureFlagService: FeatureFlagService,
) {
    suspend fun create(candidate: SegmentedVideoCandidate, displayName: String?): MediaFile {
        val continuousWindowSupported = candidate.segments.all { it.durationUs in 1..6_000_000L }
        val owner: ProtectedSegmentedVideoOwner = if (featureFlagService.isFeatureEnabled(FeatureFlags.ContinuousProtectedVideoPlayback) && continuousWindowSupported) {
            ContinuousVideoPlaybackOwner.open(candidate, displayName, acquirer, dispatchers, systemClock)
        } else {
            SegmentedVideoPlaybackOwner.open(candidate, displayName, acquirer, dispatchers, systemClock)
        }
        return SegmentedVideoPlaybackMediaFile(owner)
    }
}

internal object SegmentedVideoPlaybackRegistry {
    private val owners = ConcurrentHashMap<String, ProtectedSegmentedVideoOwner>()

    fun register(owner: ProtectedSegmentedVideoOwner): Uri {
        val id = UUID.randomUUID().toString()
        check(owners.putIfAbsent(id, owner) == null)
        return Uri.Builder().scheme(SCHEME).authority("local").appendPath(id).build()
    }

    fun resolve(uri: Uri): ProtectedSegmentedVideoOwner? =
        uri.takeIf { it.scheme == SCHEME }?.lastPathSegment?.let(owners::get)

    fun remove(uri: Uri, owner: ProtectedSegmentedVideoOwner) {
        uri.lastPathSegment?.let { owners.remove(it, owner) }
    }

    const val SCHEME = "yizhe-segmented-video"
}

internal interface ProtectedSegmentedVideoOwner {
    val continuous: Boolean get() = false
    val uri: Uri
    suspend fun attach(player: ExoPlayer): SegmentedVideoAttachment
    suspend fun closeAndAwait()
}

internal interface SegmentedVideoAttachment {
    fun seek(targetTimeUs: Long): SegmentedVideoSeekStart
    fun onFragmentEnded(mediaId: String?): SegmentedVideoAdvanceStart
    fun timelineSnapshot(): SegmentedVideoTimelineSnapshot?
    fun failureFlow(): kotlinx.coroutines.flow.StateFlow<Throwable?>? = null
    suspend fun closeAndAwait()
}

internal class SegmentedVideoPlaybackOwner private constructor(
    private val coordinator: SegmentedSeekReplacementCoordinator,
    private val initialSource: SegmentedVideoPlayerSource,
    private val dispatchers: CoroutineDispatchers,
    private val decoderOwner: Media3SegmentedDecoderOwner,
) : ProtectedSegmentedVideoOwner {
    private val mutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + dispatchers.computation)
    private var controller: SegmentedVideoPlayerController? = null
    private var playerSink: Media3SegmentedVideoPlayerSink? = null
    private var closeSignal: CompletableDeferred<Unit>? = null
    override val uri: Uri = SegmentedVideoPlaybackRegistry.register(this)

    override suspend fun attach(player: ExoPlayer): SegmentedVideoAttachment = mutex.withLock {
        check(closeSignal == null) { "Segmented video owner is closing." }
        check(controller == null) { "Segmented video owner is already attached." }
        val sink = Media3SegmentedVideoPlayerSink(player)
        decoderOwner.bind(sink)
        sink.install(initialSource)
        val installed = SegmentedVideoPlayerController(
            replacementOwner = CoordinatorSegmentedVideoReplacementOwner(coordinator),
            sink = sink,
            initialSource = initialSource,
            operationDispatcher = dispatchers.main,
            cleanupDispatcher = dispatchers.computation,
        )
        playerSink = sink
        controller = installed
        LegacySegmentedVideoAttachment(this, installed)
    }

    override suspend fun closeAndAwait() {
        val eagerQuiescenceFailure = runCatching {
            playerSink?.quiesceIfOnOwnerThread()
        }.exceptionOrNull()
        val signal = mutex.withLock {
            closeSignal?.let { return@withLock it }
            CompletableDeferred<Unit>().also { created ->
                closeSignal = created
                val attachedController = controller
                cleanupScope.launch {
                    val failures = mutableListOf<Throwable>()
                    eagerQuiescenceFailure?.let(failures::add)
                    try {
                        if (attachedController != null) {
                            val receipt = attachedController.closeAndAwait()
                            if (!receipt.succeeded) {
                                failures += receipt.toFailure()
                            }
                        } else {
                            val receipt = coordinator.terminateAndAwait()
                            if (!receipt.succeeded) {
                                failures += IllegalStateException("Segmented coordinator cleanup was incomplete.").also { terminal ->
                                    receipt.failures.forEach { terminal.addSuppressed(it.failure) }
                                    receipt.publishedSessionCloseReceipt?.failures?.forEach(terminal::addSuppressed)
                                }
                            }
                        }
                    } catch (failure: Throwable) {
                        failures += failure
                    } finally {
                        SegmentedVideoPlaybackRegistry.remove(uri, this@SegmentedVideoPlaybackOwner)
                    }
                    if (failures.isEmpty()) {
                        created.complete(Unit)
                    } else {
                        created.completeExceptionally(
                            IllegalStateException("Segmented video cleanup was incomplete.").also { terminal ->
                                failures.forEach(terminal::addSuppressed)
                            }
                        )
                    }
                }
            }
        }
        signal.await()
    }

    companion object {
        suspend fun open(
            candidate: SegmentedVideoCandidate,
            displayName: String?,
            acquirer: SegmentedProtectedMediaResourceAcquirer,
            dispatchers: CoroutineDispatchers,
            systemClock: SystemClock,
        ): SegmentedVideoPlaybackOwner {
            val validated = SegmentedMediaManifestValidatorPrototype.validate(candidate.toPrototype())
            check(candidate.expiresAtEpochMillis > systemClock.epochMillis()) { "Segmented video manifest is expired." }
            val ownerToken = UUID.randomUUID().toString()
            val initialContext = SegmentedMediaReadContextPrototype(ownerToken, UUID.randomUUID().toString(), 1L)
            val gate = candidate.authorizationGate(ownerToken, systemClock)
            val provider = candidate.resourceProvider(acquirer, displayName, systemClock)
            val init = provider.acquire(
                SegmentedAuthorizedResourceRequest(
                    role = io.element.android.libraries.mediaviewer.impl.segmented.bridge.CandidateResourceRole.INIT,
                    manifestIdentity = io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedManifestIdentityFactory.create(validated),
                    descriptor = validated.manifest.init,
                    context = initialContext,
                )
            )
            val segment = try {
                provider.acquire(
                    SegmentedAuthorizedResourceRequest(
                        role = io.element.android.libraries.mediaviewer.impl.segmented.bridge.CandidateResourceRole.SEGMENT,
                        manifestIdentity = io.element.android.libraries.mediaviewer.impl.segmented.bridge.SegmentedManifestIdentityFactory.create(validated),
                        descriptor = validated.manifest.segments.first(),
                        context = initialContext,
                    )
                )
            } catch (failure: Throwable) {
                init.lease.close()
                throw failure
            }
            val bridge = LocalAuthenticatedSegmentBridge(dispatchers.io, dispatchers.computation)
            val decoderOwner = Media3SegmentedDecoderOwner()
            val session = bridge.prepare(validated, 0, init, segment, initialContext, gate, decoderOwner.lifecycle())
            val coordinator = SegmentedSeekReplacementCoordinator(
                initialSession = session,
                leaseIssuer = ProviderBackedLocalSegmentLeaseIssuer(provider),
                sessionPreparer = bridge,
                authorizationGate = gate,
                decoderLifecycleFactory = decoderOwner::lifecycle,
                operationDispatcher = dispatchers.io,
                cleanupDispatcher = dispatchers.computation,
                freshLeaseId = { UUID.randomUUID().toString() },
            )
            return SegmentedVideoPlaybackOwner(
                coordinator,
                SegmentedVideoPlayerSource(
                    cursor = session.cursor(0L),
                    uri = session.uri,
                    dataSourceFactory = session.dataSourceFactory(),
                    startPositionUs = 0L,
                ),
                dispatchers,
                decoderOwner,
            )
        }
    }
}

private class LegacySegmentedVideoAttachment(
    private val owner: SegmentedVideoPlaybackOwner,
    private val controller: SegmentedVideoPlayerController,
) : SegmentedVideoAttachment {
    override fun seek(targetTimeUs: Long): SegmentedVideoSeekStart = controller.seek(targetTimeUs)
    override fun onFragmentEnded(mediaId: String?): SegmentedVideoAdvanceStart = controller.onFragmentEnded(mediaId)
    override fun timelineSnapshot(): SegmentedVideoTimelineSnapshot? = controller.timelineSnapshot()
    override suspend fun closeAndAwait() = owner.closeAndAwait()
}

private class SegmentedVideoPlaybackMediaFile(
    private val owner: ProtectedSegmentedVideoOwner,
) : MediaFile {
    private val closed = AtomicBoolean()

    override fun path(): String = error("Segmented protected video has no plaintext filesystem path.")
    override fun uri(): Uri = owner.uri
    override fun uriString(): String = owner.uri.toString()
    override fun openInputStream(): InputStream = error("Segmented protected video is decoder-only.")
    override fun persist(path: String): Boolean = false
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runBlocking { owner.closeAndAwait() }
        }
    }
}

internal fun SegmentedVideoCandidate.toPrototype() = SegmentedMediaManifestPrototype(
    schema = schema,
    assetId = assetId,
    mediaVersion = mediaVersion,
    roomIdHash = roomIdSha256Hex,
    contentContractId = contentContractId,
    expiresAtMs = expiresAtEpochMillis,
    container = container,
    codecs = codecs,
    durationUs = durationUs,
    totalPlainBytes = totalPlainBytes,
    init = init.toPrototype(),
    segments = segments.map { it.toPrototype() },
    resourceCount = resourceCount,
)

private fun SegmentedVideoResource.toPrototype() = SegmentedMediaResourcePrototype(
    resourceId = resourceId,
    contentId = contentId,
    kind = SegmentedMediaResourceKindPrototype.valueOf(kind),
    index = index,
    startUs = startUs,
    durationUs = durationUs,
    plainLength = plainLength,
    innerContextDigestSha256 = immutableContextSha256Hex,
    locator = SegmentedMatrixMediaLocatorPrototype(
        authority = locator.authority,
        mxcUri = locator.mxcUri,
        outerCipherSha256 = locator.outerCipherSha256Hex,
        cipherLength = locator.outerCipherLength,
    ),
    declaredIndependentRandomAccess = declaredIndependentRandomAccess,
)

internal fun SegmentedVideoCandidate.resourceProvider(
    acquirer: SegmentedProtectedMediaResourceAcquirer,
    displayName: String?,
    systemClock: SystemClock,
): SegmentedAuthorizedResourceProvider {
    val resources = (listOf(init) + segments).associateBy(SegmentedVideoResource::resourceId)
    return SegmentedAuthorizedResourceProvider { request ->
        val source = requireNotNull(resources[request.descriptor.resourceId])
        require(source.toPrototype() == request.descriptor)
        val remaining = expiresAtEpochMillis - systemClock.epochMillis()
        require(remaining > 0L) { "Segmented video manifest expired during acquisition." }
        val acquisition = acquirer.acquire(
            SegmentedProtectedMediaAcquisitionRequest(
                resource = source.toProtectedResource(this),
                acquisitionId = listOf(
                    request.context.ownerToken,
                    request.context.leaseId,
                    request.context.generation,
                    source.resourceId,
                ).joinToString(":"),
                displayName = displayName,
                timeToLive = minOf(remaining.milliseconds, 1.hours),
            )
        )
        SegmentedAuthorizedResourceHandle(request.descriptor, acquisition.lease)
    }
}

private fun SegmentedVideoResource.toProtectedResource(candidate: SegmentedVideoCandidate) =
    SegmentedProtectedMediaResource(
        assetId = candidate.assetId,
        mediaVersion = candidate.mediaVersion.toLong(),
        roomIdSha256Hex = candidate.roomIdSha256Hex,
        contentContractId = candidate.contentContractId,
        contentPolicyVersion = candidate.contentPolicyVersion,
        resourceId = resourceId,
        contentId = contentId,
        kind = kind,
        protectedContentKind = protectedContentKind,
        index = index,
        expectedPlaintextLength = plainLength,
        immutableContextSha256Hex = immutableContextSha256Hex,
        locator = SegmentedProtectedMediaLocator(
            mxcUri = locator.mxcUri,
            expectedOuterSha256Hex = locator.outerCipherSha256Hex,
            expectedOuterLength = locator.outerCipherLength,
            encryptedFile = SegmentedMatrixEncryptedFile(
                version = locator.encryptedFile.version,
                algorithm = locator.encryptedFile.algorithm,
                keyType = locator.encryptedFile.keyType,
                keyOperations = locator.encryptedFile.keyOperations,
                extractable = locator.encryptedFile.extractable,
                keyBase64Url = locator.encryptedFile.keyBase64Url,
                ivBase64 = locator.encryptedFile.ivBase64,
            ),
        ),
    )

internal fun SegmentedVideoCandidate.authorizationGate(
    ownerToken: String,
    systemClock: SystemClock,
) = SegmentedMediaAuthorizationGatePrototype(
    validator = { context, nowEpochMillis, _ ->
        when {
            context.ownerToken != ownerToken -> SegmentedMediaAuthorizationDecisionPrototype.OWNER_MISMATCH
            nowEpochMillis >= expiresAtEpochMillis -> SegmentedMediaAuthorizationDecisionPrototype.EXPIRED
            else -> SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED
        }
    },
    wallClock = { systemClock.epochMillis() },
    elapsedClock = { android.os.SystemClock.elapsedRealtime() },
)

private class Media3SegmentedDecoderOwner {
    private val sink = AtomicReference<Media3SegmentedVideoPlayerSink?>()

    fun bind(value: Media3SegmentedVideoPlayerSink) {
        check(sink.compareAndSet(null, value)) { "Segmented decoder owner is already bound." }
    }

    fun lifecycle(): SegmentedDecoderLifecycle = object : SegmentedDecoderLifecycle {
        override fun beginStop() {
            sink.get()?.quiesceCurrentSourceAndAwait()
        }

        override fun flushAndRelease() {
            // beginStop already crossed a Media3 playback-thread command barrier.
        }
    }
}

private fun SegmentedVideoPlayerCloseReceipt.toFailure(): Throwable =
    IllegalStateException("Segmented video player cleanup was incomplete.").also { terminal ->
        sinkFailure?.let(terminal::addSuppressed)
        coordinator.failures.forEach { terminal.addSuppressed(it.failure) }
        coordinator.activeReplacementReceipt?.failures?.forEach { terminal.addSuppressed(it.failure) }
        coordinator.publishedSessionCloseReceipt?.failures?.forEach(terminal::addSuppressed)
    }
