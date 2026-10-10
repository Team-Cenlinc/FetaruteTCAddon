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
@Entry("fetarute_assign_guard_duty", "给玩家派 FTCA 车掌任务", Colors.RED, "mdi:bell-ring")
/**
 * “派车掌任务”动作：让玩家当一班表定车次的车掌。
 *
 * 从指定车站即将发出的车次里挑一趟（可以只挑某个交路，或指定车次），把它的车掌任务派给玩家。列车停在这一站时，
 * 玩家坐进车尾驾驶室即上岗：开关车门、监视站台、确认出站信号、按发车铃，值乘到终点站，或到设定的交班站为止。
 * 接班站是这趟车的始发站时，列车会停着等玩家上岗。
 *
 * 车掌奖励（经验与钱币，在 FetaruteTCAddon 的 `drive.yml` 里设定）与其他车掌任务一样照发，
 * 剧情另发奖励时可以在这里关掉。
 *
 * 只有真的派出了车掌任务，本动作的后续条目才会触发。派不成时（窗口内没有车次、这趟车已有车掌、
 * 玩家正在值乘、正在驾驶或已有车掌任务、车掌功能未开启等）改为触发“派不成时触发”的条目。
 *
 * ## 用法示例
 * “第一天当车掌”剧情：把下一班普通车的车掌任务派给玩家，值乘三站后交班。
 */
class AssignGuardDutyActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("上岗的车站站码。")
    val station: Var<String> = ConstVar(""),
    @Help("只要这个交路编号的车次；留空不限交路。")
    val route: Var<String> = ConstVar(""),
    @Help("指定车次号；留空则派最近发出的一趟。")
    val trip: Var<String> = ConstVar(""),
    @Help("交班站站码；留空则值乘到终点站。")
    val handoverStation: Var<String> = ConstVar(""),
    @Help("往后找多少分钟内发车的车次。")
    @Default("20")
    val windowMinutes: Var<Int> = ConstVar(20),
    @Help("也派已经停在站里的车（可能等不到玩家赶到就开走）。")
    val includeStanding: Boolean = false,
    @Help("照发 FTCA 自带的车掌奖励（经验与钱币）。")
    @Default("true")
    val builtInRewards: Boolean = true,
    @Help("没派成车掌任务时触发。")
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
                logger.info("[FetaruteTC] $stationCode 没有可派给 ${player.name} 的车掌任务")
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
                logger.info("[FetaruteTC] 车次 ${offer.tripCode()} 的车掌任务未能派给 ${player.name}：$result")
                failedTriggers.triggerFor(player)
            }
        }
    }
}

/** 按交路或车次筛选前最多看多少条候选；窗口内的车次一般远少于此。 */
private const val MAX_OFFERS = 500
