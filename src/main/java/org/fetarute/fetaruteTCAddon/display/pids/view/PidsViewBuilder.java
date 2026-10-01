package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import org.fetarute.fetaruteTCAddon.display.Lateness;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSnapshot;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Arrival;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.ArrivalMode;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Badge;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Destination;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Label;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.PlatformCell;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Tone;

/**
 * 由快照算出一块屏幕的 {@link PidsView}：按站台过滤、解析名称与颜色、决定每行的状态词。
 *
 * <p>纯函数，依赖只有名称目录与文案。状态词口径：
 *
 * <ul>
 *   <li>取消：色牌与站台空心，终点划掉，到站写“取消”（红）
 *   <li>回库：色牌空心写“—”，终点写“回库”，不显示时间
 *   <li>停靠中、进站、即将通过：到站格反白
 *   <li>通过：终点用次要色，状态写“通过”，优先于晚点
 *   <li>计划（未出票）：分钟数与“计划”框用次要色
 *   <li>晚点：按表运行时才显示状态；不足 1 分为准点，5 分以上为严重晚点（红），其余为晚点（琥珀）
 * </ul>
 */
public final class PidsViewBuilder {

  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

  /** 回库车色牌上的代码。 */
  private static final String OUT_OF_SERVICE_CODE = "—";

  private final PidsDirectory directory;
  private final PidsVocabulary vocabulary;

