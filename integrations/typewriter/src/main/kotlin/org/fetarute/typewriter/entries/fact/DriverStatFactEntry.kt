package org.fetarute.typewriter.entries.fact

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import org.bukkit.entity.Player
import org.fetarute.typewriter.DriverStatsCache
import org.koin.java.KoinJavaComponent

/** 读哪一项累计成绩。 */
enum class DriverStat {
    TASKS,
    COMPLETED,
    TOTAL_POINTS,
    BEST_GRADE,
    LAST_POINTS,
}

@Entry("fetarute_driver_stat_fact", "玩家的 FTCA 驾驶记录", Colors.PURPLE, "mdi:trophy")
/**
 * [事实](/docs/creating-stories/facts)：玩家的一项驾驶记录。
 *
 * <fields.ReadonlyFactInfo />
 *
 * | 记录 | 值 |
 * |------|----|
 * | 车次数 | 玩家开过的车次数 |
 * | 开完 | 开到终点的车次数 |
 * | 总得分 | 开到终点的车次得分合计（与排行相同） |
 * | 最好评级 | S = 5，A = 4，B = 3，C = 2，D = 1，没有 = 0 |
 * | 最近得分 | 最近一趟的得分（0 到 100） |
 *
 * ## 用法示例
 * 玩家以 B 级以上开完五趟后解锁更难的线路。
 */
class DriverStatFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("读哪一项记录。")
    val stat: DriverStat = DriverStat.COMPLETED,
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val stats = KoinJavaComponent.get<DriverStatsCache>(DriverStatsCache::class.java).of(player.uniqueId)
        val value = when (stat) {
            DriverStat.TASKS -> stats.tasks
            DriverStat.COMPLETED -> stats.completed
            DriverStat.TOTAL_POINTS -> stats.totalPoints.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            DriverStat.BEST_GRADE -> DriverStatsCache.gradeRank(stats.bestGrade)
            DriverStat.LAST_POINTS -> stats.lastPoints
        }
        return FactData(value)
    }
}
