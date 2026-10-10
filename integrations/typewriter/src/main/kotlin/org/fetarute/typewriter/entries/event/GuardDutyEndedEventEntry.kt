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
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.triggerAllFor
import org.fetarute.fetaruteTCAddon.api.event.GuardDutyEndedEvent
import org.fetarute.typewriter.GuardDutyEndReason
import java.util.Optional
import kotlin.reflect.KClass

@Entry("fetarute_guard_duty_ended_event", "FTCA 车掌离岗时", Colors.YELLOW, "mdi:bell-off")
@ContextKeys(GuardDutyEndedContextKeys::class)
/**
 * “车掌离岗”事件：玩家不再是这列车的车掌时触发——自己结束、值乘到交班站、漏乘、连续超时、
 * 下线，或被撤下。
 *
 * ## 用法示例
 * 收起车掌相关的剧情提示，或在交班站之后告诉玩家下一步做什么。
 */
class GuardDutyEndedEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("只要因这个原因离岗的；留空不限原因。")
    val reason: Optional<GuardDutyEndReason> = Optional.empty(),
) : EventEntry

enum class GuardDutyEndedContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(String::class)
    TRAIN(String::class),

    @KeyType(String::class)
    REASON(String::class),
}

@EntryListener(GuardDutyEndedEventEntry::class)
fun onGuardDutyEnded(event: GuardDutyEndedEvent, query: Query<GuardDutyEndedEventEntry>) {
    val player = event.player.orElse(null) ?: return
    query.findWhere {
        it.reason.map { wanted -> wanted.name == event.reason }.orElse(true)
    }.triggerAllFor(player) {
        GuardDutyEndedContextKeys.TRAIN += event.trainName
        GuardDutyEndedContextKeys.REASON += event.reason
    }
}
