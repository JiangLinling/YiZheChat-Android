/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.element.android.features.messages.api.MessageReferenceState
import io.element.android.features.messages.api.MessageReferenceStore
import io.element.android.features.messages.impl.messagecomposer.ordinaryMessageLifecycleRequest
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.di.LiveTimeline
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.encryption.HiddenMessageRecoveryInventory
import io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.IntentionalMention
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.PendingSendLifetime
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.TimelineProvider
import io.element.android.libraries.matrix.api.timeline.item.TimelineItemDebugInfo
import io.element.android.libraries.matrix.api.timeline.item.event.EventTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.FormattedBody
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageFormat
import io.element.android.libraries.matrix.api.timeline.item.event.MessageShield
import io.element.android.libraries.matrix.api.timeline.item.event.MessageShieldProvider
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMediaAlbum
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.RedactedContent
import io.element.android.libraries.matrix.api.timeline.item.event.SendHandleProvider
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.TimelineItemDebugInfoProvider
import io.element.android.libraries.matrix.api.timeline.item.event.TimelineItemEventOrigin
import io.element.android.libraries.matrix.api.timeline.item.event.UnableToDecryptContent
import io.element.android.libraries.matrix.api.timeline.item.event.UtdCause
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.isAuthorizedForUserDisplay
import io.element.android.libraries.matrix.api.timeline.item.virtual.VirtualTimelineItem
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.security.contentprotection.api.DisabledOfflineOutboxService
import io.element.android.security.contentprotection.api.OfflineOutboxService
import io.element.android.security.contentprotection.api.OfflineTextOutboxMessage
import io.element.android.security.dataprotection.api.AccountHistoryAccessBoundary
import io.element.android.security.dataprotection.api.AccountHistoryAccessBoundaryProvider
import io.element.android.security.dataprotection.api.LifecycleStageTiming
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import io.element.android.libraries.matrix.api.media.AudioDetails
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Duration
import java.io.Closeable
import java.util.Optional
import java.util.UUID
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.performMessageWithDeferredReceipts
import java.util.concurrent.ConcurrentHashMap
import io.element.android.libraries.matrix.api.room.SendQueueUpdate
import io.element.android.libraries.matrix.api.timeline.protectedTextTransactionId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect

/**
 * This controller is responsible of using the right timeline to display messages and make associated actions.
 * It can be focused on the live timeline or on a detached timeline (focusing an unknown event).
 */
