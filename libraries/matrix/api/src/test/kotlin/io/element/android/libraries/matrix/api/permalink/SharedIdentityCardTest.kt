package io.element.android.libraries.matrix.api.permalink

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedIdentityCardTest {
    @Test fun `contact identity with encoded punctuation round trips`() {
        val card = SharedIdentityCard(false, "Alice", SharedIdentityCard.userLink("@alice:example.org"))
        assertThat(SharedIdentityCard.parse(card.body())).isEqualTo(card)
    }
    @Test fun `foreign links cannot become actionable cards`() {
        assertThat(SharedIdentityCard.parse("[乙者联系人名片]\nAlice\nhttps://attacker.example")).isNull()
        assertThat(SharedIdentityCard.parse("[乙者联系人名片]\nAlice\nyizhe://group/v1?user_id=%40alice%3Aexample.org")).isNull()
    }
    @Test fun `group requires room identity and capability`() {
        assertThat(SharedIdentityCard.parse("[乙者群名片]\nGroup\nyizhe://group/v1?room_id=%21group%3Aexample.org")).isNull()
    }
    @Test fun `newlines in names cannot inject a different target`() {
        val card = SharedIdentityCard(false, "Alice\nBob", SharedIdentityCard.userLink("@alice:example.org"))
        assertThat(SharedIdentityCard.parse(card.body())?.name).isEqualTo("Alice Bob")
    }
}
