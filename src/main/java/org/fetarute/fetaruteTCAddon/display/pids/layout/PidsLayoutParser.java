package org.fetarute.fetaruteTCAddon.display.pids.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.Align;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.ArrivalStyle;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.BadgeStyle;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.Column;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.Columns;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.Departures;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.DestinationStyle;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.Header;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.PlatformStyle;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.RowStyle;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.TextStyle;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout.Widget;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsFonts;

/**
 * 解析布局文件（YAML）。
 *
 * <p>不抛异常：返回布局或全部问题（带键路径）。有任何问题的布局整份作废，不做部分回退——摆放错一处，整块屏就是乱的。 坐标与尺寸是地图像素，几何键必须写明；样式键可省略，取注释中的默认值。
 */
public final class PidsLayoutParser {

  /** 当前布局文件格式。 */
  public static final int FORMAT = 1;

  /** 单块屏幕最多的地图行数或列数。 */
  static final int MAX_TILES = 8;

  private PidsLayoutParser() {}

  /**
   * 解析结果。
   *
   * @param layout 布局；有问题时为空
   * @param problems 问题列表；为空表示成功
   */
  public record Result(Optional<PidsLayout> layout, List<String> problems) {

    public Result {
      layout = layout == null ? Optional.empty() : layout;
      problems = List.copyOf(problems);
    }
  }

