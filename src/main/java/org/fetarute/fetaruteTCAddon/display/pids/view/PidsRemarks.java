package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory.RouteStop;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Remark;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.RemarkPart;

/**
 * 到发行的备注，按要紧程度排列（也是放不下时保留的顺序）：
 *
 * <ol>
 *   <li>末班车：快照标出的当天从本站开往这个终点的最后一班，红色标签
 *   <li>直通：本站之后、终点之前第一次换线（CHANGE），写换入线路的名称（放不下改写线路代码），标签用该线路的颜色
 *   <li>经由：本站之后、终点之前停车的站，琥珀色标签。交路配置了经由站就只写配置的、还没经过的站，配置的站都已经过就不写；
 *       没有配置时推一个：换乘线路最多的站，一样多时站台多的，再一样时直通站优先，最后取离本站近的。 只停一条线、站台不到 {@value #BIG_STATION_PLATFORMS}
 *       个又不是直通站的不算
 * </ol>
 *
 * <p>取消、回库、本站终到、通过的行没有备注；不知道本站停靠序号（或交路停靠点）时只看末班车。
 *
 * <p>直通与经由的判定（{@link #trip}、{@link #through}、{@link #via}）也给 2×1 停站屏用：停站表上的经由站、换线站与备注同一口径。
 */
final class PidsRemarks {

  /** 站台数达到这么多算大站，可以推作经由站。 */
  static final int BIG_STATION_PLATFORMS = 4;

  private final PidsDirectory directory;
  private final PidsVocabulary vocabulary;

