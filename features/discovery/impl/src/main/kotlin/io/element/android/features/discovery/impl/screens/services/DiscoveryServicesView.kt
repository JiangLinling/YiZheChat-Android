/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.services

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.discovery.impl.R
import io.element.android.features.discovery.impl.screens.mall.MallCard
import io.element.android.features.discovery.impl.screens.mall.MallFailure
import io.element.android.features.discovery.impl.screens.mall.MallLayout
import io.element.android.features.discovery.impl.screens.services.feedback.FeedbackTarget
import io.element.android.features.discovery.impl.screens.services.feedback.DefaultDiscoveryFeedbackPort
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

@Composable
internal fun DiscoveryServicesView(reader: DiscoveryServicesReader, feedback: DefaultDiscoveryFeedbackPort, onTeachers: (FeedbackTarget) -> Unit,
    onFeedback: (FeedbackTarget) -> Unit,
    onShop: () -> Unit, onOrders: () -> Unit) {
    var rows by remember { mutableStateOf<List<ServiceEnrollment>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }; var retry by remember { mutableIntStateOf(0) }
    var total by remember { mutableLongStateOf(0) }; var loading by remember { mutableStateOf(true) }; var failed by remember { mutableStateOf(false) }
    var resumeFeedback by remember { mutableStateOf<FeedbackTarget?>(null) }; var resumeFailed by remember { mutableStateOf(false) }
    LaunchedEffect(retry) {
        resumeFailed = false
        try { resumeFeedback = feedback.resumableTarget() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { resumeFailed = true }
    }
    LaunchedEffect(page, retry) {
        loading = true; failed = false
        if (page == 1) { rows = emptyList(); total = 0 }
        try {
            val result = reader.enrollments(page)
            if (page > 1) require(result.total == total && result.rows.none { next -> rows.any { it.target.enrollmentId == next.target.enrollmentId } } &&
                (rows.isEmpty() || result.rows.isEmpty() || result.rows.first().target.enrollmentId < rows.last().target.enrollmentId))
            rows = if (page == 1) result.rows else rows + result.rows
            total = result.total
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true }
        loading = false
    }
    Box(Modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.screen_discovery_root_services), style = ElementTheme.typography.fontHeadingLgBold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onShop) { Text(stringResource(R.string.screen_discovery_mall_shop)) }
                    TextButton(onClick = onOrders) { Text(stringResource(R.string.screen_discovery_mall_orders)) }
                    TextButton(onClick = { page = 1; retry++ }) { Text(stringResource(R.string.screen_discovery_refresh)) }
                }
            }
            resumeFeedback?.let { target -> item { TextButton(onClick = { onFeedback(target) }) { Text(stringResource(R.string.screen_discovery_feedback_resume)) } } }
            if (resumeFailed) item { MallFailure { retry++ } }
            items(rows, key = { it.target.enrollmentId }) { enrollment -> ServiceEnrollmentCard(enrollment, onTeachers, onFeedback) }
            item {
                if (loading) CircularProgressIndicator() else if (failed) MallFailure { retry++ }
                else if (rows.isEmpty()) Text(stringResource(R.string.screen_discovery_services_empty), color = ElementTheme.colors.textSecondary)
                else if (rows.size.toLong() < total) TextButton(onClick = { loading = true; page++ }) { Text(stringResource(R.string.screen_discovery_mall_load_more)) }
            }
        }
    }
}

@Composable
internal fun ServiceEnrollmentCard(enrollment: ServiceEnrollment, onTeachers: (FeedbackTarget) -> Unit, onFeedback: (FeedbackTarget) -> Unit) = MallCard {
    Text(enrollment.course, style = ElementTheme.typography.fontHeadingMdBold)
    Text(enrollment.session)
    Text(enrollment.person.ifBlank { stringResource(R.string.screen_discovery_services_person_missing) }, style = ElementTheme.typography.fontBodyLgMedium)
    enrollment.startAt?.let { Text(serviceTime(it), color = ElementTheme.colors.textSecondary) }
    enrollment.endAt?.let { Text(serviceTime(it), color = ElementTheme.colors.textSecondary) }
    TextButton(onClick = { onTeachers(enrollment.target) }) { Text(stringResource(R.string.screen_discovery_services_teachers)) }
    TextButton(onClick = { onFeedback(enrollment.target) }) { Text(stringResource(R.string.screen_discovery_feedback_title)) }
}

private fun serviceTime(value: Long) = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(value))

@Composable
internal fun DiscoveryTeachersView(reader: DiscoveryServicesReader, target: FeedbackTarget, onBack: () -> Unit, modifier: Modifier) {
    var rows by remember { mutableStateOf<List<ServiceTeacher>?>(null) }; var failed by remember { mutableStateOf(false) }; var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(target, retry) {
        rows = null; failed = false
        try { rows = reader.teachers(target) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true }
    }
    MallLayout(R.string.screen_discovery_services_teachers, onBack, modifier) {
        if (failed) item { MallFailure { retry++ } }
        else if (rows == null) item { CircularProgressIndicator() }
        else if (rows!!.isEmpty()) item { Text(stringResource(R.string.screen_discovery_services_no_teachers)) }
        rows?.let { teachers -> items(teachers, key = { it.role }) { TeacherCard(it) } }
    }
}

@Composable
internal fun TeacherCard(teacher: ServiceTeacher) = MallCard {
    val context = LocalContext.current
    var dialFailed by remember(teacher.id, teacher.phone) { mutableStateOf(false) }
    Text(teacher.name, style = ElementTheme.typography.fontHeadingMdBold)
    Text(stringResource(if (teacher.role == "lead") R.string.screen_discovery_services_lead else R.string.screen_discovery_services_assistant),
        color = ElementTheme.colors.textSecondary)
    teacher.phone?.takeIf { it.isNotBlank() }?.let { phone ->
        Text(phone)
        if (phone.matches(Regex("[+0-9() *#-]{1,20}")) && phone.any { it.isDigit() }) TextButton(onClick = {
            try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))
            } catch (_: ActivityNotFoundException) { dialFailed = true } catch (_: SecurityException) { dialFailed = true }
        }) { Text(stringResource(R.string.screen_discovery_services_call)) }
    }
    if (dialFailed) Text(stringResource(R.string.screen_discovery_services_dial_failed), color = ElementTheme.colors.textSecondary)
}
