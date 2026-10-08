package org.fetarute.fetaruteTCAddon.display.pids;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayoutRegistry;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;

/**
 * 组合翻页：一块屏幕除主布局外还可以选几个布局，按时钟轮流显示（如车站统屏的到发与线路运行状况）。
 *
 * <ul>
 *   <li>能组合的只有不按站台轮播的布局（没有站台号组件：车站统屏、线路运行状况屏）；站台屏、多站台屏与停站屏有自己的轮播，只能单独用。
 *   <li>翻页布局须与屏幕同尺寸。显示时跳过不存在、尺寸不符或不能组合的（布局文件改过、删过）；主布局不能组合时只显示主布局。
 *   <li>到发要绑车站：没绑车站（只绑运营商或都没绑）的屏幕不能加入到发页，已存着的显示时跳过。
 *   <li>每一轮各布局依次显示一次，停留时间按布局种类取（{@link PidsSettings.PageSettings}）。线路运行状况分几页时每轮只放其中一页、
 *       各页按轮轮换，线路再多一轮的长度也不变，到发始终占同样的比例。
 *   <li>按时钟取当前布局：同一车站的屏幕同时翻页，不同车站按站名错开，免得全服同一秒整屏重发。
 * </ul>
 */
public final class PidsScreenPages {

  private PidsScreenPages() {}

  /** 布局能否与别的布局组合翻页：没有自己的轮播（{@link PidsPlatformSelection#hasCarousel}）。 */
  public static boolean combinable(PidsLayout layout) {
    return !PidsPlatformSelection.hasCarousel(layout);
  }

  /**
   * 屏幕实际轮流显示的布局：主布局在前（不存在或尺寸不符时退回同尺寸的内置布局），其后是有效的翻页布局，按屏幕记录的顺序、不重复； 没绑车站时翻页里的到发页不算。
   *
   * @return 连主布局都没有（没有同尺寸的内置布局）时为空
   */
  public static List<PidsLayout> resolve(PidsScreen screen, PidsLayoutRegistry layouts) {
    Optional<PidsLayout> primary =
        layouts.resolve(screen.layoutId(), screen.tileRows(), screen.tileCols());
    if (primary.isEmpty()) {
      return List.of();
    }
    Map<String, PidsLayout> pages = new LinkedHashMap<>();
    pages.put(primary.get().id(), primary.get());
    if (combinable(primary.get())) {
      for (String id : screen.pageLayoutIds()) {
        layouts
            .find(id)
            .filter(layout -> fits(screen, layout) && combinable(layout))
            .filter(layout -> showable(screen, layout))
            .ifPresent(layout -> pages.putIfAbsent(layout.id(), layout));
      }
    }
    return List.copyOf(pages.values());
  }

  /**
   * 菜单“布局”一行点了某个布局：它成为主布局，原有的翻页布局中能与它组合的保留（它自己原在翻页里的去掉）；它不能组合时只用它。
   *
   * @param screen 屏幕
   * @param chosen 选的布局（已确认与屏幕同尺寸）
   * @param layouts 布局目录
   * @return 新的布局 ID，主布局在前
   */
  public static List<String> withPrimary(
      PidsScreen screen, PidsLayout chosen, PidsLayoutRegistry layouts) {
    List<String> ids = new ArrayList<>();
    ids.add(chosen.id());
    if (combinable(chosen)) {
      for (String id : screen.pageLayoutIds()) {
        if (!id.equals(chosen.id())
            && layouts.find(id).filter(PidsScreenPages::combinable).isPresent()) {
          ids.add(id);
        }
      }
    }
    return ids;
  }

  /** 增删翻页布局的结果。 */
  public enum Outcome {
    OK,
    /** 没有这个布局，或尺寸与屏幕不符。 */
    UNKNOWN_LAYOUT,
    /** 点的是主布局：主布局在“布局”一行改选。 */
    PRIMARY,
    /** 主布局有自己的轮播（站台屏、多站台屏、停站屏），这块屏幕不能组合翻页。 */
    PRIMARY_NOT_COMBINABLE,
    /** 点的布局有自己的轮播，不能加入组合翻页。 */
    NOT_COMBINABLE,
    /** 点的是到发布局，而屏幕没绑车站。 */
    NEED_STATION
  }

  /**
   * @param outcome 结果
   * @param layoutIds 新的布局 ID（主布局在前）；不成功时为原来的
   */
  public record Edit(Outcome outcome, List<String> layoutIds) {
    public Edit {
      Objects.requireNonNull(outcome, "outcome");
      layoutIds = List.copyOf(layoutIds);
    }
  }