  /**
   * 解析一份布局。
   *
   * @param id 布局 ID（文件名去掉扩展名）
   * @param root 文件根节点
   */
  public static Result parse(String id, ConfigurationSection root) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(root, "root");
    List<String> problems = new ArrayList<>();
    Node node = new Node("", toMap(root), problems);
    int format = node.optInt("format", FORMAT);
    if (format != FORMAT) {
      problems.add("format 只支持 " + FORMAT + "，实际为 " + format);
      return new Result(Optional.empty(), problems);
    }
    Node tiles = node.requireChild("tiles");
    int rows = tiles.requireInt("rows");
    int cols = tiles.requireInt("cols");
    if (rows < 1 || rows > MAX_TILES || cols < 1 || cols > MAX_TILES) {
      problems.add("tiles 的行列数必须在 1 到 " + MAX_TILES + " 之间");
    }
    List<Widget> widgets = new ArrayList<>();
    List<Node> widgetNodes = node.children("widgets");
    if (widgetNodes.isEmpty()) {
      problems.add("widgets 至少需要一个组件");
    }
    for (Node widget : widgetNodes) {
      parseWidget(widget).ifPresent(widgets::add);
    }
    if (!problems.isEmpty()) {
      return new Result(Optional.empty(), problems);
    }
    int boldFrom = node.optInt("bold-from", PidsLayout.DEFAULT_BOLD_FROM);
    if (boldFrom < 0) {
      problems.add("bold-from 不能为负数");
    }
    PidsLayout layout =
        new PidsLayout(id, node.optString("name", id), rows, cols, boldFrom, widgets);
    new Validator(layout, problems).run();
    return problems.isEmpty()
        ? new Result(Optional.of(layout), problems)
        : new Result(Optional.empty(), problems);
  }

  private static Optional<Widget> parseWidget(Node node) {
    String type = node.requireString("type");
    return switch (type) {
      case "platform" -> Optional.of(
          new PidsLayout.Platform(
              node.requireInt("x"),
              node.requireInt("y"),
              node.requireInt("box"),
              node.optInt("number", 20),
              node.optInt("label-gap", 3),
              textStyle(node.child("label"), 12, 10, 1),
              node.optInt("max", 1),
              node.optInt("per-row", 2),
              node.optInt("gap", 4)));
      case "clock" -> Optional.of(
          new PidsLayout.Clock(
              node.requireInt("x"),
              node.requireInt("y"),
              node.optInt("size", 20),
              node.optAlign("align", Align.LEFT)));
      case "line-band" -> Optional.of(
          new PidsLayout.LineBand(
              node.requireInt("x"),
              node.requireInt("y"),
              node.requireInt("width"),
              node.requireInt("height")));
      case "station-title" -> Optional.of(
          new PidsLayout.StationTitle(
              node.requireInt("x"),
              node.requireInt("y"),
              node.requireInt("width"),
              node.intList("sizes", List.of(36, 24, 20)),
              node.optInt("secondary", 20),
              node.optInt("gap", 12)));
      case "line-strip" -> Optional.of(lineStrip(node));
      case "departures" -> Optional.of(departures(node));
      case "" -> Optional.empty();
      default -> {
        node.problem("type", "未知组件类型 " + type);
        yield Optional.empty();
      }
    };
  }

  private static PidsLayout.LineStrip lineStrip(Node node) {
    Node tick = node.child("tick");
    Node chip = node.child("chip");
    return new PidsLayout.LineStrip(
        node.requireInt("x"),
        node.requireInt("y"),
        node.requireInt("width"),
        node.optInt("band", 8),
        node.optInt("inset", 12),
        tick.optInt("width", 2),
        tick.optInt("height", 4),
        tick.optInt("offset", 6),
        chip.optInt("height", 16),
        chip.optInt("padding", 6),
        chip.optInt("size", 12),
        textStyle(node.child("name"), 12, 10, 6),
        node.optInt("name-gap", 6));
  }

  private static Departures departures(Node node) {
    Node columns = node.requireChild("columns");
    Optional<Header> header =
        node.has("header") ? Optional.of(header(node.child("header"))) : Optional.empty();
    List<RowStyle> rows = new ArrayList<>();
    List<Node> rowNodes = node.children("rows");
    if (rowNodes.isEmpty()) {
      node.problem("rows", "至少需要一种行样式");
    }
    for (Node row : rowNodes) {
      rows.add(rowStyle(row));
    }
    return new Departures(
        node.requireInt("x"),
        node.requireInt("y"),
        node.requireInt("width"),
        new Columns(
            column(columns.requireChild("badge")),
            column(columns.requireChild("destination")),
            columns.has("platform")
                ? Optional.of(column(columns.child("platform")))
                : Optional.empty(),
            column(columns.requireChild("arrival"))),
        header,
        rows);
  }

  private static Column column(Node node) {
    return new Column(node.requireInt("x"), node.requireInt("width"));
  }

  private static Header header(Node node) {
    return new Header(
        node.requireInt("height"),
        node.optInt("gap", 2),
        node.optInt("inset", 8),
        textStyle(node.child("label"), 12, 10, 4));
  }

  private static RowStyle rowStyle(Node node) {
    Node badge = node.requireChild("badge");
    Node destination = node.child("destination");
    Node platform = node.child("platform");
    Node arrival = node.child("arrival");
    Node unit = arrival.child("unit");
    Node status = arrival.child("status");
    Node highlight = arrival.child("highlight");
    return new RowStyle(
        node.requireInt("height"),
        node.optInt("gap", 0),
        node.optInt("divider", 0),
        node.optBool("panel", false),
        node.optInt("count", 1),
        new BadgeStyle(
            badge.requireInt("width"),
            badge.requireInt("height"),
            badge.optInt("code-width", badge.optInt("width", 0) / 2),
            badge.optInt("code", 12),
            badge.optInt("type", 10),
            badge.optInt("type-gap", 1),
            badge.optInt("inset", -1)),
        new DestinationStyle(
            textStyle(destination, 12, 10, 1),
            destination.optBool("inline", false),
            destination.optBool("wrap", false),
            destination.optInt("fallback", 0),
            destination.optInt("inset", 6)),
        new PlatformStyle(
            platform.optInt("box", 24),
            platform.optInt("size", 20),
            platform.optInt("label", 12),
            platform.optInt("gap", 4),
            platform.optInt("inset", 6)),
        new ArrivalStyle(
            arrival.optInt("inset", 6),
            arrival.optInt("inset-right", arrival.optInt("inset", 6)),
            arrival.optInt("number", 20),
            arrival.optInt("number-box", 0),
            arrival.optAlign("number-align", Align.RIGHT),
            arrival.optInt("number-gap", 2),
            textStyle(unit, 12, 0, 1),
            unit.optBool("stacked", false),
            textStyle(status, 12, 10, 1),
            status.optBool("stacked", true),
            status.optInt("offset", 0),
            textStyle(highlight, 12, 10, 4),
            highlight.optBool("spread", true),
            arrival.optBool("dash", true)));
  }

  private static TextStyle textStyle(Node node, int size, int secondary, int gap) {
    return new TextStyle(
        node.optInt("size", size), node.optInt("secondary", secondary), node.optInt("gap", gap));
  }

  /** 把 Bukkit 配置节点转成普通映射，列表项里的映射与顶层用同一套读取方式。 */
  private static Map<String, Object> toMap(ConfigurationSection section) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (String key : section.getKeys(false)) {
      map.put(key, normalize(section.get(key)));
    }
    return map;
  }

  private static Object normalize(Object value) {
    if (value instanceof ConfigurationSection section) {
      return toMap(section);
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach((key, child) -> out.put(String.valueOf(key), normalize(child)));
      return out;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(PidsLayoutParser::normalize).toList();
    }
    return value;
  }

  /** 带键路径与问题收集的映射读取。 */
  private static final class Node {
    private final String path;
    private final Map<?, ?> map;
    private final List<String> problems;

    private Node(String path, Map<?, ?> map, List<String> problems) {
      this.path = path;
      this.map = map;
      this.problems = problems;
    }

    boolean has(String key) {
      return map.containsKey(key);
    }

    void problem(String key, String message) {
      problems.add(pathOf(key) + ": " + message);
    }

    int requireInt(String key) {
      if (!map.containsKey(key)) {
        problem(key, "缺少必填项");
        return 0;
      }
      return optInt(key, 0);
    }

    int optInt(String key, int fallback) {
      Object value = map.get(key);
      if (value == null) {
        return fallback;
      }
      Optional<Integer> parsed = asInt(value);
      if (parsed.isPresent()) {
        return parsed.get();
      }
      problem(key, "应为整数，实际为 " + value);
      return fallback;
    }

    /** YAML 整数解析为 Integer 或 Long；小数与超出 int 范围的值不算整数。 */
    private static Optional<Integer> asInt(Object value) {
      if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
        return Optional.of(((Number) value).intValue());
      }
      if (value instanceof Long number
          && number >= Integer.MIN_VALUE
          && number <= Integer.MAX_VALUE) {
        return Optional.of(number.intValue());
      }
      return Optional.empty();
    }

    boolean optBool(String key, boolean fallback) {
      Object value = map.get(key);
      if (value == null) {
        return fallback;
      }
      if (value instanceof Boolean bool) {
        return bool;
      }
      problem(key, "应为 true 或 false，实际为 " + value);
      return fallback;
    }

    String requireString(String key) {
      Object value = map.get(key);
      if (value == null || String.valueOf(value).isBlank()) {
        problem(key, "缺少必填项");
        return "";
      }
      return String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    String optString(String key, String fallback) {
      Object value = map.get(key);
      return value == null || String.valueOf(value).isBlank()
          ? fallback
          : String.valueOf(value).trim();
    }

    Align optAlign(String key, Align fallback) {
      Object value = map.get(key);
      if (value == null) {
        return fallback;
      }
      try {
        return Align.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        problem(key, "应为 left 或 right，实际为 " + value);
        return fallback;
      }
    }

    List<Integer> intList(String key, List<Integer> fallback) {
      Object value = map.get(key);
      if (value == null) {
        return fallback;
      }
      if (value instanceof List<?> list && !list.isEmpty()) {
        List<Integer> out = new ArrayList<>();
        for (Object item : list) {
          Optional<Integer> parsed = asInt(item);
          if (parsed.isEmpty()) {
            problem(key, "应为整数列表，含有 " + item);
            return fallback;
          }
          out.add(parsed.get());
        }
        return out;
      }
      problem(key, "应为非空整数列表");
      return fallback;
    }

    /** 子节点；缺失时为空节点（取默认值）。 */
    Node child(String key) {
      Object value = map.get(key);
      if (value == null) {
        return new Node(pathOf(key), Map.of(), problems);
      }
      if (value instanceof Map<?, ?> child) {
        return new Node(pathOf(key), child, problems);
      }
      problem(key, "应为映射");
      return new Node(pathOf(key), Map.of(), problems);
    }

    Node requireChild(String key) {
      if (!map.containsKey(key)) {
        problem(key, "缺少必填项");
      }
      return child(key);
    }

    List<Node> children(String key) {
      Object value = map.get(key);
      if (value == null) {
        return List.of();
      }
      if (!(value instanceof List<?> list)) {
        problem(key, "应为列表");
        return List.of();
      }
      List<Node> out = new ArrayList<>();
      for (int i = 0; i < list.size(); i++) {
        String itemPath = pathOf(key) + "[" + i + "]";
        if (list.get(i) instanceof Map<?, ?> item) {
          out.add(new Node(itemPath, item, problems));
        } else {
          problems.add(itemPath + ": 应为映射");
        }
      }
      return out;
    }

    private String pathOf(String key) {
      return path.isEmpty() ? key : path + "." + key;
    }
  }

  /** 解析后的几何与字号校验。 */
  private static final class Validator {
    private final PidsLayout layout;
    private final List<String> problems;

    private Validator(PidsLayout layout, List<String> problems) {
      this.layout = layout;
      this.problems = problems;
    }

    void run() {
      for (int i = 0; i < layout.widgets().size(); i++) {
        check("widgets[" + i + "]", layout.widgets().get(i));
      }
    }

    private void check(String path, Widget widget) {
      if (widget instanceof PidsLayout.Platform p) {
        if (p.max() < 1 || p.perRow() < 1) {
          problems.add(path + ": max 与 per-row 至少为 1");
          return;
        }
        int columns = Math.min(p.max(), p.perRow());
        int width = columns * p.box() + (columns - 1) * p.gap();
        int height =
            p.rows() * p.box()
                + (p.rows() - 1) * p.gap()
                + p.labelGap()
                + p.label().size()
                + p.label().gap()
                + p.label().secondarySize();
        within(path, p.x(), p.y(), width, height);
        sizes(path, p.numberSize(), p.label().size(), p.label().secondarySize());
      } else if (widget instanceof PidsLayout.Clock c) {
        within(path, c.align() == Align.RIGHT ? c.x() - 1 : c.x(), c.y(), 1, c.size());
        sizes(path, c.size());
      } else if (widget instanceof PidsLayout.LineBand b) {
        within(path, b.x(), b.y(), b.width(), b.height());
      } else if (widget instanceof PidsLayout.StationTitle t) {
        within(path, t.x(), t.y(), t.width(), t.sizes().get(0));
        t.sizes().forEach(size -> sizes(path, size));
        sizes(path, t.secondarySize());
      } else if (widget instanceof PidsLayout.LineStrip s) {
        within(path, s.x(), s.y(), s.width(), s.bandHeight() + s.tickHeight() + s.chipHeight());
        sizes(path, s.chipSize(), s.name().size(), s.name().secondarySize());
      } else if (widget instanceof Departures d) {
        checkDepartures(path, d);
      }
    }

    private void checkDepartures(String path, Departures d) {
      int bottomGap = d.rows().isEmpty() ? 0 : d.rows().get(d.rows().size() - 1).gap();
      within(path, d.x(), d.y(), d.width(), d.firstRowY() - d.y() + d.rowsHeight() - bottomGap);
      Columns columns = d.columns();
      column(path + ".columns.badge", d, columns.badge());
      column(path + ".columns.destination", d, columns.destination());
      columns.platform().ifPresent(c -> column(path + ".columns.platform", d, c));
      column(path + ".columns.arrival", d, columns.arrival());
      d.header()
          .ifPresent(h -> sizes(path + ".header", h.label().size(), h.label().secondarySize()));
      for (int i = 0; i < d.rows().size(); i++) {
        RowStyle row = d.rows().get(i);
        String rowPath = path + ".rows[" + i + "]";
        if (row.count() < 1) {
          problems.add(rowPath + ".count: 至少为 1");
        }
        if (row.badge().width() > columns.badge().width()
            || row.badge().height() > row.height() - row.divider()) {
          problems.add(rowPath + ".badge: 色牌大于所在格");
        }
        sizes(
            rowPath,
            row.badge().codeSize(),
            row.badge().typeSize(),
            row.destination().text().size(),
            row.destination().text().secondarySize(),
            row.destination().fallbackSize(),
            row.platform().size(),
            row.platform().labelSize(),
            row.arrival().numberSize(),
            row.arrival().unit().size(),
            row.arrival().unit().secondarySize(),
            row.arrival().status().size(),
            row.arrival().status().secondarySize(),
            row.arrival().highlight().size(),
            row.arrival().highlight().secondarySize());
      }
    }

    private void column(String path, Departures d, Column column) {
      if (column.x() < 0 || column.width() <= 0 || column.x() + column.width() > d.width()) {
        problems.add(path + ": 超出到发表宽度");
      }
    }

    private void within(String path, int x, int y, int width, int height) {
      if (x < 0 || y < 0 || x + width > layout.width() || y + height > layout.height()) {
        problems.add(
            path
                + ": 超出画布 "
                + layout.width()
                + "×"
                + layout.height()
                + "（x="
                + x
                + " y="
                + y
                + " 宽="
                + width
                + " 高="
                + height
                + "）");
      }
    }

    /** 0 表示不显示，跳过；其余必须是像素字体支持的字号。 */
    private void sizes(String path, int... sizes) {
      for (int size : sizes) {
        if (size != 0 && !PidsFonts.supports(size)) {
          problems.add(path + ": 字号 " + size + " 不是 10 或 12 的整数倍");
        }
      }
    }
  }
}
