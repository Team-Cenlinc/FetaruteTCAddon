package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * TrainCarts 牌子触发监听：对带 {@link #FTA_BYPASS_TAG} 的列车屏蔽绝大多数牌子控制。
 *
 * <p>需求：某些列车希望“完全绕过站台/发车/目的地/脚本等牌子控制”，只保留最基础的安全与道岔行为：
 *
 * <ul>
 *   <li>{@code destroy}：销毁列车（用于兜底清理）
 *   <li>{@code switcher}：道岔控制（用于轨道物理与进路）
 * </ul>
 *
 * <p>实现方式：监听 {@link SignActionEvent} 并在满足条件时直接 {@link SignActionEvent#setCancelled(boolean)}，
 * TrainCarts 将跳过后续 SignAction 执行。
 *
 * <p>注意：该监听仅影响“列车触发牌子”的运行时行为，不影响建牌/拆牌注册流程。
 */
public final class TrainSignBypassListener implements Listener {

  /** 具备该 tag 的列车将屏蔽大多数牌子控制。 */
  public static final String FTA_BYPASS_TAG = "FTA_BYPASS";

  private static final String ALLOW_SWITCHER = "switcher";
  private static final String ALLOW_DESTROY = "destroy";

  private final Consumer<String> debugLogger;
  private final Predicate<SignActionEvent> bypassPredicate;
  private final BiPredicate<SignActionEvent, SignActionType> debugLogPredicate;

  public TrainSignBypassListener(Consumer<String> debugLogger) {
    this(
        debugLogger,
        TrainSignBypassListener::hasBypassTag,
        TrainSignBypassListener::shouldDebugLogForEnter);
  }

  TrainSignBypassListener(
      Consumer<String> debugLogger, Predicate<SignActionEvent> bypassPredicate) {
    this(debugLogger, bypassPredicate, TrainSignBypassListener::shouldDebugLogForEnter);
  }

  TrainSignBypassListener(
      Consumer<String> debugLogger,
      Predicate<SignActionEvent> bypassPredicate,
      BiPredicate<SignActionEvent, SignActionType> debugLogPredicate) {
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.bypassPredicate = Objects.requireNonNull(bypassPredicate, "bypassPredicate");
    this.debugLogPredicate = Objects.requireNonNull(debugLogPredicate, "debugLogPredicate");
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onSignActionEarly(SignActionEvent event) {
    handleSignAction(event, true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onSignActionLate(SignActionEvent event) {
    handleSignAction(event, false);
  }

  private void handleSignAction(SignActionEvent event, boolean allowDebugLog) {
    Objects.requireNonNull(event, "event");
    if (event.isCancelled()) {
      return;
    }

    if (!bypassPredicate.test(event)) {
      return;
    }

    if (event.isType(ALLOW_DESTROY) || event.isType(ALLOW_SWITCHER)) {
      return;
    }

    event.setCancelled(true);
    if (!allowDebugLog) {
      return;
    }
    SignActionType action = event.getAction();
    if (!debugLogPredicate.test(event, action)) {
      return;
    }
    String trainName = safeTrainName(event);
    String type = safeSignType(event);
    Location location = event.getLocation();
    debugLogger.accept(
        "FTA_BYPASS: 已屏蔽牌子触发: train="
            + trainName
            + " action="
            + (action != null ? action.name().toLowerCase(Locale.ROOT) : "")
            + " type="
            + type
            + " @ "
            + formatLocation(location));
  }

  /**
   * Debug 日志只打印一次：TrainCarts 会对同一块牌子按“每节车厢 member_enter”触发事件，长编组会导致刷屏。
   *
   * <p>这里选择仅在 {@link SignActionType#MEMBER_ENTER} 且触发成员为列车“车头（head）”时输出一次。
   */
  private static boolean shouldDebugLogForEnter(SignActionEvent event, SignActionType action) {
    if (action == null || action != SignActionType.MEMBER_ENTER) {
      return false;
    }

    MinecartGroup group = resolveGroup(event);
    if (group == null) {
      return false;
    }
    MinecartMember<?> member = event.getMember();
    if (member == null) {
      return false;
    }
    MinecartMember<?> head = group.head();
    return head == member;
  }

  private static boolean hasBypassTag(SignActionEvent event) {
    MinecartGroup group = resolveGroup(event);
    if (group == null) {
      return false;
    }
    TrainProperties properties = group.getProperties();
    return properties != null && properties.matchTag(FTA_BYPASS_TAG);
  }

  private static MinecartGroup resolveGroup(SignActionEvent event) {
    if (event.hasGroup()) {
      return event.getGroup();
    }
    if (!event.hasMember()) {
      return null;
    }
    MinecartMember<?> member = event.getMember();
    return member != null ? member.getGroup() : null;
  }

  private static String safeTrainName(SignActionEvent event) {
    try {
      MinecartGroup group = resolveGroup(event);
      if (group != null) {
        TrainProperties properties = group.getProperties();
        if (properties != null) {
          String name = properties.getTrainName();
          if (name != null && !name.isEmpty()) {
            return name;
          }
        }
      }
      String rcName = event.getRCName();
      return rcName != null ? rcName : "unknown";
    } catch (Throwable ignored) {
      return "unknown";
    }
  }

  private static String safeSignType(SignActionEvent event) {
    String type = event.getLine(1);
    if (type == null) {
      return "";
    }
    return type.trim().toLowerCase(Locale.ROOT);
  }

  private static String formatLocation(Location location) {
    if (location == null) {
      return "unknown";
    }
    World world = location.getWorld();
    return (world != null ? world.getName() : "unknown")
        + " ("
        + location.getBlockX()
        + ","
        + location.getBlockY()
        + ","
        + location.getBlockZ()
        + ")";
  }
}
