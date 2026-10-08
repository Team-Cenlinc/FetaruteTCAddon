package org.fetarute.fetaruteTCAddon.display.pids;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;

/**
 * 一块屏幕能绑定哪些站台，由布局决定。
 *
 * <ul>
 *   <li>单站台屏（站台号组件 {@code max: 1}，内置 1×3、1×4）：恰好一个站台，菜单里点哪个就换成哪个。
 *   <li>多站台屏（{@code max} 大于 1，内置多站台 1×3、1×4）：一到 {@code max} 个，增删切换，不能全去掉，也不能选全部。
 *   <li>车站统屏（没有站台号组件，内置 3×5）：任意几个，或全部（空集合）。
 * </ul>
 */
public final class PidsPlatformSelection {

  private PidsPlatformSelection() {}

  /** 选择结果。 */
  public enum Outcome {
    OK,
    /** 站台屏不能没有站台，也不能选全部。 */
    NEED_PLATFORM,
    /** 超过布局能显示的站台数。 */
    TOO_MANY
  }

  /**
   * @param outcome 结果
   * @param platforms 选择后的站台；不成功时为原来的站台
   */
  public record Result(Outcome outcome, Set<String> platforms) {
    public Result {
      Objects.requireNonNull(outcome, "outcome");
      platforms = Set.copyOf(platforms);
    }
  }

  /** 布局对站台数的上限；没有站台号组件（车站统屏）时为空，表示任意几个或全部。 */
  public static OptionalInt limit(PidsLayout layout) {
    return layout.widgets().stream()
        .filter(PidsLayout.Platform.class::isInstance)
        .mapToInt(widget -> ((PidsLayout.Platform) widget).max())
        .findFirst();
  }

  /** 布局有自己的轮播：带站台号组件的（站台屏、多站台屏、停站屏）轮播宣传页、公告与空位页，不能与别的布局组合翻页； 车站统屏、线路运行状况屏没有。 */
  public static boolean hasCarousel(PidsLayout layout) {
    return limit(layout).isPresent();
  }

  /**
   * 菜单点了一个站台（或 {@value PidsScreen#ALL}）之后的站台。
   *
   * @param current 当前站台
   * @param value 点的站台号（已规整），或 {@value PidsScreen#ALL}
   * @param limit 见 {@link #limit}
   */
  public static Result select(Set<String> current, String value, OptionalInt limit) {
    boolean all = PidsScreen.ALL.equalsIgnoreCase(value);
    if (limit.isEmpty()) {
      return new Result(Outcome.OK, PidsScreen.toggle(current, value));
    }
    if (all) {
      return new Result(Outcome.NEED_PLATFORM, current);
    }
    if (limit.getAsInt() == 1) {
      return new Result(Outcome.OK, Set.of(value));
    }
    Set<String> next = PidsScreen.toggle(current, value);
    if (next.isEmpty()) {
      return new Result(Outcome.NEED_PLATFORM, current);
    }
    if (next.size() > limit.getAsInt()) {
      return new Result(Outcome.TOO_MANY, current);
    }
    return new Result(Outcome.OK, next);
  }

  /**
   * 安装或改绑车站时自动绑定的站台：这个车站离屏幕最近的几个站台，按布局上限截取；车站统屏为空（全部）。
   *
   * @param nearby 附近的站台节点，由近到远
   * @param station 绑定的车站
   * @param limit 见 {@link #limit}
   */
  public static Set<String> nearest(
      List<PidsPlatformNode> nearby, PidsStationKey station, OptionalInt limit) {
    if (limit.isEmpty()) {
      return Set.of();
    }
    Set<String> platforms = new LinkedHashSet<>();
    for (PidsPlatformNode node : nearby) {
      if (platforms.size() >= limit.getAsInt()) {
        break;
      }
      if (node.station().equals(station)) {
        platforms.add(node.platform());
      }
    }
    return platforms;
  }
}
