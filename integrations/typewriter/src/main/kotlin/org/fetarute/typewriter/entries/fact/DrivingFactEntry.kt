package org.fetarute.typewriter.entries.fact

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import org.bukkit.entity.Player
import org.fetarute.typewriter.driveApi

@Entry("fetarute_driving_fact", "玩家是否在驾驶 FTCA 列车", Colors.PURPLE, "mdi:steering")
/**
 * [事实](/docs/creating-stories/facts)：玩家是否在驾驶列车。
 *
 * <fields.ReadonlyFactInfo />
 *
 * | 状态 | 值 |
 * |------|----|
 * | 未驾驶 | 0 |
 * | 自由驾驶（非调度列车） | 1 |
 * | 驾驶调度列车 | 2 |
 *
 * ## 用法示例
 * 玩家驾驶时隐藏某些对话选项。
 */
class DrivingFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val session = driveApi()?.sessionOf(player.uniqueId)?.orElse(null) ?: return FactData(0)
        return FactData(if (session.dispatched()) 2 else 1)
    }
}
