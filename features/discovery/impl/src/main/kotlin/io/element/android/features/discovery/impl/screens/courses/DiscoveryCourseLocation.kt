/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.courses

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.element.android.features.discovery.impl.R

internal data class CourseCoordinate(val latitude: Double, val longitude: Double)

internal fun courseCoordinate(session: DiscoveryCourseSession): CourseCoordinate? {
    val pattern = Regex("(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)")
    for (raw in listOf(session.amap, session.baidu)) {
        val match = pattern.find(raw) ?: continue
        val longitude = match.groupValues[1].toDoubleOrNull() ?: continue
        val latitude = match.groupValues[2].toDoubleOrNull() ?: continue
        if (longitude in -180.0..180.0 && latitude in -90.0..90.0) return CourseCoordinate(latitude, longitude)
    }
    return null
}

internal fun openCourseLocation(context: Context, session: DiscoveryCourseSession) {
    val coordinate = courseCoordinate(session)
    if (coordinate != null) {
        val point = "${coordinate.latitude},${coordinate.longitude}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=$point(${Uri.encode(session.title)})"))
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            return
        }
    }
    val link = listOf(session.amap, session.baidu).firstOrNull { it.startsWith("https://") && it.length <= 500 }
    if (link != null) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Course location", link))
        Toast.makeText(context, context.getString(R.string.screen_discovery_course_map_copied), Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, context.getString(R.string.screen_discovery_course_map_unavailable), Toast.LENGTH_SHORT).show()
    }
}
