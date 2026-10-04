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
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi
import org.fetarute.typewriter.DrivingMode
import org.fetarute.typewriter.ENTRY_KEY
import org.fetarute.typewriter.SOURCE
import org.fetarute.typewriter.driveApi
import java.time.Duration
import java.time.Instant

@Tags("fetarute_assign_trip")
@Entry("fetarute_assign_trip", "Give the player a FetaruteTC driving task", Colors.RED, "mdi:train")
/**
 * The `Assign Trip` action gives the player a scheduled trip to drive.
 *
 * It takes the next departure from the given station (optionally only of one route, or one exact trip)
 * and assigns it to the player. The player then boards the train at that station, confirms the seat
 * and drives it to the terminus, or only up to the alight station when one is set.
 *
 * The triggers of this action only fire when the task was actually assigned.
 * When no trip could be assigned (none departing in the window, already taken, the player already
 * has a task, driving tasks closed, ...) the failed triggers fire instead.
 *
 * ## How could this be used?
 * Start a "first shift" quest: assign the next local train from the depot station and
 * let the player drive three stations before handing over.
 */
class AssignTripActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("Station code where the player takes over the train.")
    val station: Var<String> = ConstVar(""),
    @Help("Only trips of this route code. Leave blank for any route.")
    val route: Var<String> = ConstVar(""),
    @Help("This exact trip code. Leave blank to take the next departure.")
    val trip: Var<String> = ConstVar(""),
    @Help("Station code where the task ends. Leave blank to drive to the terminus.")
    val alightStation: Var<String> = ConstVar(""),
    @Help("How far ahead to look for departures, in minutes.")
    @Default("20")
    val windowMinutes: Var<Int> = ConstVar(20),
    @Help("Manual driving or ATO.")
    val mode: DrivingMode = DrivingMode.MANUAL,
    @Help("When the train comes out of a depot, pick it up at the depot.")
    val depotPickup: Boolean = false,
    @Help("Fired when no trip could be assigned.")
    val failedTriggers: List<Ref<TriggerableEntry>> = emptyList(),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val stationCode = station.get(player, context).trim()
        val routeCode = route.get(player, context).trim()
        val tripCode = trip.get(player, context).trim()
        val alight = alightStation.get(player, context).trim()
        val window = windowMinutes.get(player, context).coerceIn(1, 24 * 60)
        // 派任务只能在主线程做：成败要等派完才知道，后续触发届时再手动放出
        disableAutomaticTriggering()
        if (stationCode.isEmpty()) {
            failedTriggers.triggerFor(player)
            return
        }
        Dispatchers.Sync.launch {
            if (!player.isOnline) return@launch
            val api = driveApi()
            if (api == null) {
                failedTriggers.triggerFor(player)
                return@launch
            }
            val offer = api.offersAt(stationCode, Instant.now(), Duration.ofMinutes(window.toLong()), MAX_OFFERS)
                .firstOrNull { offer ->
                    (routeCode.isEmpty() || offer.routeCode().equals(routeCode, ignoreCase = true)) &&
                        (tripCode.isEmpty() || offer.tripCode().equals(tripCode, ignoreCase = true))
                }
            if (offer == null) {
                logger.info("[FetaruteTC] no trip to assign at $stationCode for ${player.name}")
                failedTriggers.triggerFor(player)
                return@launch
            }
            val request = DriveApi.TaskRequest.of(offer, stationCode)
                .alightAt(alight)
                .mode(mode.api)
                .depotPickup(depotPickup)
                .tagged(SOURCE, mapOf(ENTRY_KEY to id))
            val result = api.assign(player, request)
            if (result == DriveApi.AssignResult.ASSIGNED) {
                triggerManually()
            } else {
                logger.info("[FetaruteTC] assign ${offer.tripCode()} to ${player.name}: $result")
                failedTriggers.triggerFor(player)
            }
        }
    }
}

/** 按交路或车次筛选前最多看多少条候选；窗口内的车次一般远少于此。 */
private const val MAX_OFFERS = 500
