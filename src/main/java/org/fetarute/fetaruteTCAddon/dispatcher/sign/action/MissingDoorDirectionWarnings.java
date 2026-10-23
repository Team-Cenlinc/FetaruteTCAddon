package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoStation 牌子第 4 行缺少开门方向的告警：同一车站节点在一个服务器进程内只告警一次。
 *
 * <p>第 4 行为空或无法识别时停站照常进行、只是不开门（见 {@link AutoStationDoorDirection#isMissing}），只留调试日志的话无人察觉；
 * 列车在这类站台停站时写一条 WARN，点名节点与第 4 行原文，提示建牌者补上方向。显式写 {@code NONE} 的站台不告警。
 */
final class MissingDoorDirectionWarnings {

  private final Set<String> warnedNodes = ConcurrentHashMap.newKeySet();

  /**
   * 列车在该节点停站时调用。
   *
   * @param nodeId 车站节点 ID
   * @param rawLine 牌子第 4 行原文
   * @param location 牌子位置（仅用于告警文本）
   * @return 第 4 行缺少有效方向、且该节点尚未告警过时返回告警文本
   */
  Optional<String> onStop(String nodeId, String rawLine, String location) {
    if (nodeId == null || nodeId.isBlank() || !AutoStationDoorDirection.isMissing(rawLine)) {
      return Optional.empty();
    }
    if (!warnedNodes.add(nodeId)) {
      return Optional.empty();
    }
    String raw = rawLine == null ? "" : rawLine;
    return Optional.of(
        "AutoStation 牌子未写有效的开门方向，列车在此停站不会开门: node="
            + nodeId
            + ", 第4行=\""
            + raw
            + "\""
            + (raw.isBlank() ? "（空行）" : "（无法识别）")
            + (location == null || location.isBlank() ? "" : " @ " + location)
            + "。请在第 4 行写开门的世界方向（N/E/S/W/NE/NW/SE/SW）或 BOTH；该站台确实不开门请写 NONE。"
            + "同一节点本次运行只提示一次。");
  }
}
