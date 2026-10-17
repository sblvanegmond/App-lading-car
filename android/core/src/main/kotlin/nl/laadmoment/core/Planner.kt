package nl.laadmoment.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

private const val HOUR_SECONDS = 3600L
private const val EPS = 1e-6

/** One hour on the shared axis of price and sun. */
data class HourSlot(
    val start: Instant,
    /** EUR/kWh excluding VAT, null when the price is not published yet. */
    val marketPrice: Double?,
    /** What you pay per kWh from the grid, null without a market price. */
    val allInPrice: Double?,
    /** Expected AC output of the panels, kW. */
    val pvKw: Double,
    /** What is left for the car after the house, kW. */
    val surplusKw: Double,
    val weather: WeatherHour?,
) {
    val end: Instant get() = start.plusSeconds(HOUR_SECONDS)
    val hasPrice: Boolean get() = allInPrice != null
}

/**
 * Merge prices and weather into one hourly timeline.
 *
 * The weather reaches further than the prices. By default everything after
 * the last known price is dropped; pass [until] to keep those hours (without
 * a price) up to that moment, so tomorrow's sun shows before its prices do.
 */
fun buildTimeline(
    prices: List<PricePoint>,
    weather: WeatherForecast?,
    settings: Settings,
    until: Instant? = null,
): List<HourSlot> {
    val priceBy = prices.associateBy { it.startEpoch.floorDiv(HOUR_SECONDS) * HOUR_SECONDS }
    val weatherBy = weather?.hours.orEmpty().associateBy { it.startEpoch.floorDiv(HOUR_SECONDS) * HOUR_SECONDS }
    val keys = (priceBy.keys + weatherBy.keys).sorted()
    val lastPriced = priceBy.keys.maxOrNull()
    return keys
        .filter { key ->
            when {
                until != null -> key < until.epochSecond || key <= (lastPriced ?: Long.MIN_VALUE)
                else -> lastPriced == null || key <= lastPriced
            }
        }
        .map { key ->
            val market = priceBy[key]?.marketPrice
            val hour = weatherBy[key]
            val gti = settings.pv.arrays.mapIndexed { i, plane ->
                hour?.gti?.getOrNull(i) ?: Solar.estimateGtiFromGhi(hour?.ghi ?: 0.0, plane.tilt)
            }
            val pvKw = if (hour == null) 0.0 else Solar.systemPowerKw(gti, hour.tempC, settings.pv)
            HourSlot(
                start = Instant.ofEpochSecond(key),
                marketPrice = market,
                allInPrice = market?.let { Pricing.allInPrice(it, settings.tariffs) },
                pvKw = pvKw,
                surplusKw = Solar.surplusKw(pvKw, settings.houseBaseLoadKw),
                weather = hour,
            )
        }
}

data class EnergyNeed(val netKwh: Double, val grossKwh: Double, val socGapPct: Double)

/** Energy that has to go into the battery, plus what is lost on the way. */
fun energyNeed(car: CarSettings): EnergyNeed {
    val capacity = max(0.0, car.batteryKwh)
    val gap = max(0.0, car.targetSocPct.coerceIn(0.0, 100.0) - car.currentSocPct.coerceIn(0.0, 100.0))
    val net = capacity * gap / 100
    val loss = car.chargeLossPct.coerceIn(0.0, 40.0)
    return EnergyNeed(netKwh = net, grossKwh = net / (1 - loss / 100), socGapPct = gap)
}

/** The next time the clock reads [hhmm], strictly after [from]. */
fun nextTimeOccurrence(from: Instant, hhmm: String, zone: ZoneId): Instant {
    val time = parseClock(hhmm) ?: LocalTime.of(7, 0)
    val local = from.atZone(zone)
    var candidate = local.toLocalDate().atTime(time).atZone(zone)
    if (!candidate.toInstant().isAfter(from)) candidate = candidate.toLocalDate().plusDays(1).atTime(time).atZone(zone)
    return candidate.toInstant()
}

fun parseClock(hhmm: String): LocalTime? {
    val parts = hhmm.trim().split(':', '.')
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    if (h !in 0..23 || m !in 0..59) return null
    return LocalTime.of(h, m)
}

enum class ChargeSource { SOLAR, MIX, GRID }

/** One hour (or part of it) the car will charge in. */
data class ChargeSlot(
    val slot: HourSlot,
    val from: Instant,
    val to: Instant,
    val powerKw: Double,
    val kwh: Double,
    val solarKwh: Double,
    val gridKwh: Double,
    val cost: Double,
    val partial: Boolean,
    val source: ChargeSource,
)

