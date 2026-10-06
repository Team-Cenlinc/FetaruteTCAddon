package org.fetarute.fetaruteTCAddon.display.pids.screen;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
 * @param layoutId 布局 ID；不存在或尺寸不符时退回同尺寸的内置布局
 * @param station 绑定的车站；未绑定为空
 * @param operator 不绑车站、只绑运营商（线路运行状况屏用）时的运营商代码，规整为大写；绑了车站时为空（运营商取车站的）
 * @param platforms 只显示这些站台；为空表示全部（车站统屏）
 * @param lines 只显示这些线路的代码；为空表示全部
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
    String layoutId,
    Optional<PidsStationKey> station,
    Optional<String> operator,
    Set<String> platforms,
    Set<String> lines,
    Appearance appearance,
    Mode mode,
    Instant createdAt,
    Instant updatedAt) {

  /** 多选切换里表示“全部”的取值。 */
  public static final String ALL = "all";

  public PidsScreen {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(facing, "facing");
    if (tileRows < 1 || tileCols < 1) {
      throw new IllegalArgumentException("屏幕尺寸无效: " + tileRows + "×" + tileCols);
    }
    Objects.requireNonNull(layoutId, "layoutId");
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
    Objects.requireNonNull(appearance, "appearance");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
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
        layoutId,
        station,
        Optional.empty(),
        platforms,
        lines,
        appearance,
        mode,
        createdAt,
        updatedAt);
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

  /** 改绑车站与过滤；绑了车站就不再单独记运营商。 */
  public PidsScreen withBinding(
      Optional<PidsStationKey> station, Set<String> platforms, Set<String> lines, Instant now) {
    return new PidsScreen(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        layoutId,
        station,
        operator,
        platforms,
        lines,
        appearance,
        mode,
        createdAt,
        now);
  }

  /** 不绑车站、只绑运营商（线路运行状况屏）：清掉车站与站台，换上新的线路过滤。 */
  public PidsScreen withOperator(String operatorCode, Set<String> lines, Instant now) {
    return new PidsScreen(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        layoutId,
        Optional.empty(),
        Optional.of(operatorCode),
        Set.of(),
        lines,
        appearance,
        mode,
        createdAt,
        now);
  }

  /** 换布局。 */
  public PidsScreen withLayout(String layoutId, Instant now) {
    return new PidsScreen(
        id,
        worldId,
        anchor,
        facing,
        tileRows,
        tileCols,
        layoutId,
        station,
        operator,
        platforms,
        lines,
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
        layoutId,
        station,
        operator,
        platforms,
        lines,
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
        layoutId,
        station,
        operator,
        platforms,
        lines,
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

  /** 私有的有序副本；对外只经访问器给只读视图。 */
  private static Set<String> sorted(Set<String> values) {
    return values == null ? new TreeSet<>() : new TreeSet<>(values);
  }
}
