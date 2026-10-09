package org.fetarute.typewriter.entries.action

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Default
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.utils.Sync
import kotlinx.coroutines.Dispatchers
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi
import org.fetarute.typewriter.ENTRY_KEY
import org.fetarute.typewriter.SOURCE
import org.fetarute.typewriter.guardApi
import java.time.Duration
import java.time.Instant

@Tags("fetarute_assign_guard_duty")
@Entry("fetarute_assign_guard_duty", "Give the player a FetaruteTC guard duty", Colors.RED, "mdi:bell-ring")
/**
 * The `Assign Guard Duty` action makes the player the guard of a scheduled trip.
 *
 * It takes the next departure from the given station (optionally only of one route, or one exact trip)
 * and gives its guard duty to the player. When the train stands at that station, the player sits in
 * the rear cab and is on duty: they open and close the doors, watch the platform, confirm the starting signal
 * and sound the buzzer, up to the terminus or the handover station when one is set.
 * At the first station of a trip the train waits for the player before it leaves.
 *
 * The guard's rewards (experience and money, set in FetaruteTCAddon's `drive.yml`) are paid as for any other
 * guard duty unless turned off here, e.g. when the quest gives its own reward.
 *
 * The triggers of this action only fire when the duty was actually given.
 * When none could be given (no trip departing in the window, the trip already has a guard, the player is on duty,
 * driving or already has a guard duty, guards are switched off, ...) the failed triggers fire instead.
 *
 * ## How could this be used?
 * A "first day as a guard" quest: give the player the guard duty of the next local train and
 * let them work three stations before handing over.
 */
class AssignGuardDutyActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Station code where the player goes on duty.")
    val station: Var<String> = ConstVar(""),
    @Help("Only trips of this route code. Leave blank for any route.")
    val route: Var<String> = ConstVar(""),
    @Help("This exact trip code. Leave blank to take the next departure.")
    val trip: Var<String> = ConstVar(""),
    @Help("Station code where the player's duty ends. Leave blank to work to the terminus.")
    val handoverStation: Var<String> = ConstVar(""),
    @Help("How far ahead to look for departures, in minutes.")
    @Default("20")
    val windowMinutes: Var<Int> = ConstVar(20),
    @Help("Also take a train already standing at the station. It may leave before the player gets there.")
    val includeStanding: Boolean = false,
    @Help("Pay FetaruteTCAddon's guard rewards (experience and money) for this duty.")
    @Default("true")
    val builtInRewards: Boolean = true,
    @Help("Fired when no guard duty could be given.")
    val failedTriggers: List<Ref<TriggerableEntry>> = emptyList(),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val stationCode = station.get(player, context).trim()
        val routeCode = route.get(player, context).trim()
        val tripCode = trip.get(player, context).trim()
        val handover = handoverStation.get(player, context).trim()
        val window = windowMinutes.get(player, context).coerceIn(1, 24 * 60)
        // 派任务只能在主线程做：成败要等派完才知道，后续触发届时再手动放出
        disableAutomaticTriggering()
        if (stationCode.isEmpty()) {
            failedTriggers.triggerFor(player)
            return
        }
        Dispatchers.Sync.launch {
            if (!player.isOnline) return@launch
            val api = guardApi()
            if (api == null) {
                failedTriggers.triggerFor(player)
                return@launch
            }
            val offer = api.offersAt(stationCode, Instant.now(), Duration.ofMinutes(window.toLong()), MAX_OFFERS)
                .firstOrNull { offer ->
                    (includeStanding || !offer.dwelling()) &&
                        (routeCode.isEmpty() || offer.routeCode().equals(routeCode, ignoreCase = true)) &&
                        (tripCode.isEmpty() || offer.tripCode().equals(tripCode, ignoreCase = true))
                }
            if (offer == null) {
                logger.info("[FetaruteTC] no guard duty to give at $stationCode for ${player.name}")
                failedTriggers.triggerFor(player)
                return@launch
            }
            val request = GuardApi.TaskRequest.of(offer, stationCode)
                .handoverAt(handover)
                .rewards(builtInRewards)
                .tagged(SOURCE, mapOf(ENTRY_KEY to id))
            val result = api.assign(player, request)
            if (result == GuardApi.AssignResult.ASSIGNED) {
                triggerManually()
            } else {
                logger.info("[FetaruteTC] guard duty ${offer.tripCode()} for ${player.name}: $result")
                failedTriggers.triggerFor(player)
            }
        }
    }
}

/** 按交路或车次筛选前最多看多少条候选；窗口内的车次一般远少于此。 */
private const val MAX_OFFERS = 500
