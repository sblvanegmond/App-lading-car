package nl.adempauze

import nl.adempauze.core.Category
import nl.adempauze.core.MessageContext
import nl.adempauze.core.Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

class MessagesTest {
    private fun ctx(
        category: Category = Category.VIDEO,
        hour: Int = 14,
        day: Int = 9,
        attempts: Int = 1,
        opened: Int = 0,
        workTime: Boolean = false,
        custom: List<String> = emptyList(),
    ) = MessageContext("YouTube", category, LocalDateTime.of(2026, 11, day, hour, 5), attempts, opened, workTime, custom)

    private fun sample(c: MessageContext, n: Int = 400): List<String> {
        val r = Random(42)
        return List(n) { Messages.pick(c, r) }
    }

    @Test
    fun geenOpenPlaatshoudersInTeksten() {
        val c = ctx(attempts = 5, opened = 3)
        Messages.allTemplates().forEach { t ->
            val filled = Messages.fill(t, c)
            assertFalse("Niet ingevuld: $filled", filled.contains('{') || filled.contains('}'))
        }
    }

    @Test
    fun appNaamEnTijdWordenIngevuld() {
        val c = ctx(hour = 23)
        assertEquals("Het is al 23:05. Je slaap is meer waard dan nog even YouTube.",
            Messages.fill("Het is al {tijd}. Je slaap is meer waard dan nog even {app}.", c))
    }

    @Test
    fun werkmailNaWerktijdKrijgtWerkTeksten() {
        val texts = sample(ctx(category = Category.WORK_MAIL, hour = 21, workTime = false))
        assertTrue(texts.any { it.contains("werkdag zit erop") || it.contains("collega's") || it.contains("vrije tijd") })
    }

    @Test
    fun werkmailOnderWerktijdKrijgtGeenAvondteksten() {
        val texts = sample(ctx(category = Category.WORK_MAIL, hour = 11, workTime = true))
        assertFalse(texts.any { it.contains("werkdag zit erop") })
    }

    @Test
    fun eigenZinnenKomenVaakVoorbij() {
        val mine = "Je wilde vanavond toch lezen?"
        val texts = sample(ctx(custom = listOf(mine)))
        val share = texts.count { it == mine } / texts.size.toDouble()
        assertTrue("Aandeel eigen zin te laag: $share", share > 0.05)
    }

    @Test
    fun herhaaldePogingWordtBenoemd() {
        val texts = sample(ctx(attempts = 6))
        assertTrue(texts.any { it.contains("6e") })
    }

    @Test
    fun laatOpDeAvondGaatHetOverSlaap() {
        val texts = sample(ctx(hour = 0))
        assertTrue(texts.any { it.contains("slaap") || it.contains("Morgen-jij") })
    }

    @Test
    fun minutenEnkelvoudEnMeervoud() {
        assertEquals("1 minuut", Messages.minutes(1))
        assertEquals("5 minuten", Messages.minutes(5))
    }
}
