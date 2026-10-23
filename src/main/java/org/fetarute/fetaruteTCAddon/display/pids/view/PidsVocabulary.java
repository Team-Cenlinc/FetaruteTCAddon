package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 站台屏文案。中英文同时显示，每条文案取两个键：{@code <键>} 为中文，{@code <键>-secondary} 为英文。
 *
 * <p>文案来自语言文件 {@code pids.board.*}，按纯文本读取；含 {@code <minutes>} 等占位符的条目由这里替换。
 */
public final class PidsVocabulary {

  private static final String PREFIX = "pids.board.";
  private static final String SECONDARY = "-secondary";
  private static final String MINUTES = "<minutes>";
  private static final String PLATFORM = "<platform>";
  private static final String STATION = "<station>";
  private static final String COUNT = "<count>";
  private static final String FROM = "<from>";
  private static final String TO = "<to>";
  private static final String TIME = "<time>";
  private static final String END = "<end>";
  private static final String CARS = "<cars>";
  private static final String DESTINATION = "<destination>";

  private final Function<String, String> text;

  /**
   * @param text 按完整键读取纯文本（如 {@code LocaleManager#text}）
   */
  public PidsVocabulary(Function<String, String> text) {
    this.text = Objects.requireNonNull(text, "text");
  }

  /** 固定文案。 */
  public PidsView.Labels labels() {
    return new PidsView.Labels(
        names("platform"),
        names("minutes"),
        names("no-more-trains"),
        names("header.line"),
        names("header.destination"),
        names("header.platform"),
        names("header.arrival"));
  }

  /** 叫车提示：“可右键本屏叫车”。 */
  public Names callHint() {
    return names("call-hint");
  }

  /** 暂无后续列车且能叫车：“暂无后续列车，可右键本屏叫车”。 */
  public Names noMoreTrainsCall() {
    return names("no-more-trains-call");
  }

  /** 叫来的车的状态：“叫车”。 */
  public Names onCall() {
    return names("status.on-call");
  }

  /** 管理员加开的车的状态：“加开”。 */
  public Names extra() {
    return names("status.extra");
  }

  /** 准点。 */
  public Names onTime() {
    return names("status.on-time");
  }

  /** 晚点 N 分。 */
  public Names late(long minutes) {
    return withMinutes(names("status.late"), minutes);
  }

  /** 严重晚点 N 分。 */
  public Names severelyLate(long minutes) {
    return withMinutes(names("status.severely-late"), minutes);
  }

  /** 取消。 */
  public Names cancelled() {
    return names("status.cancelled");
  }

  /** 进站。 */
  public Names arriving() {
    return names("status.arriving");
  }

  /** 通过。 */
  public Names passing() {
    return names("status.passing");
  }

  /** 停靠中。 */
  public Names boarding() {
    return names("status.boarding");
  }

  /** 计划。 */
  public Names planned() {
    return names("status.planned");
  }

  /** 站台待定（单站台屏的状态）。 */
  public Names platformPending() {
    return names("status.platform-pending");
  }

  /** 站台变更（列车改到这个站台）。 */
  public Names platformChanged() {
    return names("status.platform-changed");
  }

  /** 改至 N 站台（原定停这个站台的车改去别处）。 */
  public Names movedTo(String platform) {
    Names names = names("status.moved");
    return new Names(
        names.primary().replace(PLATFORM, platform), names.secondary().replace(PLATFORM, platform));
  }

  /** 空位页文案。 */
  public PidsVacancyView.Labels vacancyLabels() {
    return new PidsVacancyView.Labels(
        names("minutes"),
        names("vacancy.advice"),
        names("vacancy.many"),
        names("vacancy.some"),
        names("vacancy.few"));
  }

  /** 本站终到（代替终点名）。 */
  public Names terminating() {
    return names("destination.terminating");
  }

  /** 回库（代替终点名）。 */
  public Names outOfService() {
    return names("destination.out-of-service");
  }

  /** 宣传页、安全提示页的标题。 */
  public Names noticeTitle(PidsNotice notice) {
    return names("notice." + notice.key() + ".title");
  }

  /** 宣传页、安全提示页的说明。 */
  public Names noticeBody(PidsNotice notice) {
    return names("notice." + notice.key() + ".body");
  }

