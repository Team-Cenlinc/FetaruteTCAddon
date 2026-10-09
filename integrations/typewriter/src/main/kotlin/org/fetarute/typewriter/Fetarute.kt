package org.fetarute.typewriter

import com.typewritermc.core.entries.Ref
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import org.fetarute.fetaruteTCAddon.api.FetaruteApi
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi
import org.fetarute.typewriter.entries.action.AssignTripActionEntry

/** 本扩展派出的任务的来源标记。 */
internal const val SOURCE = "typewriter"

/** 派任务的动作条目 ID 记在任务附加数据的这个键下。 */
internal const val ENTRY_KEY = "entry"

/** 需要的 FetaruteTCAddon 公开 API 最低版本（车掌 API 与车掌事件自此版本起）。 */
private const val REQUIRED_API = "1.14.0"

/** 版本不够时只告警一次。 */
@Volatile
private var warnedIncompatible = false

/** FetaruteTCAddon 的驾驶任务 API；插件未加载或版本过旧时为 null。 */
internal fun driveApi(): DriveApi? = fetaruteApi()?.drive()

/** FetaruteTCAddon 的车掌 API；插件未加载或版本过旧时为 null。 */
internal fun guardApi(): GuardApi? = fetaruteApi()?.guard()

/** FetaruteTCAddon 的公开 API；插件未加载或版本过旧时为 null。 */
private fun fetaruteApi(): FetaruteApi? {
    val api = FetaruteApi.getInstance() ?: return null
    // 版本号要在运行时向 FetaruteTCAddon 要：编译期常量会被内联成编译时的版本。
    if (!FetaruteApi.isCompatible(plugin, REQUIRED_API)) {
        if (!warnedIncompatible) {
            warnedIncompatible = true
            logger.warning(
                "[FetaruteTC] FetaruteTCAddon API ${api.version()} is too old; $REQUIRED_API or newer is required"
            )
        }
        return null
    }
    return api
}

/** 没指定动作条目时认所有任务；指定了只认这个条目派出的任务。 */
internal fun DriveApi.TaskView.assignedBy(entry: Ref<AssignTripActionEntry>): Boolean =
    !entry.isSet || (source() == SOURCE && metadata()[ENTRY_KEY] == entry.id)

/** 驾驶方式。 */
enum class DrivingMode(internal val api: DriveApi.Mode) {
    MANUAL(DriveApi.Mode.MANUAL),
    ATO(DriveApi.Mode.ATO),
}

/** 任务的终态。 */
enum class TaskEndState {
    COMPLETED,
    ABANDONED,
    EXPIRED,
    FAILED,
    INTERRUPTED,
}

/** 一站的停车结果。 */
enum class StopOutcome {
    ACCURATE,
    ACCEPTED,
    SHORT,
    OVERRUN,
    SKIPPED,
}

/** 任务从哪里来；与 FetaruteTCAddon 任务的来源标记一一对应，其他插件派的归为 [OTHER_PLUGIN]。 */
enum class TaskSource(private val key: String?) {
    TASK_BOARD("board"),
    TYPEWRITER(SOURCE),
    TAKEOVER("takeover"),
    CONTINUATION("continuation"),
    ROAD_TEST("exam"),
    ROAD_TEST_PRACTICE("training"),
    OTHER_PLUGIN(null),
    ;

    fun matches(source: String): Boolean =
        if (key != null) key == source else TaskSource.entries.none { it.key == source }

    companion object {
        /** 路考练习的来源标记：不进驾驶记录。 */
        internal const val PRACTICE = "training"
    }
}

/** 没选来源时认所有任务（也认没有任务的驾驶）；选了只认这些来源的任务。 */
internal fun List<TaskSource>.allows(task: DriveApi.TaskView?): Boolean =
    isEmpty() || (task != null && any { it.matches(task.source()) })
