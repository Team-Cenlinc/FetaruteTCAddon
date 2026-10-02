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
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.graph.GraphApi;
import org.fetarute.fetaruteTCAddon.display.Lateness;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSnapshot;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
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
 *   <li>站台待定：有站台列的屏，站台方块空心写“-”；没有站台列的单站台屏，候选里有本站台就列出， 状态写“站台待定”（琥珀），不显示“进站”
 *   <li>站台变更：站台方块用琥珀色，状态写“站台变更”（琥珀），进站、停靠中照旧反白；原定停本站台、改去别处的车仍在本站台的屏上列出， 状态写“改至 N 站台”（琥珀），不写“进站”
 * </ul>
 *
 * <p>空位页（{@link #vacancy}）：首行是可以上车的运行中列车（不是还没开出、通过、本站终到或回库）且读得到载客时，给出各节车有多满与空位数。
 */
public final class PidsViewBuilder {

  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

  /** 回库车色牌上的代码。 */
  private static final String OUT_OF_SERVICE_CODE = "—";

  /** 在座达到一半算座位较少，达到八成算座位紧张。 */
  private static final double HALF = 0.5;

  private static final double CROWDED = 0.8;

  /** 屏幕“向右”与行车方向的夹角余弦至少这么大才算屏幕顺着轨道，否则说不清车头朝哪一侧。 */
  private static final double ALONG_TRACK = 0.5;

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
   * @param platformColumn 到发表有站台列
   * @param placement 屏幕在世界里的朝向；不知道时为空，空位页车头画在左侧、不画前进方向
   */
  public record Request(
      PidsSnapshot snapshot,
      Instant now,
      ZoneId zone,
      PidsTheme theme,
      Set<String> platforms,
      List<String> platformLabels,
      int capacity,
      boolean platformColumn,
      Optional<Placement> placement) {

    public Request {
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(now, "now");
      Objects.requireNonNull(zone, "zone");
      Objects.requireNonNull(theme, "theme");
      platforms = Set.copyOf(platforms);
      platformLabels = List.copyOf(platformLabels);
      placement = placement == null ? Optional.empty() : placement;
    }

    /** 不知道屏幕位置。 */
    public Request(
        PidsSnapshot snapshot,
        Instant now,
        ZoneId zone,
        PidsTheme theme,
        Set<String> platforms,
        List<String> platformLabels,
        int capacity,
        boolean platformColumn) {
      this(
          snapshot,
          now,
          zone,
          theme,
          platforms,
          platformLabels,
          capacity,
          platformColumn,
          Optional.empty());
    }
  }

  /**
   * 屏幕在世界里的朝向，用来让空位页的车头朝向对上现场。
   *
   * @param worldId 世界
   * @param rightX 站在屏幕前看去“向右”的 x 分量
   * @param rightZ 同上，z 分量
   */
  public record Placement(UUID worldId, int rightX, int rightZ) {

    public Placement {
      Objects.requireNonNull(worldId, "worldId");
    }
  }

  /** 构建视图。 */
  public PidsView build(Request request) {
    List<PidsView.Row> rows = shown(request).stream().map(row -> row(row, request)).toList();
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

  /** 本屏显示的行：按站台过滤，至多布局行数。 */
  private static List<PidsRow> shown(Request request) {
    List<PidsRow> out = new ArrayList<>();
    for (PidsRow row : request.snapshot().rows()) {
      if (out.size() >= request.capacity()) {
        break;
      }
      if (request.platforms().isEmpty() || row.mayUse(request.platforms())) {
        out.add(row);
      }
    }
    return out;
  }

  /**
   * 空位页：本屏下一班真会来的列车（改去别的站台的不算）是可以上车的运行中列车、且读得到载客（全车有座位）时才有。 色牌、终点、到站与主页上这一行相同，色带与主页相同。
   *
   * <p>屏幕朝向已知时，按本站站台节点处的行车方向定车头朝屏幕哪一侧。
   *
   * @param request 与主页相同的显示要求
   * @return 空位页；没有可显示的空位时为空，轮播跳过这一页
   */
  public Optional<PidsVacancyView> vacancy(Request request) {
    List<PidsRow> shown = shown(request);
    Optional<PidsRow> found = vacancyRow(request, shown);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    PidsRow next = found.get();
    PidsView.Row view = row(next, request);
    List<PidsView.Row> rows = shown.stream().map(row -> row(row, request)).toList();
    return Optional.of(
        new PidsVacancyView(
            request.theme(),
            view.badge(),
            view.destination().names(),
            view.arrival(),
            next.cars().stream().map(PidsViewBuilder::car).toList(),
            request
                .placement()
                .flatMap(placement -> front(next, request.snapshot().station(), placement)),
            vocabulary.vacancyLabels(),
            bandColors(request, rows)));
  }

  /** 本屏此刻有没有空位页：轮播据此决定副页能不能轮到空位页。只看那一行，不构建视图。 */
  public boolean hasVacancy(Request request) {
    return vacancyRow(request, shown(request)).isPresent();
  }

  /** 空位页画的那一班：本屏首个真会来本站台的行，且可以上车、读得到座位。 */
  private static Optional<PidsRow> vacancyRow(Request request, List<PidsRow> shown) {
    return shown.stream()
        .filter(row -> !row.movedAwayFrom(request.platforms()))
        .findFirst()
        .filter(PidsViewBuilder::boardable)
        .filter(row -> row.cars().stream().mapToInt(PidsRow.Car::seats).sum() > 0);
  }

  /**
   * 车头朝屏幕哪一侧：站台节点到下一个途经节点（终点站为上一个节点到站台节点）的方向与屏幕“向右”的夹角余弦。 屏幕不顺着轨道（余弦绝对值不到 {@value
   * #ALONG_TRACK}）、或节点坐标不全时为空。
   */
  private Optional<PidsVacancyView.Front> front(
      PidsRow row, PidsStationKey station, Placement placement) {
    List<String> waypoints = directory.waypoints(row.routeId());
    int stop = row.stopSequence();
    if (stop < 0 || stop >= waypoints.size()) {
      return Optional.empty();
    }
    Optional<GraphApi.Position> at =
        Optional.of(row.platform())
            .filter(platform -> !row.platformPending() && !"-".equals(platform))
            .flatMap(
                platform ->
                    directory.nodePosition(
                        placement.worldId(),
                        station.operatorCode() + ":S:" + station.stationCode() + ":" + platform))
            .or(() -> directory.nodePosition(placement.worldId(), waypoints.get(stop)));
    if (at.isEmpty()) {
      return Optional.empty();
    }
    Optional<GraphApi.Position> next =
        stop + 1 < waypoints.size()
            ? directory.nodePosition(placement.worldId(), waypoints.get(stop + 1))
            : Optional.empty();
    Optional<GraphApi.Position> previous =
        stop > 0
            ? directory.nodePosition(placement.worldId(), waypoints.get(stop - 1))
            : Optional.empty();
    double dx;
    double dz;
    if (next.isPresent()) {
      dx = next.get().x() - at.get().x();
      dz = next.get().z() - at.get().z();
    } else if (previous.isPresent()) {
      dx = at.get().x() - previous.get().x();
      dz = at.get().z() - previous.get().z();
    } else {
      return Optional.empty();
    }
    double norm = Math.hypot(dx, dz);
    if (norm < 1.0e-6) {
      return Optional.empty();
    }
    double along = (dx * placement.rightX() + dz * placement.rightZ()) / norm;
    if (Math.abs(along) < ALONG_TRACK) {
      return Optional.empty();
    }
    return Optional.of(along > 0 ? PidsVacancyView.Front.RIGHT : PidsVacancyView.Front.LEFT);
  }

  /** 乘客可以上的运行中列车：不是还没开出、通过、本站终到或回库。 */
  private static boolean boardable(PidsRow row) {
    return (row.status() == PidsRow.Status.EN_ROUTE
            || row.status() == PidsRow.Status.ARRIVING
            || row.status() == PidsRow.Status.BOARDING)
        && !row.passing()
        && !row.terminating()
        && !row.outOfService();
  }

  private static PidsVacancyView.Car car(PidsRow.Car car) {
    if (car.seats() <= 0) {
      return new PidsVacancyView.Car(PidsVacancyView.Level.NONE, 0);
    }
    int occupied = Math.min(car.occupied(), car.seats());
    double filled = (double) occupied / car.seats();
    PidsVacancyView.Level level =
        filled >= CROWDED
            ? PidsVacancyView.Level.FEW
            : filled >= HALF ? PidsVacancyView.Level.SOME : PidsVacancyView.Level.MANY;
    return new PidsVacancyView.Car(level, car.seats() - occupied);
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
    Arrival arrival = arrival(row, cancelled, request.now());
    if (row.platformPending() && !request.platformColumn()) {
      arrival = platformPending(arrival);
    }
    boolean changed = !cancelled && !row.passing() && row.previousPlatform().isPresent();
    return new PidsView.Row(
        badge(row, cancelled, request.theme()),
        destination(row, cancelled),
        new PlatformCell(row.platform(), cancelled || row.platformPending(), changed),
        changed ? platformChanged(row, request, arrival) : arrival);
  }

  /** 站台变更的行。换到本站台（或统屏、多站台屏上看得到新站台）的写“站台变更”，进站、停靠中照旧反白；原定停本站台、现在改去别处的写“改至 N 站台”， 不写“进站”——车不来这里。 */
  private Arrival platformChanged(PidsRow row, Request request, Arrival arrival) {
    if (row.movedAwayFrom(request.platforms())) {
      Label moved = Label.of(vocabulary.movedTo(row.platform()), Tone.AMBER);
      return arrival.mode() == ArrivalMode.HIGHLIGHT
          ? new Arrival(
              ArrivalMode.COUNTDOWN,
              minutesUntil(row.expectedAt(), request.now()),
              Tone.NORMAL,
              Optional.of(moved))
          : new Arrival(
              arrival.mode(), arrival.minutes(), arrival.minutesTone(), Optional.of(moved));
    }
    if (arrival.mode() == ArrivalMode.HIGHLIGHT) {
      return arrival;
    }
    return new Arrival(
        arrival.mode(),
        arrival.minutes(),
        arrival.minutesTone(),
        Optional.of(Label.of(vocabulary.platformChanged(), Tone.AMBER)));
  }

  /** 单站台屏上站台待定的行：列车可能停在别的站台，不能写“进站”；状态改写“站台待定”，分钟数照常倒数。 */
  private Arrival platformPending(Arrival arrival) {
    Label pending = Label.of(vocabulary.platformPending(), Tone.AMBER);
    return arrival.mode() == ArrivalMode.HIGHLIGHT
        ? new Arrival(ArrivalMode.COUNTDOWN, 0, Tone.NORMAL, Optional.of(pending))
        : new Arrival(
            arrival.mode(), arrival.minutes(), arrival.minutesTone(), Optional.of(pending));
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
