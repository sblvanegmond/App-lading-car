package nl.adempauze.core

import android.content.pm.ApplicationInfo
import java.time.LocalDateTime
import kotlin.math.min

object Policy {
    const val ESCALATION_STEP_SECONDS = 5

    /**
     * Hoe lang de pauze duurt. Met [GuardedApp.escalate] komt er voor elke keer
     * dat je de app vandaag al opende [ESCALATION_STEP_SECONDS] bij, tot het
     * maximum. Zo wordt de vijfde keer YouTube merkbaar trager dan de eerste.
     */
    fun waitSeconds(app: GuardedApp, openedToday: Int): Int {
        val base = app.waitSeconds.coerceIn(GuardedApp.MIN_WAIT_SECONDS, GuardedApp.MAX_WAIT_SECONDS)
        if (!app.escalate) return base
        return min(GuardedApp.MAX_WAIT_SECONDS, base + ESCALATION_STEP_SECONDS * openedToday.coerceAtLeast(0))
    }

    /** Valt [now] binnen je werktijd? Werkt ook als je werktijd over middernacht loopt. */
    fun isWorkTime(work: WorkHours, now: LocalDateTime): Boolean {
        val minute = now.hour * 60 + now.minute
        val start = work.startMinute
        val end = work.endMinute
        if (start == end) return false
        return if (start < end) {
            now.dayOfWeek in work.days && minute >= start && minute < end
        } else {
            // Nachtdienst: het blok hoort bij de dag waarop het begon.
            (minute >= start && now.dayOfWeek in work.days) ||
                (minute < end && now.dayOfWeek.minus(1) in work.days)
        }
    }

    /** Moet de pauze voor deze app nu verschijnen? */
    fun isActive(app: GuardedApp, work: WorkHours, now: LocalDateTime): Boolean {
        if (!app.enabled) return false
        return when (app.schedule) {
            Schedule.ALWAYS -> true
            Schedule.OUTSIDE_WORK -> !isWorkTime(work, now)
            Schedule.DURING_WORK -> isWorkTime(work, now)
        }
    }