  /** 公告页右上角的标签：“公告 / Notice”或“重要公告 / Important”。 */
  public Names bulletinLabel(boolean important) {
    return names(important ? "bulletin.important" : "bulletin.label");
  }

  /** 备注标签：末班车。 */
  public String lastTrainTag() {
    return primary("remark.last-train");
  }

  /** 备注标签：分行；本站分行时“本站分行”。 */
  public String divisionTag(boolean here) {
    return divisionTagNames(here).primary();
  }

  /**
   * 分行页标题：“本次列车在 X 分行 / This train divides at X”。
   *
   * @param station 分行站；为空时就在本站分行
   */
  public Names divisionTitle(Optional<Names> station) {
    if (station.isEmpty()) {
      return names("division.title-here");
    }
    Names names = names("division.title");
    return new Names(
        names.primary().replace(STATION, station.get().primary()),
        names.secondary().replace(STATION, secondaryOrPrimary(station.get())));
  }

  /**
   * 分行页一段车厢的说明：“前 4 节开往南渡 / Front 4 cars to Nam Toa”；分行后不载客时“后 2 节至 X 止”，在本站分行时“后 2 节本站止”。
   *
   * @param front 车头一侧的一段
   * @param destination 分行后开往的车站；不载客时为空
   * @param station 分行站；为空时就在本站分行
   */
  public Names divisionSegment(
      boolean front, int cars, Optional<Names> destination, Optional<Names> station) {
    Names end = names(front ? "division.front" : "division.rear");
    String key;
    Names place;
    if (destination.isPresent()) {
      key = "division.to";
      place = destination.get();
    } else if (station.isPresent()) {
      key = "division.ends";
      place = station.get();
    } else {
      key = "division.ends-here";
      place = new Names("", "");
    }
    // 英文按单复数另有一套（“1 car ends”）
    return counted(key, cars, end, place);
  }

  /**
   * 备注：解下另走的那一段，“后 4 节往大港城”；分行后不载客时“后 2 节至 X 止”，在本站分行时“后 2 节本站止”。
   *
   * @param front 解下的一段在车头一侧
   * @param destination 分行后开往的车站名；不载客时为空
   * @param station 分行站名；为空时就在本站分行
   */
  public String divisionRemark(
      boolean front, int cars, Optional<String> destination, Optional<String> station) {
    String end = primary(front ? "division.front" : "division.rear");
    if (destination.isPresent()) {
      return fill(primary("division.remark"), end, cars, destination.get());
    }
    return station
        .map(name -> fill(primary("division.ends"), end, cars, name))
        .orElseGet(() -> fill(primary("division.ends-here"), end, cars, ""));
  }

  /**
   * 拆开以后的车厢页标题：“本站已分行，前 4 节即将开往南渡 / Divided · front 4 cars to Nam Toa”。
   *
   * @param front 这一行的列车（解下接着载客的一段）在车头一侧
   * @param destination 它开往的车站
   */
  public Names dividedTitle(boolean front, int cars, Names destination) {
    return counted(
        "division.title-divided",
        cars,
        names(front ? "division.divided-front" : "division.divided-rear"),
        destination);
  }

  /** 拆开以后接着载客的一段：“开往南渡，即将发车 / To Nam Toa · departing soon”。 */
  public Names dividedDeparting(Names destination) {
    return filled("division.departing", 0, new Names("", ""), destination);
  }

  /** 拆开以后不再载客的一段：“不载客，请勿上车 / Not in service”。 */
  public Names dividedNotInService() {
    return names("division.not-in-service");
  }

  /**
   * 联挂页标题。前方站：“本次列车在 X 联挂 / This train joins another at X”；本站：“本次列车在本站联挂，8 节开往 Y / Joins another
   * train here · 8 cars to Y”。
   *
   * @param station 联挂站；为空时就在本站联挂
   * @param cars 联挂以后一共几节（本站联挂时写）
   * @param destination 联挂以后开往的车站（本站联挂时写）
   */
  public Names joinTitle(Optional<Names> station, int cars, Names destination) {
    if (station.isPresent()) {
      return filled("join.title", cars, new Names("", ""), station.get());
    }
    return filled("join.title-here", cars, new Names("", ""), destination);
  }

