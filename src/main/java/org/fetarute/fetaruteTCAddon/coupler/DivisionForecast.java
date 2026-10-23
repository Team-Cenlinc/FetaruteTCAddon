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
 * @param detachedTrip 解下的一段按表接着跑的那一班（子交路首班）的车次标识，与站牌票据、预测行同一口径；不按表、子交路只回库时为空
 * @param divided 已经在这一站拆开（{@code routeId}/{@code stopIndex} 是解下的车接着跑的交路与它在那条交路里的下标）：站台屏画拆开后的车厢标识
 */
public record DivisionForecast(
    String routeId,
    int stopIndex,
    String stationId,
    String station,
    List<Part> parts,
    Optional<String> detachedTrip,
    boolean divided) {

  public DivisionForecast {
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(stationId, "stationId");
    station = station == null ? "" : station;
    parts = List.copyOf(parts);
    detachedTrip = detachedTrip == null ? Optional.empty() : detachedTrip;
  }

  /** 还没拆开的分行。 */
  public DivisionForecast(
      String routeId,
      int stopIndex,
      String stationId,
      String station,
      List<Part> parts,
      Optional<String> detachedTrip) {
    this(routeId, stopIndex, stationId, station, parts, detachedTrip, false);
  }

  /** 还没拆开、不知道解下的一段接着跑哪一班。 */
  public DivisionForecast(
      String routeId, int stopIndex, String stationId, String station, List<Part> parts) {
    this(routeId, stopIndex, stationId, station, parts, Optional.empty(), false);
  }

  /**
   * 拆开以后，从解下的车这边看：交路与下标换成它接着跑的那一段（{@link Part#route}、{@link Part#fromIndex}），标为已拆开。
   *
   * @return 解下的一段不载客、不知道它接着跑哪条交路时为空
   */
  public Optional<DivisionForecast> dividedFor(Part detached) {
    if (detached == null || detached.route().isEmpty() || !parts.contains(detached)) {
      return Optional.empty();
    }
    return Optional.of(
        new DivisionForecast(
            detached.route().get(),
            detached.fromIndex(),
            stationId,
            station,
            parts,
            detachedTrip,
            true));
  }

  /**
   * 一段车厢。
   *
   * @param cars 节数
   * @param detached 在分行站解下另走；否则留下继续本交路
   * @param destinationId 分行后开往的车站（{@code 运营商:站码}）；分行后不载客（就地待命、回库）时为空
   * @param destination 开往的车站显示名；不载客时为空字符串
   * @param stops 分行后这一段还停几个车站（到它的终点为止）；不载客时为 0
   * @param route 分行后这一段跑的交路（{@code 运营商:线路:交路}）；不载客时为空
   * @param fromIndex 这一段在 {@code route} 里从哪一个下标接着跑（分行站在那条交路里的下标）
   */
  public record Part(
      int cars,
      boolean detached,
      Optional<String> destinationId,
      String destination,
      int stops,
      Optional<String> route,
      int fromIndex) {

    public Part {
      cars = Math.max(1, cars);
      destinationId = destinationId == null ? Optional.empty() : destinationId;
      destination = destination == null ? "" : destination;
      stops = destinationId.isEmpty() ? 0 : Math.max(0, stops);
      route = route == null ? Optional.empty() : route;
      fromIndex = Math.max(0, fromIndex);
    }

    /** 不知道分行后的行程（停站数记 0）。 */
    public Part(int cars, boolean detached, Optional<String> destinationId, String destination) {
      this(cars, detached, destinationId, destination, 0, Optional.empty(), 0);
    }

    /** 分行后继续载客。 */
    public boolean inService() {
      return destinationId.isPresent();
    }
  }

  /**
   * 主段：分行后开得更远的一段，站牌与公开接口把它的去向写作列车的主目的地。比分行后的停站数，一样多时取留下的一段（本交路的终点）；不载客的一段不算。
   *
   * @return 两段都不再载客时为空
   */
  public Optional<Part> primary() {
    Part best = null;
    for (Part part : parts) {
      if (!part.inService()) {
        continue;
      }
      if (best == null
          || part.stops() > best.stops()
          || (part.stops() == best.stops() && best.detached() && !part.detached())) {
        best = part;
      }
    }
    return Optional.ofNullable(best);
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

  /**
   * 留下的一段分行后开往哪里。在运营终点拆时留下的一段到这里就不再载客；那样两段都不再载客时乘客都在这里下车，不必分行（为空）。纯计算。
   *
   * @param atTerminus 分行站就是本交路的运营终点
   * @param routeEnd 本交路的运营终点
   * @param detached 解下的一段开往哪里
   */
  static Optional<Destination> keptDestination(
      boolean atTerminus, Destination routeEnd, Destination detached) {
    if (!atTerminus) {
      return Optional.of(routeEnd);
    }
    return detached.inService() ? Optional.of(Destination.NOT_IN_SERVICE) : Optional.empty();
  }

  private static Part part(int cars, boolean detached, Destination destination) {
    return new Part(
        cars,
        detached,
        destination.stationId(),
        destination.name(),
        destination.stops(),
        destination.route(),
        destination.fromIndex());
  }

  /**
   * 一段分行后开往的车站与行程。
   *
   * @param stationId 车站（{@code 运营商:站码}）；不载客时为空
   * @param name 显示名
   * @param stops 分行后还停几个车站
   * @param route 分行后跑的交路（{@code 运营商:线路:交路}）
   * @param fromIndex 在那条交路里从哪一个下标接着跑
   */
  record Destination(
      Optional<String> stationId, String name, int stops, Optional<String> route, int fromIndex) {

    static final Destination NOT_IN_SERVICE = new Destination(Optional.empty(), "");

    Destination {
      stationId = stationId == null ? Optional.empty() : stationId;
      name = name == null ? "" : name;
      route = route == null ? Optional.empty() : route;
    }

    Destination(Optional<String> stationId, String name) {
      this(stationId, name, 0, Optional.empty(), 0);
    }

    /** 分行后继续载客。 */
    boolean inService() {
      return stationId.isPresent();
    }
  }
}
