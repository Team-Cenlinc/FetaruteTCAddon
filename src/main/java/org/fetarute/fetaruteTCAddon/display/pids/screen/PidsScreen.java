package org.fetarute.fetaruteTCAddon.display.pids.screen;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;

/**
 * 一块挂在墙上的站台屏。
 *
 * <p>位置记左上角展示框所在的方块；其余展示框按 {@link PidsFacing} 向右、向下排开，见 {@link #frames()}。
 *
 * @param id 屏幕 ID，同时写在地图物品上
 * @param worldId 世界 UID
 * @param anchor 左上角展示框所在方块
 * @param facing 展示框朝向
 * @param tileRows 地图行数
 * @param tileCols 地图列数
 * @param layoutIds 布局 ID，至少一个、不重复。第一个是主布局：不存在（含空白）或尺寸不符时退回同尺寸的内置布局，拆除时按它退回安装纸；
 *     其余是组合翻页时与主布局轮流显示的布局（见 {@code PidsScreenPages}），空白的去掉，不存在、尺寸不符或不能组合的显示时跳过
 * @param station 绑定的车站；未绑定为空
 * @param operator 不绑车站、只绑运营商（线路运行状况屏用）时的运营商代码，规整为大写；绑了车站时为空（运营商取车站的）
 * @param platforms 只显示这些站台；为空表示全部（车站统屏）
 * @param lines 只显示这些线路的代码；为空表示全部。线路运行状况屏轮播几家运营商时，写成 {@code 运营商:线路} 的只管那一家，只写线路代码的只管屏幕所属的运营商
 * @param rotation 线路运行状况按运营商轮播：依次显示这些运营商（大写代码，按加入的先后，同一公司），每家分自己的页；为空时只显示屏幕所属的运营商
 * @param zone 站场图屏绑定的控制区代码（屏幕所属运营商名下，大写）；为空时站场图只画绑定的车站。没有所属运营商时总为空
 * @param appearance 外观
 * @param mode 显示模式
 * @param createdAt 创建时刻
 * @param updatedAt 最后修改时刻
 */
