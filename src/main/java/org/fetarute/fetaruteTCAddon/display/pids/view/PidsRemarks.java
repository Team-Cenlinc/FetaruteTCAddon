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
    List<RouteStop> stops = directory.stops(row.routeId());
    int here = row.stopSequence();
    int end = lastStop(stops);
    if (here >= 0 && here < end) {
      through(stops, here, end, theme).ifPresent(parts::add);
      via(row, stops, here, end, theme).ifPresent(parts::add);
    }
    return parts.isEmpty() ? Optional.empty() : Optional.of(new Remark(parts));
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

  private Optional<RemarkPart> through(List<RouteStop> stops, int here, int end, PidsTheme theme) {
    for (int i = here + 1; i < end; i++) {
      Optional<RouteApi.LineRef> change = stops.get(i).lineChange();
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
            new RemarkPart(
                vocabulary.throughTag(),
                style.map(PidsDirectory.LineStyle::color).orElse(theme.outline()),
                List.of(name),
                name.equals(code) ? Optional.empty() : Optional.of(code)));
      }
    }
    return Optional.empty();
  }

  private Optional<RemarkPart> via(
      PidsRow row, List<RouteStop> stops, int here, int end, PidsTheme theme) {
    Set<String> skipped = new HashSet<>();
    stops.get(here).stationId().ifPresent(id -> skipped.add(normalize(id)));
    stops.get(end).stationId().ifPresent(id -> skipped.add(normalize(id)));
    row.destinationId().ifPresent(id -> skipped.add(normalize(id)));
    List<Candidate> candidates = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = here + 1; i < end; i++) {
      RouteStop stop = stops.get(i);
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
    if (chosen.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new RemarkPart(
            vocabulary.viaTag(),
            theme.amber(),
            chosen.stream().map(candidate -> stationName(candidate.stationId())).toList(),
            Optional.empty()));
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
    int colon = candidate.stationId().indexOf(':');
    if (colon <= 0) {
      return 0;
    }
    return directory
        .linesServing(
            new PidsStationKey(
                candidate.stationId().substring(0, colon),
                candidate.stationId().substring(colon + 1)))
        .size();
  }

  private String stationName(String stationId) {
    return directory
        .stationName(stationId)
        .map(Names::primary)
        .map(PidsText::compactSeparators)
        .orElseGet(() -> stationId.substring(stationId.indexOf(':') + 1));
  }

  private static String normalize(String stationId) {
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
      return stationId.substring(stationId.indexOf(':') + 1);
    }
  }
}
