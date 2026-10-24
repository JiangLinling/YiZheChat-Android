/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.session

import io.element.android.features.discovery.api.DiscoveryAccess
import io.element.android.features.discovery.api.DiscoveryAccessState
import io.element.android.features.discovery.api.DiscoveryError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** All authority remains outside this guest. Observe and discard; never drive host lifecycle. */
internal class DiscoverySessionManager(
    private val gate: DiscoveryIdentityGate,
    private val authority: DiscoveryAuthorityClient,
    private val gateway: DiscoveryGatewayClient,
    private val clock: DiscoveryClock,
    private val coroutineScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : DiscoveryAccess {
    override val configured = true
    private val mutableState = MutableStateFlow(DiscoveryAccessState())
    override val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var generation = 0L
    private var handoff: DiscoveryCode? = null
    private var session: DiscoverySession? = null
    private var operation: Job? = null
    private var expiry: Job? = null
    private var owner: DiscoveryAccountScope? = null

    init {
        coroutineScope.launch(dispatcher) {
            gate.scope.collect { next ->
                if (owner != next) {
                    generation++
                    owner = next
                    operation?.cancel()
                    expiry?.cancel()
                    handoff = null
                    session = null
                    mutableState.value = DiscoveryAccessState()
                }
            }
        }
        coroutineScope.launch(dispatcher) {
            while (isActive) {
                delay(1_000)
                // Signed host snapshots/managed licenses can expire without a Flow emission.
                if (state.value.entryVisible || state.value.busy) {
                    val current = currentIdentity()
                    if (current == null || current != owner) {
                        operation?.cancel()
                        invalidate()
                    }
                }
            }
        }
    }

    override suspend fun refreshAccess() {
        withContext(dispatcher) { perform(enter = state.value.entered, refresh = true) }
    }

    override suspend fun enter(): Boolean = withContext(dispatcher) { perform(enter = true, refresh = false) }

    internal suspend fun <T> withSession(block: suspend (DiscoverySession) -> T): T = withContext(dispatcher) {
        val identity = owner ?: throw DiscoveryFailure(401)
        val epoch = generation
        val credential = session ?: throw DiscoveryFailure(401)
        suspend fun check() {
            checkCurrent(identity, epoch)
            if (!state.value.entered || clock.wallMillis() >= credential.expiresAtMillis ||
                clock.elapsedMillis() >= credential.expiresAtElapsedMillis) throw DiscoveryFailure(401)
        }
        try {
            check()
            block(credential).also { check() }
        } catch (failure: DiscoveryFailure) {
            // A background refresh may already have replaced this request's session.
            // Its late rejection/expiry must not clear the newer, independently admitted session.
            if (epoch == generation && session === credential && failure.status in setOf(401, 403, 409)) invalidate()
            throw failure
        }
    }

    private suspend fun perform(enter: Boolean, refresh: Boolean): Boolean = mutex.withLock {
        val identity = currentIdentity() ?: run {
            invalidate()
            return@withLock false
        }
        // A newly observed owner must not inherit another request's proof or result.
        if (owner != identity) {
            invalidate()
            owner = identity
        }
        if (enter && !refresh && state.value.entered && session?.let {
            clock.wallMillis() < it.expiresAtMillis && clock.elapsedMillis() < it.expiresAtElapsedMillis
        } == true) return@withLock true
        val epoch = generation
        operation = currentCoroutineContext()[Job]
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        try {
            val code = handoff?.takeIf { clock.wallMillis() < it.codeExpiresAtMillis &&
                clock.elapsedMillis() - it.startedAtElapsedMillis in 0 until 30_000 } ?: authority.issue(identity)
            checkCurrent(identity, epoch)
            handoff = code
            val allowed = gateway.allowed(code)
            checkCurrent(identity, epoch)
            if (!allowed) {
                invalidate()
                return@withLock false
            }
            if (enter) {
                val result = gateway.exchange(code)
                checkCurrent(identity, epoch)
                if (clock.wallMillis() >= result.expiresAtMillis || clock.elapsedMillis() >= result.expiresAtElapsedMillis) {
                    throw DiscoveryFailure(401)
                }
                session = result
                mutableState.value = DiscoveryAccessState(entryVisible = true, entered = true)
                expireAt(result.expiresAtElapsedMillis)
            } else {
                mutableState.value = mutableState.value.copy(entryVisible = true, busy = false)
                // A capability result is bounded too; no indefinitely cached permission.
                val remaining = minOf(60_000 - (clock.elapsedMillis() - code.startedAtElapsedMillis),
                    code.authorizationExpiresAtMillis - clock.wallMillis())
                if (remaining <= 0) throw DiscoveryFailure(401)
                expireAt(clock.elapsedMillis() + remaining)
            }
            true
        } catch (cancelled: CancellationException) {
            if (epoch == generation) invalidate()
            throw cancelled
        } catch (failure: Exception) {
            if (epoch == generation) {
                // Keep a still-valid handoff for an explicit retry of an unknown result.
                session = null
                expiry?.cancel()
                mutableState.value = DiscoveryAccessState(error = when ((failure as? DiscoveryFailure)?.status) {
                    401 -> DiscoveryError.SESSION_EXPIRED
                    403 -> DiscoveryError.DENIED
                    else -> DiscoveryError.UNAVAILABLE
                })
                if ((failure as? DiscoveryFailure)?.status in setOf(401, 403, 409)) handoff = null
            }
            false
        } finally {
            operation = null
        }
    }

    private suspend fun checkCurrent(identity: DiscoveryAccountScope, epoch: Long) {
        if (generation != epoch || currentIdentity() != identity) throw CancellationException("Discovery identity changed")
    }

    private suspend fun currentIdentity(): DiscoveryAccountScope? = try {
        gate.current()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun expireAt(deadline: Long) {
        expiry?.cancel()
        val epoch = generation
        expiry = coroutineScope.launch(dispatcher) {
            delay((deadline - clock.elapsedMillis()).coerceAtLeast(0))
            if (generation == epoch) invalidate()
        }
    }

    private fun invalidate() {
        generation++
        handoff = null
        session = null
        expiry?.cancel()
        mutableState.value = DiscoveryAccessState()
    }

    override suspend fun leave(): Unit = withContext(dispatcher) {
        val previous = session
        operation?.cancel()
        invalidate()
        if (previous != null) {
            // Local exit is immediate; bounded guest revocation never blocks host logout.
            coroutineScope.launch {
                try {
                    gateway.revoke(previous)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The original absolute deadline still applies remotely.
                }
            }
        }
        Unit
    }
}