  /**
   * 菜单“组合翻页”一行点了某个布局：不在翻页里的加到最后，已在的去掉。已在翻页里的总能去掉（哪怕布局已删改过，免得留着清不掉）。
   *
   * @param screen 屏幕
   * @param value 点的布局 ID
   * @param layouts 布局目录
   */
  public static Edit togglePage(PidsScreen screen, String value, PidsLayoutRegistry layouts) {
    String id = value.trim();
    List<String> current = screen.layoutIds();
    if (screen.pageLayoutIds().contains(id)) {
      List<String> next = new ArrayList<>(current);
      next.remove(id);
      return new Edit(Outcome.OK, next);
    }
    Optional<PidsLayout> layout = layouts.find(id).filter(found -> fits(screen, found));
    if (layout.isEmpty()) {
      return new Edit(Outcome.UNKNOWN_LAYOUT, current);
    }
    Optional<PidsLayout> primary =
        layouts.resolve(screen.layoutId(), screen.tileRows(), screen.tileCols());
    if (primary.map(PidsLayout::id).filter(id::equals).isPresent()) {
      return new Edit(Outcome.PRIMARY, current);
    }
    if (primary.filter(PidsScreenPages::combinable).isEmpty()) {
      return new Edit(Outcome.PRIMARY_NOT_COMBINABLE, current);
    }
    if (!combinable(layout.get())) {
      return new Edit(Outcome.NOT_COMBINABLE, current);
    }
    if (!showable(screen, layout.get())) {
      return new Edit(Outcome.NEED_STATION, current);
    }
    List<String> next = new ArrayList<>(current);
    next.add(id);
    return new Edit(Outcome.OK, next);
  }

  /**
   * 菜单“组合翻页”一行可选的布局：与屏幕同尺寸、能组合、不是主布局的，按布局目录的顺序；没绑车站时不列到发布局。
   *
   * @return 主布局不能组合时为空
   */
  public static List<PidsLayout> candidates(PidsScreen screen, PidsLayoutRegistry layouts) {
    Optional<PidsLayout> primary =
        layouts.resolve(screen.layoutId(), screen.tileRows(), screen.tileCols());
    if (primary.filter(PidsScreenPages::combinable).isEmpty()) {
      return List.of();
    }
    return layouts.all().stream()
        .filter(layout -> fits(screen, layout) && combinable(layout))
        .filter(layout -> showable(screen, layout))
        .filter(layout -> !layout.id().equals(primary.get().id()))
        .toList();
  }

  /**
   * 此刻显示的布局。
   *
   * @param page 第几个布局（0 起）
   * @param round 第几轮：按时钟数起，每轮加一；分几页的布局按它轮换显示哪一页
   */
  public record Turn(int page, long round) {}

  /**
   * 按时钟取此刻显示哪个布局。
   *
   * @param seconds 各布局每轮停留多少秒（不足 1 秒按 1 秒），按显示顺序；不能为空
   * @param syncKey 同步键：键相同的屏幕同时翻页，不同的错开（绑了车站时为车站，只绑运营商时为运营商代码）
   * @param now 当前时刻
   */
  public static Turn turn(List<Integer> seconds, String syncKey, Instant now) {
    if (seconds.isEmpty()) {
      throw new IllegalArgumentException("至少要有一个布局");
    }
    long cycle = 0;
    for (int length : seconds) {
      cycle += Math.max(1, length);
    }
    long shifted = now.getEpochSecond() + PidsCarousel.offset(syncKey, cycle);
    long round = Math.floorDiv(shifted, cycle);
    long position = Math.floorMod(shifted, cycle);
    for (int i = 0; i < seconds.size(); i++) {
      long length = Math.max(1, seconds.get(i));
      if (position < length) {
        return new Turn(i, round);
      }
      position -= length;
    }
    throw new IllegalStateException("翻页位置超出一轮: " + position);
  }

  /** 布局能在这块屏幕上作翻页显示：到发布局（没有状况表组件）要绑车站。 */
  private static boolean showable(PidsScreen screen, PidsLayout layout) {
    return layout.lineStatus().isPresent() || screen.station().isPresent();
  }

  /** 布局与屏幕同尺寸。 */
  public static boolean fits(PidsScreen screen, PidsLayout layout) {
    return layout.tileRows() == screen.tileRows() && layout.tileCols() == screen.tileCols();
  }
}
