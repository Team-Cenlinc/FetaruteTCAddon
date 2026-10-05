package org.fetarute.fetaruteTCAddon.dispatcher.graph.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeTypeNames;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * 调度图失效告警：控制台记日志，在线且有 {@value #ALERT_PERMISSION} 权限的玩家收到提示，有权限的玩家上线时补一次提醒。
 *
 * <p>节点牌子可能被 WorldEdit 等批量编辑一次拆掉多块，同一 tick 内的变更合并成一条。只有"正常 → 失效"的那一批向玩家广播；
 * 已失效的世界里继续增删牌子只记控制台，建线时连续放牌子不会刷屏。
 */
public final class GraphStaleNotifier implements GraphStaleListener, Listener {

  /** 接收调度图失效告警的权限。 */
  public static final String ALERT_PERMISSION = "fetarute.graph.alert";

  static final int MAX_LISTED_CHANGES = 5;
  private static final int MAX_LOGGED_CHANGES = 20;
  private static final long JOIN_REMINDER_DELAY_TICKS = 40L;
  private static final String IMPACT = "重建前该世界的调度无法使用调度图，开启跨世界时经过该世界的交路也受影响";

  /** 调度与在线玩家的来源；测试用它替换 Bukkit 调度器。 */
  interface Host {
    Collection<? extends Player> onlinePlayers();

    List<World> worlds();

    void runNextTick(Runnable task);

    void runLater(Runnable task, long delayTicks);
  }

  private final Host host;
  private final RailGraphService railGraphService;
  private final LocaleManager locale;
  private final LoggerManager logger;
  private final Map<UUID, PendingBatch> pending = new LinkedHashMap<>();

  GraphStaleNotifier(
      Host host, RailGraphService railGraphService, LocaleManager locale, LoggerManager logger) {
    this.host = Objects.requireNonNull(host, "host");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.locale = Objects.requireNonNull(locale, "locale");
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  public static GraphStaleNotifier forPlugin(
      Plugin plugin,
      RailGraphService railGraphService,
      LocaleManager locale,
      LoggerManager logger) {
    Objects.requireNonNull(plugin, "plugin");
    Host host =
        new Host() {
          @Override
          public Collection<? extends Player> onlinePlayers() {
            return plugin.getServer().getOnlinePlayers();
          }

          @Override
          public List<World> worlds() {
            return plugin.getServer().getWorlds();
          }

          @Override
          public void runNextTick(Runnable task) {
            plugin.getServer().getScheduler().runTask(plugin, task);
          }

          @Override
          public void runLater(Runnable task, long delayTicks) {
            plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
          }
        };
    return new GraphStaleNotifier(host, railGraphService, locale, logger);
  }

  @Override
  public void onStale(World world, NodeChange change, boolean wasStale) {
    if (world == null || change == null) {
      return;
    }
    UUID worldId = world.getUID();
    PendingBatch batch = pending.get(worldId);
    boolean firstInTick = batch == null;
    if (firstInTick) {
      batch = new PendingBatch(world);
      pending.put(worldId, batch);
    }
    batch.add(change, !wasStale);
    if (firstInTick) {
      try {
        host.runNextTick(() -> flush(worldId));
      } catch (RuntimeException ex) {
        // 插件停用期间调度器拒绝新任务：就地发出，不丢告警。
        flush(worldId);
      }
    }
  }

  @Override
  public void onRecovered(World world) {
    if (world == null) {
      return;
    }
    UUID worldId = world.getUID();
    PendingBatch batch = pending.remove(worldId);
    String detail = batch != null ? "；同一 tick 内的变更: " + batch.consoleSummary() : "";
    logger.info("调度图已恢复: world=" + world.getName() + "，节点牌子与快照重新一致" + detail);
    // 失效就发生在这一批里、告警还没发出：恢复也不用广播。其余情况（含开服时就失效、上线时提醒过的）都要告诉管理员。
    if (batch == null || !batch.transitioned) {
      broadcast(
          List.of(locale.component("graph.alert.recovered", Map.of("world", world.getName()))));
    }
  }

  /** 启动载入图之后调用：控制台报告已经处于失效状态的世界。 */
  public void logStaleWorlds() {
    for (World world : host.worlds()) {
      if (isStale(world)) {
        logger.warn(
            "调度图快照已失效: world="
                + world.getName()
                + "，节点牌子与快照不一致，"
                + IMPACT
                + "；请执行 /fta graph build");
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    if (!player.hasPermission(ALERT_PERMISSION)) {
      return;
    }
    host.runLater(() -> remind(player), JOIN_REMINDER_DELAY_TICKS);
  }

  void remind(Player player) {
    if (!player.isOnline()) {
      return;
    }
    boolean any = false;
    for (World world : host.worlds()) {
      if (isStale(world)) {
        player.sendMessage(locale.component("graph.alert.join", Map.of("world", world.getName())));
        any = true;
      }
    }
    if (any) {
      player.sendMessage(locale.component("graph.alert.hint"));
    }
  }

  void flush(UUID worldId) {
    PendingBatch batch = pending.remove(worldId);
    if (batch == null) {
      return;
    }
    String summary = "world=" + batch.world.getName() + " " + batch.consoleSummary();
    if (!isStale(batch.world)) {
      logger.info("节点牌子变更后调度图已重新有效: " + summary);
      return;
    }
    if (!batch.transitioned) {
      logger.info("调度图仍处于失效状态，又有节点牌子变更: " + summary);
      return;
    }
    logger.warn("调度图已失效: " + summary + "；" + IMPACT + "，请执行 /fta graph build");
    broadcast(adminMessage(batch));
  }

  private List<Component> adminMessage(PendingBatch batch) {
    List<Component> lines = new ArrayList<>();
    lines.add(
        locale.component(
            "graph.alert.stale",
            Map.of(
                "world", batch.world.getName(),
                "removed", String.valueOf(batch.count(true)),
                "added", String.valueOf(batch.count(false)))));
    int listed = Math.min(batch.changes.size(), MAX_LISTED_CHANGES);
    for (int i = 0; i < listed; i++) {
      NodeChange change = batch.changes.get(i);
      lines.add(
          locale.component(
              "graph.alert.entry",
              Map.of(
                  "action",
                  locale.text(
                      change.removed() ? "graph.alert.action-removed" : "graph.alert.action-added"),
                  "type",
                  SignNodeTypeNames.localized(locale, change.definition()),
                  "node",
                  change.definition().nodeId().value(),
                  "x",
                  String.valueOf(change.x()),
                  "y",
                  String.valueOf(change.y()),
                  "z",
                  String.valueOf(change.z()))));
    }
    if (batch.changes.size() > listed) {
      lines.add(
          locale.component(
              "graph.alert.more", Map.of("count", String.valueOf(batch.changes.size() - listed))));
    }
    lines.add(locale.component("graph.alert.hint"));
    return lines;
  }

  private void broadcast(List<Component> lines) {
    for (Player player : host.onlinePlayers()) {
      if (player.hasPermission(ALERT_PERMISSION)) {
        lines.forEach(player::sendMessage);
      }
    }
  }

  private boolean isStale(World world) {
    return world != null && railGraphService.getStaleState(world).isPresent();
  }

  /** 同一世界在同一 tick 内的节点变更。 */
  private static final class PendingBatch {
    private final World world;
    private final List<NodeChange> changes = new ArrayList<>();
    private boolean transitioned;

    private PendingBatch(World world) {
      this.world = world;
    }

    private void add(NodeChange change, boolean causedTransition) {
      changes.add(change);
      transitioned |= causedTransition;
    }

    private long count(boolean removed) {
      return changes.stream().filter(change -> change.removed() == removed).count();
    }

    private String consoleSummary() {
      StringBuilder text =
          new StringBuilder("移除 ")
              .append(count(true))
              .append(" 处、新增 ")
              .append(count(false))
              .append(" 处: [");
      int listed = Math.min(changes.size(), MAX_LOGGED_CHANGES);
      for (int i = 0; i < listed; i++) {
        NodeChange change = changes.get(i);
        if (i > 0) {
          text.append(", ");
        }
        text.append(change.removed() ? "-" : "+")
            .append(change.definition().nodeId().value())
            .append('@')
            .append(change.x())
            .append(',')
            .append(change.y())
            .append(',')
            .append(change.z());
      }
      if (changes.size() > listed) {
        text.append(", …另有 ").append(changes.size() - listed).append(" 处");
      }
      return text.append(']').toString();
    }
  }
}
