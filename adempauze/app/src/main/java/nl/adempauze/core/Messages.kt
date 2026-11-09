package nl.adempauze.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.random.Random

/** Wat we weten op het moment dat het pauzescherm verschijnt. */
data class MessageContext(
    val appLabel: String,
    val category: Category,
    val now: LocalDateTime,
    /** Inclusief deze poging. */
    val attemptsToday: Int,
    val openedToday: Int,
    val isWorkTime: Boolean,
    val customMessages: List<String> = emptyList(),
)

/**
 * De teksten op het pauzescherm. Ze zijn kort, stellen een vraag en passen bij
 * het soort app en het moment van de dag. Eigen zinnen tellen drie keer zo
 * zwaar, zodat je ze vaak ziet.
 *
 * In de tekst kun je gebruiken: {app}, {tijd}, {n} (de hoeveelste poging
 * vandaag) en {geopend} (hoe vaak je de app vandaag echt opende).
 */
object Messages {

    private val general = listOf(
        "Wat hoopte je te vinden toen je je telefoon pakte?",
        "Is dit een keuze, of een gewoonte?",
        "Je mist niets. Het is er straks ook nog.",
        "Wat zou je nu doen als {app} niet bestond?",
        "Even niks doen is ook iets doen.",
        "Voel je voeten op de grond. Wat heb je nú echt nodig?",
        "Pak je je telefoon uit verveling, onrust of nieuwsgierigheid?",
        "Je aandacht is het waardevolste wat je hebt. Aan wie geef je hem?",
        "Wat ga je precies doen in {app}? Zeg het even voor jezelf.",
    )

    private val byCategory: Map<Category, List<String>> = mapOf(
        Category.VIDEO to listOf(
            "Eén video worden er al snel tien. Weet je welke je wilt zien?",
            "Zoek je iets specifieks, of laat je het algoritme kiezen?",
            "Wat ga je kijken, en hoe lang?",
            "{app} is gemaakt om je vast te houden. Jij bepaalt wanneer je stopt.",
            "Is dit ontspanning, of uitstel?",
            "Kies je straks zelf de volgende video, of doet autoplay dat?",
            "Je kunt ook even uit het raam kijken. Ook mooi beeld.",
            "Als je gaat kijken: spreek met jezelf een eindtijd af.",
        ),
        Category.SOCIAL to listOf(
            "Scrollen voelt als rust. Is het dat ook?",
            "Je feed is morgen nog precies even eindeloos.",
            "Ga je straks jezelf weer vergelijken met de hoogtepunten van anderen?",
            "Wil je iemand spreken? Stuur die persoon dan gewoon een berichtje.",
            "Wil je iets posten, zoeken of zien? Of is dit je duim op de automatische piloot?",
            "Voel je je na het scrollen meestal beter dan ervoor?",
            "De feed heeft geen bodem. Jij mag wel een grens hebben.",
            "Kijk eerst even om je heen. Het echte leven heeft geen filter.",
        ),
        Category.WORK_MAIL to listOf(
            "Kan dit wachten tot je volgende werkblok?",
            "Wat ga je doen met wat je zo leest?",
            "Een volle inbox loopt niet weg. Je focus wel.",
            "Zoek je een bepaald bericht, of zoek je afleiding?",
            "Mail checken voelt als werken. Is het dat ook?",
            "Wat is het ene ding dat je nu echt af moet maken?",
            "Elke keer dat je kijkt, kost het je minuten om weer in je werk te komen.",
        ),
        Category.MAIL to listOf(
            "Verwacht je een belangrijke mail, of kijk je uit gewoonte?",
            "Je inbox riep je niet. Waarom ga je toch kijken?",
            "Wat ga je doen met wat je zo leest?",
            "Mail is geen chat. Eén keer per dagdeel is vaak genoeg.",
        ),
        Category.MESSAGING to listOf(
            "Wil je iemand iets sturen, of kijk je alleen of er iets is?",
            "Een snel antwoord is fijn. Een rustig antwoord is beter.",
            "Niet elk bericht vraagt om een reactie nú.",
            "Ga je iemand iets vertellen, of groepsapps teruglezen?",
        ),
        Category.NEWS to listOf(
            "Is er het afgelopen uur iets gebeurd dat jij moet weten?",
            "Nieuws checken voelt als grip, maar maakt vaak onrustiger.",
            "Belangrijk nieuws vindt jou wel. Geef het een vast moment op de dag.",
            "Wat ga je anders doen nadat je het gelezen hebt?",
        ),
        Category.SHOPPING to listOf(
            "Heb je iets nodig, of zoek je een beloning?",
            "Zet het op een lijstje en kijk morgen of je het nog steeds wilt.",
            "Het fijnste aan iets kopen is vaak het moment ervoor. Wil je het echt?",
            "Wat kost dit je, behalve geld?",
        ),
        Category.GAMES to listOf(
            "Eén potje is prima. Hoeveel potjes worden het?",
            "Speel je om te ontspannen, of om iets anders niet te hoeven doen?",
            "Spreek met jezelf af wanneer je stopt, nog voor je begint.",
        ),
        Category.BROWSER to listOf(
            "Wat ga je opzoeken? Zeg het even hardop.",
            "Eén tabblad, één vraag. En daarna weer dicht?",
        ),
        Category.OTHER to emptyList(),
    )

