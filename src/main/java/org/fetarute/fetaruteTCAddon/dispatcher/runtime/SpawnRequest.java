package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.Objects;

/**
 * 向 Depot 发起的发车请求（当 Layover 池无可用车时）。
 *
 * <p>当前主要作为 ServiceTicket 的兜底请求。
 */
public record SpawnRequest(
    String requestId, Instant requestTime, ServiceTicket ticket, String depotKey) {

  public SpawnRequest {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(requestTime, "requestTime");
    Objects.requireNonNull(ticket, "ticket");
    // depotKey 允许为 null：表示任意 depot 都可接受
  }
}