@SingleIn(RoomScope::class)
@ContributesBinding(RoomScope::class, binding = binding<TimelineProvider>())
class TimelineController(
    private val room: JoinedRoom,
    @LiveTimeline private val liveTimeline: Timeline,
    private val hiddenMessageRecoveryInventory: HiddenMessageRecoveryInventory = HiddenMessageRecoveryInventory.Noop,
    private val requireContentProtection: Boolean = false,
    private val offlineOutboxService: OfflineOutboxService = DisabledOfflineOutboxService,
    private val accountHistoryAccessBoundaryProvider: AccountHistoryAccessBoundaryProvider =
        AccountHistoryAccessBoundaryProvider.Noop,
    private val messageReferenceStore: MessageReferenceStore? = null,
    private val matrixClient: MatrixClient? = null,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator? = null,
) : Closeable, TimelineProvider {
    @Inject
    constructor(
        room: JoinedRoom,
        @LiveTimeline liveTimeline: Timeline,
        hiddenMessageRecoveryInventory: HiddenMessageRecoveryInventory,
        offlineOutboxService: OfflineOutboxService,
        accountHistoryAccessBoundaryProvider: AccountHistoryAccessBoundaryProvider,
        messageReferenceStore: MessageReferenceStore,
        matrixClient: MatrixClient,
        appOperationalStateCoordinator: AppOperationalStateCoordinator,
    ) : this(
        room = room,
        liveTimeline = liveTimeline,
        hiddenMessageRecoveryInventory = hiddenMessageRecoveryInventory,
        requireContentProtection = true,
        offlineOutboxService = offlineOutboxService,
        accountHistoryAccessBoundaryProvider = accountHistoryAccessBoundaryProvider,
        messageReferenceStore = messageReferenceStore,
        matrixClient = matrixClient,
        appOperationalStateCoordinator = appOperationalStateCoordinator,
    )

    private val coroutineScope = CoroutineScope(SupervisorJob())

    private val liveTimelineFlow = flowOf(liveTimeline)
    private val detachedTimelineFlow = MutableStateFlow<Optional<Timeline>>(Optional.empty())
    private val optimisticLocalEchoes = MutableStateFlow<List<OptimisticLocalEcho>>(emptyList())
    private val stagedComposerIntents = ConcurrentHashMap.newKeySet<UniqueId>()
    private val retryingDisplayIds = ConcurrentHashMap.newKeySet<UniqueId>()
    private val pendingMediaSendPresentations = MutableStateFlow<List<PendingMediaSendPresentation>>(emptyList())
    private val confirmedDisplayIds = mutableMapOf<String, UniqueId>()
    private val confirmedTransactionDisplayIds = mutableMapOf<TransactionId, UniqueId>()
    private val confirmedEventIds = mutableMapOf<UniqueId, EventId>()
    private val confirmedServerIdentities = mutableMapOf<UniqueId, String>()
    private var confirmedIdentityGeneration = 0L
    private val canonicalTimestamps = mutableMapOf<UniqueId, Long>()
    private val retiredOptimisticDisplayIds = mutableSetOf<UniqueId>()
    private val localReferenceState = messageReferenceStore?.state ?: MutableStateFlow(MessageReferenceState())
    private val localReferenceStateReady = MutableStateFlow(messageReferenceStore == null)

    suspend fun lastViewedPosition(): EventId? {
        localReferenceStateReady.first { it }
        return localReferenceState.value.lastViewedEventIds[room.roomId.value]?.let(::EventId)
    }

    suspend fun saveLastViewedPosition(eventId: EventId) {
        messageReferenceStore?.saveLastViewedPosition(room.roomId, eventId)
    }

    suspend fun readingNavigation(): io.element.android.features.messages.api.RoomReadingNavigation {
        localReferenceStateReady.first { it }
        return localReferenceState.value.readingNavigation[room.roomId.value]
            ?: io.element.android.features.messages.api.RoomReadingNavigation()
    }

    suspend fun saveReadingNavigation(position: io.element.android.features.messages.api.RoomReadingNavigation) {
        messageReferenceStore?.saveReadingNavigation(room.roomId, position)
    }

    fun hasEarlierHistory(): Boolean = currentTimelineFlow.value.backwardPaginationStatus.value.hasMoreToLoad

    init {
        coroutineScope.launch { restoreEncryptedOutbox() }
        observeTextSendConfirmations()
        messageReferenceStore?.let { store ->
            coroutineScope.launch {
                runCatching { store.load() }
                    .onSuccess { localReferenceStateReady.value = true }
                    .onFailure { failure ->
                        hiddenMessageRecoveryInventory.updateRecoveryStatus(HistoryRecoveryStatus.FAILED_CLOSED)
                        Timber.tag("YiZheHistoryRecovery").e(failure, "Local history suppression index could not be loaded")
                    }
            }
        }
    }

    private fun observeTextSendConfirmations() {
        val coordinator = appOperationalStateCoordinator ?: return
        if (!requireContentProtection) return
        // This small queue signal is independent of history mapping and re-decryption. If the
        // optional observer is unavailable, the existing authenticated remote handover remains.
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            room.subscribeToSendQueueUpdates().catch { failure ->
                if (failure is CancellationException) throw failure
                Timber.w("TEXT send confirmation observer unavailable")
            }.collect { update ->
                if (update !is SendQueueUpdate.SentEvent) return@collect
                val owned = optimisticLocalEchoes.value.singleOrNull {
                    it.confirmationBinding?.sdkTransactionId == update.transactionId
                } ?: return@collect
                if (owned.nativeConfirmedEventId != null) return@collect
                val operationId = checkNotNull(owned.confirmationBinding).request.operationId
                LifecycleStageTiming.point("send_queue_confirmed", update.eventId.value, operationId = operationId)
                optimisticLocalEchoes.update { echoes -> echoes.map { echo ->
                    if (echo.item.uniqueId == owned.item.uniqueId) echo.copy(nativeConfirmedEventId = update.eventId) else echo
                } }
                // Only read this original signed status. The bounded refresh closes an early
                // snapshot race; normal remote handover remains the fallback after its deadline.
                coroutineScope.launch {
                    runCatchingExceptions {
                        refreshTextSendConfirmation(checkNotNull(owned.confirmationBinding), update.eventId,
                            isPending = {
                                optimisticLocalEchoes.value.any { echo ->
                                    echo.item.uniqueId == owned.item.uniqueId && !isOptimisticDisplayIdRetired(echo.item.uniqueId) &&
                                        echo.item.event.localSendState !is LocalEventSendState.Sent &&
                                        echo.item.event.localSendState !is LocalEventSendState.Failed
                                }
                            },
                        ) {
                            LifecycleStageTiming.measure("send_confirmation_status_read", update.eventId.value, operationId = operationId) {
                                coordinator.operation(operationId)
                            }
                        }
                    }
                }
            }
        }
        coroutineScope.launch {
            combine(optimisticLocalEchoes, coordinator.operations) { echoes, operations ->
                echoes.mapNotNull { echo ->
                    val binding = echo.confirmationBinding ?: return@mapNotNull null
                    val eventId = echo.nativeConfirmedEventId ?: return@mapNotNull null
                    if (echo.item.event.localSendState is LocalEventSendState.Sent ||
                        echo.item.event.localSendState is LocalEventSendState.Failed ||
                        !binding.confirms(eventId, operations[binding.request.operationId] ?: return@mapNotNull null)
                    ) return@mapNotNull null
                    echo.item.uniqueId to eventId
                }
            }.collect { confirmations ->
                if (confirmations.isEmpty()) return@collect
                val confirmed = confirmations.toMap()
                optimisticLocalEchoes.update { echoes -> echoes.map { echo ->
                    val eventId = confirmed[echo.item.uniqueId] ?: return@map echo
                    if (isOptimisticDisplayIdRetired(echo.item.uniqueId) ||
                        echo.item.event.localSendState is LocalEventSendState.Failed) return@map echo
                    val item = echo.item.copy(event = echo.item.event.copy(
                        eventId = eventId, localSendState = LocalEventSendState.Sent(eventId),
                    ))
                    rememberConfirmedDisplayId(item, item.uniqueId)
                    LifecycleStageTiming.observed("send_confirmation_projected", eventId.value,
                        operationId = echo.confirmationBinding?.request?.operationId.orEmpty())
                    synchronized(confirmedDisplayIds) {
                        val sdkTransaction = checkNotNull(echo.confirmationBinding).sdkTransactionId
                        if (confirmedTransactionDisplayIds[sdkTransaction] == null) {
                            confirmedTransactionDisplayIds[sdkTransaction] = item.uniqueId
                            confirmedIdentityGeneration++
                        }
                    }
                    echo.copy(item = item)
                } }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun timelineItems(): Flow<List<MatrixTimelineItem>> {
        return combine(
            currentTimelineFlow.flatMapLatest { timeline ->
                combine(
                    timeline.timelineItems,
                    timeline.backwardPaginationStatus.map { !it.hasMoreToLoad }.distinctUntilChanged(),
                ) { items, historyStartReached ->
                    LoadedTimelineWindow(items, historyStartReached)
                }
            },
            optimisticLocalEchoes,
            localReferenceState,
            localReferenceStateReady,
        ) { window, localEchoes, messageReferenceState, referenceStateReady ->
            val timelineItems = window.items
            val identityItems = timelineItems.coalesceDuplicateRemoteEvents()
            val projectionItems = identityItems.withoutConfirmedSdkLocalEchoes()
            val oldestLoadedRemoteTimestamp = projectionItems.asSequence()
                .filterIsInstance<MatrixTimelineItem.Event>()
                .filter { it.event.isRemote }
                .minOfOrNull { it.event.timestamp }
            val restoredEchoIds = localEchoes.asSequence()
                .filter { it.restoredFromOutbox }
                .map { it.item.uniqueId }
                .toSet()
            // Reconciliation compares each echo with the same immutable snapshot several times.
            // Cache identity lookups only for this emission, invalidating even cached misses as
            // soon as an exact binding changes. Content and authorization are always re-evaluated.
            val displayIdentityCache = java.util.IdentityHashMap<MatrixTimelineItem, UniqueId?>()
            var identityGeneration = -1L
            fun confirmedDisplayId(item: MatrixTimelineItem): UniqueId? = synchronized(confirmedDisplayIds) {
                if (identityGeneration != confirmedIdentityGeneration) {
                    displayIdentityCache.clear()
                    identityGeneration = confirmedIdentityGeneration
                }
                if (displayIdentityCache.containsKey(item)) return@synchronized displayIdentityCache[item]
                val result = this@TimelineController.confirmedDisplayId(item)
                if (identityGeneration != confirmedIdentityGeneration) {
                    displayIdentityCache.clear()
                    identityGeneration = confirmedIdentityGeneration
                }
                displayIdentityCache[item] = result
                result
            }
            val accountHistoryAccessBoundary = accountHistoryAccessBoundaryProvider.current()
            hiddenMessageRecoveryInventory.replaceRoomCandidates(
                roomId = room.roomId,
                eventIds = if (referenceStateReady) {
                    projectionItems
                        .filterNot { item -> item.isLocallyHiddenForRecovery(room.roomId, messageReferenceState) }
                        .mapNotNull(MatrixTimelineItem::recoverableHistoryCandidateId)
                        .toSet()
                } else {
                    emptySet()
                },
            )
            val matchedTimelineIndices = mutableSetOf<Int>()
            val stableDisplayIds = mutableMapOf<Int, UniqueId>()
            val protectedShellReplacements = mutableMapOf<Int, MatrixTimelineItem.Event>()
            val unmatchedLocalEchoes = mutableListOf<MatrixTimelineItem.Event>()
            val authenticatedLocalEchoIds = mutableSetOf<UniqueId>()
            localEchoes.forEach localEchoLoop@{ localEcho ->
                if (isOptimisticDisplayIdRetired(localEcho.item.uniqueId)) return@localEchoLoop
                // Retain the SDK local carrier for identity even when a terminal remote replaces it in this snapshot.
                bindProtectedMediaProjection(localEcho, identityItems)
                val authoritativeTerminalIndex = projectionItems.closestMatchingIndex(localEcho, matchedTimelineIndices) { item ->
                    item.isAuthoritativeTerminalOutcomeFor(localEcho) ||
                        (item is MatrixTimelineItem.Event && item.event.content is RedactedContent &&
                            confirmedDisplayId(item) == localEcho.item.uniqueId) ||
                        item.isAuthoritativeProtectedOutcomeFor(confirmedDisplayId(item), localEcho)
                }
                if (authoritativeTerminalIndex >= 0) {
                    matchedTimelineIndices += authoritativeTerminalIndex
                    stableDisplayIds[authoritativeTerminalIndex] = localEcho.item.uniqueId
                    rememberConfirmedDisplayId(projectionItems[authoritativeTerminalIndex], localEcho.item.uniqueId)
                    retireOptimisticDisplayId(localEcho.item.uniqueId)
                    authenticatedLocalEchoIds += localEcho.item.uniqueId
                    return@localEchoLoop
                }
                val authenticatedIndex = projectionItems.closestMatchingIndex(localEcho, matchedTimelineIndices) { item ->
                    (item.matchesAuthenticated(localEcho) ||
                        (item is MatrixTimelineItem.Event && item.event.isOwn &&
                            item.event.content is MessageContent && item.protectedMessageStatus() == null &&
                            confirmedDisplayId(item) == localEcho.item.uniqueId)) &&
                        (confirmedDisplayId(item) == null || confirmedDisplayId(item) == localEcho.item.uniqueId)
                }
                when {
                    authenticatedIndex >= 0 -> {
                        matchedTimelineIndices += authenticatedIndex
                        stableDisplayIds[authenticatedIndex] = localEcho.item.uniqueId
                        rememberConfirmedDisplayId(projectionItems[authenticatedIndex], localEcho.item.uniqueId)
                        val sdkEvent = projectionItems[authenticatedIndex] as MatrixTimelineItem.Event
                        val pendingSdkText = (localEcho.media == null ||
                            (localEcho.item.event.content as? MessageContent)?.type is VoiceMessageType) && !sdkEvent.event.isRemote &&
                            sdkEvent.event.localSendState !is LocalEventSendState.Failed
                        if (localEcho.media != null && !localEcho.sendAccepted &&
                            (localEcho.item.event.content as? MessageContent)?.type !is VoiceMessageType) {
                            // Keep the local URI and the real transfer percentage visible while the SDK-owned
                            // event is uploading. The authenticated event replaces this exact row on success.
                            protectedShellReplacements[authenticatedIndex] = localEcho.item.withSdkMediaUploadProgress(
                                projectionItems[authenticatedIndex]
                            )
                        } else if (pendingSdkText) {
                            // Queue acknowledgement is not the authenticated remote handover. Retain the owned
                            // pending row across SDK local removal and remote decryption; terminal outcomes above still win.
                            protectedShellReplacements[authenticatedIndex] = localEcho.item.copy(
                                uniqueId = sdkEvent.uniqueId,
                                event = localEcho.item.event.copy(
                                    localSendState = localEcho.item.event.localSendState.let {
                                        if (it is LocalEventSendState.Sent) it else LocalEventSendState.Sending.Event
                                    },
                                ),
                            )
                        } else {
                            retireOptimisticDisplayId(localEcho.item.uniqueId)
                            authenticatedLocalEchoIds += localEcho.item.uniqueId
                        }
                        if (!pendingSdkText) {
                            coroutineScope.launch {
                            offlineOutboxService.remove(
                                room.sessionId.value,
                                room.roomId.value,
                                checkNotNull(localEcho.item.event.transactionId).value,
                            )
                        }
                        }
                    }
                    else -> {
                        val protectedShellIndex = projectionItems.closestMatchingIndex(localEcho, matchedTimelineIndices) { item ->
                            item.matchesProtectedShell(localEcho) ||
                                (item.protectedMessageStatus() != null && confirmedDisplayId(item) == localEcho.item.uniqueId)
                        }
                        if (protectedShellIndex < 0) {
                            val previouslyConfirmedIndex = projectionItems.indices.firstOrNull { index ->
                                index !in matchedTimelineIndices &&
                                    confirmedDisplayId(projectionItems[index]) == localEcho.item.uniqueId
                            }
                            if (previouslyConfirmedIndex == null) {
                                unmatchedLocalEchoes += localEcho.item
                            } else {
                                // Edits replace the authenticated event body, so body-based reconciliation no longer
                                // matches the original local echo. Keep the already-confirmed event identity instead of
                                // appending the stale echo with the same LazyColumn key.
                                matchedTimelineIndices += previouslyConfirmedIndex
                                stableDisplayIds[previouslyConfirmedIndex] = localEcho.item.uniqueId
                            }
                        } else {
                            matchedTimelineIndices += protectedShellIndex
                            val status = projectionItems[protectedShellIndex].protectedMessageStatus()
                            if (status.isDecryptingProtectedStatus()) {
                                protectedShellReplacements[protectedShellIndex] = localEcho.item
                                rememberConfirmedDisplayId(projectionItems[protectedShellIndex], localEcho.item.uniqueId)
                            } else {
                                // A terminal security result must replace the optimistic echo. Never mask it.
                                stableDisplayIds[protectedShellIndex] = localEcho.item.uniqueId
                                rememberConfirmedDisplayId(projectionItems[protectedShellIndex], localEcho.item.uniqueId)
                                retireOptimisticDisplayId(localEcho.item.uniqueId)
                                authenticatedLocalEchoIds += localEcho.item.uniqueId
                            }
                        }
                    }
                }
            }
            val stableTimelineItems = projectionItems.mapIndexed { index, item ->
                val protectedShellReplacement = protectedShellReplacements[index]
                if (protectedShellReplacement != null) {
                    protectedShellReplacement
                } else {
                    val displayUniqueId = stableDisplayIds[index] ?: confirmedDisplayId(item)
                    if (displayUniqueId != null && item is MatrixTimelineItem.Event) {
                        item.copy(displayUniqueId = displayUniqueId)
                    } else {
                        item
                    }
                }
            }
            val result = stableTimelineItems
                .insertPendingEventsBeforeTrailingVirtualItems(unmatchedLocalEchoes.filter { item ->
                    // Restoring the encrypted outbox must not fill an incomplete live window with
                    // days-old failures. Keep those entries intact and reveal them when pagination
                    // reaches their real timestamp (or proves that there is no earlier history).
                    // New composer echoes still appear immediately, including in an empty room.
                    item.uniqueId !in restoredEchoIds || window.historyStartReached ||
                        oldestLoadedRemoteTimestamp?.let { item.event.timestamp >= it } == true
                })
                .withCanonicalEventOrder(::canonicalTimestamp)
                .filter { it.isAuthorizedForTimelineDisplay(requireContentProtection) }
                .filter { it.isVisibleAcrossIdentityResetBoundary(accountHistoryAccessBoundary) }
                .withoutEmptyDayDividers()
            if (authenticatedLocalEchoIds.isNotEmpty()) {
                coroutineScope.launch {
                    optimisticLocalEchoes.update { echoes ->
                        echoes.filterNot { it.item.uniqueId in authenticatedLocalEchoIds }
                    }
                    authenticatedLocalEchoIds.forEach { id ->
                        mediaCancelActions.remove(id)
                        mediaRetryActions.remove(id)
                        mediaUserDiscardActions.remove(id)
                        mediaDiscardActions.remove(id)?.invoke()
                    }
                }
            }
            result
        }
    }

    private fun MatrixTimelineItem.isVisibleAcrossIdentityResetBoundary(
        boundary: AccountHistoryAccessBoundary?,
    ): Boolean {
        if (boundary == null) return true
        val eventItem = (this as? MatrixTimelineItem.Event)?.event ?: return true
        if (eventItem.timestamp > boundary.resetCommittedAtEpochMillis) return true
        // Do not hide or downgrade arbitrary Matrix shields. Only content from before the signed
        // reset boundary that Matrix itself marks as authenticity-not-guaranteed is suppressed.
        // New events and all other warning categories keep their normal security UI.
        return eventItem.messageShieldProvider(strict = false) !is MessageShield.AuthenticityNotGuaranteed
    }

    /** Adds the local echo; send will encrypt it into the device outbox before KMS and Matrix are invoked. */
    fun addOptimisticLocalEcho(
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention> = emptyList(),
        repliedToEventId: EventId? = null,
    ): UniqueId {
        val token = UUID.randomUUID().toString()
        val transactionId = "$OPTIMISTIC_LOCAL_ECHO_PREFIX$token"
        val timestamp = System.currentTimeMillis()
        val localEcho = createLocalEcho(
            transactionId = transactionId,
            body = body,
            htmlBody = htmlBody,
            intentionalMentions = intentionalMentions,
            repliedToEventId = repliedToEventId,
            timestamp = timestamp,
            localSendState = LocalEventSendState.Sending.Encrypting,
        )
        Timber.tag("YiZheMessageLatency").d(
            "stage=intent_capture identity=%s epoch_ms=%d monotonic_ns=%d",
            localEcho.item.uniqueId.value,
            timestamp,
            System.nanoTime(),
        )
        optimisticLocalEchoes.update { current -> (current + localEcho).takeLast(MAX_OPTIMISTIC_LOCAL_ECHOES) }
        return localEcho.item.uniqueId
    }

    /** Metadata-only LC-17 presentation; no recording URI is exposed before authorized handoff. */
    fun addOptimisticVoiceEcho(operationId: String, duration: Duration, waveform: List<Float>, burnAfterRead: Boolean): UniqueId {
        val echo = createLocalEcho(
            transactionId = "$OPTIMISTIC_MEDIA_ECHO_PREFIX$operationId",
            body = "voice.ogg", htmlBody = null, intentionalMentions = emptyList(), repliedToEventId = null,
            timestamp = System.currentTimeMillis(), localSendState = LocalEventSendState.Sending.Encrypting,
        )
        val content = echo.item.event.content as MessageContent
        val voice = echo.copy(
            item = echo.item.copy(event = echo.item.event.copy(content = content.copy(
                type = VoiceMessageType("voice.ogg", null, null, MediaSource(""),
                    AudioInfo(duration, null, "audio/ogg"), AudioDetails(duration, waveform.toImmutableList())),
                isBurnAfterRead = burnAfterRead,
            ))),
            media = OptimisticMedia("voice.ogg", "audio/ogg", null),
            sendStarted = true,
        )
        optimisticLocalEchoes.update { current -> (current + voice).takeLast(MAX_OPTIMISTIC_LOCAL_ECHOES) }
        return voice.item.uniqueId
    }

    fun markOptimisticVoiceSending(uniqueId: UniqueId, encrypting: Boolean = false) {
        optimisticLocalEchoes.update { echoes -> echoes.map { echo ->
            if (echo.item.uniqueId != uniqueId) echo else echo.copy(item = echo.item.copy(
                event = echo.item.event.copy(localSendState = if (encrypting) LocalEventSendState.Sending.Encrypting else LocalEventSendState.Sending.Event),
            ))
        } }
    }

    fun updateOptimisticVoiceInfo(uniqueId: UniqueId, duration: Duration, waveform: List<Float>) {
        optimisticLocalEchoes.update { echoes -> echoes.map { echo ->
            val content = echo.item.event.content as? MessageContent
            val voice = content?.type as? VoiceMessageType
            if (echo.item.uniqueId != uniqueId || voice == null) echo else echo.copy(item = echo.item.copy(
                event = echo.item.event.copy(content = content.copy(type = voice.copy(
                    info = voice.info?.copy(duration = duration), details = AudioDetails(duration, waveform.toImmutableList()),
                ))),
            ))
        } }
    }

    /** Adds a room-local media row before preprocessing starts; no URI or progress is serialized to Matrix. */
    fun addOptimisticMediaEcho(
        localMedia: LocalMedia,
        caption: String?,
        mediaAlbum: ProtectedMediaAlbum?,
        burnAfterRead: Boolean,
        preparedMedia: MediaUploadInfo? = null,
    ): UniqueId {
        val token = UUID.randomUUID().toString()
        val transactionId = "$OPTIMISTIC_MEDIA_ECHO_PREFIX$token"
        val timestamp = System.currentTimeMillis()
        val localEcho = createLocalMediaEcho(
            transactionId = transactionId,
            localMedia = localMedia,
            caption = caption,
            mediaAlbum = mediaAlbum,
            burnAfterRead = burnAfterRead,
            timestamp = timestamp,
            preparedMedia = preparedMedia,
        )
        optimisticLocalEchoes.update { current -> (current + localEcho).takeLast(MAX_OPTIMISTIC_LOCAL_ECHOES) }
        pendingMediaSendPresentations.update { current ->
            (current + PendingMediaSendPresentation(localEcho.item.uniqueId)).takeLast(MAX_OPTIMISTIC_LOCAL_ECHOES)
        }
        return localEcho.item.uniqueId
    }

    private val mediaCancelActions = java.util.concurrent.ConcurrentHashMap<UniqueId, suspend () -> Result<Unit>>()

    fun optimisticLocalEchoId(id: UniqueId, displayId: UniqueId): UniqueId? =
        optimisticLocalEchoes.value.firstOrNull { it.item.uniqueId == id || it.item.uniqueId == displayId }?.item?.uniqueId

    private val mediaRetryActions = java.util.concurrent.ConcurrentHashMap<UniqueId, suspend () -> Result<Unit>>()
    private val mediaDiscardActions = java.util.concurrent.ConcurrentHashMap<UniqueId, () -> Unit>()
    private val mediaUserDiscardActions = java.util.concurrent.ConcurrentHashMap<UniqueId, () -> Unit>()

    /** Media retries stay with their LC-16 owner and retain the exact edit/quality choices. */
    fun registerOptimisticMediaActions(uniqueId: UniqueId, retry: suspend () -> Result<Unit>, discard: () -> Unit, onUserDiscard: () -> Unit = {}, cancelPending: (suspend () -> Result<Unit>)? = null) {
        mediaRetryActions[uniqueId] = retry
        mediaDiscardActions[uniqueId] = discard
        mediaUserDiscardActions[uniqueId] = onUserDiscard
        if (cancelPending != null) mediaCancelActions[uniqueId] = cancelPending
    }

    /** Carry the existing preprocessing result into the local row before encryption/upload starts. */
    fun updateOptimisticMediaInfo(uniqueId: UniqueId, preparedMedia: MediaUploadInfo) {
        optimisticLocalEchoes.update { current ->
            current.map { echo ->
                if (echo.item.uniqueId != uniqueId) return@map echo
                val content = echo.item.event.content as? MessageContent ?: return@map echo
                val type = when (val type = content.type) {
                    is ImageMessageType -> type.copy(info = (preparedMedia as? MediaUploadInfo.Image)?.imageInfo ?: type.info)
                    is VideoMessageType -> type.copy(info = (preparedMedia as? MediaUploadInfo.Video)?.videoInfo ?: type.info)
                    else -> return@map echo
                }
                echo.copy(item = echo.item.copy(event = echo.item.event.copy(content = content.copy(type = type))))
            }
        }
    }

    /**
     * Bypasses the comparatively expensive Matrix-to-Compose timeline conversion for the first visual frame.
     * The presentation carries identity only; the protected transfer and authoritative event remain owned by
     * the existing LC-16/LC-21 pipeline.
     */
    fun pendingMediaSendPresentations(): StateFlow<List<PendingMediaSendPresentation>> = pendingMediaSendPresentations

    fun acknowledgePendingMediaPresentation(displayId: UniqueId) {
        pendingMediaSendPresentations.update { current ->
            current.filterNot { it.displayId == displayId }
        }
    }

    fun updateOptimisticMediaProgress(uniqueId: UniqueId, progress: Float) {
        optimisticLocalEchoes.update { current ->
            current.map { localEcho ->
                if (localEcho.item.uniqueId != uniqueId || localEcho.media == null) {
                    localEcho
                } else {
                    val messageContent = localEcho.item.event.content as MessageContent
                    localEcho.copy(
                        item = localEcho.item.copy(
                            event = localEcho.item.event.copy(
                                content = messageContent.copy(localMediaProgress = progress.coerceIn(0f, 1f))
                            )
                        )
                    )
                }
            }
        }
    }

    /** Binds the existing protected upload aggregate; this grants no content access. */
    fun bindOptimisticMediaTransport(uniqueId: UniqueId, transportName: String) {
        optimisticLocalEchoes.update { current ->
            current.map { localEcho ->
                if (localEcho.item.uniqueId != uniqueId || localEcho.media == null ||
                    localEcho.media.transportName != null
                ) {
                    localEcho
                } else {
                    localEcho.copy(media = localEcho.media.copy(transportName = transportName))
                }
            }
        }
    }

    /**
     * Keeps a successful echo visible until the authenticated SDK echo is rendered, or retains it as a
     * recoverable failed item. This prevents the send bubble from disappearing between SDK acceptance and sync.
     */
    fun resolveOptimisticLocalEcho(uniqueId: UniqueId, failure: Throwable?) {
        optimisticLocalEchoes.update { current ->
            current.map { localEcho ->
                if (localEcho.item.uniqueId != uniqueId) {
                    localEcho
                } else if (failure == null) {
                    localEcho.copy(
                        sendAccepted = true,
                        item = localEcho.item.copy(event = localEcho.item.event.copy(localSendState =
                            localEcho.item.event.localSendState.let {
                                if (it is LocalEventSendState.Sent) it else LocalEventSendState.Sending.Event
                            })),
                    )
                } else {
                    val failedContent = (localEcho.item.event.content as? MessageContent)?.let { content ->
                        if (localEcho.media != null) content.copy(localMediaProgress = null) else content
                    } ?: localEcho.item.event.content
                    localEcho.copy(
                        sendAccepted = false,
                        item = localEcho.item.copy(
                            event = localEcho.item.event.copy(
                                localSendState = LocalEventSendState.Failed.Unknown(failure.javaClass.simpleName),
                                content = failedContent,
                            )
                        )
                    )
                }
            }
        }
    }

    /** Secure the captured intent before suspended remote lifecycle admission. */
    suspend fun stageOptimisticLocalEcho(uniqueId: UniqueId): Result<Unit> = runCatchingExceptions {
        val localEcho = optimisticLocalEchoes.value.firstOrNull { it.item.uniqueId == uniqueId }
            ?: error("Local echo is no longer available.")
        check(localEcho.media == null) { "Media echoes belong to the protected transfer pipeline." }
        check(LifecycleStageTiming.measure("send_intent_persist", uniqueId.value) {
            offlineOutboxService.stage(localEcho.toOutboxMessage())
        }) { "Unable to secure the offline outbox." }
        stagedComposerIntents.add(uniqueId)
    }

    suspend fun sendOptimisticLocalEcho(uniqueId: UniqueId): Result<Unit> {
        val localEcho = optimisticLocalEchoes.value.firstOrNull { it.item.uniqueId == uniqueId }
            ?: return Result.failure(IllegalStateException("Local echo is no longer available."))
        if (localEcho.media != null) {
            return mediaRetryActions[uniqueId]?.invoke()
                ?: Result.failure(IllegalStateException("The media source is no longer available. Select it again."))
        }
        // A previous SDK-owned echo can have the same body and a nearby timestamp. Do not let it
        // claim this new UI echo in the small gap between addOptimisticLocalEcho() and the actual
        // send attempt. Once this flag is set, the local snapshot above is already owned by this
        // invocation, so reconciliation cannot make the send itself disappear.
        optimisticLocalEchoes.update { current ->
            current.map { candidate ->
                if (candidate.item.uniqueId == uniqueId) candidate.copy(sendStarted = true) else candidate
            }
        }
        val staged = if (uniqueId in stagedComposerIntents) Result.success(Unit) else stageOptimisticLocalEcho(uniqueId)
        if (staged.isFailure) {
            return Result.failure(staged.exceptionOrNull() ?: IllegalStateException("Unable to secure the offline outbox."))
        }
        val result = runCatchingExceptions {
            if (requireContentProtection && currentLifecycleOperationContext() == null) {
                // Restored intents and both retry entry points must obtain the same authorization
                // as the originating composer send before any KMS or Matrix call.
                checkNotNull(appOperationalStateCoordinator).performMessageWithDeferredReceipts(
                    ordinaryMessageLifecycleRequest(
                        room,
                        checkNotNull(matrixClient),
                        if (localEcho.repliedToEventId == null) "send" else "reply",
                        checkNotNull(localEcho.item.event.transactionId).value,
                    )
                ) { sendCapturedTextIntent(localEcho).getOrThrow() }
            } else {
                sendCapturedTextIntent(localEcho).getOrThrow()
            }
        }
        if (result.isSuccess) {
            runCatchingExceptions {
                offlineOutboxService.remove(
                    room.sessionId.value,
                    room.roomId.value,
                    checkNotNull(localEcho.item.event.transactionId).value,
                )
            }
            stagedComposerIntents.remove(uniqueId)
        }
        return result
    }

    private suspend fun sendCapturedTextIntent(localEcho: OptimisticLocalEcho): Result<Unit> =
        LifecycleStageTiming.measure("send_transaction", localEcho.item.uniqueId.value) {
            val client = matrixClient
            val owner = currentLifecycleOperationContext()
            if (requireContentProtection && client != null && localEcho.repliedToEventId == null) {
                val transaction = checkNotNull(localEcho.item.event.transactionId)
                val request = ordinaryMessageLifecycleRequest(room, client, "send", transaction.value)
                val installation = owner?.let { appOperationalStateCoordinator?.operations?.value?.get(it.operationId) }
                    ?.subjects?.installationId
                if (owner?.operationId == request.operationId && owner.lifecycleId == request.lifecycleId && installation != null) {
                    val binding = TextSendConfirmationBinding(request, protectedTextTransactionId(room.roomId, transaction), installation)
                    optimisticLocalEchoes.update { echoes -> echoes.map { echo ->
                        if (echo.item.uniqueId == localEcho.item.uniqueId) echo.copy(confirmationBinding = binding) else echo
                    } }
                }
            }
            currentTimelineFlow.value.sendMessageTransaction(
                transactionId = checkNotNull(localEcho.item.event.transactionId),
                body = localEcho.body,
                htmlBody = localEcho.htmlBody,
                intentionalMentions = localEcho.intentionalMentions,
                repliedToEventId = localEcho.repliedToEventId,
            )
        }

    /** Both retry UI entry points retain the original send owner and transaction identity. */
    suspend fun retryFailedEvent(event: TimelineItem.Event): Result<Unit> {
        if (!event.isMine || !event.failedToSend) {
            return Result.failure(IllegalStateException("Message is not an outgoing failed send."))
        }
        if (!retryingDisplayIds.add(event.displayId)) return Result.success(Unit)
        return try {
            val ownedEcho = optimisticLocalEchoes.value.firstOrNull {
                it.item.uniqueId == event.displayId || it.item.uniqueId == event.id
            }
            if (ownedEcho != null) {
                // A protected retry failure must not fall through to a second send path.
                retryOptimisticLocalEcho(ownedEcho.item.uniqueId)
            } else {
                // An SDK-owned failure keeps its existing encrypted queue item, including media.
                event.sendhandle?.retry()
                    ?: Result.failure(IllegalStateException("The original send queue item is unavailable."))
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Result.failure(failure)
        } finally {
            retryingDisplayIds.remove(event.displayId)
        }
    }

    suspend fun retryOptimisticLocalEcho(uniqueId: UniqueId): Result<Unit> {
        val localEcho = optimisticLocalEchoes.value.firstOrNull { it.item.uniqueId == uniqueId }
            ?: return Result.failure(IllegalStateException("Local echo is no longer available."))
        val error = (localEcho.item.event.localSendState as? LocalEventSendState.Failed.Unknown)?.error
        if (error in setOf("MediaCancellationPendingException", "MediaSendOutcomeUnknownException")) {
            return Result.failure(IllegalStateException("Original media outcome must be reconciled before retry."))
        }
        optimisticLocalEchoes.update { current ->
            current.map { candidate ->
                if (candidate.item.uniqueId != uniqueId) {
                    candidate
                } else {
                    candidate.copy(
                        sendAccepted = false,
                        item = candidate.item.copy(
                            event = candidate.item.event.copy(localSendState = LocalEventSendState.Sending.Encrypting)
                        )
                    )
                }
            }
        }
        val result = sendOptimisticLocalEcho(uniqueId)
        resolveOptimisticLocalEcho(uniqueId, result.exceptionOrNull())
        return result
    }

    suspend fun discardOptimisticLocalEcho(uniqueId: UniqueId): Result<Unit> {
        val localEcho = optimisticLocalEchoes.value.firstOrNull { it.item.uniqueId == uniqueId }
            ?: return Result.failure(IllegalStateException("Local echo is no longer available."))
        if (localEcho.media != null) {
            val cancel = mediaCancelActions[uniqueId]
            if (cancel != null) {
                val result = cancel()
                if (result.isFailure) return result
                mediaCancelActions.remove(uniqueId, cancel)
            }
            mediaUserDiscardActions.remove(uniqueId)?.invoke()
            mediaRetryActions.remove(uniqueId)
            mediaDiscardActions.remove(uniqueId)?.invoke()
            optimisticLocalEchoes.update { echoes -> echoes.filterNot { it.item.uniqueId == uniqueId } }
            pendingMediaSendPresentations.update { entries -> entries.filterNot { it.displayId == uniqueId } }
            return Result.success(Unit)
        }
        val transactionId = checkNotNull(localEcho.item.event.transactionId)
        return currentTimelineFlow.value.discardMessageTransaction(transactionId).onSuccess {
            offlineOutboxService.remove(room.sessionId.value, room.roomId.value, transactionId.value)
            optimisticLocalEchoes.update { echoes -> echoes.filterNot { it.item.uniqueId == uniqueId } }
            synchronized(canonicalTimestamps) { canonicalTimestamps.remove(uniqueId) }
        }
    }

    private suspend fun restoreEncryptedOutbox() {
        val entries = runCatchingExceptions {
            offlineOutboxService.messages(room.sessionId.value, room.roomId.value)
        }.getOrNull() ?: return
        val restored = entries.map { entry ->
            createLocalEcho(
                transactionId = entry.transactionId,
                body = entry.body,
                htmlBody = entry.htmlBody,
                intentionalMentions = buildList {
                    entry.mentionedUserIds.forEach { add(IntentionalMention.User(UserId(it))) }
                    if (entry.mentionsRoom) add(IntentionalMention.Room)
                },
                repliedToEventId = entry.repliedToEventId?.let(::EventId),
                timestamp = entry.createdAtEpochMillis,
                localSendState = LocalEventSendState.Failed.Unknown(OUTBOX_WAITING_ERROR_CODE),
            ).copy(restoredFromOutbox = true)
        }
        optimisticLocalEchoes.update { current ->
            val existing = current.mapTo(mutableSetOf()) { it.item.event.transactionId }
            (current + restored.filterNot { it.item.event.transactionId in existing }).takeLast(MAX_OPTIMISTIC_LOCAL_ECHOES)
        }
    }

    private fun createLocalEcho(
        transactionId: String,
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        repliedToEventId: EventId?,
        timestamp: Long,
        localSendState: LocalEventSendState,
    ): OptimisticLocalEcho {
        val uniqueId = UniqueId(transactionId)
        synchronized(canonicalTimestamps) { canonicalTimestamps[uniqueId] = timestamp }
        val formatted = htmlBody?.let { FormattedBody(MessageFormat.HTML, it) }
        val item = MatrixTimelineItem.Event(
            uniqueId = uniqueId,
            event = EventTimelineItem(
                eventId = null,
                transactionId = TransactionId(transactionId),
                isEditable = false,
                canBeRepliedTo = false,
                isOwn = true,
                isRemote = false,
                localSendState = localSendState,
                reactions = persistentListOf(),
                receipts = persistentListOf(),
                sender = UserId(room.sessionId.value),
                senderProfile = ProfileDetails.Unavailable,
                timestamp = timestamp,
                content = MessageContent(
                    body = body,
                    inReplyTo = null,
                    isEdited = false,
                    threadInfo = null,
                    type = TextMessageType(body, formatted),
                    isContentProtectionAuthorized = true,
                ),
                origin = TimelineItemEventOrigin.LOCAL,
                timelineItemDebugInfoProvider = TimelineItemDebugInfoProvider {
                    TimelineItemDebugInfo(model = "encrypted-offline-outbox", originalJson = null, latestEditedJson = null)
                },
                messageShieldProvider = MessageShieldProvider { null },
                sendHandleProvider = SendHandleProvider { null },
                forwarder = null,
                forwarderProfile = null,
            ),
        )
        return OptimisticLocalEcho(item, body, htmlBody, intentionalMentions, repliedToEventId)
    }

    private fun createLocalMediaEcho(
        transactionId: String,
        localMedia: LocalMedia,
        caption: String?,
        mediaAlbum: ProtectedMediaAlbum?,
        burnAfterRead: Boolean,
        timestamp: Long,
        preparedMedia: MediaUploadInfo?,
    ): OptimisticLocalEcho {
        val uniqueId = UniqueId(transactionId)
        synchronized(canonicalTimestamps) { canonicalTimestamps[uniqueId] = timestamp }
        val media = OptimisticMedia(
            filename = localMedia.info.filename,
            mimeType = localMedia.info.mimeType,
            mediaAlbum = mediaAlbum,
        )
        val source = MediaSource(localMedia.uri.toString())
        val messageType = if (localMedia.info.mimeType.isMimeTypeVideo()) {
            VideoMessageType(
                filename = media.filename,
                caption = caption,
                formattedCaption = null,
                source = source,
                info = (preparedMedia as? MediaUploadInfo.Video)?.videoInfo,
            )
        } else {
            ImageMessageType(
                filename = media.filename,
                caption = caption,
                formattedCaption = null,
                source = source,
                info = (preparedMedia as? MediaUploadInfo.Image)?.imageInfo ?: ImageInfo(
                    height = localMedia.imageDecodeHeight?.toLong(),
                    width = localMedia.imageDecodeWidth?.toLong(),
                    mimetype = media.mimeType,
                    size = localMedia.info.fileSize,
                    thumbnailInfo = null,
                    thumbnailSource = null,
                    blurhash = null,
                ),
            )
        }
        val item = MatrixTimelineItem.Event(
            uniqueId = uniqueId,
            event = EventTimelineItem(
                eventId = null,
                transactionId = TransactionId(transactionId),
                isEditable = false,
                canBeRepliedTo = false,
                isOwn = true,
                isRemote = false,
                localSendState = LocalEventSendState.Sending.Event,
                reactions = persistentListOf(),
                receipts = persistentListOf(),
                sender = UserId(room.sessionId.value),
                senderProfile = ProfileDetails.Unavailable,
                timestamp = timestamp,
                content = MessageContent(
                    body = media.filename,
                    inReplyTo = null,
                    isEdited = false,
                    threadInfo = null,
                    type = messageType,
                    isContentProtectionAuthorized = true,
                    isBurnAfterRead = burnAfterRead,
                    protectedMediaAlbum = mediaAlbum,
                    localMediaProgress = 0f,
                ),
                origin = TimelineItemEventOrigin.LOCAL,
                timelineItemDebugInfoProvider = TimelineItemDebugInfoProvider {
                    TimelineItemDebugInfo(model = "lc16-local-media-transfer", originalJson = null, latestEditedJson = null)
                },
                messageShieldProvider = MessageShieldProvider { null },
                sendHandleProvider = SendHandleProvider { null },
                forwarder = null,
                forwarderProfile = null,
            ),
        )
        return OptimisticLocalEcho(
            item = item,
            body = media.filename,
            htmlBody = null,
            intentionalMentions = emptyList(),
            repliedToEventId = null,
            sendStarted = true,
            media = media,
        )
    }

    private fun OptimisticLocalEcho.toOutboxMessage(): OfflineTextOutboxMessage = OfflineTextOutboxMessage(
        transactionId = checkNotNull(item.event.transactionId).value,
        accountId = room.sessionId.value,
        roomId = room.roomId.value,
        body = body,
        htmlBody = htmlBody,
        mentionedUserIds = intentionalMentions.filterIsInstance<IntentionalMention.User>().map { it.userId.value },
        mentionsRoom = IntentionalMention.Room in intentionalMentions,
        repliedToEventId = repliedToEventId?.value,
        createdAtEpochMillis = item.event.timestamp,
    )

    fun isLive(): Flow<Boolean> {
        return detachedTimelineFlow.map { !it.isPresent }
    }

    fun mainTimelineMode(): Timeline.Mode = liveTimeline.mode

    fun detachedTimelineMode(): Timeline.Mode? {
        return detachedTimelineFlow.value.orElse(null)?.mode
    }

    suspend fun invokeOnCurrentTimeline(block: suspend (Timeline.() -> Unit)) {
        currentTimelineFlow.value.run {
            block(this)
        }
    }

    suspend fun focusOnEvent(eventId: EventId, threadRootId: ThreadId?): Result<EventFocusResult> {
        return if (threadRootId != null) {
            Result.success(EventFocusResult.IsInThread(threadRootId))
        } else {
            room.createTimeline(CreateTimelineParams.Focused(eventId))
                .onFailure {
                    if (it is CancellationException) {
                        throw it
                    }
                }
                .map { newDetachedTimeline ->
                    detachedTimelineFlow.getAndUpdate { current ->
                        if (current.isPresent) {
                            current.get().close()
                        }
                        Optional.of(newDetachedTimeline)
                    }
                    EventFocusResult.FocusedOnLive
                }
        }
    }

    /**
     * Makes sure the controller is focused on the live timeline.
     * This does close the detached timeline if any.
     */
    fun focusOnLive() {
        closeDetachedTimeline()
    }

    private fun closeDetachedTimeline() {
        detachedTimelineFlow.getAndUpdate {
            when {
                it.isPresent -> {
                    it.get().close()
                    Optional.empty()
                }
                else -> Optional.empty()
            }
        }
    }

    private val pendingComposerSends = PendingSendLifetime(::closeAfterSends)

    suspend fun <T> withComposerSendLifetime(block: suspend () -> T): T = pendingComposerSends.withSend(block)

    override fun close() = pendingComposerSends.close()

    private fun closeAfterSends() {
        // Keep the content-free recovery candidate inventory alive for the session-scoped
        // recovery screen. Closing this room while navigating there must not erase the
        // evidence that caused the recovery entry point to be shown.
        mediaRetryActions.clear()
        mediaUserDiscardActions.clear()
        mediaDiscardActions.values.forEach { it() }
        mediaDiscardActions.clear()
        optimisticLocalEchoes.value = emptyList()
        stagedComposerIntents.clear()
        pendingMediaSendPresentations.value = emptyList()
        synchronized(confirmedDisplayIds) {
            confirmedDisplayIds.clear()
            confirmedServerIdentities.clear()
            confirmedTransactionDisplayIds.clear()
            confirmedEventIds.clear()
        }
        synchronized(canonicalTimestamps) {
            canonicalTimestamps.clear()
        }
        synchronized(retiredOptimisticDisplayIds) {
            retiredOptimisticDisplayIds.clear()
        }
        coroutineScope.cancel()
        closeDetachedTimeline()
    }

    private fun rememberConfirmedDisplayId(item: MatrixTimelineItem, displayId: UniqueId) {
        val event = item as? MatrixTimelineItem.Event ?: return
        synchronized(confirmedDisplayIds) {
            val previousEventId = confirmedEventIds[displayId]
            // An older SDK local snapshot must not erase the canonical alias learned from
            // the direct signed send confirmation while ordered sync is still catching up.
            val identity = (event.eventId ?: previousEventId)?.let { "event:${it.value}" } ?: event.serverIdentity()
            if (previousEventId != null && event.eventId != null && previousEventId != event.eventId) return
            val existingOwner = confirmedDisplayIds[identity]
                ?: event.transactionId?.let(confirmedTransactionDisplayIds::get)
                ?: event.priorSdkLocalIdentity()?.let(confirmedDisplayIds::get)
            if (existingOwner != null && existingOwner != displayId) return
            if (confirmedDisplayIds[identity] == displayId && confirmedServerIdentities[displayId] == identity &&
                (event.transactionId == null || confirmedTransactionDisplayIds[event.transactionId] == displayId) &&
                (event.eventId == null || previousEventId == event.eventId)
            ) return
            confirmedIdentityGeneration++
            event.transactionId?.let { confirmedTransactionDisplayIds[it] = displayId }
            event.eventId?.let { confirmedEventIds[displayId] = it }
            if (previousEventId == null && event.eventId != null) {
                Timber.tag("YiZheMessageLatency").d(
                    "stage=event_bound identity=%s event=%s epoch_ms=%d monotonic_ns=%d",
                    displayId.value,
                    event.eventId?.value,
                    System.currentTimeMillis(),
                    System.nanoTime(),
                )
            }
            confirmedServerIdentities.put(displayId, identity)
                ?.takeIf { previousIdentity -> previousIdentity != identity }
                ?.let(confirmedDisplayIds::remove)
            confirmedDisplayIds.put(identity, displayId)
                ?.takeIf { previousDisplayId -> previousDisplayId != displayId }
                ?.let(confirmedServerIdentities::remove)
        }
    }

    private fun confirmedDisplayId(item: MatrixTimelineItem): UniqueId? {
        val event = item as? MatrixTimelineItem.Event ?: return null
        val identity = event.serverIdentity()
        return synchronized(confirmedDisplayIds) {
            val displayId = confirmedDisplayIds[identity]
                ?: event.transactionId?.let(confirmedTransactionDisplayIds::get)
                ?: event.priorSdkLocalIdentity()?.let(confirmedDisplayIds::get)
                ?: return@synchronized null
            val previousEventId = confirmedEventIds[displayId]
            if (previousEventId != null && event.eventId != null && previousEventId != event.eventId) {
                null
            } else {
                rememberConfirmedDisplayId(item, displayId)
                displayId
            }
        }
    }

    private fun bindProtectedMediaProjection(localEcho: OptimisticLocalEcho, items: List<MatrixTimelineItem>) {
        val transportName = localEcho.media?.transportName ?: return
        val candidates = items.filterIsInstance<MatrixTimelineItem.Event>().filter { item ->
            item.event.isOwn && item.protectedMediaTransportName == transportName &&
                (confirmedDisplayId(item) == null || confirmedDisplayId(item) == localEcho.item.uniqueId)
        }
        // An ambiguous carrier cannot collapse two distinct sends into one display row.
        if (candidates.mapNotNull { it.eventId }.distinct().size > 1 ||
            candidates.mapNotNull { it.transactionId }.distinct().size > 1
        ) {
            return
        }
        candidates.forEach { rememberConfirmedDisplayId(it, localEcho.item.uniqueId) }
    }

    private fun retireOptimisticDisplayId(displayId: UniqueId) {
        synchronized(retiredOptimisticDisplayIds) {
            retiredOptimisticDisplayIds += displayId
        }
    }

    private fun isOptimisticDisplayIdRetired(displayId: UniqueId): Boolean =
        synchronized(retiredOptimisticDisplayIds) {
            displayId in retiredOptimisticDisplayIds
        }

    private fun canonicalTimestamp(displayId: UniqueId): Long? = synchronized(canonicalTimestamps) {
        canonicalTimestamps[displayId]
    }

    suspend fun paginate(direction: Timeline.PaginationDirection): Result<Boolean> {
        return currentTimelineFlow.value.paginate(direction)
            .onSuccess { hasReachedEnd ->
                if (direction == Timeline.PaginationDirection.FORWARDS && hasReachedEnd) {
                    focusOnLive()
                }
            }
    }

    fun updateViewport(visibleItemIds: List<UniqueId>) {
        // The combined presentation flow can lag one dispatcher turn after focusOnEvent returns.
        // Capture the authoritative detached/live state directly at the UI call boundary. Rust
        // binds the update again to its current commit scope, so a delayed update cannot cross a
        // subscription boundary either.
        detachedTimelineFlow.value.orElse(liveTimeline).updateViewport(visibleItemIds)
    }

    private val currentTimelineFlow = combine(liveTimelineFlow, detachedTimelineFlow) { live, detached ->
        when {
            detached.isPresent -> detached.get()
            else -> live
        }
    }.stateIn(coroutineScope, SharingStarted.Eagerly, room.liveTimeline)

    override fun activeTimelineFlow(): StateFlow<Timeline> {
        return currentTimelineFlow
    }
}

private data class OptimisticLocalEcho(
    val item: MatrixTimelineItem.Event,
    val body: String,
    val htmlBody: String?,
    val intentionalMentions: List<IntentionalMention>,
    val repliedToEventId: EventId?,
    val sendStarted: Boolean = false,
    val sendAccepted: Boolean = false,
    val media: OptimisticMedia? = null,
    val restoredFromOutbox: Boolean = false,
    val confirmationBinding: TextSendConfirmationBinding? = null,
    val nativeConfirmedEventId: EventId? = null,
)

private data class LoadedTimelineWindow(
    val items: List<MatrixTimelineItem>,
    val historyStartReached: Boolean,
)

private data class OptimisticMedia(
    val filename: String,
    val mimeType: String,
    val mediaAlbum: ProtectedMediaAlbum?,
    val transportName: String? = null,
)

private fun MatrixTimelineItem.matchesAuthenticated(localEcho: OptimisticLocalEcho): Boolean {
    val eventItem = this as? MatrixTimelineItem.Event ?: return false
    if (!eventItem.event.isOwn) return false
    val messageContent = eventItem.event.content as? MessageContent
    if (messageContent?.protectedMessageStatus != null) return false
    val remoteTransactionId = eventItem.event.transactionId?.value
    val localTransactionId = localEcho.item.event.transactionId?.value
    if (remoteTransactionId != null && remoteTransactionId == localTransactionId) return true
    if (messageContent == null) return false
    localEcho.media?.let { localMedia ->
        if ((localEcho.item.event.content as? MessageContent)?.type is VoiceMessageType) return false
        val remoteAttachment = messageContent.type
        val remoteFilename = when (remoteAttachment) {
            is ImageMessageType -> remoteAttachment.filename
            is VideoMessageType -> remoteAttachment.filename
            else -> return false
        }
        if (remoteFilename != localMedia.filename) return false
        if (messageContent.protectedMediaAlbum != localMedia.mediaAlbum) return false
        if (eventItem.isSdkManagedLocalEcho()) return localEcho.sendStarted && isWithinLocalEchoWindow(localEcho)
        if (!localEcho.sendAccepted || remoteTransactionId != null) return false
        return isWithinLocalEchoWindow(localEcho)
    }
    if (messageContent.body != localEcho.body) return false
    // The SDK can publish its queue-owned local echo before the suspending send call returns. It is
    // safe to reconcile that item before acceptance because it has no event id and is not remote;
    // an older confirmed event can never satisfy this branch.
    if (eventItem.isSdkManagedLocalEcho()) return localEcho.sendStarted && isWithinLocalEchoWindow(localEcho)
    // For remote/event-backed items, body and timestamp are only a legacy fallback after the send
    // has actually been accepted. Before then, matching can consume a brand-new echo against an
    // older same-body message and make the send fail with "Local echo is no longer available".
    if (!localEcho.sendAccepted) return false
    if (remoteTransactionId != null) return false
    return isWithinLocalEchoWindow(localEcho)
}

/**
 * The Matrix SDK allocates its own transaction id when [Timeline.sendMessageTransaction] hands an
 * event to the SDK send queue. Reconcile that queue-owned local echo by content and timestamp only
 * while it is still a local, unconfirmed event. Remote events must continue to use their Matrix
 * transaction/event identity so two separate sends can never be collapsed after synchronization.
 */
private fun MatrixTimelineItem.Event.isSdkManagedLocalEcho(): Boolean =
    event.eventId == null &&
        event.transactionId != null &&
        !event.isRemote

private fun MatrixTimelineItem.matchesProtectedShell(localEcho: OptimisticLocalEcho): Boolean {
    val eventItem = this as? MatrixTimelineItem.Event ?: return false
    if (!eventItem.event.isOwn) return false
    if (eventItem.event.content !is MessageContent) return false
    if (protectedMessageStatus() == null) return false
    if ((localEcho.item.event.content as? MessageContent)?.type is VoiceMessageType) return false
    val remoteTransactionId = eventItem.event.transactionId?.value
    val localTransactionId = localEcho.item.event.transactionId?.value
    if (remoteTransactionId != null) {
        if (remoteTransactionId == localTransactionId) return true
        if (localEcho.media != null && eventItem.isSdkManagedLocalEcho()) {
            return localEcho.sendStarted && isWithinLocalEchoWindow(localEcho)
        }
        return false
    }
    // A transaction-less protected shell is only eligible after the send call has returned
    // successfully. Otherwise an unrelated historical shell can delete an unsent local echo.
    if (!localEcho.sendAccepted) return false
    return isWithinLocalEchoWindow(localEcho)
}

/**
 * A redaction that carries the exact send transaction is authoritative even if it wins the race
 * against normal local-echo reconciliation. The optimistic cleartext must never be reinserted.
 */
private fun MatrixTimelineItem.isAuthoritativeTerminalOutcomeFor(localEcho: OptimisticLocalEcho): Boolean {
    val eventItem = this as? MatrixTimelineItem.Event ?: return false
    if (!eventItem.event.isOwn || eventItem.event.content !is RedactedContent) return false
    val remoteTransactionId = eventItem.event.transactionId ?: return false
    return remoteTransactionId == localEcho.item.event.transactionId
}

/**
 * Once a protected event has produced a non-decrypting server result for this stable display row,
 * that result owns the row. This includes the unopened burn-after-read shell: retaining or later
 * restoring the optimistic plaintext would bypass the fail-closed presentation boundary.
 */
private fun MatrixTimelineItem.isAuthoritativeProtectedOutcomeFor(
    confirmedDisplayId: UniqueId?,
    localEcho: OptimisticLocalEcho,
): Boolean {
    val eventItem = this as? MatrixTimelineItem.Event ?: return false
    if (!eventItem.event.isOwn) return false
    val status = protectedMessageStatus() ?: return false
    if (status.isDecryptingProtectedStatus()) return false
    val remoteTransactionId = eventItem.event.transactionId
    val hasExactTransaction = remoteTransactionId != null && remoteTransactionId == localEcho.item.event.transactionId
    return hasExactTransaction || confirmedDisplayId == localEcho.item.uniqueId
}

private fun MatrixTimelineItem.protectedMessageStatus(): ProtectedMessageStatus? =
    ((this as? MatrixTimelineItem.Event)?.event?.content as? MessageContent)?.protectedMessageStatus

private fun ProtectedMessageStatus?.isDecryptingProtectedStatus(): Boolean =
    this == ProtectedMessageStatus.DECRYPTING || this == ProtectedMessageStatus.ATTACHMENT_DECRYPTING

private fun MatrixTimelineItem.recoverableHistoryCandidateId(): String? {
    val eventItem = this as? MatrixTimelineItem.Event ?: return null
    val unableToDecrypt = eventItem.event.content as? UnableToDecryptContent ?: return null
    val mayExistInBackup = when (val data = unableToDecrypt.data) {
        is UnableToDecryptContent.Data.MegolmV1AesSha2 -> when (data.utdCause) {
            UtdCause.Unknown,
            UtdCause.HistoricalMessageAndBackupIsDisabled,
            UtdCause.HistoricalMessageAndDeviceIsUnverified -> true
            UtdCause.SentBeforeWeJoined,
            UtdCause.VerificationViolation,
            UtdCause.UnsignedDevice,
            UtdCause.UnknownDevice,
            UtdCause.WithheldUnverifiedOrInsecureDevice,
            UtdCause.WithheldBySender -> false
        }
        // An unknown failure is not evidence that a backup contains the key. Do not advertise
        // recovery unless the SDK has identified a history-key condition that recovery can address.
        UnableToDecryptContent.Data.Unknown -> false
        is UnableToDecryptContent.Data.OlmV1Curve25519AesSha2 -> false
    }
    return if (mayExistInBackup) eventItem.eventId?.value else null
}

private fun MatrixTimelineItem.isLocallyHiddenForRecovery(
    roomId: io.element.android.libraries.matrix.api.core.RoomId,
    state: MessageReferenceState,
): Boolean {
    val event = (this as? MatrixTimelineItem.Event)?.event ?: return false
    val eventId = event.eventId ?: return false
    return state.isLocallyDeleted(roomId, eventId) ||
        state.conversationCutoff(roomId)?.let { cutoff -> event.timestamp <= cutoff } == true ||
        state.isUnavailableHistoryDismissed(event.timestamp)
}

/**
 * Remote and SDK-backed local events render only after every content-authorization layer passes.
 * The in-process optimistic echo is explicitly authorized when it is created from the composer.
 */
private fun MatrixTimelineItem.isAuthorizedForTimelineDisplay(requireContentProtection: Boolean): Boolean = when (this) {
    is MatrixTimelineItem.Event -> event.content.isAuthorizedForUserDisplay(requireContentProtection)
    MatrixTimelineItem.Other,
    is MatrixTimelineItem.Virtual -> true
}

/** Keeps real events in canonical order while presentation-only tail items remain at the visual edge. */
internal fun List<MatrixTimelineItem>.insertPendingEventsBeforeTrailingVirtualItems(
    pendingEvents: List<MatrixTimelineItem.Event>,
): List<MatrixTimelineItem> {
    if (pendingEvents.isEmpty()) return this
    val source = this
    val insertionIndex = source.indexOfLast { !it.isTrailingVirtualItem() } + 1
    return buildList(source.size + pendingEvents.size) {
        addAll(source.subList(0, insertionIndex))
        addAll(pendingEvents)
        addAll(source.subList(insertionIndex, source.size))
    }
}

/**
 * Reconciled events retain the local echo's real-message ordinal. Only event values are reordered;
 * virtual presentation slots are never used as sort keys and stay in their original positions.
 */
internal fun List<MatrixTimelineItem>.withCanonicalEventOrder(
    canonicalTimestamp: (UniqueId) -> Long?,
): List<MatrixTimelineItem> {
    val events = filterIsInstance<MatrixTimelineItem.Event>()
    val canonicalEvents = events.mapIndexedNotNull { index, event ->
        canonicalTimestamp(event.displayUniqueId)?.let { timestamp -> CanonicalEvent(event, timestamp, index) }
    }.sortedWith(compareBy<CanonicalEvent> { it.timestamp }.thenBy { it.originalIndex })
    if (canonicalEvents.isEmpty()) return this

    val canonicalIds = canonicalEvents.mapTo(mutableSetOf()) { it.event.displayUniqueId }
    val ordinaryEvents = events.filter { it.displayUniqueId !in canonicalIds }
    val mergedEvents = ArrayList<MatrixTimelineItem.Event>(events.size)
    var canonicalIndex = 0
    ordinaryEvents.forEach { ordinaryEvent ->
        while (canonicalIndex < canonicalEvents.size && canonicalEvents[canonicalIndex].timestamp <= ordinaryEvent.event.timestamp) {
            mergedEvents += canonicalEvents[canonicalIndex++].event
        }
        mergedEvents += ordinaryEvent
    }
    while (canonicalIndex < canonicalEvents.size) {
        mergedEvents += canonicalEvents[canonicalIndex++].event
    }

    val eventIterator = mergedEvents.iterator()
    return map { item -> if (item is MatrixTimelineItem.Event) eventIterator.next() else item }
}

private data class CanonicalEvent(
    val event: MatrixTimelineItem.Event,
    val timestamp: Long,
    val originalIndex: Int,
)

private fun MatrixTimelineItem.isTrailingVirtualItem(): Boolean {
    val virtualItem = (this as? MatrixTimelineItem.Virtual)?.virtual ?: return false
    return when (virtualItem) {
        VirtualTimelineItem.TypingNotification,
        VirtualTimelineItem.LastForwardIndicator -> true
        is VirtualTimelineItem.LoadingIndicator -> virtualItem.direction == Timeline.PaginationDirection.FORWARDS
        VirtualTimelineItem.ReadMarker,
        VirtualTimelineItem.RoomBeginning,
        is VirtualTimelineItem.DayDivider -> false
    }
}

private fun MatrixTimelineItem.Event.serverIdentity(): String =
    eventId?.let { "event:${it.value}" } ?: "unique:${uniqueId.value}"

/**
 * The SDK exposes event OR transaction identity, not both. Preserve an already-bound own local
 * row during the exact SDK unique-id upgrade; this never matches by body or timestamp. Once the
 * event is known, rememberConfirmedDisplayId migrates the alias to its authoritative event ID.
 */
private fun MatrixTimelineItem.Event.priorSdkLocalIdentity(): String? =
    if (event.isOwn && eventId != null) "unique:${uniqueId.value}" else null

/** SDK replacement can expose both forms in one snapshot. Only an exact own transaction is equivalent. */
private fun List<MatrixTimelineItem>.withoutConfirmedSdkLocalEchoes(): List<MatrixTimelineItem> {
    val confirmedTransactions = filterIsInstance<MatrixTimelineItem.Event>()
        .filter { it.event.isOwn && it.event.isRemote && it.eventId != null }
        .mapNotNullTo(mutableSetOf()) { it.transactionId }
    if (confirmedTransactions.isEmpty()) return this
    return filterNot { item ->
        item is MatrixTimelineItem.Event && item.event.isOwn && item.isSdkManagedLocalEcho() &&
            item.transactionId in confirmedTransactions
    }
}

/**
 * The Rust timeline may briefly contain the same confirmed event in two slots while a local echo
 * is replaced and a second sync carries the transaction id. Keep raw SDK indexing untouched and
 * collapse only the presentation snapshot. A redaction or fail-closed protected shell always wins
 * over stale plaintext; items at the same security rank use the newest SDK projection.
 */
private fun List<MatrixTimelineItem>.coalesceDuplicateRemoteEvents(): List<MatrixTimelineItem> {
    if (size < 2) return this
    val preferredIndexByEventId = mutableMapOf<EventId, Int>()
    forEachIndexed { index, item ->
        val event = item as? MatrixTimelineItem.Event ?: return@forEachIndexed
        val eventId = event.eventId ?: return@forEachIndexed
        val previousIndex = preferredIndexByEventId[eventId]
        if (previousIndex == null) {
            preferredIndexByEventId[eventId] = index
        } else {
            val previous = this[previousIndex] as MatrixTimelineItem.Event
            if (event.presentationSecurityRank() >= previous.presentationSecurityRank()) {
                preferredIndexByEventId[eventId] = index
            }
        }
    }
    val duplicateEventIds = groupingBy { item ->
        (item as? MatrixTimelineItem.Event)?.eventId
    }.eachCount().filter { (eventId, count) -> eventId != null && count > 1 }.keys.filterNotNull()
    if (duplicateEventIds.isEmpty()) return this
    duplicateEventIds.forEach { eventId ->
        Timber.tag("YiZheTimelineIdentity").w(
            "duplicate_server_event_coalesced eventId=%s candidateCount=%d",
            eventId.value,
            count { item -> (item as? MatrixTimelineItem.Event)?.eventId == eventId },
        )
    }
    return filterIndexed { index, item ->
        val eventId = (item as? MatrixTimelineItem.Event)?.eventId
        eventId == null || preferredIndexByEventId[eventId] == index
    }
}

private fun MatrixTimelineItem.Event.presentationSecurityRank(): Int = when {
    event.content is RedactedContent -> 2
    protectedMessageStatus() != null -> 1
    else -> 0
}

private fun List<MatrixTimelineItem>.withoutEmptyDayDividers(): List<MatrixTimelineItem> = filterIndexed { index, item ->
    if (item !is MatrixTimelineItem.Virtual || item.virtual !is VirtualTimelineItem.DayDivider) return@filterIndexed true
    asSequence()
        .drop(index + 1)
        .takeWhile { candidate ->
            candidate !is MatrixTimelineItem.Virtual || candidate.virtual !is VirtualTimelineItem.DayDivider
        }
        .any { it is MatrixTimelineItem.Event }
}

private fun MatrixTimelineItem.isWithinLocalEchoWindow(localEcho: OptimisticLocalEcho): Boolean {
    val eventItem = this as? MatrixTimelineItem.Event ?: return false
    val delta = eventItem.event.timestamp - localEcho.item.event.timestamp
    return delta in -LOCAL_ECHO_CLOCK_TOLERANCE_MILLIS..LOCAL_ECHO_MATCH_WINDOW_MILLIS
}

private fun List<MatrixTimelineItem>.closestMatchingIndex(
    localEcho: OptimisticLocalEcho,
    excludedIndices: Set<Int>,
    predicate: (MatrixTimelineItem) -> Boolean,
): Int = indices
    .asSequence()
    .filter { it !in excludedIndices && predicate(this[it]) }
    .minByOrNull { index ->
        val eventItem = this[index] as MatrixTimelineItem.Event
        kotlin.math.abs(eventItem.event.timestamp - localEcho.item.event.timestamp)
    } ?: -1

private fun MatrixTimelineItem.Event.withSdkMediaUploadProgress(sdkItem: MatrixTimelineItem): MatrixTimelineItem.Event {
    val sdkEvent = (sdkItem as? MatrixTimelineItem.Event)?.event ?: return this
    val sdkSendState = sdkEvent.localSendState
    val uploadState = sdkSendState as? LocalEventSendState.Sending.MediaWithProgress
    val messageContent = event.content as? MessageContent ?: return this
    val uploadProgress = uploadState?.let { state ->
        if (state.total > 0L) state.progress.toFloat() / state.total.toFloat() else 0f
    }
    val overallProgress = uploadProgress?.let {
        MEDIA_ENCRYPTION_PROGRESS_END + it.coerceIn(0f, 1f) * MEDIA_UPLOAD_PROGRESS_WEIGHT
    }
    return copy(
        event = event.copy(
            localSendState = sdkSendState ?: event.localSendState,
            content = messageContent.copy(
                localMediaProgress = maxOf(messageContent.localMediaProgress ?: 0f, overallProgress ?: 0f)
            ),
        )
    )
}

private const val MAX_OPTIMISTIC_LOCAL_ECHOES = 64
private const val OPTIMISTIC_LOCAL_ECHO_PREFIX = "optimistic:"
private const val OPTIMISTIC_MEDIA_ECHO_PREFIX = "optimistic-media:"
private const val OUTBOX_WAITING_ERROR_CODE = "WAITING_FOR_SECURE_NETWORK_AUTHORIZATION"
private const val LOCAL_ECHO_CLOCK_TOLERANCE_MILLIS = 5_000L
private const val LOCAL_ECHO_MATCH_WINDOW_MILLIS = 120_000L
private const val MEDIA_ENCRYPTION_PROGRESS_END = 0.45f
private const val MEDIA_UPLOAD_PROGRESS_WEIGHT = 1f - MEDIA_ENCRYPTION_PROGRESS_END

sealed interface EventFocusResult {
    data object FocusedOnLive : EventFocusResult
    data class IsInThread(val threadId: ThreadId) : EventFocusResult
}
