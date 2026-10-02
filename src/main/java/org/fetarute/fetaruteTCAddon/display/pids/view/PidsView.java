package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;

/**
 * 一块屏幕此刻要显示的内容：文字已定稿、颜色已解析，渲染器只负责摆放。
 *
 * <p>是纯数据，{@code equals} 即脏标记：与上一次相同就不必重绘。
 *
 * @param theme 配色
 * @param clock 当前时间（{@code HH:mm}）
 * @param platforms 站台号方块里写的站台，按站台号排序；车站统屏为空
 * @param station 站名；站台屏不显示时可为空
 * @param lines 本站停靠线路（换乘条）
 * @param bandColors 线路色带：本站台停靠线路的颜色，色带按条数等分；为空时用面板色
 * @param rows 到发行，按到站先后排列，条数不超过布局行数
 * @param labels 固定文案
 */
public record PidsView(
    PidsTheme theme,
    String clock,
    List<String> platforms,
    Optional<Names> station,
    List<LineChip> lines,
    List<Integer> bandColors,
    List<Row> rows,
    Labels labels) {

  public PidsView {
    Objects.requireNonNull(theme, "theme");
    Objects.requireNonNull(clock, "clock");
    platforms = List.copyOf(platforms);
    station = station == null ? Optional.empty() : station;
    lines = List.copyOf(lines);
    bandColors = List.copyOf(bandColors);
    rows = List.copyOf(rows);
    Objects.requireNonNull(labels, "labels");
  }

  /** 文字色调。 */
  public enum Tone {
    /** 正文（英文为次要色）。 */
    NORMAL,
    /** 次要色。 */
    MUTED,
    /** 琥珀（晚点）。 */
    AMBER,
    /** 红（严重晚点、取消）。 */
    RED
  }

  /**
   * 中英文名称。
   *
   * @param primary 中文
   * @param secondary 英文；可为空串
   */
  public record Names(String primary, String secondary) {

    public Names {
      Objects.requireNonNull(primary, "primary");
      secondary = secondary == null ? "" : secondary;
    }
  }

  /**
   * 一段中英文文字及其色调。
   *
   * @param text 文字
   * @param tone 色调
   * @param boxed 是否加空心框（“计划”）
   * @param compact 位置放不下全称时改用的短写法（如“严重晚点 6 分”→“晚点 6 分”，色调不变）
   */
  public record Label(Names text, Tone tone, boolean boxed, Optional<Names> compact) {

    public Label {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(tone, "tone");
      compact = compact == null ? Optional.empty() : compact;
    }

    /** 不加框、没有短写法。 */
    public static Label of(Names text, Tone tone) {
      return new Label(text, tone, false, Optional.empty());
    }

    /** 加空心框。 */
    public static Label boxed(Names text, Tone tone) {
      return new Label(text, tone, true, Optional.empty());
    }

    /** 带上短写法。 */
    public Label withCompact(Names shorter) {
      return new Label(text, tone, boxed, Optional.of(shorter));
    }
  }

  /**
   * 换乘条上的一条线路。
   *
   * @param code 线路代码
   * @param color 线路色
   * @param name 线路名
   */
  public record LineChip(String code, int color, Names name) {}

  /**
   * 线路色牌。
   *
   * @param code 线路代码；回库时为“—”
   * @param type 类型（各停、快速等）；为空时只画代码
   * @param color 线路色；回库时为空心框色
   * @param hollow 空心（取消、回库）
   */
  public record Badge(String code, Optional<String> type, int color, boolean hollow) {

    public Badge {
      Objects.requireNonNull(code, "code");
      type = type == null ? Optional.empty() : type;
    }
  }

  /**
   * 终点。
   *
   * @param names 名称
   * @param tone 色调；{@link Tone#MUTED} 时中英文都用次要色
   * @param struck 划掉（取消）
   */
  public record Destination(Names names, Tone tone, boolean struck) {

    public Destination {
      Objects.requireNonNull(names, "names");
      Objects.requireNonNull(tone, "tone");
    }
  }

  /** 到站格的形态。 */
  public enum ArrivalMode {
    /** 分钟数加单位，右侧（或其后）为状态。 */
    COUNTDOWN,
    /** 整格提示（进站、通过、停靠中），反白。 */
    HIGHLIGHT,
    /** 没有到站时间（取消、回库）；状态为可选的提示。 */
    DASH
  }

  /**
   * 到站与状态。
   *
   * @param mode 形态
   * @param minutes 分钟数；仅 {@link ArrivalMode#COUNTDOWN} 有意义
   * @param minutesTone 分钟数的色调（计划班次为次要色）
   * @param status 状态或提示
   */
  public record Arrival(ArrivalMode mode, int minutes, Tone minutesTone, Optional<Label> status) {

    public Arrival {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(minutesTone, "minutesTone");
      status = status == null ? Optional.empty() : status;
    }
  }

  /**
   * 站台。
   *
   * @param number 站台号；未知或待定为“-”
   * @param hollow 空心（取消、站台待定）
   * @param changed 站台变更过（方块用琥珀色）
   */
  public record PlatformCell(String number, boolean hollow, boolean changed) {

    public PlatformCell {
      Objects.requireNonNull(number, "number");
    }

    /** 没有变更的站台。 */
    public PlatformCell(String number, boolean hollow) {
      this(number, hollow, false);
    }
  }

  /**
   * 到发行。
   *
   * @param badge 线路色牌
   * @param destination 终点
   * @param platform 站台
   * @param arrival 到站与状态
   */
  public record Row(Badge badge, Destination destination, PlatformCell platform, Arrival arrival) {

    public Row {
      Objects.requireNonNull(badge, "badge");
      Objects.requireNonNull(destination, "destination");
      Objects.requireNonNull(platform, "platform");
      Objects.requireNonNull(arrival, "arrival");
    }
  }

  /**
   * 固定文案（中英文）。
   *
   * @param platform “站台 / Platform”
   * @param minutes “分 / min”
   * @param noMoreTrains “暂无后续列车 / No further trains”
   * @param headerLine 表头“线路”
   * @param headerDestination 表头“终点”
   * @param headerPlatform 表头“站台”
   * @param headerArrival 表头“到站”
   */
  public record Labels(
      Names platform,
      Names minutes,
      Names noMoreTrains,
      Names headerLine,
      Names headerDestination,
      Names headerPlatform,
      Names headerArrival) {}
}