    /** Bekende apps en hun soort. Verder kijken we naar de naam en de Play-categorie. */
    val knownApps: Map<String, Category> = mapOf(
        // Video
        "com.google.android.youtube" to Category.VIDEO,
        "com.google.android.apps.youtube.music" to Category.VIDEO,
        "com.netflix.mediaclient" to Category.VIDEO,
        "com.disney.disneyplus" to Category.VIDEO,
        "com.amazon.avod.thirdpartyclient" to Category.VIDEO,
        "tv.twitch.android.app" to Category.VIDEO,
        "nl.uitzendinggemist" to Category.VIDEO,
        "com.videoland.app" to Category.VIDEO,
        "com.hbo.hbonow" to Category.VIDEO,
        "com.wbd.stream" to Category.VIDEO,
        // Sociale media
        "com.zhiliaoapp.musically" to Category.SOCIAL,
        "com.ss.android.ugc.trill" to Category.SOCIAL,
        "com.instagram.android" to Category.SOCIAL,
        "com.instagram.barcelona" to Category.SOCIAL,
        "com.facebook.katana" to Category.SOCIAL,
        "com.facebook.lite" to Category.SOCIAL,
        "com.snapchat.android" to Category.SOCIAL,
        "com.twitter.android" to Category.SOCIAL,
        "com.reddit.frontpage" to Category.SOCIAL,
        "com.linkedin.android" to Category.SOCIAL,
        "com.pinterest" to Category.SOCIAL,
        "xyz.blueskyweb.app" to Category.SOCIAL,
        "com.bereal.ft" to Category.SOCIAL,
        "com.tumblr" to Category.SOCIAL,
        // Werk
        "com.microsoft.office.outlook" to Category.WORK_MAIL,
        "com.microsoft.teams" to Category.WORK_MAIL,
        "com.Slack" to Category.WORK_MAIL,
        "com.google.android.apps.dynamite" to Category.WORK_MAIL,
        "com.microsoft.office.officehubrow" to Category.WORK_MAIL,
        "com.blackberry.hub" to Category.WORK_MAIL,
        "com.airwatch.androidagent" to Category.WORK_MAIL,
        "com.boxer.email" to Category.WORK_MAIL,
        // Mail
        "com.google.android.gm" to Category.MAIL,
        "com.samsung.android.email.provider" to Category.MAIL,
        "ch.protonmail.android" to Category.MAIL,
        "com.yahoo.mobile.client.android.mail" to Category.MAIL,
        // Berichten
        "com.whatsapp" to Category.MESSAGING,
        "com.whatsapp.w4b" to Category.MESSAGING,
        "org.telegram.messenger" to Category.MESSAGING,
        "org.thoughtcrime.securesms" to Category.MESSAGING,
        "com.facebook.orca" to Category.MESSAGING,
        "com.discord" to Category.MESSAGING,
        // Nieuws
        "nl.nos.app" to Category.NEWS,
        "nl.sanomamedia.android.nu" to Category.NEWS,
        "nl.telegraaf" to Category.NEWS,
        "nl.ad.android" to Category.NEWS,
        "com.google.android.apps.magazines" to Category.NEWS,
        "nl.volkskrant.android" to Category.NEWS,
        "com.nrc.nrcapp" to Category.NEWS,
        "flipboard.app" to Category.NEWS,
        // Winkelen
        "com.bol.shop" to Category.SHOPPING,
        "com.amazon.mShop.android.shopping" to Category.SHOPPING,
        "com.einnovation.temu" to Category.SHOPPING,
        "com.zzkko" to Category.SHOPPING,
        "com.alibaba.aliexpresshd" to Category.SHOPPING,
        "nl.marktplaats.android" to Category.SHOPPING,
        "fr.vinted" to Category.SHOPPING,
        "com.zalando.mobile" to Category.SHOPPING,
        "com.ebay.mobile" to Category.SHOPPING,
        // Browsers
        "com.android.chrome" to Category.BROWSER,
        "com.sec.android.app.sbrowser" to Category.BROWSER,
        "org.mozilla.firefox" to Category.BROWSER,
        "com.brave.browser" to Category.BROWSER,
        "com.microsoft.emmx" to Category.BROWSER,
        "com.opera.browser" to Category.BROWSER,
    )

    /** De apps die we als eerste voorstellen in de lijst. */
    val suggestedCategories = setOf(
        Category.VIDEO, Category.SOCIAL, Category.WORK_MAIL, Category.MAIL, Category.NEWS, Category.SHOPPING,
    )

    /**
     * Raadt de soort app. [playCategory] is [ApplicationInfo.category]
     * (of [ApplicationInfo.CATEGORY_UNDEFINED] als die er niet is).
     */
    fun detectCategory(packageName: String, label: String, playCategory: Int): Category {
        knownApps[packageName]?.let { return it }
        val name = label.lowercase()
        val pkg = packageName.lowercase()
        return when {
            listOf("outlook", "teams", "slack", "werkmail", "intune", "workspace").any { it in name || it in pkg } -> Category.WORK_MAIL
            listOf("mail", "inbox").any { it in name } -> Category.MAIL
            listOf("nieuws", "news", "krant").any { it in name } -> Category.NEWS
            listOf("video", "tv", "stream").any { it in name } && playCategory == ApplicationInfo.CATEGORY_VIDEO -> Category.VIDEO
            else -> when (playCategory) {
                ApplicationInfo.CATEGORY_GAME -> Category.GAMES
                ApplicationInfo.CATEGORY_VIDEO -> Category.VIDEO
                ApplicationInfo.CATEGORY_SOCIAL -> Category.SOCIAL
                ApplicationInfo.CATEGORY_NEWS -> Category.NEWS
                else -> Category.OTHER
            }
        }
    }
}