  /**
   * 联挂页一段车厢的说明。
   *
   * @param part 哪一段
   * @param cars 这一段几节
   * @param station 联挂站（前方站联挂时“在 X 挂上 4 节”用）；本站联挂时为空
   */
  public Names joinSegment(JoinPart part, int cars, Optional<Names> station) {
    Names place = station.orElse(new Names("", ""));
    return part.counted
        ? counted(part.key, cars, new Names("", ""), place)
        : filled(part.key, cars, new Names("", ""), place);
  }

  /** 联挂页的几段。 */
  public enum JoinPart {
    /** 本列车，前方站联挂：“本列车 4 节，无需换乘”（英文不写节数）。 */
    OWN_AHEAD("join.own-ahead", false),
    /** 前方站要挂上的几节：“在 X 挂上 4 节”。 */
    ADDED_AHEAD("join.added", true),
    /** 本列车，本站联挂、已在站台或另一列车还没到：“本列车 4 节”。 */
    OWN_HERE("join.own-here", true),
    /** 本列车，本站联挂、另一列车已在站台：“本列车 4 节，进站后挂上”。 */
    OWN_ARRIVING("join.own-arriving", true),
    /** 已在站台的另一列车：“已在站台的 4 节”。 */
    STANDING("join.standing", true),
    /** 还没到的另一列车：“进站挂上 4 节”。 */
    JOINING("join.joining", true);

    private final String key;

    /** 英文按单复数另有一条。 */
    private final boolean counted;

    JoinPart(String key, boolean counted) {
      this.key = key;
      this.counted = counted;
    }
  }

  /** 按节数取中英文案（英文一节另有 {@code -one} 一条），填 {@code <end>}、{@code <cars>} 与地名。 */
  private Names counted(String key, int cars, Names end, Names place) {
    return filled(key, cars == 1 ? "-one" : "", cars, end, place);
  }

  /** 取中英文案，填 {@code <end>}、{@code <cars>} 与地名。 */
  private Names filled(String key, int cars, Names end, Names place) {
    return filled(key, "", cars, end, place);
  }

  private Names filled(String key, String variant, int cars, Names end, Names place) {
    String secondary = text.apply(PREFIX + key + variant + SECONDARY);
    return new Names(
        fill(primary(key), end.primary(), cars, place.primary()),
        fill(secondary, secondaryOrPrimary(end), cars, secondaryOrPrimary(place)));
  }

  /** 填分行说明里的 {@code <end>}、{@code <cars>} 与地名（{@code <destination>} 或 {@code <station>}）。 */
  private static String fill(String template, String end, int cars, String place) {
    return template
        .replace(END, end)
        .replace(CARS, Integer.toString(cars))
        .replace(DESTINATION, place)
        .replace(STATION, place);
  }

  /** 备注标签：直通。 */
  public String throughTag() {
    return primary("remark.through");
  }

  /** 备注标签：联挂（后面写联挂站）。 */
  public String joinTag() {
    return joinTagNames().primary();
  }

  /** 备注标签：经由。 */
  public String viaTag() {
    return primary("remark.via");
  }

  /** 2×1 停站屏文案。 */
  public PidsStopListView.Labels stopListLabels() {
    return new PidsStopListView.Labels(
        names("minutes"),
        names("no-more-trains"),
        names("remark.via"),
        names("remark.through"),
        names("remark.division"),
        names("remark.join"));
  }

  /** 分行标签的中英文：“分行 / Divides”；本站分行时“本站分行 / Divides here”。 */
  public Names divisionTagNames(boolean here) {
    return names(here ? "remark.division-here" : "remark.division");
  }

  /** 联挂标签的中英文：“联挂 / Joins”。 */
  public Names joinTagNames() {
    return names("remark.join");
  }

  /** 2×1 停站屏：分行后另一段不再载客，“后 4 节到此止 / Rear 4 cars end”（站名写在上一行）。 */
  public Names stopListEndsThere(boolean front, int cars) {
    return counted(
        "stop-list.ends-there",
        cars,
        names(front ? "division.front" : "division.rear"),
        new Names("", ""));
  }

  /** 2×1 停站屏：本站分行、另一段不再载客，“后 4 节本站止 / Rear 4 cars end”。 */
  public Names stopListEndsHere(boolean front, int cars) {
    return counted(
        "stop-list.ends-here",
        cars,
        names(front ? "division.front" : "division.rear"),
        new Names("", ""));
  }

