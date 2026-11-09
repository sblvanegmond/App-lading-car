package nl.adempauze.service

/**
 * Beslist of het pauzescherm moet verschijnen als er een app op de voorgrond
 * komt. Los van Android, zodat het te testen is.
 *
 * Eén app opent vaak een paar vensters kort na elkaar (opstartscherm, dan het
 * hoofdscherm). Daarom wacht hij [debounceMillis] voordat hij dezelfde app
 * opnieuw onderschept. Na "Nee, laat maar" negeert hij de app nog even, zodat
 * hij niet nog eens verschijnt terwijl Android naar het startscherm gaat.
 */
class Interceptor(
    private val debounceMillis: Long = 1_500,
    private val suppressAfterDismissMillis: Long = 1_500,
) {
    /** De laatste echte app op de voorgrond (zonder toetsenbord en systeembalk). */
    var foreground: String? = null
        private set

    private var lastInterceptPackage: String? = null
    private var lastInterceptAt: Long = Long.MIN_VALUE / 2
    private val suppressedUntil = mutableMapOf<String, Long>()

    /**
     * @param guardedAndActive de app staat in de lijst én de pauze geldt nu
     * @param passValidUntil tot wanneer je de app mag gebruiken (0 = geen pas)
     * @return true als het pauzescherm moet verschijnen
     */
    fun onForeground(packageName: String, guardedAndActive: Boolean, passValidUntil: Long, now: Long): Boolean {
        foreground = packageName
        if (!guardedAndActive) return false
        if (passValidUntil > now) return false
        suppressedUntil[packageName]?.let { if (now < it) return false else suppressedUntil.remove(packageName) }
        if (packageName == lastInterceptPackage && now - lastInterceptAt < debounceMillis) return false
        lastInterceptPackage = packageName
        lastInterceptAt = now
        return true
    }

    /** Het pauzescherm zelf of een andere app van ons staat vooraan. */
    fun onOwnWindow(packageName: String) {
        foreground = packageName
    }

    fun onDismissed(packageName: String, now: Long) {
        suppressedUntil[packageName] = now + suppressAfterDismissMillis
        lastInterceptPackage = null
    }

    /**
     * Na "Openen" komt de app weer vooraan. Niet elke telefoon meldt dat, dus
     * we onthouden het hier zelf; anders zou "je tijd is om" niet verschijnen.
     */
    fun onOpened(packageName: String) {
        foreground = packageName
        lastInterceptPackage = null
    }
}
