package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 车站组变化（1.6.0）：建组、成员增删改、删组。
 *
 * <p>与其他公开事件一样在变化后的下一个 tick 由主线程发出。事件发出时车站组与停靠线路查询已经是新数据， {@link #getDataRevision()} 等于当时的 {@code
 * FetaruteApi#dataRevision()}。
 */
public final class StationGroupChangedEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final ChangeType changeType;
  private final UUID groupId;
  private final UUID companyId;
  private final String groupCode;
  private final Optional<UUID> stationId;
  private final long dataRevision;

  public StationGroupChangedEvent(
      ChangeType changeType,
      UUID groupId,
      UUID companyId,
      String groupCode,
      Optional<UUID> stationId,
      long dataRevision) {
    this.changeType = Objects.requireNonNull(changeType, "changeType");
    this.groupId = Objects.requireNonNull(groupId, "groupId");
    this.companyId = Objects.requireNonNull(companyId, "companyId");
    this.groupCode = Objects.requireNonNull(groupCode, "groupCode");
    this.stationId = stationId == null ? Optional.empty() : stationId;
    this.dataRevision = dataRevision;
  }

  /** 变化类型。 */
  public ChangeType getChangeType() {
    return changeType;
  }

  /** 车站组 ID（删组时组已不存在）。 */
  public UUID getGroupId() {
    return groupId;
  }

  /** 组所属公司。 */
  public UUID getCompanyId() {
    return companyId;
  }

  /** 组代码。 */
  public String getGroupCode() {
    return groupCode;
  }

  /** 涉及的成员车站（成员变化时）。 */
  public Optional<UUID> getStationId() {
    return stationId;
  }

  /** 变化生效后的数据版本。 */
  public long getDataRevision() {
    return dataRevision;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }

  /** 变化类型。 */
  public enum ChangeType {
    /** 建组 */
    CREATED,
    /** 加入成员 */
    MEMBER_ADDED,
    /** 成员换乘方式/步行秒数变化 */
    MEMBER_UPDATED,
    /** 移除成员 */
    MEMBER_REMOVED,
    /** 删组 */
    DELETED
  }
}
