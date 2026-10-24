/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import io.element.android.features.discovery.impl.util.discoveryTextFits

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.network.DiscoveryTarget
import io.element.android.features.discovery.impl.network.PinnedDiscoveryTransport
import io.element.android.features.discovery.impl.session.DefaultDiscoveryAccess
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.*

internal fun memberBusinessDate(): LocalDate = LocalDate.now(ZoneOffset.ofHours(8))

internal data class MemberPersonRecord(
    val target: PersonEditTarget, val relationId: Long?, val draft: MemberPersonDraft,
    val maskedPhone: String, val completion: Int, val missingFields: List<String>,
) {
    val identity get() = MemberIdentity(requireNotNull(target.userId),
        draft.fullName.ifBlank { draft.nickname }.ifBlank { "—" }, target.kind == PersonEditKind.CHILD, maskedPhone, completion)
    fun snapshot(target: PersonEditTarget = this.target) = PersonEditorSnapshot(target, draft, maskedPhone)
    override fun toString() = "MemberPersonRecord(<redacted>)"
}

@Inject
internal class DiscoveryPersonReader(private val access: DefaultDiscoveryAccess, private val transport: PinnedDiscoveryTransport) {
    suspend fun self(): MemberPersonRecord = query(buildJsonObject { put("kind", "profile") }) { parseMemberPerson(it, child = false) }
    suspend fun child(id: Long): MemberPersonRecord {
        require(id in 1..Int.MAX_VALUE.toLong())
        return query(buildJsonObject { put("kind", "child"); put("child_user_id", id) }) {
            parseMemberPerson(it, child = true).also { person -> require(person.target.userId == id) }
        }
    }
    suspend fun children(): List<MemberPersonRecord> = access.withSession { session ->
        readAllMemberChildren { page ->
            val request = buildJsonObject { put("kind", "children"); put("page", page); put("page_size", 20) }
            post(request, session.secret.value) { parseMemberChildren(it, page, 20) }
        }
    }
    private suspend fun <T> query(request: JsonObject, parse: (JsonObject) -> T): T = access.withSession { session ->
        post(request, session.secret.value, parse)
    }
    private suspend fun <T> post(request: JsonObject, secret: String, parse: (JsonObject) -> T): T {
        val body = request.toString().encodeToByteArray()
        try {
            val response = transport.post(DiscoveryTarget.GATEWAY, "/v1/discovery/business/member/query", body,
                mapOf("Authorization" to "Bearer $secret"))
            try { return parse(Json.parseToJsonElement(response.decodeToString()).jsonObject) } finally { response.fill(0) }
        } finally { body.fill(0) }
    }
}

internal val adultPersonFields = setOf("nickname", "full_name", "email", "gender", "birthdate", "city", "occupation",
    "wechat_id", "emergency_contact_name", "emergency_contact_phone")
internal val childPersonFields = setOf("full_name", "relation_type", "gender", "birthdate", "school", "grade", "is_primary")

internal fun personForm(draft: MemberPersonDraft, child: Boolean): JsonObject = buildJsonObject {
    val normalized = draft.normalized()
    fun optional(key: String, value: String) { put(key, value.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull) }
    optional("full_name", normalized.fullName)
    put("gender", if (normalized.gender == MemberGender.UNSET) JsonNull else JsonPrimitive(normalized.gender.name.lowercase()))
    optional("birthdate", normalized.birthdate)
    if (child) {
        put("relation_type", normalized.relation.name.lowercase()); put("is_primary", normalized.primaryGuardian)
        optional("school", normalized.school); optional("grade", normalized.grade)
    } else {
        optional("nickname", normalized.nickname); optional("email", normalized.email)
        optional("city", normalized.city); optional("occupation", normalized.occupation); optional("wechat_id", normalized.wechatId)
        optional("emergency_contact_name", normalized.emergencyContactName); optional("emergency_contact_phone", normalized.emergencyContactPhone)
    }
}