  /** 2×1 停站屏：回程支线车在联挂站，“联挂后无需换乘 / Stay on board”。 */
  public Names stopListJoinsThere() {
    return names("stop-list.joins-there");
  }

  /** 2×1 停站屏：分行站之后的站只有部分车厢开到，“仅前 4 节 / Front 4 only”。 */
  public Names stopListOnly(boolean front, int cars) {
    return filled(
        "stop-list.only",
        cars,
        names(front ? "division.front" : "division.rear"),
        new Names("", ""));
  }

  /** 2×1 停站屏本站分行时最底下一行：“前往南渡请乘前 4 节 / Front 4 cars to Nam Toa”。 */
  public Names stopListBoardPart(boolean front, int cars, Names destination) {
    return counted(
        "stop-list.board-part",
        cars,
        names(front ? "division.front" : "division.rear"),
        destination);
  }

  /** 2×1 后续列车页的标题。 */
  public Names following() {
    return names("stop-list.following");
  }

  /** 2×1 停站屏终点下面一行：从哪站起直通。 */
  public String throughFrom(String station) {
    return primary("stop-list.through-from").replace(STATION, station);
  }

  /** 线路运行状况屏的页标题。 */
  public Names lineStatusTitle() {
    return names("line-status.title");
  }

  /**
   * 线路运行状况屏的表头与空表提示。
   *
   * @param filtered 运营商有线路、只是屏幕的线路过滤一条也对不上：空表提示改说过滤
   */
  public PidsLineStatusView.Labels lineStatusLabels(boolean filtered) {
    return new PidsLineStatusView.Labels(
        names("line-status.header.line"),
        names("line-status.header.status"),
        names("line-status.header.details"),
        names(filtered ? "line-status.filtered" : "line-status.empty"));
  }

  /** 线路运行状况。 */
  public Names condition(PidsLineStatus.Condition condition) {
    return names(
        "line-status.condition." + condition.name().toLowerCase(Locale.ROOT).replace('_', '-'));
  }

  /** 说明：晚点最多 N 分钟。 */
  public Names lateUpTo(long minutes) {
    return withMinutes(names("line-status.detail.late"), minutes);
  }

  /** 说明：近 1 小时取消 N 班（英文单复数分开写）。 */
  public Names cancelledTrips(int trips) {
    String count = Integer.toString(trips);
    String secondary =
        trips == 1 ? "line-status.detail.cancelled-one" : "line-status.detail.cancelled";
    return new Names(
        primary("line-status.detail.cancelled").replace(COUNT, count),
        text.apply(PREFIX + secondary + SECONDARY).replace(COUNT, count));
  }

  /** 说明：某站至某站暂停运营；英文没有英文站名时写中文站名。 */
  public Names sectionClosed(Names from, Names to) {
    Names names = names("line-status.detail.closed");
    return new Names(
        names.primary().replace(FROM, from.primary()).replace(TO, to.primary()),
        names
            .secondary()
            .replace(FROM, secondaryOrPrimary(from))
            .replace(TO, secondaryOrPrimary(to)));
  }

  /** 说明：线路检修。 */
  public Names maintenance() {
    return names("line-status.detail.maintenance");
  }

  /** 说明：首班几点。 */
  public Names firstTrain(String time) {
    Names names = names("line-status.detail.first-train");
    return new Names(names.primary().replace(TIME, time), names.secondary().replace(TIME, time));
  }

  /** 色牌上的停站类型；普通、其他不显示。 */
  public Optional<String> type(RouteApi.OperationType type) {
    return switch (type) {
      case LOCAL -> Optional.of(primary("type.local"));
      case RAPID -> Optional.of(primary("type.rapid"));
      case EXPRESS -> Optional.of(primary("type.express"));
      case NORMAL, OTHER -> Optional.empty();
    };
  }

  private Names names(String key) {
    return new Names(primary(key), text.apply(PREFIX + key + SECONDARY));
  }

  private String primary(String key) {
    return text.apply(PREFIX + key);
  }

  private static String secondaryOrPrimary(Names names) {
    return names.secondary().isBlank() ? names.primary() : names.secondary();
  }

  private static Names withMinutes(Names names, long minutes) {
    String value = Long.toString(minutes);
    return new Names(
        names.primary().replace(MINUTES, value), names.secondary().replace(MINUTES, value));
  }
}
