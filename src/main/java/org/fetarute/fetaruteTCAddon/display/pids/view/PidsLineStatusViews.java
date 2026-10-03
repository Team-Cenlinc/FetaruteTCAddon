package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory.OperatorLine;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus.Condition;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus.Detail;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatusView.Row;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatusView.Style;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 线路运行状况屏的视图。
 *
 * <ul>
 *   <li>范围：本站所属运营商的线路（名称目录已去掉筹建中的），按屏幕的线路过滤；停靠本站的排在前面（按换乘条的顺序，运营商与线路代码一起认），其余按线路代码
 *   <li>密度：大行一页放得下全部线路时用大行，否则用小行，放不下时每 {@value #PAGE_SECONDS} 秒翻一页（按时刻对齐，同站各屏同步）
 *   <li>说明：全线停运写“线路检修”，其余按 {@link PidsLineStatus.Detail}；运行正常、暂无列车不写
 *   <li>没有线路时写“暂无线路信息”；运营商有线路、只是屏幕的线路过滤一条也对不上时，改写过滤没有匹配的线路
 * </ul>
 *
 * <p>只为当前页的线路取状况。
 */
public final class PidsLineStatusViews {

  /** 翻页间隔（秒）。 */
  static final int PAGE_SECONDS = 15;

  private final PidsDirectory directory;
  private final PidsVocabulary vocabulary;

  public PidsLineStatusViews(PidsDirectory directory, PidsVocabulary vocabulary) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary");
  }

  /**
   * @param station 屏幕绑定的车站
   * @param lines 屏幕的线路过滤（大写线路代码）；为空表示不过滤
   * @param theme 配色
   * @param now 当前时刻
   * @param zone 时钟时区
   * @param roomyRows 大行一页几条
   * @param compactRows 小行一页几条
   */
  public record Request(
      PidsStationKey station,
      Set<String> lines,
      PidsTheme theme,
      Instant now,
      ZoneId zone,
      int roomyRows,
      int compactRows) {

    public Request {
      Objects.requireNonNull(station, "station");
      lines = Set.copyOf(lines);
      Objects.requireNonNull(theme, "theme");
      Objects.requireNonNull(now, "now");
      Objects.requireNonNull(zone, "zone");
    }
  }

  /**
   * 屏幕线路过滤可选的线路：线路运行状况屏为本站所属运营商的线路，其余屏为停靠本站的线路。
   *
   * @param directory 名称目录
   * @param station 屏幕绑定的车站
   * @param lineStatus 是线路运行状况屏
   */
  public static List<PidsView.LineChip> filterOptions(
      PidsDirectory directory, PidsStationKey station, boolean lineStatus) {
    return lineStatus
        ? directory.operatorLines(station.operatorCode()).stream().map(OperatorLine::chip).toList()
        : directory.linesServing(station);
  }

  /**
   * @param request 要求
   * @param statuses 线路状况来源
   */
  public PidsLineStatusView build(Request request, PidsLineStatusSource statuses) {
    List<OperatorLine> all = directory.operatorLines(request.station().operatorCode());
    List<OperatorLine> lines = lines(request, all);
    boolean roomy = lines.size() <= request.roomyRows();
    int perPage = Math.max(1, roomy ? request.roomyRows() : request.compactRows());
    int pages = Math.max(1, (lines.size() + perPage - 1) / perPage);
    int page =
        (int) Math.floorMod(Math.floorDiv(request.now().getEpochSecond(), PAGE_SECONDS), pages);
    List<Row> rows =
        lines
            .subList(
                Math.min(lines.size(), page * perPage),
                Math.min(lines.size(), (page + 1) * perPage))
            .stream()
            .map(line -> row(line, statuses.statusOf(line, request.now()), request.zone()))
            .toList();
    String operator = request.station().operatorCode();
    return new PidsLineStatusView(
        request.theme(),
        PidsViewBuilder.CLOCK.format(request.now().atZone(request.zone())),
        vocabulary.lineStatusTitle(),
        directory.operatorName(operator).orElseGet(() -> new Names(operator, "")),
        vocabulary.lineStatusLabels(lines.isEmpty() && !all.isEmpty()),
        rows,
        roomy,
        page,
        pages);
  }

  private List<OperatorLine> lines(Request request, List<OperatorLine> all) {
    List<String> serving =
        directory.lineRefsServing(request.station()).stream()
            .map(ref -> PidsDirectory.lineKey(ref.operatorCode(), ref.lineCode()))
            .toList();
    return all.stream()
        .filter(
            line ->
                request.lines().isEmpty()
                    || request.lines().contains(line.chip().code().toUpperCase(Locale.ROOT)))
        .sorted(
            Comparator.comparingInt(
                line -> {
                  int index =
                      serving.indexOf(
                          PidsDirectory.lineKey(line.operatorCode(), line.chip().code()));
                  return index < 0 ? Integer.MAX_VALUE : index;
                }))
        .toList();
  }

  private Row row(OperatorLine line, PidsLineStatus status, ZoneId zone) {
    return new Row(
        line.chip(),
        vocabulary.condition(status.condition()),
        style(status.condition()),
        detail(status, zone));
  }

  /** 状况的样式：延误与取消琥珀色、严重延误与停运红色、正常绿字、其余灰字。 */
  static Style style(Condition condition) {
    return switch (condition) {
      case SUSPENDED, PART_SUSPENDED, SEVERE_DELAYS -> Style.RED;
      case MINOR_DELAYS, CANCELLATIONS -> Style.AMBER;
      case GOOD -> Style.GOOD;
      case ENDED, NO_TRAINS -> Style.MUTED;
    };
  }

  private Optional<Names> detail(PidsLineStatus status, ZoneId zone) {
    if (status.condition() == Condition.SUSPENDED) {
      return Optional.of(vocabulary.maintenance());
    }
    return switch (status.detail()) {
      case Detail.None ignored -> Optional.empty();
      case Detail.Late late -> Optional.of(vocabulary.lateUpTo(late.minutes()));
      case Detail.Cancelled cancelled -> Optional.of(vocabulary.cancelledTrips(cancelled.trips()));
      case Detail.Closed closed -> Optional.of(
          vocabulary.sectionClosed(
              station(closed.section().from()), station(closed.section().to())));
      case Detail.FirstTrain first -> Optional.of(
          vocabulary.firstTrain(PidsViewBuilder.CLOCK.format(first.at().atZone(zone))));
    };
  }

  /** 车站的中英文名；不知道时写站码。 */
  private Names station(String stationId) {
    return directory
        .stationName(stationId)
        .orElseGet(() -> new Names(stationId.substring(stationId.indexOf(':') + 1), ""));
  }
}
