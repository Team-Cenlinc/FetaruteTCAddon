package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableMember;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 组合编组写法 {@code A+B}：插件约定的写法，按 {@code +} 分段，每段是一个 TrainCarts 出车写法（通常是存车名），按顺序拼成一列（车头到车尾）。
 *
 * <p>TrainCarts 自己的写法里没有 {@code +}；它也能把几辆存车拼成一列，但出车后不告诉我们各段的分界。按 {@code +} 分段出车，每段的车厢能打上各自的来源编组，
 * 解编（DECP）才知道从哪里切。不写 {@code +} 的写法照旧整串交给 TrainCarts。
 *
 * <p>拼法：第一段照常解析，后几段的车厢追加在它后面，列车级属性（存车里的限速、名字格式等）沿用第一段。
 */
public final class CompositeSpawnPattern {

  /** 分段符。 */
  public static final char SEPARATOR = '+';

  private CompositeSpawnPattern() {}

  /**
   * 按 {@code +} 分段：去掉首尾空白，丢掉空段；没有 {@code +} 时就是原写法一段。纯计算。
   *
   * @param pattern 编组写法
   * @return 各段（车头到车尾）；写法为空时为空表
   */
  public static List<String> parts(String pattern) {
    if (pattern == null || pattern.isBlank()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (String part : pattern.split("\\" + SEPARATOR, -1)) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return List.copyOf(out);
  }

  /** 是不是组合写法（至少两段）。纯计算。 */
  public static boolean isComposite(String pattern) {
    return parts(pattern).size() > 1;
  }

  /**
   * 解析结果。
   *
   * @param group 拼好的一列（交给 TrainCarts 出车）
   * @param parts 各段写法
   * @param partSizes 各段的节数，与 {@code parts} 一一对应
   */
  public record Parsed(SpawnableGroup group, List<String> parts, List<Integer> partSizes) {

    public Parsed {
      Objects.requireNonNull(group, "group");
      parts = List.copyOf(parts);
      partSizes = List.copyOf(partSizes);
    }

    /** 任一段超出 TrainCarts 的存车出车上限。 */
    public boolean exceedsSpawnLimit(TrainCarts trainCarts) {
      for (String part : parts) {
        SpawnableGroup single = SpawnableGroup.parse(trainCarts, part);
        if (single != null && single.isExceedingSpawnLimit()) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * 用 TrainCarts 解析：各段分别解析，按顺序拼成一列。只能在主线程调用。
   *
   * @return 任一段解析不出或没有车厢时为空
   */
  public static Optional<Parsed> parse(TrainCarts trainCarts, String pattern) {
    List<String> parts = parts(pattern);
    if (trainCarts == null || parts.isEmpty()) {
      return Optional.empty();
    }
    if (parts.size() == 1) {
      SpawnableGroup single = SpawnableGroup.parse(trainCarts, pattern.trim());
      if (single == null || single.getMembers().isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(new Parsed(single, parts, List.of(single.getMembers().size())));
    }
    SpawnableGroup head = null;
    List<Integer> sizes = new ArrayList<>();
    for (String part : parts) {
      SpawnableGroup piece = SpawnableGroup.parse(trainCarts, part);
      if (piece == null || piece.getMembers().isEmpty()) {
        return Optional.empty();
      }
      sizes.add(piece.getMembers().size());
      if (head == null) {
        head = piece;
        continue;
      }
      for (SpawnableMember member : List.copyOf(piece.getMembers())) {
        head.addMember(member);
      }
    }
    return Optional.of(new Parsed(head, parts, sizes));
  }
}
