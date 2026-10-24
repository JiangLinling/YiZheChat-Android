/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses.enrollment

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.actions.DiscoveryBusinessActions
import io.element.android.features.discovery.impl.actions.DiscoveryCourseCommand
import io.element.android.features.discovery.impl.actions.DiscoveryCourseEnrollmentCommand
import io.element.android.features.discovery.impl.actions.DiscoveryCourseQuoteCommand
import io.element.android.features.discovery.impl.actions.DiscoveryEnrollmentResultLocator
import java.util.UUID
import kotlinx.serialization.json.*

/** course-enrollment-v1, using the existing protected journal for both quote and enrollment. */
@Inject
internal class DefaultDiscoveryEnrollmentPort(
    private val reader: DiscoveryEnrollmentReader,
    private val actions: DiscoveryBusinessActions,
) : DiscoveryEnrollmentPort {
    override suspend fun options(courseId: Long, sessionId: Long) = reader.options(courseId, sessionId).formOptions()

    override suspend fun quote(request: EnrollmentRequest): EnrollmentQuote {
        val options = options(request.courseId, request.sessionId)
        if (options.version != request.optionsVersion) throw EnrollmentQuoteResolvedFailure(options, "OPTIONS_CHANGED")
        val command = DiscoveryCourseQuoteCommand(UUID.randomUUID().toString(), request.courseId, request.sessionId,
            request.optionsVersion, request.selection(options))
        val result = actions.execute(command, accepts = { it == command }) { original, receipt ->
            require(original == command)
            parseQuoteReceipt(receipt, command)
        }
        // A known rejection must retire its journal before the controller is allowed to edit/requote.
        if (result == null || result.rejected) throw EnrollmentQuoteResolvedFailure(reason = result?.reason)
        return EnrollmentQuote(requireNotNull(result.id), request, result.lines, result.total,
            result.expiresAt, requireNotNull(result.selectionHash))
    }

    override suspend fun submit(quote: EnrollmentQuote): EnrollmentOutcome {
        val command = DiscoveryCourseEnrollmentCommand(UUID.randomUUID().toString(), quote.request.courseId, quote.request.sessionId,
            quote.id, requireNotNull(quote.selectionSha256))
        return when (val result = execute(command.courseId, command.sessionId, command)) {
            is EnrollmentRecovery.Confirmed -> EnrollmentOutcome.Confirmed(result.receipt)
            is EnrollmentRecovery.Rejected -> EnrollmentOutcome.Rejected(result.reason)
            else -> EnrollmentOutcome.Pending
        }
    }

    override suspend fun recover(courseId: Long, sessionId: Long): EnrollmentRecovery {
        val recovered = execute(courseId, sessionId, null)
        if (recovered != EnrollmentRecovery.Clear) return recovered
        val locator = actions.enrollmentResult(courseId, sessionId) ?: return EnrollmentRecovery.Clear
        return EnrollmentRecovery.Confirmed(readLocatedResult(locator), locator)
    }

    private suspend fun execute(courseId: Long, sessionId: Long, command: DiscoveryCourseEnrollmentCommand?): EnrollmentRecovery =
        actions.execute<EnrollmentRecovery>(command,
            accepts = { it is DiscoveryCourseCommand && it.courseId == courseId && it.sessionId == sessionId },
            confirmedEnrollment = { (it as? EnrollmentRecovery.Confirmed)?.locator }) { original, value ->
            when (original) {
                is DiscoveryCourseQuoteCommand -> {
                    val result = parseQuoteReceipt(value, original)
                    // A recovered quote is resolved but never enrolled. The page can safely ask for a fresh quote.
                    if (result.rejected) EnrollmentRecovery.Rejected(result.reason) else EnrollmentRecovery.Clear
                }
                is DiscoveryCourseEnrollmentCommand -> {
                    value.exact("operation_id", "outcome", "quote_id", "course_id", "session_id", "selection_sha256",
                        "enrollment_ids", "order_id", "reason")
                    require(value.text("quote_id", 36) == original.quoteId && value.text("selection_sha256", 64) == original.selectionSha256)
                    val ids = value.array("enrollment_ids", 10).map { element ->
                        element.jsonPrimitive.also { require(!it.isString) }.long.also { require(it in 1..Int.MAX_VALUE.toLong()) }
                    }
                    require(ids.distinct().size == ids.size)
                    val order = value.optionalId("order_id")
                    if (value.text("outcome", 16) == "REJECTED") {
                        require(ids.isEmpty() && order == null)
                        EnrollmentRecovery.Rejected(value.reason())
                    } else {
                        require(value.text("outcome", 16) == "APPLIED" && ids.isNotEmpty() && value["reason"] == JsonNull)
                        // If this read fails the original intent stays pending. Recovery reuses the same operation; no new enrollment is created.
                        val current = reader.current(courseId, sessionId, ids.first())
                        require(current.enrollments.map { it.enrollmentId }.toSet() == ids.toSet() && current.order?.orderId == order)
                        EnrollmentRecovery.Confirmed(current.receipt(ids.first()),
                            DiscoveryEnrollmentResultLocator(original.operationId, courseId, sessionId, ids, order))
                    }
                }
                else -> error("Unrelated operation")
            }
        } ?: EnrollmentRecovery.Clear

    override suspend fun current(receipt: EnrollmentReceipt): EnrollmentReceipt {
        actions.enrollmentResult(receipt.courseId, receipt.sessionId)?.let { locator ->
            require(receipt.enrollmentId.toLong() in locator.enrollmentIds && receipt.orderId == locator.orderId?.toString())
            return readLocatedResult(locator)
        }
        val current = reader.current(receipt.courseId, receipt.sessionId, receipt.enrollmentId.toLong())
        require(current.order?.orderId?.toString() == receipt.orderId)
        return current.receipt(receipt.enrollmentId.toLong())
    }

    private suspend fun readLocatedResult(locator: DiscoveryEnrollmentResultLocator): EnrollmentReceipt {
        val current = reader.current(locator.courseId, locator.sessionId, locator.enrollmentIds.first())
        require(current.enrollments.map { it.enrollmentId }.toSet() == locator.enrollmentIds.toSet() && current.order?.orderId == locator.orderId)
        return current.receipt(locator.enrollmentIds.first())
    }

    override suspend fun dismiss(receipt: EnrollmentReceipt) =
        actions.dismissEnrollmentResult(receipt.courseId, receipt.sessionId, receipt.enrollmentId.toLong())
}