public record PidsScreen(
    UUID id,
    UUID worldId,
    Position anchor,
    PidsFacing facing,
    int tileRows,
    int tileCols,
    List<String> layoutIds,
    Optional<PidsStationKey> station,
    Optional<String> operator,
    Set<String> platforms,
    Set<String> lines,
    List<String> rotation,
    Optional<String> zone,
    Appearance appearance,
    Mode mode,
    Instant createdAt,
    Instant updatedAt) {

  /** 多选切换里表示“全部”的取值。 */
  public static final String ALL = "all";

  /** 控制区单选里表示“不绑定”的取值。 */
  public static final String NONE = "none";

  public PidsScreen {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(facing, "facing");
    if (tileRows < 1 || tileCols < 1) {
      throw new IllegalArgumentException("屏幕尺寸无效: " + tileRows + "×" + tileCols);
    }
    layoutIds = normalizeLayoutIds(layoutIds);
    station = station == null ? Optional.empty() : station;
    operator =
        station.isPresent() || operator == null
            ? Optional.empty()
            : operator
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .map(code -> code.toUpperCase(java.util.Locale.ROOT));
    platforms = sorted(platforms);
    lines = sorted(lines);
    rotation = normalizeCodes(rotation);
    zone =
        zone == null || (station.isEmpty() && operator.isEmpty())
            ? Optional.empty()
            : zone.map(String::trim)
                .filter(code -> !code.isEmpty())
                .map(code -> code.toUpperCase(java.util.Locale.ROOT));
    Objects.requireNonNull(appearance, "appearance");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  /** 不绑控制区的屏幕。 */
  public PidsScreen(
      UUID id,
      UUID worldId,
      Position anchor,
      PidsFacing facing,
      int tileRows,
      int tileCols,
      List<String> layoutIds,
      Optional<PidsStationKey> station,
      Optional<String> operator,
      Set<String> platforms,
      Set<String> lines,
      List<String> rotation,
      Appearance appearance,
      Mode mode,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        layoutIds,
        station,
        operator,
        platforms,
        lines,
        rotation,
        Optional.empty(),
        appearance,
        mode,
        createdAt,
        updatedAt);
  }

  /** 不按运营商轮播的屏幕。 */
  public PidsScreen(
      UUID id,
      UUID worldId,
      Position anchor,
      PidsFacing facing,
      int tileRows,
      int tileCols,
      List<String> layoutIds,
      Optional<PidsStationKey> station,
      Optional<String> operator,
      Set<String> platforms,
      Set<String> lines,
      Appearance appearance,
      Mode mode,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        layoutIds,
        station,
        operator,
        platforms,
        lines,
        List.of(),
        appearance,
        mode,
        createdAt,
        updatedAt);
  }

  /** 绑车站（或未绑定）的屏幕。 */
  public PidsScreen(
      UUID id,
      UUID worldId,
      Position anchor,
      PidsFacing facing,
      int tileRows,
      int tileCols,
      String layoutId,
      Optional<PidsStationKey> station,
      Set<String> platforms,
      Set<String> lines,
      Appearance appearance,
      Mode mode,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        List.of(Objects.requireNonNull(layoutId, "layoutId")),
        station,
        Optional.empty(),
        platforms,
        lines,
        appearance,
        mode,
        createdAt,
        updatedAt);
  }

  /** 只用一个布局（不组合翻页）。 */
  public PidsScreen(
      UUID id,
      UUID worldId,
      Position anchor,
      PidsFacing facing,
      int tileRows,
      int tileCols,
      String layoutId,
      Optional<PidsStationKey> station,
      Optional<String> operator,
      Set<String> platforms,
      Set<String> lines,
      Appearance appearance,
      Mode mode,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        List.of(Objects.requireNonNull(layoutId, "layoutId")),
        station,
        operator,
        platforms,
        lines,
        appearance,
        mode,
        createdAt,
        updatedAt);
  }

  /** 全部布局 ID，主布局在前；不可修改。 */
  @Override
  public List<String> layoutIds() {
    return Collections.unmodifiableList(layoutIds);
  }

  /** 主布局 ID。 */
  public String layoutId() {
    return layoutIds.get(0);
  }

  /** 组合翻页时与主布局轮流显示的布局 ID，按显示顺序；不组合时为空。 */
  public List<String> pageLayoutIds() {
    return layoutIds.subList(1, layoutIds.size());
  }

  /** 屏幕所属的运营商：绑了车站时取车站的，只绑运营商时取它；都没绑时为空。 */
  public Optional<String> operatorCode() {
    return station.map(PidsStationKey::operatorCode).or(() -> operator);
  }

  /** 只显示的站台，按字典序；不可修改。 */
  @Override
  public Set<String> platforms() {
    return Collections.unmodifiableSet(platforms);
  }

  /** 只显示的线路代码，按字典序；不可修改。 */
  @Override
  public Set<String> lines() {
    return Collections.unmodifiableSet(lines);
  }

  /** 按运营商轮播的运营商代码，按加入的先后；不可修改。 */
  @Override
  public List<String> rotation() {
    return Collections.unmodifiableList(rotation);
  }

  /** 方块坐标。 */
  public record Position(int x, int y, int z) {}

  /** 外观：跟随 {@code pids.yml} 的全局设置，或固定深浅。 */
  public enum Appearance {
    AUTO,
    LIGHT,
    DARK
  }

  /** 显示模式。 */
  public enum Mode {
    /** 测试卡：刚装上、尚未确认绑定，显示尺寸、编号与识别出的车站。 */
    TEST_CARD,
    /** 正常显示到发信息。 */
    LIVE
  }

  /** 全部展示框所在的方块，按行优先、从左上角起排列。 */
  public List<Position> frames() {
    List<Position> frames = new ArrayList<>(tileRows * tileCols);
    for (int row = 0; row < tileRows; row++) {
      for (int col = 0; col < tileCols; col++) {
        frames.add(frameAt(row, col));
      }
    }
    return frames;
  }

  /** 屏幕中间那块地图所在的方块（识别附近车站、查找展示框时以它为中心）。 */
  public Position center() {
    return center(anchor, facing, tileRows, tileCols);
  }

  /** 尚未建出屏幕记录时按左上角与尺寸求中心，口径同 {@link #center()}。 */
  public static Position center(Position anchor, PidsFacing facing, int rows, int cols) {
    return facing.offset(anchor, rows / 2, cols / 2);
  }

  /** 第 {@code row} 行、第 {@code col} 列展示框所在的方块。 */
  public Position frameAt(int row, int col) {
    return facing.offset(anchor, row, col);
  }

  /** 改绑车站与过滤；绑了车站就不再单独记运营商。运营商变了时控制区一并解绑。 */
  public PidsScreen withBinding(
      Optional<PidsStationKey> station, Set<String> platforms, Set<String> lines, Instant now) {
    return new PidsScreen(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        layoutIds,
        station,
        operator,
        platforms,
        lines,
        rotation,
        sameOperator(station.map(PidsStationKey::operatorCode).or(() -> operator))
            ? zone
            : Optional.empty(),
        appearance,
        mode,
        createdAt,
        now);
  }

  /** 不绑车站、只绑运营商（线路运行状况屏、控制区站场图屏）：清掉车站与站台，换上新的线路过滤；运营商变了时控制区一并解绑。 */
  public PidsScreen withOperator(String operatorCode, Set<String> lines, Instant now) {
    return new PidsScreen(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        layoutIds,
        Optional.empty(),
        Optional.of(operatorCode),
        Set.of(),
        lines,
        rotation,
        sameOperator(Optional.of(operatorCode)) ? zone : Optional.empty(),
        appearance,
        mode,
        createdAt,
        now);
  }

  /** 换绑控制区（站场图屏用）；空表示不绑，只画绑定的车站。 */
  public PidsScreen withZone(Optional<String> zone, Instant now) {
    return new PidsScreen(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        layoutIds,
        station,
        operator,
        platforms,
        lines,
        rotation,
        zone,
        appearance,
        mode,
        createdAt,
        now);
  }

  /** 改绑后的运营商是否不变：控制区按运营商记，换了运营商就不再绑。 */
  private boolean sameOperator(Optional<String> next) {
    return next.map(code -> code.trim().toUpperCase(java.util.Locale.ROOT))
        .equals(operatorCode().map(code -> code.toUpperCase(java.util.Locale.ROOT)));
  }

  /** 只用一个布局（去掉组合翻页）。 */
  public PidsScreen withLayout(String layoutId, Instant now) {
    return withLayouts(List.of(layoutId), now);
  }

  /** 换布局：第一个为主布局，其余与主布局轮流显示。 */
  public PidsScreen withLayouts(List<String> layoutIds, Instant now) {
    return new PidsScreen(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        layoutIds,
        station,
        operator,
        platforms,
        lines,
        rotation,
        zone,
        appearance,
        mode,
        createdAt,
        now);
  }

  /** 换按运营商轮播的清单（见 {@link #toggleRotation}）；不再轮播的运营商在线路过滤里写的 {@code 运营商:线路} 一并去掉（屏幕所属运营商的保留）。 */
  public PidsScreen withRotation(List<String> rotation, Instant now) {
    List<String> next = normalizeCodes(rotation);
    String own = operatorCode().map(code -> code.toUpperCase(java.util.Locale.ROOT)).orElse("");
    Set<String> kept = new TreeSet<>();
    for (String entry : lines) {
      int colon = entry.indexOf(':');
      String code = colon < 0 ? "" : entry.substring(0, colon).toUpperCase(java.util.Locale.ROOT);
      if (colon < 0 || code.equals(own) || next.contains(code)) {
        kept.add(entry);
      }
    }
    return new PidsScreen(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        layoutIds,
        station,
        operator,
        platforms,
        kept,
        next,
        zone,
        appearance,
        mode,
        createdAt,
        now);
  }

  /** 换外观。 */
  public PidsScreen withAppearance(Appearance appearance, Instant now) {
    return new PidsScreen(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        layoutIds,
        station,
        operator,
        platforms,
        lines,
        rotation,
        zone,
        appearance,
        mode,
        createdAt,
        now);
  }

  /** 换显示模式。 */
  public PidsScreen withMode(Mode mode, Instant now) {
    return new PidsScreen(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        layoutIds,
        station,
        operator,
        platforms,
        lines,
        rotation,
        zone,
        appearance,
        mode,
        createdAt,
        now);
  }

  /**
   * 菜单里的多选切换：{@code all} 清空（表示全部），其余有则去掉、无则加上。
   *
   * @param current 当前选中项
   * @param value 点击的项
   */
  public static Set<String> toggle(Set<String> current, String value) {
    if (ALL.equalsIgnoreCase(value)) {
      return Set.of();
    }
    Set<String> next = new TreeSet<>(current);
    if (!next.remove(value)) {
      next.add(value);
    }
    return next;
  }

  /**
   * 按运营商轮播的增删：清单为空时加入一家，屏幕自己的运营商排在它前面；已在清单里的去掉；去掉后只剩一家时清空（回到只显示屏幕自己的运营商， 与改绑车站、运营商时的 {@code
   * PidsService#keepRotationInCompany} 一致）。
   *
   * @param current 当前清单
   * @param own 屏幕所属的运营商
   * @param operator 点击的运营商
   */
  public static List<String> toggleRotation(List<String> current, String own, String operator) {
    String code = operator.trim().toUpperCase(java.util.Locale.ROOT);
    String self = own.trim().toUpperCase(java.util.Locale.ROOT);
    List<String> next = new ArrayList<>(current);
    if (!next.remove(code)) {
      if (next.isEmpty() && !code.equals(self)) {
        next.add(self);
      }
      next.add(code);
    }
    return next.size() <= 1 ? List.of() : List.copyOf(next);
  }

  /** 运营商代码：去空白、大写、去重，保持顺序。 */
  private static List<String> normalizeCodes(List<String> values) {
    if (values == null) {
      return List.of();
    }
    LinkedHashSet<String> codes = new LinkedHashSet<>();
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        codes.add(value.trim().toUpperCase(java.util.Locale.ROOT));
      }
    }
    return List.copyOf(codes);
  }

  /** 去首尾空白、去重，保持顺序；至少一个。主布局空白也保留（按不存在处理），其余空白的去掉。 */
  private static List<String> normalizeLayoutIds(List<String> values) {
    Objects.requireNonNull(values, "layoutIds");
    if (values.isEmpty()) {
      throw new IllegalArgumentException("屏幕至少要有一个布局");
    }
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    ids.add(Objects.requireNonNull(values.get(0), "layoutId").trim());
    for (String value : values.subList(1, values.size())) {
      Objects.requireNonNull(value, "layoutId");
      if (!value.isBlank()) {
        ids.add(value.trim());
      }
    }
    return List.copyOf(ids);
  }

  /** 私有的有序副本；对外只经访问器给只读视图。 */
  private static Set<String> sorted(Set<String> values) {
    return values == null ? new TreeSet<>() : new TreeSet<>(values);
  }
}
