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
import org.fetarute.typewriter.GuardStatsCache
import org.koin.java.KoinJavaComponent

/** 读哪一项累计车掌成绩。 */
enum class GuardStat {
    TRIPS,
    COMPLETED,
    TOTAL_POINTS,
    BEST_GRADE,
    LAST_POINTS,
}

@Entry("fetarute_guard_stat_fact", "玩家的 FTCA 车掌记录", Colors.PURPLE, "mdi:medal")
/**
 * [事实](/docs/creating-stories/facts)：玩家的一项车掌记录。只算按时刻表运行、做过作业的车次。
 *
 * <fields.ReadonlyFactInfo />
 *
 * | 记录 | 值 |
 * |------|----|
 * | 趟数 | 玩家当车掌值乘过的趟数 |
 * | 开完 | 值乘到底的趟数 |
 * | 总得分 | 值乘到底的趟数得分合计 |
 * | 最好评级 | S = 5，A = 4，B = 3，C = 2，D = 1，没有 = 0 |
 * | 最近得分 | 最近一趟的得分（0 到 100） |
 *
 * ## 用法示例
 * 玩家当车掌值乘满十趟后开放驾驶证剧情。
 */
class GuardStatFactEntry(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    @Help("读哪一项记录。")
    val stat: GuardStat = GuardStat.COMPLETED,
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        val stats = KoinJavaComponent.get<GuardStatsCache>(GuardStatsCache::class.java).of(player.uniqueId)
        val value = when (stat) {
            GuardStat.TRIPS -> stats.trips
            GuardStat.COMPLETED -> stats.completed
            GuardStat.TOTAL_POINTS -> stats.totalPoints.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            GuardStat.BEST_GRADE -> DriverStatsCache.gradeRank(stats.bestGrade)
            GuardStat.LAST_POINTS -> stats.lastPoints
        }
        return FactData(value)
    }
}