internal data class ParsedEnrollmentQuote(val rejected: Boolean, val id: String? = null, val selectionHash: String? = null,
    val lines: List<EnrollmentFeeLine> = emptyList(), val total: Long = 0, val expiresAt: Long = 0, val reason: String? = null)

internal fun parseQuoteReceipt(value: JsonObject, command: DiscoveryCourseQuoteCommand): ParsedEnrollmentQuote {
    value.exact("operation_id", "outcome", "quote_id", "course_id", "session_id", "options_revision", "selection_sha256",
        "expires_at_ms", "fee_detail", "reason")
    require(value.text("operation_id", 36) == command.operationId && value.identifier("course_id") == command.courseId &&
        value.identifier("session_id") == command.sessionId)
    if (value.text("outcome", 16) == "REJECTED") {
        require(listOf("quote_id", "options_revision", "selection_sha256", "expires_at_ms", "fee_detail").all { value[it] == JsonNull })
        return ParsedEnrollmentQuote(rejected = true, reason = value.reason())
    }
    require(value.text("outcome", 16) == "APPLIED" && value["reason"] == JsonNull)
    require(value.text("options_revision", 64) == command.optionsRevision)
    val id = value.text("quote_id", 36).also { require(UUID.fromString(it).toString() == it) }
    val hash = value.text("selection_sha256", 64).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    val expires = value.integer("expires_at_ms").also { require(it > 0) }
    val detail = value.obj("fee_detail")
    detail.exact("base_fee", "accommodation_fee", "meal_fee", "transport_fee", "discount_fee", "total_fee", "participant_count", "unit_base_fee", "items")
    val total = detail.money("total_fee")
    val names = listOf("base_fee", "accommodation_fee", "meal_fee", "transport_fee")
    require(detail.money("discount_fee") == 0L)
    val amounts = names.map { detail.money(it) }
    require(amounts.sum() == total)
    val count = detail.integer("participant_count").also { require(it in 1..10) }
    require(Math.multiplyExact(detail.money("unit_base_fee"), count) == amounts.first())
    val items = detail.array("items", 4)
    require(items.size == 4)
    val keys = listOf(arrayOf("name", "amount", "unit_amount", "count"), arrayOf("name", "amount", "mode", "nights", "unit"),
        arrayOf("name", "amount", "count", "unit"), arrayOf("name", "amount", "mode", "unit"))
    val lines = items.mapIndexed { index, element ->
        val item = element.jsonObject
        item.exact(*keys[index])
        val amount = item.money("amount").also { require(it == amounts[index]) }
        EnrollmentFeeLine(item.text("name", 120).also { require(it.isNotBlank()) }, amount)
    }
    return ParsedEnrollmentQuote(false, id, hash, lines, total, expires)
}

private fun JsonObject.reason() = text("reason", 64).also { require(it.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) }

internal fun EnrollmentCurrentWire.receipt(enrollmentId: Long): EnrollmentReceipt {
    require(enrollments.any { it.enrollmentId == enrollmentId })
    val states = enrollments.map { it.status }.toSet()
    val status = when {
        states == setOf("refunded") -> EnrollmentReceiptStatus.REFUNDED
        "refunding" in states || order?.status == "refunding" -> EnrollmentReceiptStatus.REFUNDING
        states.all { it in setOf("cancelled", "closed", "rejected") } -> EnrollmentReceiptStatus.CLOSED
        states == setOf("waitlisted") -> EnrollmentReceiptStatus.WAITLISTED
        states == setOf("pending_payment") && order?.status == "pending" -> EnrollmentReceiptStatus.PENDING_PAYMENT
        states == setOf("enrolled") && (order == null || order.status == "paid") -> EnrollmentReceiptStatus.ENROLLED
        else -> EnrollmentReceiptStatus.PROCESSING
    }
    val amount = order?.amount ?: enrollments.fold(0L) { sum, row -> Math.addExact(sum, row.amount) }
    return EnrollmentReceipt(courseId, sessionId, enrollmentId.toString(), order?.orderId?.toString(), title, status, amount,
        enrollments.map { it.name }, paymentChannel)
}
