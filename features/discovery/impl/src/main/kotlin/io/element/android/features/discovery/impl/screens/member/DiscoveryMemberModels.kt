/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

internal enum class MemberOrderSource { COURSE, MALL }
internal enum class MemberOrderFilter { ALL, PENDING_PAYMENT, USABLE, SHIPPING }
internal enum class MemberServiceKind { PASS, GROUP, MEALS, ACCOMMODATION, MATERIALS, NOTICES, PICKUP, TEACHERS, FEEDBACK, CONSULT }

/** Navigation retains identifiers only; never names, addresses, form data or backend payloads. */
internal sealed interface MemberRoute : Parcelable {
    @Parcelize data object Services : MemberRoute
    @Parcelize data object Profile : MemberRoute
    @Parcelize data class Orders(val filter: MemberOrderFilter = MemberOrderFilter.ALL) : MemberRoute
    @Parcelize data class Order(val source: MemberOrderSource, val id: String) : MemberRoute
    @Parcelize data object Identities : MemberRoute
    @Parcelize data object Children : MemberRoute
    @Parcelize data object Addresses : MemberRoute
    @Parcelize data class EditPerson(val kind: PersonEditKind, val userId: Long?) : MemberRoute {
        init { PersonEditTarget(kind, userId) }
    }
    @Parcelize data class Service(val sessionId: Long, val enrollmentId: String, val kind: MemberServiceKind) : MemberRoute
}

internal data class MemberIdentity(val id: Long, val name: String, val child: Boolean, val subtitle: String, val completion: Int?) {
    init { require(id > 0 && name.isNotBlank() && (completion == null || completion in 0..100)) }
}
internal data class MemberCourse(
    val courseId: Long, val sessionId: Long, val enrollmentId: String, val title: String,
    val participant: String, val period: String, val status: String, val location: String,
) {
    init { require(courseId > 0 && sessionId > 0 && enrollmentId.isNotBlank()) }
    fun service(kind: MemberServiceKind) = MemberRoute.Service(sessionId, enrollmentId, kind)
}
internal data class MemberServiceItem(val kind: MemberServiceKind, val status: String, val available: Boolean)
internal data class MemberServiceSummary(val course: MemberCourse, val items: List<MemberServiceItem>, val bring: String, val note: String) {
    init { require(items.map { it.kind }.distinct().size == items.size) }
}
internal data class MemberOrderSummary(
    val source: MemberOrderSource, val id: String, val number: String, val title: String,
    val person: String, val status: String, val amountFen: Long, val createdAt: String,
) {
    init { require(id.isNotBlank() && amountFen >= 0) }
    val route get() = MemberRoute.Order(source, id)
    val key get() = "${source.name}:$id"
}
internal data class MemberField(val label: String, val value: String)
internal data class MemberTeacher(val id: Long, val name: String, val role: String, val phone: String?)
internal data class MemberDetailGroup(val title: String, val fields: List<MemberField>)

/** Bounded authorised projections. Shared wire adapters must validate identity and map the original business fields. */
internal sealed interface MemberPage {
    val personId: Long
    data class Profile(
        override val personId: Long, val identity: MemberIdentity, val courses: List<MemberCourse>,
        val orderCounts: Map<MemberOrderFilter, Int>, val learningFacts: List<MemberField>,
    ) : MemberPage {
        init { require(identity.id == personId && orderCounts.values.all { it >= 0 }) }
    }
    data class Services(override val personId: Long, val sessions: List<MemberServiceSummary>) : MemberPage {
        init { require(sessions.map { it.course.enrollmentId }.distinct().size == sessions.size) }
    }
    data class Orders(
        override val personId: Long, val filter: MemberOrderFilter, val rows: List<MemberOrderSummary>,
        val nextCursor: String?,
    ) : MemberPage {
        init { require(rows.map { it.key }.distinct().size == rows.size && (nextCursor == null || nextCursor.isNotBlank())) }
    }
    data class Order(
        override val personId: Long, val summary: MemberOrderSummary, val fields: List<MemberField>,
        val course: MemberCourse?, val services: List<MemberServiceKind>,
    ) : MemberPage
    data class Identities(override val personId: Long, val people: List<MemberIdentity>) : MemberPage {
        init {
            require(people.count { !it.child } == 1 && people.map { it.id }.distinct().size == people.size)
            require(people.any { it.id == personId })
        }
    }
    data class Service(
        override val personId: Long, val route: MemberRoute.Service, val courseTitle: String, val participant: String,
        val status: String, val groups: List<MemberDetailGroup>, val teachers: List<MemberTeacher> = emptyList(),
    ) : MemberPage
}

internal fun MemberPage.matches(route: MemberRoute, identity: Long): Boolean = personId == identity && when (route) {
    MemberRoute.Profile -> this is MemberPage.Profile
    MemberRoute.Services -> this is MemberPage.Services
    MemberRoute.Identities, MemberRoute.Children -> this is MemberPage.Identities
    is MemberRoute.Orders -> this is MemberPage.Orders && filter == route.filter
    is MemberRoute.Order -> this is MemberPage.Order && summary.source == route.source && summary.id == route.id
    is MemberRoute.Service -> this is MemberPage.Service && this.route == route
    is MemberRoute.EditPerson, MemberRoute.Addresses -> false // Editors own their read and protected write lifecycle.
}

internal data class DiscoveryMemberState(
    val route: MemberRoute, val loading: Boolean = true, val failed: Boolean = false,
    val page: MemberPage? = null, val loadingMore: Boolean = false, val moreFailed: Boolean = false,
    val reload: () -> Unit, val more: () -> Unit,
)

/** Pure read boundary; no production fallback. Bind only to accepted Discovery HTTP queries, never mini JWT routes. */
internal interface DiscoveryMemberReader {
    suspend fun load(route: MemberRoute, personId: Long, cursor: String? = null): MemberPage
}
