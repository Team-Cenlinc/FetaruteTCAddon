package org.fetarute.fetaruteTCAddon.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 遍历顺序跨进程可复现的不可变副本。
 *
 * <h2>为什么不能用 {@code Set.copyOf}/{@code Map.copyOf}</h2>
 *
 * <p>JDK 不可变集合（{@code Set.copyOf}、{@code Set.of}、{@code Map.copyOf}、{@code Map.of}）的遍历顺序取决于 {@code
 * java.util.ImmutableCollections.SALT32L}，每个 JVM 启动时随机一次。同一进程内它完全稳定，于是"第一个合格者赢"的循环、{@code
 * findFirst}、按序触发的副作用在测试里看不出问题，却会在每次服务器重启后换一个选择。元素的 {@code hashCode} 若含枚举（枚举用身份 hash），连 {@code
 * HashSet}/{@code HashMap} 的顺序也会随进程变。
 *
 * <h2>规则</h2>
 *
 * <p>调度决策路径上，凡是遍历顺序可能影响选择或副作用先后的集合：
 *
 * <ul>
 *   <li>来源本身带语义顺序（{@code List}、{@code LinkedHashSet}/{@code LinkedHashMap}、已排序集合）时，用 {@link
 *       #copyInInsertionOrder} 原样保留——例如占用判定给出的 blocker 按请求路径顺序排列，"第一个"就是路径上最近的那个；
 *   <li>来源顺序不可信（来自 {@code HashSet}，或元素 hash 按进程变）时，用 {@link #copySorted} 按显式比较器排序。
 * </ul>
 *
 * <p>两者都与 {@code Set.copyOf}/{@code Map.copyOf} 一样拒绝 null 元素、键与值，返回不可修改的视图；{@code equals}/{@code
 * hashCode} 仍按内容比较，与顺序无关。
 */
public final class StableCollections {

  private StableCollections() {}

  /**
   * 按来源的遍历顺序复制为不可变 set；重复元素只保留第一次出现的位置。
   *
   * @param source 来源集合，其遍历顺序必须本身是确定的
   * @return 保留插入序的不可变 set
   * @throws NullPointerException 来源或任一元素为 null
   */
  public static <T> Set<T> copyInInsertionOrder(Collection<? extends T> source) {
    Objects.requireNonNull(source, "source");
    if (source.isEmpty()) {
      return Set.of();
    }
    Set<T> copy = new LinkedHashSet<>(Math.max(16, (int) (source.size() / 0.75f) + 1));
    for (T element : source) {
      copy.add(Objects.requireNonNull(element, "element"));
    }
    return Collections.unmodifiableSet(copy);
  }

  /**
   * 按显式比较器排序后复制为不可变 set。
   *
   * <p>排序是稳定的：比较器判为相等的不同元素保持来源中的相对顺序。因此比较器应当与 {@code equals} 一致，否则结果仍依赖来源顺序。
   *
   * @param source 来源集合，顺序无关
   * @param order 显式顺序
   * @return 按 {@code order} 排列的不可变 set
   * @throws NullPointerException 来源、比较器或任一元素为 null
   */
  public static <T> Set<T> copySorted(Collection<? extends T> source, Comparator<? super T> order) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(order, "order");
    if (source.isEmpty()) {
      return Set.of();
    }
    List<T> sorted = new ArrayList<>(source.size());
    for (T element : source) {
      sorted.add(Objects.requireNonNull(element, "element"));
    }
    sorted.sort(order);
    return copyInInsertionOrder(sorted);
  }

  /**
   * 按来源的遍历顺序复制为不可变 map。
   *
   * @param source 来源 map，其遍历顺序必须本身是确定的
   * @return 保留插入序的不可变 map
   * @throws NullPointerException 来源或任一键、值为 null
   */
  public static <K, V> Map<K, V> copyInInsertionOrder(Map<? extends K, ? extends V> source) {
    Objects.requireNonNull(source, "source");
    if (source.isEmpty()) {
      return Map.of();
    }
    Map<K, V> copy = new LinkedHashMap<>(Math.max(16, (int) (source.size() / 0.75f) + 1));
    for (Map.Entry<? extends K, ? extends V> entry : source.entrySet()) {
      copy.put(
          Objects.requireNonNull(entry.getKey(), "key"),
          Objects.requireNonNull(entry.getValue(), "value"));
    }
    return Collections.unmodifiableMap(copy);
  }
}