/** Consecutive charging hours glued into one window. */
data class ChargeBlock(
    val from: Instant,
    val to: Instant,
    val kwh: Double,
    val solarKwh: Double,
    val gridKwh: Double,
    val cost: Double,
    val slots: List<ChargeSlot>,
) {
    val hours: Double get() = Duration.between(from, to).seconds / 3600.0
    val avgPricePerKwh: Double get() = if (kwh > 0) cost / kwh else 0.0
    val solarSharePct: Double get() = if (kwh > 0) solarKwh / kwh * 100 else 0.0
    /** Expected sun over the block, kW averaged per hour. */
    val avgSurplusKw: Double get() = if (slots.isEmpty()) 0.0 else slots.sumOf { it.slot.surplusKw } / slots.size
}

data class Totals(val kwh: Double, val solarKwh: Double, val gridKwh: Double, val cost: Double) {
    val avgPricePerKwh: Double get() = if (kwh > 0) cost / kwh else 0.0
    val solarSharePct: Double get() = if (kwh > 0) solarKwh / kwh * 100 else 0.0

    companion object {
        val ZERO = Totals(0.0, 0.0, 0.0, 0.0)
    }
}

data class Comparison(
    /** Plugging in right now and taking the earliest hours, whatever they cost. */
    val immediateCost: Double,
    val savingsVsImmediate: Double,
    val avgAllInPrice: Double,
    val cheapestHour: HourSlot?,
    val dearestHour: HourSlot?,
)

data class Plan(
    val mode: Mode,
    val windowStart: Instant,
    val windowEnd: Instant,
    /** True when the plan follows a "ready by" time rather than all known prices. */
    val hasDeadline: Boolean,
    val deadlineRolled: Boolean,
    val need: EnergyNeed,
    val slots: List<ChargeSlot>,
    val blocks: List<ChargeBlock>,
    val totals: Totals,
    val comparison: Comparison?,
    val feasible: Boolean,
    val shortfallKwh: Double,
    val reachableSocPct: Double,
    val warnings: List<String>,
) {
    fun chargeFor(slot: HourSlot): ChargeSlot? = slots.firstOrNull { it.slot.start == slot.start }
}

/** A whole or partial hour, evaluated for charging at a given power. */
private data class Candidate(
    val slot: HourSlot,
    val from: Instant,
    val hours: Double,
    val powerKw: Double,
    val maxKwh: Double,
    val solarKwh: Double,
    val gridKwh: Double,
    val cost: Double,
    val score: Double,
) {
    fun take(kwh: Double, source: ChargeSource): ChargeSlot {
        val factor = if (maxKwh > 0) min(1.0, kwh / maxKwh) else 0.0
        val seconds = (hours * factor * HOUR_SECONDS).toLong()
        return ChargeSlot(
            slot = slot,
            from = from,
            to = from.plusSeconds(seconds),
            powerKw = powerKw,
            kwh = maxKwh * factor,
            solarKwh = solarKwh * factor,
            gridKwh = gridKwh * factor,
            cost = cost * factor,
            partial = factor < 0.999,
            source = source,
        )
    }
}

object Planner {

