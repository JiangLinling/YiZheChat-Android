/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import java.time.LocalDate
import io.element.android.features.discovery.impl.util.discoveryTextFits
import io.element.android.features.discovery.impl.actions.DiscoveryPersonResultLocator

internal enum class MemberGender { UNSET, MALE, FEMALE, OTHER }
internal enum class MemberRelation { FATHER, MOTHER, GUARDIAN, OTHER }
enum class PersonEditKind { SELF, CHILD, NEW_CHILD }
internal enum class PersonEditField(val limit: Int) {
    FULL_NAME(120), NICKNAME(120), EMAIL(120), BIRTHDATE(10), CITY(100), OCCUPATION(120), WECHAT(120),
    EMERGENCY_NAME(120), EMERGENCY_PHONE(20), SCHOOL(120), GRADE(80);

    fun value(draft: MemberPersonDraft): String = when (this) {
        FULL_NAME -> draft.fullName; NICKNAME -> draft.nickname; EMAIL -> draft.email; BIRTHDATE -> draft.birthdate
        CITY -> draft.city; OCCUPATION -> draft.occupation; WECHAT -> draft.wechatId; EMERGENCY_NAME -> draft.emergencyContactName
        EMERGENCY_PHONE -> draft.emergencyContactPhone; SCHOOL -> draft.school; GRADE -> draft.grade
    }
    fun update(draft: MemberPersonDraft, value: String): MemberPersonDraft = when (this) {
        FULL_NAME -> draft.copy(fullName = value); NICKNAME -> draft.copy(nickname = value); EMAIL -> draft.copy(email = value)
        BIRTHDATE -> draft.copy(birthdate = value); CITY -> draft.copy(city = value); OCCUPATION -> draft.copy(occupation = value)
        WECHAT -> draft.copy(wechatId = value); EMERGENCY_NAME -> draft.copy(emergencyContactName = value)
        EMERGENCY_PHONE -> draft.copy(emergencyContactPhone = value); SCHOOL -> draft.copy(school = value); GRADE -> draft.copy(grade = value)
    }
    companion object {
        fun fields(kind: PersonEditKind): List<PersonEditField> = if (kind == PersonEditKind.SELF) {
            listOf(NICKNAME, FULL_NAME, BIRTHDATE, CITY, OCCUPATION, EMAIL, WECHAT, EMERGENCY_NAME, EMERGENCY_PHONE)
        } else listOf(FULL_NAME, BIRTHDATE, SCHOOL, GRADE)
    }
}

internal data class PersonEditTarget(val kind: PersonEditKind, val userId: Long?) {
    init { require(if (kind == PersonEditKind.NEW_CHILD) userId == null else userId != null && userId in 1..Int.MAX_VALUE.toLong()) }
}

/** Page memory only. Neither private field values nor draft data belong in a restored navigation target. */
internal data class MemberPersonDraft(
    val fullName: String = "",
    val nickname: String = "",
    val email: String = "",
    val gender: MemberGender = MemberGender.UNSET,
    val birthdate: String = "",
    val city: String = "",
    val occupation: String = "",
    val wechatId: String = "",
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    val relation: MemberRelation = MemberRelation.GUARDIAN,
    val school: String = "",
    val grade: String = "",
    val primaryGuardian: Boolean = true,
) {
    override fun toString() = "MemberPersonDraft(<redacted>)"

    fun issues(target: PersonEditTarget, today: LocalDate): Set<PersonEditIssue> = buildSet {
        if (target.kind != PersonEditKind.SELF && fullName.isBlank()) add(PersonEditIssue.NAME)
        if (birthdate.isNotBlank()) {
            val parsed = runCatching { LocalDate.parse(birthdate) }.getOrNull()
            if (!birthdate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || parsed == null || parsed > today) add(PersonEditIssue.BIRTHDATE)
        }
        if (target.kind == PersonEditKind.SELF && email.isNotBlank() &&
            !email.trim().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) add(PersonEditIssue.EMAIL)
        if (PersonEditField.fields(target.kind).any { field ->
                val value = field.value(this@MemberPersonDraft)
                !value.discoveryTextFits(field.limit) || value.any { c -> c.isISOControl() }
            }) add(PersonEditIssue.LENGTH)
    }

    fun normalized() = copy(fullName = fullName.trim(), nickname = nickname.trim(), email = email.trim(), birthdate = birthdate.trim(),
        city = city.trim(), occupation = occupation.trim(), wechatId = wechatId.trim(), emergencyContactName = emergencyContactName.trim(),
        emergencyContactPhone = emergencyContactPhone.trim(), school = school.trim(), grade = grade.trim())
}

internal enum class PersonEditIssue { NAME, BIRTHDATE, EMAIL, LENGTH }
internal enum class PersonEditPhase { LOADING, EDITING, SAVING, UNCERTAIN, SAVED_REFRESH, SAVED, FAILED }
internal enum class PersonEditNotice { LOAD_FAILED, CHECK_ORIGINAL, REJECTED, SAVED_REFRESH_FAILED }

internal data class PersonEditorSnapshot(val target: PersonEditTarget, val draft: MemberPersonDraft, val maskedPhone: String) {
    override fun toString() = "PersonEditorSnapshot(target=$target, draft=<redacted>)"
}
internal data class PersonEditResult(val target: PersonEditTarget, val person: MemberIdentity, val locator: DiscoveryPersonResultLocator? = null) {
    init {
        require(target.userId == null || target.userId == person.id)
        require(person.child == (target.kind != PersonEditKind.SELF))
    }
}
internal sealed interface PersonEditOutcome {
    data class Saved(val result: PersonEditResult) : PersonEditOutcome
    data class SavedRefresh(val locator: DiscoveryPersonResultLocator) : PersonEditOutcome
    data object Pending : PersonEditOutcome
    data object Rejected : PersonEditOutcome
    /** Valid only when recovering before any save, never proof that an uncertain write failed. */
    data object Clear : PersonEditOutcome
}

/** Bind to accepted profile/child APIs and the existing protected operation journal, never an unprotected mini route. */
internal interface DiscoveryPersonEditorPort {
    suspend fun read(target: PersonEditTarget): PersonEditorSnapshot
    suspend fun save(target: PersonEditTarget, draft: MemberPersonDraft): PersonEditOutcome
    suspend fun recover(target: PersonEditTarget): PersonEditOutcome
    suspend fun refresh(locator: DiscoveryPersonResultLocator): PersonEditResult
    suspend fun acknowledge(result: PersonEditResult)
}

internal data class PersonEditorState(
    val target: PersonEditTarget,
    val phase: PersonEditPhase = PersonEditPhase.LOADING,
    val draft: MemberPersonDraft? = null,
    val maskedPhone: String = "",
    val showIssues: Boolean = false,
    val notice: PersonEditNotice? = null,
    val useAfterSave: Boolean = false,
    val result: PersonEditResult? = null,
    val savedLocator: DiscoveryPersonResultLocator? = null,
)
