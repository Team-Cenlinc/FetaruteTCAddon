package org.fetarute.typewriter

import com.typewritermc.core.entries.Ref
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import org.fetarute.fetaruteTCAddon.api.FetaruteApi
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi
import org.fetarute.typewriter.entries.action.AssignTripActionEntry

/** 本扩展派出的任务的来源标记。 */
internal const val SOURCE = "typewriter"

/** 派任务的动作条目 ID 记在任务附加数据的这个键下。 */
internal const val ENTRY_KEY = "entry"

/** 需要的 FetaruteTCAddon 公开 API 最低版本（驾驶任务 API 自此版本起）。 */
private const val REQUIRED_API = "1.10.0"

/** 版本不够时只告警一次。 */
@Volatile
private var warnedIncompatible = false

/** FetaruteTCAddon 的驾驶任务 API；插件未加载或版本过旧时为 null。 */
internal fun driveApi(): DriveApi? {
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
    return api.drive()
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
enum class StopWindow {
    ACCURATE,
    ACCEPTED,
    SHORT,
    OVERRUN,
    SKIPPED,
}
