package org.fetarute.fetaruteTCAddon.drive.crew;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 在岗登记：一个玩家同一时间只担任一个角色（驾驶员、车掌、信号员）。
 *
 * <p>各角色登记一个“这个玩家是否正在担任我”的查询（含已领、尚未开始的任务），开始当班或领任务前统一问 {@link #busyOtherThan}，
 * 不再在角色之间两两互查。查询必须便宜、不抛异常，只在主线程调用。
 */
public final class CrewDuty {

  /** 角色。 */
  public enum Role {
    DRIVER,
    GUARD,
    SIGNALLER
  }

  private final Map<Role, Predicate<UUID>> roles = new EnumMap<>(Role.class);

  /**
   * 登记一个角色的在岗查询；同一角色再次登记时替换。
   *
   * @param role 角色
   * @param onDuty 玩家是否正在担任这个角色（含已领的任务）
   */
  public void register(Role role, Predicate<UUID> onDuty) {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(onDuty, "onDuty");
    roles.put(role, onDuty);
  }

  /** 撤销一个角色的登记（模块卸载时）。 */
  public void unregister(Role role) {
    roles.remove(role);
  }

  /**
   * 玩家此刻担任的角色；按驾驶员、车掌、信号员的顺序取第一个。查询出错的角色当作不在岗。
   *
   * @param player 玩家
   */
  public Optional<Role> busy(UUID player) {
    return busyOtherThan(player, null);
  }

  /**
   * 玩家此刻是否在担任 {@code self} 以外的角色；有就返回那个角色，开始当班或领任务前据此拒绝。
   *
   * @param player 玩家
   * @param self 要开始的角色；{@code null} 表示任何角色都算
   */
  public Optional<Role> busyOtherThan(UUID player, Role self) {
    if (player == null) {
      return Optional.empty();
    }
    for (Map.Entry<Role, Predicate<UUID>> entry : roles.entrySet()) {
      if (entry.getKey() == self) {
        continue;
      }
      boolean onDuty;
      try {
        onDuty = entry.getValue().test(player);
      } catch (RuntimeException ex) {
        onDuty = false;
      }
      if (onDuty) {
        return Optional.of(entry.getKey());
      }
    }
    return Optional.empty();
  }
}