  public PidsViewBuilder(PidsDirectory directory, PidsVocabulary vocabulary) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary");
  }

  /**
   * 一块屏幕的显示要求。
   *
   * @param snapshot 车站快照
   * @param now 当前时刻
   * @param zone 时钟时区（服务器时区）
   * @param theme 配色
   * @param platforms 只显示这些站台；为空表示全部
   * @param platformLabels 站台号方块里写的站台；车站统屏为空
   * @param capacity 布局最多显示的行数
   */
  public record Request(
      PidsSnapshot snapshot,
      Instant now,
      ZoneId zone,
      PidsTheme theme,
      Set<String> platforms,
      List<String> platformLabels,
      int capacity) {

    public Request {
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(now, "now");
      Objects.requireNonNull(zone, "zone");
      Objects.requireNonNull(theme, "theme");
      platforms = Set.copyOf(platforms);
      platformLabels = List.copyOf(platformLabels);
    }
  }

  /** 构建视图。 */
  public PidsView build(Request request) {
    List<PidsView.Row> rows = new ArrayList<>();
    for (PidsRow row : request.snapshot().rows()) {
      if (rows.size() >= request.capacity()) {
        break;
      }
      if (!request.platforms().isEmpty() && !request.platforms().contains(row.platform())) {
        continue;
      }
      rows.add(row(row, request));
    }
    return new PidsView(
        request.theme(),
        CLOCK.format(request.now().atZone(request.zone())),
        request.platformLabels(),
        Optional.of(
            stationNames(
                request.snapshot().station().toString(),
                request.snapshot().station().stationCode())),
        directory.linesServing(request.snapshot().station()),
        bandColors(request, rows),
        rows,
        vocabulary.labels());
  }

  /** 站台屏色带：停靠这些站台的线路，按代码去重、保持目录给出的顺序；目录查不到时按到发行里出现过的线路（不含回库）。 */
  private List<Integer> bandColors(Request request, List<PidsView.Row> rows) {
    Map<String, Integer> lines = new LinkedHashMap<>();
    for (String platform : new TreeSet<>(request.platforms())) {
      for (PidsView.LineChip chip :
          directory.linesServingPlatform(request.snapshot().station(), platform)) {
        lines.putIfAbsent(chip.code(), chip.color());
      }
    }
    if (lines.isEmpty()) {
      for (PidsView.Row row : rows) {
        if (!row.badge().code().equals(OUT_OF_SERVICE_CODE)) {
          lines.putIfAbsent(row.badge().code(), row.badge().color());
        }
      }
    }
    return List.copyOf(lines.values());
  }

  private PidsView.Row row(PidsRow row, Request request) {
    boolean cancelled = row.status() == PidsRow.Status.CANCELLED;
    return new PidsView.Row(
        badge(row, cancelled, request.theme()),
        destination(row, cancelled),
        new PlatformCell(row.platform(), cancelled),
        arrival(row, cancelled, request.now()));
  }

  private Badge badge(PidsRow row, boolean cancelled, PidsTheme theme) {
    if (row.outOfService()) {
      return new Badge(OUT_OF_SERVICE_CODE, Optional.empty(), theme.outline(), true);
    }
    String operator = row.routeId().split(":", 2)[0];
    PidsDirectory.LineStyle line =
        directory
            .line(operator, row.lineName())
            .orElse(new PidsDirectory.LineStyle(row.lineName(), theme.outline()));
    Optional<String> type = directory.serviceType(row.routeId()).flatMap(vocabulary::type);
    return new Badge(line.code(), type, line.color(), cancelled);
  }

  private Destination destination(PidsRow row, boolean cancelled) {
    Names names;
    if (row.terminating()) {
      names = vocabulary.terminating();
    } else if (row.outOfService()) {
      names = vocabulary.outOfService();
    } else {
      names =
          row.destinationId()
              .map(id -> stationNames(id, row.destination()))
              .orElse(new Names(row.destination(), ""));
    }
    Tone tone = cancelled || row.passing() ? Tone.MUTED : Tone.NORMAL;
    return new Destination(names, tone, cancelled);
  }

  private Arrival arrival(PidsRow row, boolean cancelled, Instant now) {
    if (cancelled) {
      return new Arrival(
          ArrivalMode.DASH, 0, Tone.MUTED, Optional.of(Label.of(vocabulary.cancelled(), Tone.RED)));
    }
    if (row.outOfService()) {
      return new Arrival(ArrivalMode.DASH, 0, Tone.MUTED, Optional.empty());
    }
    switch (row.status()) {
      case BOARDING -> {
        return highlight(vocabulary.boarding());
      }
      case ARRIVING -> {
        return highlight(row.passing() ? vocabulary.passing() : vocabulary.arriving());
      }
      case PLANNED -> {
        return new Arrival(
            ArrivalMode.COUNTDOWN,
            minutesUntil(row.expectedAt(), now),
            Tone.MUTED,
            Optional.of(Label.boxed(vocabulary.planned(), Tone.MUTED)));
      }
      default -> {
        Optional<Label> status =
            row.passing()
                ? Optional.of(Label.of(vocabulary.passing(), Tone.NORMAL))
                : delayStatus(row.delaySeconds());
        return new Arrival(
            ArrivalMode.COUNTDOWN, minutesUntil(row.expectedAt(), now), Tone.NORMAL, status);
      }
    }
  }

  private static Arrival highlight(Names text) {
    return new Arrival(
        ArrivalMode.HIGHLIGHT, 0, Tone.NORMAL, Optional.of(Label.of(text, Tone.NORMAL)));
  }

  private Optional<Label> delayStatus(OptionalLong delaySeconds) {
    if (delaySeconds.isEmpty()) {
      return Optional.empty();
    }
    long delay = delaySeconds.getAsLong();
    long minutes = Lateness.minutes(delay);
    return Optional.of(
        switch (Lateness.of(delay)) {
          case ON_TIME -> Label.of(vocabulary.onTime(), Tone.NORMAL);
          case LATE -> Label.of(vocabulary.late(minutes), Tone.AMBER);
          case SEVERELY_LATE -> Label.of(vocabulary.severelyLate(minutes), Tone.RED)
              .withCompact(vocabulary.late(minutes));
        });
  }

  /** 距到达的分钟数，向上取整、不小于 0：还差 10 秒也显示 1 分，到点之前不显示 0。 */
  static int minutesUntil(Instant expectedAt, Instant now) {
    long millis = Duration.between(now, expectedAt).toMillis();
    if (millis <= 0L) {
      return 0;
    }
    return (int) Math.min(Integer.MAX_VALUE, (millis + 59_999L) / 60_000L);
  }

  private Names stationNames(String stationId, String fallbackCode) {
    Names names = directory.stationName(stationId).orElse(new Names(fallbackCode, ""));
    return new Names(PidsText.compactSeparators(names.primary()), names.secondary());
  }
}
