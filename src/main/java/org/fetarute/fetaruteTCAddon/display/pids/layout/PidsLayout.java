package org.fetarute.fetaruteTCAddon.display.pids.layout;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 站台屏布局：画布尺寸与一组内置组件的摆放。
 *
 * <p>坐标与尺寸均为地图像素，原点在左上角；一块地图 {@value #TILE}×{@value
 * #TILE}。组件种类是固定的（站台号、时钟、线路色带、站名、换乘条、到发表、停站表、线路运行状况）， 布局只决定它们的位置、尺寸、字号与行数；新的视觉形式需要新增组件类型。
 *
 * @param id 布局 ID（文件名去掉扩展名）
 * @param name 显示名
 * @param tileRows 地图行数
 * @param tileCols 地图列数
 * @param boldFrom 主文字从这个字号起仿粗体（向右错 1 像素再画一次）；0 表示不加粗。像素字体只有一种字重，大字靠它拉开层级； 12 的中文笔画太密，加粗会糊
 * @param widgets 组件，按绘制先后排列
 */
public record PidsLayout(
    String id, String name, int tileRows, int tileCols, int boldFrom, List<Widget> widgets) {

  /** 一块地图的边长（像素）。 */
  public static final int TILE = 128;

  /** 默认仿粗体起始字号。 */
  public static final int DEFAULT_BOLD_FROM = 20;

  public PidsLayout {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    widgets = List.copyOf(widgets);
  }

  /** 画布宽度（像素）。 */
  public int width() {
    return tileCols * TILE;
  }

  /** 画布高度（像素）。 */
  public int height() {
    return tileRows * TILE;
  }

  /** 到发表组件；布局没有到发表时为空。 */
  public Optional<Departures> departures() {
    return widgets.stream()
        .filter(Departures.class::isInstance)
        .map(Departures.class::cast)
        .findFirst();
  }

  /** 到发表最多显示的行数。 */
  public int rowCapacity() {
    return departures().map(Departures::rowCapacity).orElse(0);
  }

  /** 停站表组件；不是停站屏时为空。 */
  public Optional<StopList> stopList() {
    return widgets.stream()
        .filter(StopList.class::isInstance)
        .map(StopList.class::cast)
        .findFirst();
  }

  /** 线路运行状况组件；不是线路运行状况屏时为空。 */
  public Optional<LineStatus> lineStatus() {
    return widgets.stream()
        .filter(LineStatus.class::isInstance)
        .map(LineStatus.class::cast)
        .findFirst();
  }

  /** 组件。 */
  public sealed interface Widget
      permits Platform,
          Clock,
          LineBand,
          StationTitle,
          LineStrip,
          Departures,
          StopList,
          LineStatus {}

  /**
   * 一组中英文字的字号。
   *
   * @param size 中文（主）字号
   * @param secondarySize 英文（次）字号；0 表示不显示英文
   * @param gap 中英文间距：上下叠放时为竖向间距，同行时为横向间距
   */
  public record TextStyle(int size, int secondarySize, int gap) {

    /** 是否显示英文。 */
    public boolean hasSecondary() {
      return secondarySize > 0;
    }
  }

  /**
   * 站台号：每个站台一个反白方块，从左到右、每行 {@code perRow} 个排开，下方叠放“站台 / Platform”。
   *
   * <p>{@code max} 为 1 的是单站台屏，只能绑定一个站台；大于 1 的是多站台屏，可以绑定至多这么多个站台（不能选全部）。
   *
   * @param x 左
   * @param y 上
   * @param box 方块边长
   * @param numberSize 站台号字号
   * @param labelGap 方块与标签的竖向间距
   * @param label 标签字号
   * @param max 最多显示几个站台
   * @param perRow 每行几个方块
   * @param gap 方块之间的间距
   */
  public record Platform(
      int x,
      int y,
      int box,
      int numberSize,
      int labelGap,
      TextStyle label,
      int max,
      int perRow,
      int gap)
      implements Widget {

    /** 方块排成几行（按最多站台数）。 */
    public int rows() {
      return (max + perRow - 1) / perRow;
    }
  }

  /**
   * 当前时间（服务器时区，{@code HH:mm}）。
   *
   * @param x 左对齐时为左缘，右对齐时为右缘
   * @param y 上
   * @param size 字号
   * @param align 对齐方式
   */
  public record Clock(int x, int y, int size, Align align) implements Widget {}

  /**
   * 线路色带：本站台线路的颜色。
   *
   * @param x 左
   * @param y 上
   * @param width 宽
   * @param height 高
   */
  public record LineBand(int x, int y, int width, int height) implements Widget {}

  /**
   * 站名：中文主名与英文副名同行、基线对齐。
   *
   * <p>按 {@code sizes} 依次尝试，取第一档放得下的字号；最小一档仍放不下时去掉英文，中文省略。
   *
   * @param x 左
   * @param y 上（按最大一档字号的文本框计）
   * @param width 可用宽度
   * @param sizes 中文字号，从大到小
   * @param secondarySize 英文字号
   * @param gap 中英文间距
   */
  public record StationTitle(
      int x, int y, int width, List<Integer> sizes, int secondarySize, int gap) implements Widget {

    public StationTitle {
      sizes = List.copyOf(sizes);
    }
  }

  /**
   * 换乘条：按停靠线路等分的色带，每段下方一枚线路编号块与线路名。
   *
   * @param x 左
   * @param y 上（色带顶边）
   * @param width 宽
   * @param bandHeight 色带高
   * @param inset 编号块距本段左缘的距离
   * @param tickWidth 色带与编号块之间的短竖线宽
   * @param tickHeight 短竖线高
   * @param tickOffset 短竖线距编号块左缘的距离
   * @param chipHeight 编号块高
   * @param chipPadding 编号块左右内边距
   * @param chipSize 编号字号
   * @param name 线路名字号（同行）
   * @param nameGap 编号块与线路名的间距
   */
  public record LineStrip(
      int x,
      int y,
      int width,
      int bandHeight,
      int inset,
      int tickWidth,
      int tickHeight,
      int tickOffset,
      int chipHeight,
      int chipPadding,
      int chipSize,
      TextStyle name,
      int nameGap)
      implements Widget {}

  /**
   * 到发表。
   *
   * @param x 左
   * @param y 上（有表头时为表头顶边）
   * @param width 宽
   * @param columns 各列位置（相对本组件左缘）
   * @param header 表头；为空表示不画表头
   * @param rows 行样式，按顺序取用，每种重复 {@link RowStyle#count()} 次
   */
  public record Departures(
      int x, int y, int width, Columns columns, Optional<Header> header, List<RowStyle> rows)
      implements Widget {

    public Departures {
      Objects.requireNonNull(columns, "columns");
      header = header == null ? Optional.empty() : header;
      rows = List.copyOf(rows);
    }

    /** 行样式（不可变；对不可变列表 {@code List.copyOf} 原样返回，不复制）。 */
    @Override
    public List<RowStyle> rows() {
      return List.copyOf(rows);
    }

    /** 最多显示的行数。 */
    public int rowCapacity() {
      return rows.stream().mapToInt(RowStyle::count).sum();
    }

    /** 第 {@code index} 行（0 起）的样式。 */
    public RowStyle styleOf(int index) {
      int remaining = index;
      for (RowStyle style : rows) {
        if (remaining < style.count()) {
          return style;
        }
        remaining -= style.count();
      }
      throw new IndexOutOfBoundsException("行号超出布局行数: " + index);
    }

    /** 首行顶边（表头之下）。 */
    public int firstRowY() {
      return y + header.map(h -> h.height() + h.gap()).orElse(0);
    }

    /** 全部行占用的总高度。 */
    public int rowsHeight() {
      int total = 0;
      for (RowStyle style : rows) {
        total += style.count() * (style.height() + style.gap());
      }
      return total;
    }
  }

  /**
   * 停站屏（2×1）的主体：下一班的色牌与多久到达、终点、直通或经由一行、停站表与页码。
   *
   * <p>自上而下：首行（色牌左缘在 {@code badgeX}，让出左边单独摆放的站台号组件；多久到达靠右）、终点、终点下面一行
   * （没有直通与经由时不占位）、停站表、页码（最底下一行，靠右）。停站表与上下相邻部分各隔 {@code listGap}，每站一行 {@code rowHeight}，放不下时分页。
   *
   * @param x 左
   * @param y 上
   * @param width 宽
   * @param height 高（到页码一行的底边）
   * @param inset 左右留白
   * @param headerHeight 首行高
   * @param badgeX 色牌左缘（相对本组件左缘）
   * @param badge 色牌
   * @param minutesSize 分钟数字号
   * @param unit “分 / min”（上下叠放）
   * @param destinationHeight 终点一块的高
   * @param destination 终点中英文（上下叠放）
   * @param noteGap 终点与下面一行的间距
   * @param noteHeight 终点下面一行的高（方形标签边长）
   * @param listGap 停站表与上下相邻部分的间距
   * @param rowHeight 停站表每站一行的高
   * @param stop 站名中英文（上下叠放）
   * @param footerHeight 页码一行的高
   * @param footerSize 页码字号
   */
  public record StopList(
      int x,
      int y,
      int width,
      int height,
      int inset,
      int headerHeight,
      int badgeX,
      BadgeStyle badge,
      int minutesSize,
      TextStyle unit,
      int destinationHeight,
      TextStyle destination,
      int noteGap,
      int noteHeight,
      int listGap,
      int rowHeight,
      TextStyle stop,
      int footerHeight,
      int footerSize)
      implements Widget {

    /** 终点一块的顶边。 */
    public int destinationTop() {
      return y + headerHeight;
    }

    /** 终点下面一行的顶边。 */
    public int noteTop() {
      return destinationTop() + destinationHeight + noteGap;
    }

    /** 停站表顶边。 */
    public int listTop(boolean note) {
      return destinationTop() + destinationHeight + (note ? noteGap + noteHeight : 0) + listGap;
    }

    /** 停站表底边。 */
    public int listBottom() {
      return y + height - footerHeight - listGap;
    }

    /** 每页几站。 */
    public int rowsPerPage(boolean note) {
      return Math.max(1, (listBottom() - listTop(note)) / rowHeight);
    }

    /** 这么多站要分几页。 */
    public int pages(int stops, boolean note) {
      int rows = rowsPerPage(note);
      return Math.max(1, (stops + rows - 1) / rows);
    }

    /** 后续列车页：首行下面的间距，也是每班之间的间距（分隔线画在其中）。 */
    public static final int FOLLOWING_GAP = 4;

    /** 后续列车页：色牌一行与终点之间的间距。 */
    public static final int FOLLOWING_NAME_GAP = 3;

    /** 后续列车页第一班的顶边：首行写页标题，其下隔 {@value #FOLLOWING_GAP}。 */
    public int followingTop() {
      return y + headerHeight + FOLLOWING_GAP;
    }

    /** 后续列车页每班的高：色牌一行（色牌高）、终点中文与英文两行（与站名同一字号）。 */
    public int followingRowHeight() {
      return badge.height() + FOLLOWING_NAME_GAP + stop.size() + stop.gap() + stop.secondarySize();
    }

    /** 后续列车页一页放得下几班（不留页码一行）。 */
    public int followingRows() {
      return Math.max(
          0,
          (y + height - followingTop() + FOLLOWING_GAP) / (followingRowHeight() + FOLLOWING_GAP));
    }
  }

  /**
   * 线路运行状况屏的主体：页标题、运营商名与页码、表头，以及每条线路一行（色牌、线路名、状况、说明）。
   *
   * <p>行有大小两种样式：大行一页放得下全部线路时用大行，否则用小行并翻页（{@link #rowsPerPage}）。各列位置相对本组件左缘； 色牌在行内竖向居中、左缘与页标题同在
   * {@code inset}，状况色块在行内竖向居中。
   *
   * @param x 左
   * @param y 上（页标题文本框顶边）
   * @param width 宽
   * @param height 高（到最后一行的底边）
   * @param inset 页标题、运营商名、表头首列与色牌的左缩进，页码的右缩进
   * @param title 页标题（中英文同行）
   * @param operatorY 运营商名一行的顶边（相对 {@code y}）
   * @param operator 运营商名（中英文同行）与页码（中文字号）
   * @param headerY 表头的顶边（相对 {@code y}）
   * @param header 表头（每列中英文同行）
   * @param rowsY 第一行的顶边（相对 {@code y}）
   * @param nameX 线路名列的左缘
   * @param statusX 状况列（色块）的左缘
   * @param detailX 说明列的左缘
   * @param roomy 大行
   * @param compact 小行
   */
  public record LineStatus(
      int x,
      int y,
      int width,
      int height,
      int inset,
      TextStyle title,
      int operatorY,
      TextStyle operator,
      int headerY,
      TextStyle header,
      int rowsY,
      int nameX,
      int statusX,
      int detailX,
      StatusRow roomy,
      StatusRow compact)
      implements Widget {

    public LineStatus {
      Objects.requireNonNull(title, "title");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(header, "header");
      Objects.requireNonNull(roomy, "roomy");
      Objects.requireNonNull(compact, "compact");
    }

    /** 第一行的顶边。 */
    public int rowsTop() {
      return y + rowsY;
    }

    /** 这种行一页放几条（至少 1）。 */
    public int rowsPerPage(StatusRow row) {
      return Math.max(1, (y + height - rowsTop() + row.gap()) / (row.height() + row.gap()));
    }
  }

  /**
   * 线路运行状况屏的一种行样式。
   *
   * @param height 行高（面板色铺底）
   * @param gap 行间距
   * @param badgeWidth 色牌宽
   * @param badgeHeight 色牌高
   * @param badgeSize 色牌上线路代码的字号（放不下时改用 12 号，再放不下省略）
   * @param name 线路名（上下叠放）
   * @param nameFallback 中文线路名放不下时改用的字号；0 表示直接省略
   * @param statusWidth 状况色块宽
   * @param statusHeight 状况色块高
   * @param statusInset 状况文字距色块左缘的距离（没有色块时同样缩进，与表头对齐）
   * @param status 状况（上下叠放）
   * @param detail 说明（上下叠放）
   */
  public record StatusRow(
      int height,
      int gap,
      int badgeWidth,
      int badgeHeight,
      int badgeSize,
      TextStyle name,
      int nameFallback,
      int statusWidth,
      int statusHeight,
      int statusInset,
      TextStyle status,
      TextStyle detail) {

    public StatusRow {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(detail, "detail");
    }
  }

  /**
   * 列（相对到发表左缘）。
   *
   * @param x 左
   * @param width 宽
   */
  public record Column(int x, int width) {}

  /**
   * 到发表的列。
   *
   * @param badge 线路色牌
   * @param destination 终点
   * @param platform 站台；为空表示不显示（站台屏只显示本站台）
   * @param arrival 到站与状态
   */
  public record Columns(
      Column badge, Column destination, Optional<Column> platform, Column arrival) {

    public Columns {
      Objects.requireNonNull(badge, "badge");
      Objects.requireNonNull(destination, "destination");
      platform = platform == null ? Optional.empty() : platform;
      Objects.requireNonNull(arrival, "arrival");
    }
  }

  /**
   * 表头：每列一组“中文 英文”标签，同行基线对齐。
   *
   * @param height 高
   * @param gap 表头与首行的间距
   * @param inset 标签距列左缘的距离
   * @param label 标签字号（同行）
   */
  public record Header(int height, int gap, int inset, TextStyle label) {}

  /**
   * 一种行样式。
   *
   * @param height 行高（含顶部分隔线）
   * @param gap 本行之后的间距
   * @param divider 顶部分隔线粗细（面板色）；0 表示无
   * @param panel 是否以面板色铺底
   * @param count 连续使用的行数
   * @param badge 色牌
   * @param destination 终点
   * @param platform 站台；列不存在时忽略
   * @param arrival 到站与状态
   */
  public record RowStyle(
      int height,
      int gap,
      int divider,
      boolean panel,
      int count,
      BadgeStyle badge,
      DestinationStyle destination,
      PlatformStyle platform,
      ArrivalStyle arrival) {}

  /**
   * 线路色牌：左侧线路代码，贯穿上下边的分隔线，右侧类型两字上下叠放。
   *
   * @param width 宽
   * @param height 高
   * @param codeWidth 代码区宽
   * @param codeSize 代码字号
   * @param typeSize 类型字号
   * @param typeGap 类型两字的竖向间距
   * @param inset 距列左缘的距离；负数表示在列内水平居中
   */
  public record BadgeStyle(
      int width, int height, int codeWidth, int codeSize, int typeSize, int typeGap, int inset) {}

  /**
   * 终点。
   *
   * @param text 中英文字号；{@code inline} 为真时同行基线对齐，否则上下叠放
   * @param inline 中英文同行
   * @param wrap 中文放不下时允许按“·”或从中间断成两行（此时不显示英文）
   * @param fallbackSize 两行仍放不下时改用的中文字号；0 表示直接省略
   * @param inset 距列左缘的距离
   */
  public record DestinationStyle(
      TextStyle text, boolean inline, boolean wrap, int fallbackSize, int inset) {}

  /**
   * 站台：反白方块写站台号，右侧“站台”。
   *
   * @param box 方块边长
   * @param size 站台号字号
   * @param labelSize 标签字号
   * @param gap 方块与标签的间距
   * @param inset 距列左缘的距离
   */
  public record PlatformStyle(int box, int size, int labelSize, int gap, int inset) {}

  /** 数字在定宽盒内的对齐方式。 */
  public enum Align {
    LEFT,
    RIGHT
  }

  /**
   * 到站与状态。
   *
   * @param inset 距列左缘的距离
   * @param insetRight 距列右缘的距离（靠右的状态与英文提示以它为界）
   * @param numberSize 分钟数字号
   * @param numberBox 分钟数字的定宽盒；0 表示按内容宽
   * @param numberAlign 数字在定宽盒内的对齐
   * @param numberGap 数字盒与单位的间距
   * @param unit 单位（分 / min）；{@code unitStacked} 为真时上下叠放并竖向居中，否则只显示中文并与数字基线对齐
   * @param unitStacked 单位上下叠放
   * @param status 状态（准点、晚点等）；{@code statusStacked} 为真时上下叠放、靠右，否则只显示中文、与数字基线对齐
   * @param statusStacked 状态上下叠放
   * @param statusOffset 同行状态的起点（相对 {@code inset} 内缘）；数字与单位更宽时紧跟其后
   * @param highlight 进站、通过、停靠中与取消等整格提示
   * @param highlightSpread 提示的英文靠右；否则紧跟中文、基线对齐
   * @param dash 取消、回库时在数字位置画“—”；否则状态按整格提示的位置画
   */
  public record ArrivalStyle(
      int inset,
      int insetRight,
      int numberSize,
      int numberBox,
      Align numberAlign,
      int numberGap,
      TextStyle unit,
      boolean unitStacked,
      TextStyle status,
      boolean statusStacked,
      int statusOffset,
      TextStyle highlight,
      boolean highlightSpread,
      boolean dash) {}
}
