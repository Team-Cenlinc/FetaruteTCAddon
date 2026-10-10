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

  /** 备注标签：直通。 */
  public String throughTag() {
    return primary("remark.through");
  }

  /** 备注标签：经由。 */
  public String viaTag() {
    return primary("remark.via");
  }

  /** 2×1 停站屏文案。 */
  public PidsStopListView.Labels stopListLabels() {
    return new PidsStopListView.Labels(
        names("minutes"), names("no-more-trains"), names("remark.via"), names("remark.through"));
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
