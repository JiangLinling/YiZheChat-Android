/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.emotions

import io.element.android.features.messages.impl.R

/** Wire format remains an ordinary encrypted text message; no remote executable assets. */
internal data class ChatEmotion(
    val id: String,
    val token: String,
    val label: Int,
    val tier: Int,
    val glyph: String,
    val animated: Boolean,
    val durationMillis: Int,
)

internal object ChatEmotions {
    val all = listOf(
        ChatEmotion("laugh", "😂", R.string.emotion_laugh, 0, "😂", true, 2016),
        ChatEmotion("happy", "😄", R.string.emotion_happy, 0, "😄", true, 3024),
        ChatEmotion("plead", "🥺", R.string.emotion_plead, 0, "🥺", true, 3024),
        ChatEmotion("awkward", "😅", R.string.emotion_awkward, 0, "😅", true, 3066),
        ChatEmotion("speechless", "😑", R.string.emotion_speechless, 0, "😑", true, 1554),
        ChatEmotion("angry", "😤", R.string.emotion_angry, 0, "😤", true, 3066),
        ChatEmotion("thanks", "[抱拳]", R.string.emotion_thanks, 0, "[抱拳]", false, 2600),
        ChatEmotion("cheer", "💪", R.string.emotion_cheer, 0, "💪", true, 3024),
        ChatEmotion("clap", "👏", R.string.emotion_clap, 0, "👏", true, 1176),
        ChatEmotion("night", "🌙", R.string.emotion_night, 0, "🌙", true, 3066),
        ChatEmotion("wink", "😉", R.string.emotion_wink, 0, "😉", true, 2310),
        ChatEmotion("think", "🤔", R.string.emotion_think, 0, "🤔", true, 3066),
        ChatEmotion("cool", "😎", R.string.emotion_cool, 0, "😎", true, 3066),
        ChatEmotion("shocked", "😱", R.string.emotion_shocked, 0, "😱", true, 2352),
        ChatEmotion("cry", "😭", R.string.emotion_cry, 0, "😭", true, 3066),
        ChatEmotion("shy", "😳", R.string.emotion_shy, 0, "😳", true, 3066),
        ChatEmotion("kiss", "😘", R.string.emotion_kiss, 0, "😘", true, 3024),
        ChatEmotion("love", "🥰", R.string.emotion_love, 0, "🥰", true, 3024),
        ChatEmotion("starstruck", "🤩", R.string.emotion_starstruck, 0, "🤩", true, 2982),
        ChatEmotion("silly", "🤪", R.string.emotion_silly, 0, "🤪", true, 3066),
        ChatEmotion("melt", "🫠", R.string.emotion_melt, 0, "🫠", true, 3066),
        ChatEmotion("hush", "🤫", R.string.emotion_hush, 0, "🤫", true, 3024),
        ChatEmotion("eye_roll", "🙄", R.string.emotion_eye_roll, 0, "🙄", true, 2394),
        ChatEmotion("yawn", "🥱", R.string.emotion_yawn, 0, "🥱", true, 3066),
        ChatEmotion("surprised", "😮", R.string.emotion_surprised, 0, "😮", true, 3066),
        ChatEmotion("sick", "🤒", R.string.emotion_sick, 0, "🤒", true, 3066),
        ChatEmotion("facepalm", "🤦", R.string.emotion_facepalm, 0, "🤦", true, 3066),
        ChatEmotion("nervous", "😰", R.string.emotion_nervous, 0, "😰", true, 3024),
        ChatEmotion("nod", "🙂‍↕️", R.string.emotion_nod, 0, "🙂‍↕️", true, 4746),
        ChatEmotion("no", "🙂‍↔️", R.string.emotion_no, 0, "🙂‍↔️", true, 5334),
        ChatEmotion("heart", "❤️", R.string.emotion_heart, 1, "❤️", true, 3066),
        ChatEmotion("hug", "🫂", R.string.emotion_hug, 1, "🫂", true, 3066),
        ChatEmotion("rose", "🌹", R.string.emotion_rose, 1, "🌹", false, 2600),
        ChatEmotion("lantern", "🏮", R.string.emotion_lantern, 1, "🏮", false, 2600),
        ChatEmotion("fortune", "[福气满满]", R.string.emotion_fortune, 1, "[福气满满]", false, 2600),
        ChatEmotion("like", "👍", R.string.emotion_like, 1, "👍", true, 2142),
        ChatEmotion("handheart", "🫶", R.string.emotion_handheart, 1, "🫶", true, 3066),
        ChatEmotion("tinyheart", "🫰", R.string.emotion_tinyheart, 1, "🫰", true, 2226),
        ChatEmotion("heartglow", "💖", R.string.emotion_heartglow, 1, "💖", true, 1680),
        ChatEmotion("heartfire", "❤️‍🔥", R.string.emotion_heartfire, 1, "❤️‍🔥", true, 3024),
        ChatEmotion("heal", "❤️‍🩹", R.string.emotion_heal, 1, "❤️‍🩹", true, 1470),
        ChatEmotion("heartarrow", "💘", R.string.emotion_heartarrow, 1, "💘", true, 2184),
        ChatEmotion("panda", "🐼", R.string.emotion_panda, 1, "🐼", true, 3024),
        ChatEmotion("dog", "🐶", R.string.emotion_dog, 1, "🐶", true, 3024),
        ChatEmotion("dragonface", "🐲", R.string.emotion_dragonface, 1, "🐲", true, 3024),
        ChatEmotion("tiger", "🐯", R.string.emotion_tiger, 1, "🐯", true, 3024),
        ChatEmotion("tea", "🍵", R.string.emotion_tea, 1, "🍵", true, 1974),
        ChatEmotion("butterfly", "🦋", R.string.emotion_butterfly, 1, "🦋", true, 3024),
        ChatEmotion("fireworks", "🎆", R.string.emotion_fireworks, 2, "🎆", false, 4000),
        ChatEmotion("confetti", "🎉", R.string.emotion_confetti, 2, "🎉", true, 2562),
        ChatEmotion("firecracker", "🧨", R.string.emotion_firecracker, 2, "🧨", false, 4000),
        ChatEmotion("prosperity", "[恭喜发财]", R.string.emotion_prosperity, 2, "[恭喜发财]", false, 4000),
        ChatEmotion("party", "🥳", R.string.emotion_party, 2, "🥳", true, 3066),
        ChatEmotion("birthday", "🎂", R.string.emotion_birthday, 2, "🎂", true, 3024),
        ChatEmotion("dragon", "🐉", R.string.emotion_dragon, 2, "🐉", true, 3024),
        ChatEmotion("phoenix", "🐦‍🔥", R.string.emotion_phoenix, 2, "🐦‍🔥", true, 2898),
        ChatEmotion("gift_angel", "[守护天使]", R.string.emotion_gift_angel, 2, "[守护天使]", true, 4000),
        ChatEmotion("gift_crown", "[为你加冕]", R.string.emotion_gift_crown, 2, "[为你加冕]", true, 2800),
        ChatEmotion("gift_car", "[豪车驾到]", R.string.emotion_gift_car, 2, "[豪车驾到]", true, 4000),
        ChatEmotion("gift_bouquet", "[浪漫花束]", R.string.emotion_gift_bouquet, 2, "[浪漫花束]", true, 4000),
    )
    private val byToken = all.associateBy { normalize(it.token) }
    private val byIdentifier = all.associateBy { it.id }
    fun fromBody(body: String): ChatEmotion? = if (body.length <= 16) byToken[normalize(body)] else null
    fun byId(id: String): ChatEmotion? = byIdentifier[id]
    private fun normalize(body: String) = body.replace("\uFE0F", "")
}

