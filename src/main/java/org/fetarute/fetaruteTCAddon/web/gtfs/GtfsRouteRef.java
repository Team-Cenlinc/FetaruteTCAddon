package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;

/**
 * 交路的归属信息：乘客看到的线路、交路代码与停靠表。
 *
 * <p>时刻表只记录「哪条 route」，route 自己的线路才是乘客看到的线路：实服 WS 的时刻表里跑着 IS 线的交路。停靠表（与 route 路径点下标对齐）用于读直通运转的
 * {@code CHANGE} 指令，决定在哪个换线站拆段。
 *
 * @param operatorCode 交路所属运营商代码
 * @param lineCode 交路所属线路代码（乘客看到的起点线路）
 * @param routeCode 交路代码
 * @param stops 与 route 路径点下标对齐的停靠表
 */
public record GtfsRouteRef(
    String operatorCode, String lineCode, String routeCode, List<RouteStop> stops) {

  public GtfsRouteRef {
    operatorCode = Objects.requireNonNull(operatorCode, "operatorCode").trim();
    lineCode = Objects.requireNonNull(lineCode, "lineCode").trim();
    routeCode = routeCode == null ? "" : routeCode.trim();
    stops = stops == null ? List.of() : List.copyOf(stops);
    if (operatorCode.isEmpty() || lineCode.isEmpty()) {
      throw new IllegalArgumentException("交路的运营商与线路代码不能为空");
    }
  }
}
