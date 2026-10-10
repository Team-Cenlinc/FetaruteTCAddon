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
@Entry("fetarute_assign_trip", "给玩家派 FTCA 驾驶任务", Colors.RED, "mdi:train")
/**
 * “派驾驶任务”动作：给玩家派一趟表定车次去驾驶。
 *
 * 从指定车站即将发出的车次里挑一趟（可以只挑某个交路，或指定车次）派给玩家。玩家到站后接班、确认座位，
 * 开到终点站，或开到设定的交班站为止。
 *
 * 驾驶奖励（经验与钱币，在 FetaruteTCAddon 的 `drive.yml` 里设定）与其他任务一样照发，
 * 剧情另发奖励时可以在这里关掉。
 *
 * 只有真的派出了任务，本动作的后续条目才会触发。派不成时（窗口内没有车次、车次已被领走、玩家已有任务、
 * 驾驶任务未开放等）改为触发“派不成时触发”的条目。
 *
 * ## 用法示例
 * 开始“第一次当班”剧情：从车库所在的车站派下一班普通车，让玩家开三站后交班。
 */
class AssignTripActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("接班站站码。")
    val station: Var<String> = ConstVar(""),
    @Help("只要这个交路编号的车次；留空不限交路。")
    val route: Var<String> = ConstVar(""),
    @Help("指定车次号；留空则派最近发出的一趟。")
    val trip: Var<String> = ConstVar(""),
    @Help("交班站站码；留空则开到终点站。")
    val handoverStation: Var<String> = ConstVar(""),
    @Help("往后找多少分钟内发车的车次。")
    @Default("20")
    val windowMinutes: Var<Int> = ConstVar(20),
    @Help("人工驾驶或 ATO。")
    val mode: DrivingMode = DrivingMode.MANUAL,
    @Help("车次从车库出车时，改到车库接车。")
    val depotPickup: Boolean = false,
    @Help("也派已经停在站里的车（可能等不到玩家赶到就开走）。")
    val includeStanding: Boolean = false,
    @Help("照发 FTCA 自带的驾驶奖励（经验与钱币）。")
    @Default("true")
    val builtInRewards: Boolean = true,
    @Help("没派成驾驶任务时触发。")
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
            val api = driveApi()
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
                logger.info("[FetaruteTC] $stationCode 没有可派给 ${player.name} 的车次")
                failedTriggers.triggerFor(player)
                return@launch
            }
            val request = DriveApi.TaskRequest.of(offer, stationCode)
                .handoverAt(handover)
                .mode(mode.api)
                .depotPickup(depotPickup)
                .rewards(builtInRewards)
                .tagged(SOURCE, mapOf(ENTRY_KEY to id))
            val result = api.assign(player, request)
            if (result == DriveApi.AssignResult.ASSIGNED) {
                triggerManually()
            } else {
                logger.info("[FetaruteTC] 车次 ${offer.tripCode()} 未能派给 ${player.name}：$result")
                failedTriggers.triggerFor(player)
            }
        }
    }
}

/** 按交路或车次筛选前最多看多少条候选；窗口内的车次一般远少于此。 */
private const val MAX_OFFERS = 500
