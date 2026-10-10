package org.fetarute.typewriter.entries.event

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.ContextKeys
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.KeyType
import com.typewritermc.core.interaction.EntryContextKey
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.matches
import com.typewritermc.engine.paper.entry.triggerAllFor
import org.fetarute.fetaruteTCAddon.api.event.DriverTaskClaimEvent
import org.fetarute.typewriter.TaskSource
import org.fetarute.typewriter.allows
import kotlin.reflect.KClass

@Entry("fetarute_claim_requirement", "玩家未解锁时拒绝领取 FTCA 驾驶任务", Colors.YELLOW, "mdi:lock")
@ContextKeys(ClaimRequirementContextKeys::class)
/**
 * “驾驶任务领取条件”：玩家不满足条件时拒绝他领取驾驶任务，例如剧情推进到某一步才开放的线路。拒绝了任务时触发后续条目。
 *
 * 对任务板领取和插件派发都有效，Typewriter 也在内：被这里拒绝的“派驾驶任务”会触发它的“派不成时触发”。
 * 玩家直接接管调度列车、或在终点站接着开下一趟时，拒绝任务不会让列车停下，玩家照样开，只是这一趟不计分。
 *
 * ## 用法示例
 * 玩家在交路 R1 开完五趟之前锁住交路 R2，他想领时告诉他还差什么。
 */
class ClaimRequirementEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("只要这个交路编号的车次；留空不限交路。")
    val route: String = "",
    @Help("只要这些来源的驾驶任务；留空不限来源。")
    val sources: List<TaskSource> = emptyList(),
    @Help("玩家须同时满足这些条件才能领取；留空则从不拒绝。")
    val requirements: List<Criteria> = emptyList(),
) : EventEntry

enum class ClaimRequirementContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    TRIP(String::class),

    @KeyType(String::class)
    ROUTE(String::class),

    @KeyType(String::class)
    TAKEOVER_STATION(String::class),

    @KeyType(String::class)
    SOURCE(String::class),
}

@EntryListener(ClaimRequirementEventEntry::class, ignoreCancelled = true)
fun onTaskClaim(event: DriverTaskClaimEvent, query: Query<ClaimRequirementEventEntry>) {
    val player = event.player.orElse(null) ?: return
    val task = event.task
    val refused = query.findWhere {
        (it.route.isBlank() || it.route.equals(task.routeCode(), ignoreCase = true)) &&
            it.sources.allows(task) &&
            !it.requirements.matches(player)
    }.toList()
    if (refused.isEmpty()) return
    event.isCancelled = true
    refused.triggerAllFor(player) {
        ClaimRequirementContextKeys.TRIP += task.tripCode()
        ClaimRequirementContextKeys.ROUTE += task.routeCode()
        ClaimRequirementContextKeys.TAKEOVER_STATION += task.takeoverStation().name()
        ClaimRequirementContextKeys.SOURCE += task.source()
    }
}