  PidsRemarks(PidsDirectory directory, PidsVocabulary vocabulary) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary");
  }

  /** 这一行的备注；没有可写的为空。 */
  Optional<Remark> of(PidsRow row, PidsTheme theme) {
    if (row.status() == PidsRow.Status.CANCELLED
        || row.outOfService()
        || row.terminating()
        || row.passing()) {
      return Optional.empty();
    }
    List<RemarkPart> parts = new ArrayList<>();
    if (row.lastTrain()) {
      parts.add(
          new RemarkPart(vocabulary.lastTrainTag(), theme.red(), List.of(), Optional.empty()));
    }
    trip(row)
        .ifPresent(
            trip -> {
              through(trip)
                  .map(
                      through ->
                          new RemarkPart(
                              vocabulary.throughTag(),
                              through.color().orElse(theme.outline()),
                              List.of(through.name()),
                              through.name().equals(through.code())
                                  ? Optional.empty()
                                  : Optional.of(through.code())))
                  .ifPresent(parts::add);
              List<String> via = via(row, trip);
              if (!via.isEmpty()) {
                parts.add(
                    new RemarkPart(
                        vocabulary.viaTag(),
                        theme.amber(),
                        via.stream().map(this::stationName).toList(),
                        Optional.empty()));
              }
            });
    return parts.isEmpty() ? Optional.empty() : Optional.of(new Remark(parts));
  }

  /**
   * 列车从本站往后的停靠点。
   *
   * @param stops 交路全部停靠点（与停靠序号同下标）
   * @param here 本站停靠序号
   * @param end 运营终点（最后一个停车的车站；其后的车库、折返线不算）的停靠序号
   */
  record Trip(List<RouteStop> stops, int here, int end) {

    Trip {
      stops = List.copyOf(stops);
    }
  }

  /** 本站之后还有停车站时的停靠点；不知道本站停靠序号、交路停靠点，或本站就是终点时为空。 */
  Optional<Trip> trip(PidsRow row) {
    List<RouteStop> stops = directory.stops(row.routeId());
    int here = row.stopSequence();
    int end = lastStop(stops);
    return here >= 0 && here < end ? Optional.of(new Trip(stops, here, end)) : Optional.empty();
  }

  /** 运营终点（最后一个停车的车站；其后的车库、折返线不算）的下标；没有时为 -1。 */
  private static int lastStop(List<RouteStop> stops) {
    for (int i = stops.size() - 1; i >= 0; i--) {
      if (stops.get(i).stops() && stops.get(i).stationId().isPresent()) {
        return i;
      }
    }
    return -1;
  }

  /**
   * 直通：本站之后、终点之前第一次换线。
   *
   * @param index 换线站的停靠序号
   * @param line 换入的线路
   * @param name 线路名（查不到时为线路代码）
   * @param code 线路的显示代码
   * @param color 线路色；查不到线路时为空
   */
  record Through(
      int index, RouteApi.LineRef line, String name, String code, Optional<Integer> color) {}

  /** 本站之后、终点之前第一次换线；本站就是换线站时不算（列车已按新线路发车）。 */
  Optional<Through> through(Trip trip) {
    for (int i = trip.here() + 1; i < trip.end(); i++) {
      Optional<RouteApi.LineRef> change = trip.stops().get(i).lineChange();
      if (change.isPresent()) {
        RouteApi.LineRef line = change.get();
        Optional<PidsDirectory.LineStyle> style =
            directory.line(line.operatorCode(), line.lineCode());
        String code = style.map(PidsDirectory.LineStyle::code).orElse(line.lineCode());
        String name =
            directory
                .lineName(line.operatorCode(), line.lineCode())
                .map(Names::primary)
                .filter(text -> !text.isBlank())
                .orElse(code);
        return Optional.of(
            new Through(i, line, name, code, style.map(PidsDirectory.LineStyle::color)));
      }
    }
    return Optional.empty();
  }

  /**
   * 经由站（{@code 运营商:站码}，大写）：配置了就是配置的、还没经过的站（按配置顺序），没配置时推一个。
   *
   * @return 没有可写的经由站时为空
   */
  List<String> via(PidsRow row, Trip trip) {
    Set<String> skipped = new HashSet<>();
    trip.stops().get(trip.here()).stationId().ifPresent(id -> skipped.add(normalize(id)));
    trip.stops().get(trip.end()).stationId().ifPresent(id -> skipped.add(normalize(id)));
    row.destinationId().ifPresent(id -> skipped.add(normalize(id)));
    List<Candidate> candidates = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = trip.here() + 1; i < trip.end(); i++) {
      RouteStop stop = trip.stops().get(i);
      Optional<String> station = stop.stationId().map(PidsRemarks::normalize);
      if (stop.stops()
          && station.isPresent()
          && !skipped.contains(station.get())
          && seen.add(station.get())) {
        candidates.add(new Candidate(station.get(), i, stop.lineChange().isPresent()));
      }
    }
    List<String> configured = directory.via(row.routeId());
    List<Candidate> chosen =
        configured.isEmpty()
            ? inferred(candidates).stream().toList()
            : configured.stream()
                .flatMap(
                    code ->
                        candidates.stream().filter(c -> c.code().equalsIgnoreCase(code)).limit(1))
                .toList();
    return chosen.stream().map(Candidate::stationId).toList();
  }

  /** 推一个经由站：换乘线路多、站台多、直通站、离本站近，依次比。 */
  private Optional<Candidate> inferred(List<Candidate> candidates) {
    return candidates.stream()
        .filter(
            candidate ->
                lines(candidate) >= 2
                    || directory.platformCount(candidate.stationId()) >= BIG_STATION_PLATFORMS
                    || candidate.through())
        .min(
            Comparator.comparingInt((Candidate candidate) -> -lines(candidate))
                .thenComparingInt(candidate -> -directory.platformCount(candidate.stationId()))
                .thenComparing(candidate -> !candidate.through())
                .thenComparingInt(Candidate::index));
  }

  private int lines(Candidate candidate) {
    return stationKey(candidate.stationId())
        .map(key -> directory.linesServing(key).size())
        .orElse(0);
  }

  /** {@code 运营商:站码} 拆成车站键；格式不对时为空。 */
  static Optional<PidsStationKey> stationKey(String stationId) {
    int colon = stationId.indexOf(':');
    if (colon <= 0 || colon == stationId.length() - 1) {
      return Optional.empty();
    }
    return Optional.of(
        new PidsStationKey(stationId.substring(0, colon), stationId.substring(colon + 1)));
  }

  private String stationName(String stationId) {
    return directory
        .stationName(stationId)
        .map(Names::primary)
        .map(PidsText::compactSeparators)
        .orElseGet(() -> code(stationId));
  }

  /** {@code 运营商:站码} 里的站码：查不到站名时显示它。 */
  static String code(String stationId) {
    return stationId.substring(stationId.indexOf(':') + 1);
  }

  static String normalize(String stationId) {
    return stationId.trim().toUpperCase(Locale.ROOT);
  }

  /**
   * 经由候选。
   *
   * @param stationId {@code 运营商:站码}（大写）
   * @param index 停靠序号
   * @param through 这一站换线（直通站）
   */
  private record Candidate(String stationId, int index, boolean through) {

    String code() {
      return PidsRemarks.code(stationId);
    }
  }
}