internal fun parsePersonForm(value: JsonObject, child: Boolean, today: LocalDate = memberBusinessDate()): MemberPersonDraft {
    require(value.keys == (if (child) childPersonFields else adultPersonFields))
    fun optional(key: String, maximum: Int): String {
        val element = value.getValue(key)
        if (element == JsonNull) return ""
        return element.jsonPrimitive.also { require(it.isString) }.content.also {
            require(it == it.trim() && it.discoveryTextFits(maximum) && it.none { character -> character.isISOControl() })
        }
    }
    val gender = when (value.getValue("gender")) {
        JsonNull -> MemberGender.UNSET
        JsonPrimitive("male") -> MemberGender.MALE
        JsonPrimitive("female") -> MemberGender.FEMALE
        JsonPrimitive("other") -> MemberGender.OTHER
        else -> error("Invalid profile gender")
    }
    val draft = if (child) MemberPersonDraft(fullName = optional("full_name", 120), gender = gender,
        birthdate = optional("birthdate", 10), school = optional("school", 120), grade = optional("grade", 80),
        relation = when (value.getValue("relation_type")) {
            JsonPrimitive("mother") -> MemberRelation.MOTHER; JsonPrimitive("father") -> MemberRelation.FATHER
            JsonPrimitive("guardian") -> MemberRelation.GUARDIAN; JsonPrimitive("other") -> MemberRelation.OTHER
            else -> error("Invalid guardian relation")
        }, primaryGuardian = value.getValue("is_primary").jsonPrimitive.also { require(!it.isString) }.boolean)
    else MemberPersonDraft(fullName = optional("full_name", 120), nickname = optional("nickname", 120), email = optional("email", 120),
        gender = gender, birthdate = optional("birthdate", 10), city = optional("city", 100), occupation = optional("occupation", 120),
        wechatId = optional("wechat_id", 120), emergencyContactName = optional("emergency_contact_name", 120),
        emergencyContactPhone = optional("emergency_contact_phone", 20))
    require(draft.issues(PersonEditTarget(if (child) PersonEditKind.CHILD else PersonEditKind.SELF, 1), today).isEmpty())
    return draft
}

internal fun parseMemberPerson(value: JsonObject, child: Boolean): MemberPersonRecord {
    val fields = if (child) childPersonFields else adultPersonFields
    val ids = if (child) setOf("child_user_id", "relation_id") else setOf("user_id")
    require(value.keys == fields + ids + setOf("user_type", "avatar_media_ref", "masked_phone", "completion"))
    require(value["user_type"] == JsonPrimitive(if (child) "child" else "adult") && value["avatar_media_ref"] == JsonNull)
    val id = value.personId(if (child) "child_user_id" else "user_id")
    val draft = parsePersonForm(JsonObject(value.filterKeys { it in fields }), child)
    val phone = value.getValue("masked_phone").let { element ->
        if (element == JsonNull) "" else element.jsonPrimitive.also { require(it.isString) }.content.also {
            require(it.matches(Regex("[0-9]{3}\\*{4}[0-9]{4}")))
        }
    }
    val completion = value.getValue("completion").jsonObject
    require(completion.keys == setOf("percent", "completed", "total", "missing_fields"))
    val percent = completion.personNumber("percent").also { require(it in 0..100) }
    val total = completion.personNumber("total").also { require(it > 0) }
    require(completion.personNumber("completed") in 0..total)
    val missing = completion.getValue("missing_fields").jsonArray.also { require(it.size <= 20) }.map {
        it.jsonPrimitive.also { field -> require(field.isString && field.content.length in 1..120) }.content
    }
    return MemberPersonRecord(PersonEditTarget(if (child) PersonEditKind.CHILD else PersonEditKind.SELF, id),
        if (child) value.personId("relation_id") else null, draft, phone, percent.toInt(), missing)
}

internal data class MemberChildrenPage(val rows: List<MemberPersonRecord>, val total: Long)
internal fun parseMemberChildren(value: JsonObject, page: Int, size: Int): MemberChildrenPage {
    require(value.keys == setOf("items", "total", "page", "page_size"))
    require(value.personNumber("page") == page.toLong() && value.personNumber("page_size") == size.toLong())
    val total = value.personNumber("total").also { require(it in 0..Int.MAX_VALUE.toLong()) }
    val items = value.getValue("items").jsonArray
    require(items.size.toLong() == minOf(size.toLong(), (total - (page - 1L) * size).coerceAtLeast(0)))
    return MemberChildrenPage(items.map { parseMemberPerson(it.jsonObject, child = true) }, total)
}
internal suspend fun readAllMemberChildren(read: suspend (Int) -> MemberChildrenPage): List<MemberPersonRecord> {
    val rows = linkedMapOf<Long, MemberPersonRecord>()
    val relations = mutableSetOf<Long>()
    var total: Long? = null
    for (page in 1..400) {
        val current = read(page)
        require(total == null || current.total == total)
        total = current.total
        current.rows.forEach { row ->
            require(rows.put(requireNotNull(row.target.userId), row) == null && relations.add(requireNotNull(row.relationId)))
        }
        if (rows.size.toLong() == total) return rows.values.toList()
        require(current.rows.isNotEmpty() && rows.size.toLong() < current.total)
    }
    error("Incomplete authorized children list")
}
internal fun JsonObject.personNumber(key: String): Long = getValue(key).jsonPrimitive.also { require(!it.isString) }.long
internal fun JsonObject.personId(key: String): Long = personNumber(key).also { require(it in 1..Int.MAX_VALUE.toLong()) }
