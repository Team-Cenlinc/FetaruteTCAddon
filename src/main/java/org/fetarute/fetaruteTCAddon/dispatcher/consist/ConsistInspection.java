package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 从 TrainCarts 读到的编组原始情况，不含任何推断。
 *
 * <p>由 {@link ConsistInspector} 在主线程产出；合成档案（{@link ConsistProfiles}）只读它，便于脱离服务器单测。
 *
 * @param savedTrain 写法本身就是一个存车名
 * @param cars 解析出的节数；0 表示 TrainCarts 解析不出任何车厢
 * @param lengthBlocks 车身总长（格）
 * @param tagValues 各节车厢 {@code 键=值} 标签按键（大写）汇总的取值；同一个键在不同车厢上可能有不同的值
 * @param spawnLimit 存车的出车上限；不是存车或不限时为空
 */
public record ConsistInspection(
    boolean savedTrain,
    int cars,
    double lengthBlocks,
    Map<String, Set<String>> tagValues,
    OptionalInt spawnLimit) {

  public ConsistInspection {
    Objects.requireNonNull(spawnLimit, "spawnLimit");
    Map<String, Set<String>> copy = new TreeMap<>();
    if (tagValues != null) {
      tagValues.forEach(
          (key, values) -> {
            if (key != null && values != null && !values.isEmpty()) {
              copy.put(key.toUpperCase(Locale.ROOT), Set.copyOf(new TreeSet<>(values)));
            }
          });
    }
    tagValues = Map.copyOf(copy);
    if (cars < 0) {
      throw new IllegalArgumentException("cars 不能为负");
    }
  }

  /** 解析不出任何车厢。 */
  public static ConsistInspection unresolved() {
    return new ConsistInspection(false, 0, 0.0, Map.of(), OptionalInt.empty());
  }

  /** 某个标签在各节车厢上的取值（键不区分大小写）。 */
  public Set<String> tag(String key) {
    return key == null ? Set.of() : tagValues.getOrDefault(key.toUpperCase(Locale.ROOT), Set.of());
  }
}
