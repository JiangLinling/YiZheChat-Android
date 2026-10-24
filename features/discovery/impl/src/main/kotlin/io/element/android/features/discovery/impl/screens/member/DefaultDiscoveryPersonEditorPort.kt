/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.actions.*
import java.util.UUID
import kotlinx.serialization.json.*

@Inject
internal class DefaultDiscoveryPersonEditorPort(private val reader: DiscoveryPersonReader, private val actions: DiscoveryBusinessActions) : DiscoveryPersonEditorPort {
    override suspend fun read(target: PersonEditTarget): PersonEditorSnapshot = when (target.kind) {
        PersonEditKind.NEW_CHILD -> PersonEditorSnapshot(target, MemberPersonDraft(), "")
        PersonEditKind.SELF -> reader.self().also { require(it.target == target) }.snapshot()
        PersonEditKind.CHILD -> reader.child(requireNotNull(target.userId)).also { require(it.target == target) }.snapshot()
    }
    override suspend fun save(target: PersonEditTarget, draft: MemberPersonDraft): PersonEditOutcome {
        require(draft.normalized().issues(target, memberBusinessDate()).isEmpty())
        // Bind SELF to the actual current linked adult; the id is never a client-specified write owner.
        if (target.kind == PersonEditKind.SELF) require(reader.self().target == target)
        return execute(target, DiscoveryPersonCommand(UUID.randomUUID().toString(), target.kind != PersonEditKind.SELF,
            if (target.kind == PersonEditKind.CHILD) target.userId else null, personForm(draft, target.kind != PersonEditKind.SELF)))
            ?: PersonEditOutcome.Pending
    }
    override suspend fun recover(target: PersonEditTarget): PersonEditOutcome {
        val saved = actions.personResult()
        if (saved != null && actions.pendingPerson()?.operationId != saved.operationId) {
            require(saved.target == target)
            return PersonEditOutcome.SavedRefresh(saved)
        }
        val result = execute(target, null)
        if (result != null) return result
        val locator = actions.personResult() ?: return PersonEditOutcome.Clear
        require(locator.target == target) { "A different saved person requires acknowledgement." }
        return PersonEditOutcome.SavedRefresh(locator)
    }
    private suspend fun execute(target: PersonEditTarget, command: DiscoveryPersonCommand?): PersonEditOutcome? =
        actions.execute<PersonEditOutcome>(command, accepts = { it is DiscoveryPersonCommand && it.matches(target) },
            confirmedPerson = { (it as? PersonEditOutcome.SavedRefresh)?.locator }) { original, receipt ->
            require(original is DiscoveryPersonCommand)
            require(receipt["operation_id"] == JsonPrimitive(original.operationId))
            when (receipt["outcome"]) {
                JsonPrimitive("REJECTED") -> {
                    require(receipt.keys == setOf("operation_id", "outcome", "reason"))
                    val reason = receipt.getValue("reason").jsonPrimitive
                    require(reason.isString && reason.content.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
                    PersonEditOutcome.Rejected
                }
                JsonPrimitive("APPLIED") -> {
                    val keys = if (original.child) setOf("child_user_id", "relation_id") else setOf("user_id")
                    require(receipt.keys == setOf("operation_id", "outcome") + keys)
                    PersonEditOutcome.SavedRefresh(DiscoveryPersonResultLocator(original.operationId, target,
                        receipt.personId(if (original.child) "child_user_id" else "user_id"),
                        if (original.child) receipt.personId("relation_id") else null))
                }
                else -> error("Invalid profile receipt")
            }
        }
    override suspend fun refresh(locator: DiscoveryPersonResultLocator): PersonEditResult {
        require(actions.personResult() == locator)
        val person = if (locator.target.kind == PersonEditKind.SELF) reader.self() else reader.child(locator.userId)
        require(person.target.userId == locator.userId && person.relationId == locator.relationId)
        return PersonEditResult(locator.target, person.identity, locator)
    }
    override suspend fun acknowledge(result: PersonEditResult) {
        val locator = requireNotNull(result.locator)
        require(result.target == locator.target && result.person.id == locator.userId)
        actions.dismissPersonResult(locator)
    }
}
