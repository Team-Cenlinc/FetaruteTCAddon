package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 站台屏文案。中英文同时显示，每条文案取两个键：{@code <键>} 为中文，{@code <键>-secondary} 为英文。
 *
 * <p>文案来自语言文件 {@code pids.board.*}，按纯文本读取；含 {@code <minutes>} 的条目由这里替换分钟数。
 */
public final class PidsVocabulary {

  private static final String PREFIX = "pids.board.";
  private static final String SECONDARY = "-secondary";
  private static final String MINUTES = "<minutes>";
  private static final String PLATFORM = "<platform>";

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

  private static Names withMinutes(Names names, long minutes) {
    String value = Long.toString(minutes);
    return new Names(
        names.primary().replace(MINUTES, value), names.secondary().replace(MINUTES, value));
  }
}
