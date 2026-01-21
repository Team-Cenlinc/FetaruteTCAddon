package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 分配给 Layover 列车的下一趟运营任务（票据）。
 *
 * <p>包含计划发车时间、目标线路/Route 以及优先级信息。
 */
public record ServiceTicket(
    String ticketId,
    Instant plannedDepartTime,
    UUID routeId,
    String terminalKey,
    int priority,
    TicketMode mode) {

  public ServiceTicket {
    Objects.requireNonNull(ticketId, "ticketId");
    Objects.requireNonNull(plannedDepartTime, "plannedDepartTime");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(terminalKey, "terminalKey");
    Objects.requireNonNull(mode, "mode");
  }

  /** 任务模式。 */
  public enum TicketMode {
    /** 正常运营（载客）。 */
    OPERATION,
    /** 回库/回送（非载客）。 */
    RETURN
  }
}
