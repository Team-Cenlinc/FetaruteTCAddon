package org.fetarute.fetaruteTCAddon.coupler;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives;

/**
 * 一列运行中的车在本交路前方（或此刻停着的这一站）要分行（解编 {@code DECP}）：在哪一站、拆成哪两段、各去哪里。给站台屏与公开 API 的站牌行用。
 *
 * <p>只预测下一个分行站。按表运行时表上这一班不拆、编组切不开时不预测；这一站已经拆过（或放弃）后也不再预测。
 *
 * @param routeId 交路 ID（{@code 运营商:线路:交路}，与站牌行同一口径）
 * @param stopIndex 分行站在交路里的下标（与站牌行的停靠序号同一口径）
 * @param stationId 分行站（{@code 运营商:站码}）
 * @param station 分行站显示名
 * @param parts 两段，车头在前
 */
public record DivisionForecast(
    String routeId, int stopIndex, String stationId, String station, List<Part> parts) {

  public DivisionForecast {
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(stationId, "stationId");
    station = station == null ? "" : station;
    parts = List.copyOf(parts);
  }

  /**
   * 一段车厢。
   *
   * @param cars 节数
   * @param detached 在分行站解下另走；否则留下继续本交路
   * @param destinationId 分行后开往的车站（{@code 运营商:站码}）；分行后不载客（就地待命、回库）时为空
   * @param destination 开往的车站显示名；不载客时为空字符串
   */
  public record Part(
      int cars, boolean detached, Optional<String> destinationId, String destination) {

    public Part {
      cars = Math.max(1, cars);
      destinationId = destinationId == null ? Optional.empty() : destinationId;
      destination = destination == null ? "" : destination;
    }

    /** 分行后继续载客。 */
    public boolean inService() {
      return destinationId.isPresent();
    }
  }

  /**
   * 按切法排出两段，车头在前。
   *
   * @param cars 全车节数
   * @param cut 切法（{@link DecoupleCut#resolve} 的结果，{@code at} 为后一段第一节的下标）
   * @param kept 留下的一段开往哪里
   * @param detached 解下的一段开往哪里
   */
  static List<Part> parts(int cars, DecoupleCut.Cut cut, Destination kept, Destination detached) {
    boolean frontDetached = cut.end() == RouteCouplingDirectives.End.FRONT;
    Part front = part(cut.at(), frontDetached, frontDetached ? detached : kept);
    Part rear = part(cars - cut.at(), !frontDetached, frontDetached ? kept : detached);
    return List.of(front, rear);
  }

  private static Part part(int cars, boolean detached, Destination destination) {
    return new Part(cars, detached, destination.stationId(), destination.name());
  }

  /**
   * 一段分行后开往的车站。
   *
   * @param stationId 车站（{@code 运营商:站码}）；不载客时为空
   * @param name 显示名
   */
  record Destination(Optional<String> stationId, String name) {

    static final Destination NOT_IN_SERVICE = new Destination(Optional.empty(), "");

    Destination {
      stationId = stationId == null ? Optional.empty() : stationId;
      name = name == null ? "" : name;
    }
  }
}
