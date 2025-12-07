package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.Objects;
import java.util.Optional;

/** 线路附带的运营商、线路、班次信息。 解析 RouteId 后保存结构化字段，供信息屏和调度统计使用。 */
public record RouteMetadata(
    String operator, String lineId, String serviceId, Optional<String> displayName) {

  public RouteMetadata {
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(lineId, "lineId");
    Objects.requireNonNull(serviceId, "serviceId");
  }

  public static RouteMetadata of(
      String operator, String lineId, String serviceId, String displayName) {
    return new RouteMetadata(operator, lineId, serviceId, Optional.ofNullable(displayName));
  }
}
