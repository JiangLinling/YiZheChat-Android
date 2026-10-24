/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.member

/** Local, explicit paste-to-form action. Never reads the clipboard or sends pasted text to a service. */
internal fun parseAddressPaste(input: String, current: MemberAddressDraft): MemberAddressDraft {
    val source = input.take(2000).replace(Regex("\\s+"), " ").trim()
    if (source.isBlank()) return current
    val labels = listOf("收件人", "收货人", "联系人", "真实姓名", "姓名", "手机号码", "手机号", "电话", "所在地区", "省市区", "地区", "详细地址", "地址")
    val all = labels.joinToString("|")
    fun labelled(names: String): String = Regex("(?:$names)[:：]?\\s*(.*?)(?=\\s*(?:$all)[:：]|$)").find(source)
        ?.groupValues?.get(1)?.trim(' ', ',', '，', ';', '；').orEmpty()
    fun clean(value: String) = value.replace(Regex("(?:$all)[:：]?"), " ").replace(Regex("[，,;；]"), " ").trim()
    val phones = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)").findAll(source).map { it.value }.distinct().toList()
    val phone = phones.singleOrNull().orEmpty()
    val labelledName = labelled("收件人|收货人|联系人|真实姓名|姓名")
    val candidate = labelledName.substringBefore(' ').takeIf { it.isNotBlank() } ?: if (phone.isNotEmpty()) {
        clean(source.substringBefore(phone)).split(Regex("\\s+")).lastOrNull().orEmpty()
    } else ""
    val name = candidate.takeIf { it.length in 2..80 && !it.any(Char::isDigit) && !Regex("省|市|区|县|镇|路|街|号").containsMatchIn(it) }.orEmpty()
    var region = labelled("所在地区|省市区|地区").ifBlank {
        clean(source).let { if (phone.isNotEmpty()) it.replace(phone, " ") else it }.let { if (name.isNotEmpty()) it.replaceFirst(name, " ") else it }.trim()
    }
    val direct = listOf("北京市", "上海市", "天津市", "重庆市").firstOrNull { region.startsWith(it) }
    var province = ""
    var city = ""
    fun takeRegion(pattern: String): String {
        val match = Regex(pattern).find(region) ?: return ""
        region = region.substring(match.range.last + 1).trim()
        return match.value
    }
    if (direct != null) { province = direct; city = direct; region = region.removePrefix(direct).trim().removePrefix(direct).trim() }
    else {
        province = takeRegion("^[\\p{IsHan}]{2,12}?(?:省|自治区|特别行政区)")
        city = takeRegion("^[\\p{IsHan}]{2,12}?(?:市|自治州|地区|盟)")
    }
    val district = takeRegion("^[\\p{IsHan}]{1,12}?(?:区|县|市|旗)")
    val explicitDetail = labelled("详细地址|地址")
    // A recognized region or explicit label is required before treating the remainder as address detail.
    val detail = if (explicitDetail.isNotEmpty()) explicitDetail else if (district.isNotEmpty()) region else ""
    return current.copy(name = name.ifEmpty { current.name }, phone = phone.ifEmpty { current.phone },
        province = province.ifEmpty { current.province }, city = city.ifEmpty { current.city }, district = district.ifEmpty { current.district },
        detail = detail.ifEmpty { current.detail })
}