    private val afterWork = listOf(
        "Het is {tijd}. Je werkdag zit erop. Wat je nu leest, los je toch pas morgen op.",
        "Je collega's kunnen wachten. Jouw vrije tijd niet.",
        "Wat buiten werktijd binnenkomt, mag je ook binnen werktijd lezen.",
        "Wie rust, werkt morgen beter. Gun jezelf deze vrije tijd.",
        "Eén blik in {app} en je hoofd is weer op je werk. Is dat het waard?",
    )

    private val weekend = listOf(
        "Het is weekend. Het werk is er maandag ook nog.",
        "Weekend betekent: even niet bereikbaar hoeven zijn.",
    )

    private val lateNight = listOf(
        "Het is al {tijd}. Je slaap is meer waard dan nog even {app}.",
        "Morgen-jij is je dankbaar als je nu je telefoon weglegt.",
        "Het licht van je scherm houdt je wakker. Misschien is dit het moment om te stoppen.",
    )

    private val earlyMorning = listOf(
        "Goedemorgen. Begin je dag met jezelf, niet met {app}.",
        "Het eerste uur zet de toon voor je dag. Wie mag die bepalen?",
    )

    private val repeated = listOf(
        "Dit is al de {n}e keer vandaag dat je {app} wilt openen.",
        "{n}e poging vandaag. Wat zoek je steeds?",
    )

    private val repeatedOpened = listOf(
        "Je opende {app} vandaag al {geopend} keer. Gaf de vorige keer je wat je zocht?",
    )

    private val timeUp = listOf(
        "Hoe voel je je nu, vergeleken met toen je begon?",
        "Heb je gevonden wat je zocht?",
        "Het beste moment om te stoppen is een moment dat je zelf kiest.",
        "Stoppen voelt even onwennig. Over een minuut ben je het vergeten.",
        "Wat ga je hierna doen dat je echt energie geeft?",
    )

    /** Kiest een tekst voor het pauzescherm bij het openen van een app. */
    fun pick(ctx: MessageContext, random: Random = Random.Default): String {
        val pool = mutableListOf<Pair<String, Int>>()
        ctx.customMessages.filter { it.isNotBlank() }.forEach { pool += it.trim() to 3 }
        byCategory[ctx.category].orEmpty().forEach { pool += it to 2 }
        general.forEach { pool += it to 1 }

        val isWorkApp = ctx.category == Category.WORK_MAIL || ctx.category == Category.MAIL
        if (isWorkApp && !ctx.isWorkTime) {
            afterWork.forEach { pool += it to 4 }
            val day = ctx.now.dayOfWeek
            if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) weekend.forEach { pool += it to 4 }
        }
        val hour = ctx.now.hour
        if (hour >= 23 || hour < 5) lateNight.forEach { pool += it to 4 }
        if (hour in 5..7) earlyMorning.forEach { pool += it to 3 }
        if (ctx.attemptsToday >= 3) repeated.forEach { pool += it to 2 }
        if (ctx.openedToday >= 2) repeatedOpened.forEach { pool += it to 2 }

        return fill(weightedPick(pool, random), ctx)
    }

    /** Kiest een tekst voor als je ingestelde tijd in de app voorbij is. */
    fun pickTimeUp(ctx: MessageContext, random: Random = Random.Default): String {
        val pool = timeUp.map { it to 1 }.toMutableList()
        ctx.customMessages.filter { it.isNotBlank() }.forEach { pool += it.trim() to 1 }
        val hour = ctx.now.hour
        if (hour >= 23 || hour < 5) lateNight.forEach { pool += it to 3 }
        return fill(weightedPick(pool, random), ctx)
    }

    fun fill(template: String, ctx: MessageContext): String =
        template
            .replace("{app}", ctx.appLabel)
            .replace("{tijd}", "${ctx.now.hour}:${ctx.now.minute.toString().padStart(2, '0')}")
            .replace("{n}", ctx.attemptsToday.toString())
            .replace("{geopend}", ctx.openedToday.toString())

    /** "1 minuut", "5 minuten". */
    fun minutes(n: Int): String = if (n == 1) "1 minuut" else "$n minuten"

    private fun weightedPick(pool: List<Pair<String, Int>>, random: Random): String {
        val total = pool.sumOf { it.second }
        var roll = random.nextInt(total)
        for ((text, weight) in pool) {
            if (roll < weight) return text
            roll -= weight
        }
        return pool.last().first
    }

    /** Alle teksten, voor de tests. */
    internal fun allTemplates(): List<String> =
        general + byCategory.values.flatten() + afterWork + weekend + lateNight + earlyMorning +
            repeated + repeatedOpened + timeUp
}