    /**
     * Which hours to charge in so the car gets what it needs at the lowest
     * real cost: grid kWh at the all-in price, own sun at what it would have
     * earned on the grid.
     */
    fun plan(timeline: List<HourSlot>, settings: Settings, now: Instant, zone: ZoneId): Plan {
        val mode = settings.mode
        val need = energyNeed(settings.car)
        val warnings = mutableListOf<String>()
        val windowStart = now

        var deadlineRolled = false
        val windowEnd: Instant
        if (settings.readyByEnabled) {
            var end = nextTimeOccurrence(now, settings.readyBy, zone)
            // Opening the app shortly before the car is due: today's deadline
            // can no longer deliver the charge, so plan for the next one.
            if (need.grossKwh > 0) {
                val hoursNeeded = need.grossKwh / max(0.1, settings.car.chargerKw)
                val hoursAvailable = Duration.between(now, end).seconds / 3600.0
                if (hoursAvailable < hoursNeeded) {
                    end = end.atZone(zone).plusDays(1).toInstant()
                    deadlineRolled = true
                }
            }
            windowEnd = end
        } else {
            windowEnd = timeline.lastOrNull { it.hasPrice }?.end ?: now.plusSeconds(24 * HOUR_SECONDS)
        }

        fun empty(feasible: Boolean, reachable: Double, shortfall: Double = 0.0) = Plan(
            mode = mode, windowStart = windowStart, windowEnd = windowEnd,
            hasDeadline = settings.readyByEnabled, deadlineRolled = deadlineRolled, need = need,
            slots = emptyList(), blocks = emptyList(), totals = Totals.ZERO, comparison = null,
            feasible = feasible, shortfallKwh = shortfall, reachableSocPct = reachable, warnings = warnings,
        )

        if (!windowEnd.isAfter(windowStart)) {
            warnings += "Er zijn geen uren meer over om in te laden."
            return empty(false, settings.car.currentSocPct)
        }
        if (need.grossKwh <= EPS) {
            warnings += "De accu zit al op het gewenste niveau. Laden is niet nodig."
            return empty(true, settings.car.targetSocPct)
        }
        if (deadlineRolled) {
            warnings += "Om ${settings.readyBy} red je het vandaag niet meer. Dit plan is voor ${Fmt.clock(windowEnd, zone)} de dag erna."
        }

        val candidates = timeline.mapNotNull { evaluate(it, settings, windowStart, windowEnd, mode) }
        // Every hour at full power: the grid top-up for Max zon, and the
        // "what if I plugged in now" comparison for every mode.
        val fullPower = if (mode == Mode.BALANCED) candidates else timeline.mapNotNull { evaluate(it, settings, windowStart, windowEnd, Mode.BALANCED) }
        if (candidates.isEmpty() && fullPower.isEmpty()) {
            warnings += "Nog geen bruikbare uren met een bekende prijs."
            return empty(false, settings.car.currentSocPct, need.grossKwh)
        }

        val chosen = mutableListOf<ChargeSlot>()
        var remaining = need.grossKwh
        val ordered = candidates.sortedWith(
            compareBy<Candidate> { it.score }
                // Same price: the hour with most sun, then the earliest.
                .thenByDescending { it.solarKwh }
                .thenBy { it.slot.start },
        )
        for (candidate in ordered) {
            if (remaining <= EPS) break
            val take = min(candidate.maxKwh, remaining)
            chosen += candidate.take(take, if (mode == Mode.SOLAR) ChargeSource.SOLAR else ChargeSource.MIX)
            remaining -= take
        }

        // Max zon could not fill the battery: top up from the grid in the
        // cheapest remaining hours rather than leave the driver stranded.
        if (remaining > EPS && mode == Mode.SOLAR) {
            // An hour whose sun already went into the car only has grid
            // energy left to offer, so it ranks by the grid price.
            val used = chosen.map { it.slot.start }.toSet()
            fun marginal(c: Candidate) = if (c.slot.start in used) c.slot.allInPrice ?: Double.POSITIVE_INFINITY else c.score
            val fallback = fullPower.sortedWith(compareBy<Candidate> { marginal(it) }.thenBy { it.slot.start })
            for (candidate in fallback) {
                if (remaining <= EPS) break
                val index = chosen.indexOfFirst { it.slot.start == candidate.slot.start }
                val existing = chosen.getOrNull(index)
                val headroom = candidate.maxKwh - (existing?.kwh ?: 0.0)
                if (headroom <= EPS) continue
                val take = min(headroom, remaining)
                if (existing != null) {
                    // This hour already runs on the full surplus, so whatever
                    // goes on top is grid energy. Counting the sun twice would
                    // make the plan look cheaper than it is.
                    val kwh = existing.kwh + take
                    val hours = min(candidate.hours, max(Duration.between(existing.from, existing.to).seconds / 3600.0, kwh / max(EPS, candidate.powerKw)))
                    chosen[index] = existing.copy(
                        kwh = kwh,
                        gridKwh = existing.gridKwh + take,
                        cost = existing.cost + take * (candidate.slot.allInPrice ?: 0.0),
                        powerKw = if (hours > 0) kwh / hours else existing.powerKw,
                        to = existing.from.plusSeconds((hours * HOUR_SECONDS).toLong()),
                        partial = kwh < candidate.maxKwh - EPS,
                        source = ChargeSource.MIX,
                    )
                } else {
                    val slot = candidate.take(take, ChargeSource.GRID)
                    chosen += if (slot.solarKwh > EPS) slot.copy(source = ChargeSource.MIX) else slot
                }
                remaining -= take
            }
            if (chosen.any { it.source != ChargeSource.SOLAR }) {
                warnings += "Te weinig zon voor je doel: de app vult aan in de goedkoopste uren."
            }
        }

        var feasible = true
        var reachable = settings.car.targetSocPct
        if (remaining > EPS) {
            feasible = false
            val delivered = need.grossKwh - remaining
            val lossFactor = 1 - settings.car.chargeLossPct.coerceIn(0.0, 40.0) / 100
            val capacity = max(EPS, settings.car.batteryKwh)
            reachable = min(100.0, settings.car.currentSocPct + delivered * lossFactor / capacity * 100)
            warnings += if (settings.readyByEnabled) {
                "Je haalt ${settings.car.targetSocPct.toInt()}% niet voor ${Fmt.clock(windowEnd, zone)}. Haalbaar is ongeveer ${reachable.toInt()}%."
            } else {
                "Met de bekende prijzen kom je tot ongeveer ${reachable.toInt()}%. Later vandaag komen er meer uren bij."
            }
        }

        val slots = alignPartials(chosen.sortedBy { it.from })
        val totals = Totals(
            kwh = slots.sumOf { it.kwh },
            solarKwh = slots.sumOf { it.solarKwh },
            gridKwh = slots.sumOf { it.gridKwh },
            cost = slots.sumOf { it.cost },
        )
        return Plan(
            mode = mode, windowStart = windowStart, windowEnd = windowEnd,
            hasDeadline = settings.readyByEnabled, deadlineRolled = deadlineRolled, need = need,
            slots = slots, blocks = mergeBlocks(slots), totals = totals,
            comparison = compare(fullPower, need, totals),
            feasible = feasible, shortfallKwh = max(0.0, remaining), reachableSocPct = reachable,
            warnings = warnings,
        )
    }