/** Ephemeral identities only. A just-arrived live envelope may finish decrypting within three seconds. */
internal class EmotionArrivalGate(private val enteredAt: Long) {
    private var foregroundSince = enteredAt
    private val seen = LinkedHashSet<String>()
    private val decrypting = LinkedHashMap<String, Long>()
    private var initialized = false
    fun resume(now: Long) { foregroundSince = now; decrypting.clear() }
    fun observe(rows: List<EmotionArrival>, now: Long, enabled: Boolean): String? {
        val wasInitialized = initialized
        initialized = true
        val fresh = rows.filter { seen.add(it.id) }
        // Bounded ephemeral cache; the entry timestamp still rejects older history after eviction.
        while (seen.size > 4096) seen.remove(seen.first())
        if (!wasInitialized || !enabled) { decrypting.clear(); return null }
        val present = rows.mapTo(HashSet()) { it.id }
        decrypting.entries.removeAll { it.value < now || it.key !in present }
        val candidates = fresh.filter { it.sentAt >= foregroundSince && now - it.sentAt in 0..10_000 }
        candidates.filter { it.awaitingLiveDecryption }.take(32).forEach { decrypting[it.id] = now + 3_000 }
        val ready = rows.filter { it.eligible && it.id in decrypting }
        ready.forEach { decrypting.remove(it.id) }
        while (decrypting.size > 32) decrypting.remove(decrypting.keys.first())
        return candidates.firstOrNull { it.eligible }?.id ?: ready.firstOrNull()?.id
    }
}

internal data class EmotionArrival(val id: String, val sentAt: Long, val eligible: Boolean, val awaitingLiveDecryption: Boolean = false)
