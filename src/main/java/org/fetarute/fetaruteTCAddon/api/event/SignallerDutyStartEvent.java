package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** 信号员上岗（1.15.0）：玩家点了控制台、确认上岗之后。控制区随即转为人工；调度图未就绪时 {@link #isManual()} 为 false，暂为自动。 */
public final class SignallerDutyStartEvent extends SignallerEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final boolean manual;
  private final String parentZoneCode;

  public SignallerDutyStartEvent(
      UUID playerId, String operatorCode, String zoneCode, String zoneName, boolean manual) {
    this(playerId, operatorCode, zoneCode, zoneName, null, manual);
  }

  /**
   * @param parentZoneCode 在中心工位上岗时所属大区的代码；普通控制区为 {@code null} 或空串
   */
  public SignallerDutyStartEvent(
      UUID playerId,
      String operatorCode,
      String zoneCode,
      String zoneName,
      String parentZoneCode,
      boolean manual) {
    super(playerId, operatorCode, zoneCode, zoneName);
    this.manual = manual;
    this.parentZoneCode = parentZoneCode == null ? "" : parentZoneCode;
  }

  /** 上岗后控制区已转为人工。 */
  public boolean isManual() {
    return manual;
  }

  /**
   * 在中心工位上岗时所属大区的代码：大区有人值守时后来的人分到一片临时小区，{@link #getZoneCode()} 是它的临时代码（形如 {@code
   * 大区代码~2}，只在内存里），{@link #getZoneName()} 是临时名。普通控制区为空。
   */
  public Optional<String> getParentZoneCode() {
    return parentZoneCode.isEmpty() ? Optional.empty() : Optional.of(parentZoneCode);
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