    private fun evaluate(slot: HourSlot, settings: Settings, windowStart: Instant, windowEnd: Instant, mode: Mode): Candidate? {
        val from = maxOf(slot.start, windowStart)
        val to = minOf(slot.end, windowEnd)
        val hours = Duration.between(from, to).seconds / 3600.0
        if (hours <= 0) return null

        val charger = max(0.0, settings.car.chargerKw)
        val minCharge = max(0.0, settings.car.minChargeKw)
        val sun = slot.surplusKw

        var power = charger
        if (mode == Mode.SOLAR) {
            power = min(charger, sun)
            if (power < minCharge) return null // the car would refuse this trickle
        }
        if (power <= 0) return null
        if (!slot.hasPrice && mode != Mode.SOLAR) return null

        val maxKwh = power * hours
        val solarKwh = min(sun * hours, maxKwh)
        val gridKwh = max(0.0, maxKwh - solarKwh)
        val allIn = slot.allInPrice
        val solarValue = Pricing.solarOpportunityCost(allIn ?: 0.0, settings.tariffs)
        val cost = (if (allIn == null) 0.0 else gridKwh * allIn) + solarKwh * solarValue
        val effective = if (maxKwh > 0) cost / maxKwh else Double.POSITIVE_INFINITY
        val score = if (mode == Mode.CHEAPEST) allIn ?: Double.POSITIVE_INFINITY else effective
        return Candidate(slot, from, hours, power, maxKwh, solarKwh, gridKwh, cost, score)
    }

    private fun compare(candidates: List<Candidate>, need: EnergyNeed, totals: Totals): Comparison {
        var remaining = need.grossKwh
        var immediate = 0.0
        for (candidate in candidates.sortedBy { it.from }) {
            if (remaining <= EPS) break
            val take = min(candidate.maxKwh, remaining)
            immediate += candidate.take(take, ChargeSource.MIX).cost
            remaining -= take
        }
        val priced = candidates.map { it.slot }.filter { it.hasPrice }
        return Comparison(
            immediateCost = immediate,
            savingsVsImmediate = immediate - totals.cost,
            avgAllInPrice = priced.mapNotNull { it.allInPrice }.average().takeIf { it.isFinite() } ?: 0.0,
            cheapestHour = priced.minByOrNull { it.allInPrice!! },
            dearestHour = priced.maxByOrNull { it.allInPrice!! },
        )
    }
}

/**
 * A part hour that runs straight into a full charging hour is moved to the
 * end of its hour, so "11:40-13:00" reads as one window instead of
 * "11:00-11:20" and "12:00-13:00".
 */
private fun alignPartials(slots: List<ChargeSlot>): List<ChargeSlot> {
    val starts = slots.map { it.from }.toSet()
    return slots.map { slot ->
        val end = slot.slot.end
        if (!slot.partial || slot.to == end || end !in starts) return@map slot
        val length = Duration.between(slot.from, slot.to)
        slot.copy(from = end.minus(length), to = end)
    }
}

/** Glue consecutive charging hours into human-sized windows. */
fun mergeBlocks(slots: List<ChargeSlot>): List<ChargeBlock> {
    val blocks = mutableListOf<ChargeBlock>()
    for (slot in slots.sortedBy { it.from }) {
        val last = blocks.lastOrNull()
        if (last != null && Duration.between(last.to, slot.from).abs().seconds < 60) {
            blocks[blocks.lastIndex] = last.copy(
                to = slot.to,
                kwh = last.kwh + slot.kwh,
                solarKwh = last.solarKwh + slot.solarKwh,
                gridKwh = last.gridKwh + slot.gridKwh,
                cost = last.cost + slot.cost,
                slots = last.slots + slot,
            )
        } else {
            blocks += ChargeBlock(slot.from, slot.to, slot.kwh, slot.solarKwh, slot.gridKwh, slot.cost, listOf(slot))
        }
    }
    return blocks
}
