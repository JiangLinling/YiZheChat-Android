/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight

/** Layout fixtures only; these projections are never supplied to the production reader or navigation. */
private class MemberPreviewStates : PreviewParameterProvider<DiscoveryMemberState> {
    private val self = MemberIdentity(1, "家长", false, "本人", 90)
    private val child = MemberIdentity(2, "小禾", true, "一年级", 70)
    private val course = MemberCourse(1, 10, "preview-enrollment", "亲子共读课程", "小禾", "11 月 1 日至 3 日", "已报名", "书院")
    private val order = MemberOrderSummary(MemberOrderSource.COURSE, "preview-order", "PREVIEW-001", course.title,
        child.name, "待付款", 10000, "2026-10-24")
    private fun state(route: MemberRoute, page: MemberPage) = DiscoveryMemberState(route, loading = false, page = page, reload = {}, more = {})
    override val values = sequenceOf(
        DiscoveryMemberState(MemberRoute.Profile, reload = {}, more = {}),
        DiscoveryMemberState(MemberRoute.Services, loading = false, failed = true, reload = {}, more = {}),
        state(MemberRoute.Profile, MemberPage.Profile(1, self, listOf(course), mapOf(MemberOrderFilter.PENDING_PAYMENT to 1), emptyList())),
        state(MemberRoute.Services, MemberPage.Services(1, emptyList())),
        state(MemberRoute.Services, MemberPage.Services(1, listOf(MemberServiceSummary(course,
            listOf(MemberServiceItem(MemberServiceKind.TEACHERS, "已安排", true), MemberServiceItem(MemberServiceKind.MEALS, "已选择餐食", true)),
            "水杯、笔记本", "请按课程时间到场")))),
        state(MemberRoute.Orders(), MemberPage.Orders(1, MemberOrderFilter.ALL, listOf(order,
            order.copy(source = MemberOrderSource.MALL, title = "共读笔记本", status = "已发货", amountFen = 2000)), null)),
        state(order.route, MemberPage.Order(1, order, listOf(MemberField("订单时间", order.createdAt)), course, listOf(MemberServiceKind.TEACHERS))),
        state(MemberRoute.Identities, MemberPage.Identities(1, listOf(self, child))),
        state(MemberRoute.Children, MemberPage.Identities(1, listOf(self, child))),
        state(course.service(MemberServiceKind.TEACHERS), MemberPage.Service(1, course.service(MemberServiceKind.TEACHERS),
            course.title, course.participant, "带班安排", emptyList(), listOf(MemberTeacher(1, "张老师", "主带班", null)))),
    )
}

@PreviewsDayNight
@Composable
private fun MemberPreview(@PreviewParameter(MemberPreviewStates::class) state: DiscoveryMemberState) = ElementPreview {
    DiscoveryMemberView(state, DiscoveryMemberActions(back = {}, route = {}, course = { _, _ -> }, courses = {}, personal = {},
        search = {}, editPerson = {}, selectIdentity = {}, addresses = {}, activities = {}, consult = {}, dial = {}))
}
