/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

internal data class MemberAddressDraft(
    val name: String = "", val phone: String = "", val province: String = "", val city: String = "", val district: String = "",
    val detail: String = "", val houseNumber: String = "", val isDefault: Boolean = true,
) {
    override fun toString() = "MemberAddressDraft(<redacted>)"
    fun normalized() = copy(name = name.trim(), phone = phone.trim(), province = province.trim(), city = city.trim(), district = district.trim(),
        detail = detail.trim(), houseNumber = houseNumber.trim())
    /** Source persists one detail field. On edit the adapter leaves houseNumber empty, preventing repeated concatenation. */
    val savedDetail: String get() = listOf(detail.trim(), houseNumber.trim()).filter { it.isNotEmpty() }.joinToString(" ")
    fun issues(): Set<AddressIssue> = buildSet {
        if (name.isBlank()) add(AddressIssue.NAME)
        if (!phone.trim().matches(Regex("1\\d{10}"))) add(AddressIssue.PHONE)
        if (province.isBlank() || city.isBlank() || district.isBlank()) add(AddressIssue.REGION)
        if (detail.isBlank()) add(AddressIssue.DETAIL)
        if (listOf(name, province, city, district).any { it.length > 80 } || phone.length > 30 || savedDetail.length > 300 ||
            listOf(name, phone, province, city, district, detail, houseNumber).any { it.any { c -> c.isISOControl() } }) add(AddressIssue.LENGTH)
    }
}
internal enum class AddressIssue { NAME, PHONE, REGION, DETAIL, LENGTH }
internal data class MemberAddress(val id: Long, val draft: MemberAddressDraft) {
    init { require(id > 0 && draft.houseNumber.isEmpty()) }
}
internal data class AddressNameSuggestion(val personId: Long, val name: String, val phone: String?) {
    init { require(personId > 0 && name.isNotBlank()) }
    override fun toString() = "AddressNameSuggestion(personId=$personId, details=<redacted>)"
}
internal data class MemberAddressBook(val ownerId: Long, val rows: List<MemberAddress>, val names: List<AddressNameSuggestion>) {
    init {
        require(ownerId > 0 && rows.map { it.id }.distinct().size == rows.size)
        require(rows.count { it.draft.isDefault } <= 1 && names.map { it.personId }.distinct().size == names.size)
    }
}
internal sealed interface AddressOutcome {
    data class Saved(val book: MemberAddressBook?, val addressId: Long) : AddressOutcome {
        init { require(addressId > 0 && (book == null || book.rows.any { it.id == addressId })) }
    }
    data object Pending : AddressOutcome
    data object Rejected : AddressOutcome
    data object Clear : AddressOutcome
}
/** Uses current Discovery account ownership, not the selected child as an arbitrary actor. No default implementation. */
internal interface DiscoveryAddressPort {
    suspend fun load(): MemberAddressBook
    suspend fun save(addressId: Long?, draft: MemberAddressDraft): AddressOutcome
    suspend fun recover(): AddressOutcome
}
internal enum class AddressPhase { LOADING, READY, EDITING, SAVING, SAVED, UNCERTAIN, FAILED }
internal data class AddressState(
    val phase: AddressPhase = AddressPhase.LOADING,
    val book: MemberAddressBook? = null,
    val editingId: Long? = null,
    val draft: MemberAddressDraft? = null,
    val paste: String = "",
    val showIssues: Boolean = false,
    val rejected: Boolean = false,
    val savedId: Long? = null,
)
