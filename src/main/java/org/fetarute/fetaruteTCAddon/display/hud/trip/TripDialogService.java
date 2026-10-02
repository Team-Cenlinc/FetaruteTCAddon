package org.fetarute.fetaruteTCAddon.display.hud.trip;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.display.DisplayService;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContext;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContextResolver;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 后续站点对话框：乘客需要时自己打开，不在上车时弹出。
 *
 * <ul>
 *   <li>入口：乘坐 FTA 列车时按副手交换键（默认 F），或输入 {@code /fta trip}。只在乘车时拦截副手交换，下车后按键恢复原功能； {@code
 *       runtime.hud.trip-dialog.swap-hand-key} 可关掉按键入口。
 *   <li>提示：玩家打开过一次之前，HUD 的 {@code {?trip_dialog_key}} 占位符给出按键名，默认模板据此在 ActionBar 轮播一页提示；
 *       打开过之后（记在玩家数据里）提示不再出现。
 *   <li>内容是打开那一刻的快照，不会自己刷新；对话框里有“刷新”与“只看换乘站”。乘客下车时关掉。
 *   <li>权限 {@code fetarute.trip}：按键、命令与对话框按钮都检查；同一玩家 {@link #COOLDOWN} 内只打开一次（防连按刷包）。
 *   <li>按钮回调只记“是否只看换乘站”，执行时现取当前的服务实例：{@code /fta reload} 之后点旧对话框的按钮也走新实例。
 * </ul>
 *
 * <p>对话框需要 1.21.6 及以上的客户端。
 */
public final class TripDialogService implements Listener {

  /** 最多列出的停靠站数。 */
  static final int STOP_LIMIT = 12;

  /** “只看换乘站”往前看多少站：换乘站可能在第 12 站以后。 */
  static final int TRANSFER_SCAN_LIMIT = 64;

  /** 查看后续站点的权限。 */
  public static final String PERMISSION = "fetarute.trip";

  /** 同一玩家两次打开的最短间隔。 */
  static final Duration COOLDOWN = Duration.ofSeconds(1);

  /** HUD 提示占位符的值：客户端按玩家自己的键位显示副手交换键。 */
  static final String SWAP_HAND_KEY = "<key:key.swapOffhand>";

  private static final int SUMMARY_WIDTH = 300;
  private static final int ROW_WIDTH = 260;
  private static final int BUTTON_WIDTH = 150;
  private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(10);

  private final FetaruteTCAddon plugin;
  private final LocaleManager locale;
  private final TrainHudContextResolver resolver;
  private final NamespacedKey usedKey;
  private final boolean swapHandKey;

  /** 打开着对话框的玩家与打开时刻；关闭按钮（含 Esc）会移除。 */
  private final Map<UUID, Instant> viewers = new ConcurrentHashMap<>();

  /** 各玩家上次打开的时刻，用于冷却。 */
  private final Map<UUID, Instant> lastOpened = new ConcurrentHashMap<>();

  /**
   * @param resolver 与车内 HUD 共用的上下文解析器
   */
  public TripDialogService(
      FetaruteTCAddon plugin, LocaleManager locale, TrainHudContextResolver resolver) {
    this.plugin = plugin;
    this.locale = locale;
    this.resolver = resolver;
    this.usedKey = new NamespacedKey(plugin, "trip_dialog_used");
    this.swapHandKey = plugin.getConfig().getBoolean("runtime.hud.trip-dialog.swap-hand-key", true);
  }

  public void register() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public void unregister() {
    HandlerList.unregisterAll(this);
    viewers.clear();
    lastOpened.clear();
  }

  /**
   * HUD 入口提示的按键：按键入口开着、且玩家还没打开过对话框时为按键标签，否则为空。
   *
   * @param player 玩家
   */
  public Optional<String> hintKey(Player player) {
    if (!swapHandKey
        || player == null
        || player.getPersistentDataContainer().has(usedKey, PersistentDataType.BYTE)) {
      return Optional.empty();
    }
    return Optional.of(SWAP_HAND_KEY);
  }

  /**
   * 给玩家打开后续站点对话框。
   *
   * @param player 玩家
   * @param transfersOnly 是否只列换乘站
   * @return 玩家不在 FTA 列车上时为 false（已提示玩家）
   */
  public boolean open(Player player, boolean transfersOnly) {
    if (!player.hasPermission(PERMISSION) || coolingDown(player)) {
      return false;
    }
    Optional<Ride> ride = ride(player);
    if (ride.isEmpty()) {
      player.sendMessage(locale.component("display.trip.not-on-train"));
      return false;
    }
    show(player, ride.get(), transfersOnly);
    return true;
  }

  /** 乘车时按副手交换键打开对话框；不在 FTA 列车上、没有权限时不拦截。 */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onSwapHand(PlayerSwapHandItemsEvent event) {
    Player player = event.getPlayer();
    if (!swapHandKey || !player.hasPermission(PERMISSION)) {
      return;
    }
    ride(player)
        .ifPresent(
            ride -> {
              event.setCancelled(true);
              if (!coolingDown(player)) {
                show(player, ride, false);
              }
            });
  }

  /** 下车时关掉还开着的对话框，免得乘客对着过期的列表找站。只关本插件打开、尚未关闭、且按钮回调仍有效期内的， 不误关其他插件之后打开的对话框。 */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDismount(EntityDismountEvent event) {
    if (!(event.getEntity() instanceof Player player)) {
      return;
    }
    Instant openedAt = viewers.remove(player.getUniqueId());
    if (openedAt != null && Instant.now().isBefore(openedAt.plus(CALLBACK_LIFETIME))) {
      player.closeDialog();
    }
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    viewers.remove(event.getPlayer().getUniqueId());
    lastOpened.remove(event.getPlayer().getUniqueId());
  }

  /** 冷却中返回 true；不在冷却中时记下这一次。 */
  private boolean coolingDown(Player player) {
    Instant now = Instant.now();
    Instant last = lastOpened.get(player.getUniqueId());
    if (last != null && now.isBefore(last.plus(COOLDOWN))) {
      return true;
    }
    lastOpened.put(player.getUniqueId(), now);
    return false;
  }

  /** 玩家所乘的 FTA 列车；不在车上或不是 FTA 管控的列车时为空。 */
  private Optional<Ride> ride(Player player) {
    Optional<MinecartGroup> group = resolver.resolveGroup(player);
    return group.flatMap(resolver::resolveContext).map(context -> new Ride(group.get(), context));
  }

  private void show(Player player, Ride ride, boolean transfersOnly) {
    TrainHudContext context = ride.context();
    Map<String, String> placeholders = resolver.buildPlaceholders(context, 0.0f);
    resolver.applyPlayerPlaceholders(placeholders, player, ride.group());
    TrainHudContextResolver.UpcomingStops upcoming =
        resolver.resolveUpcomingStops(context, transfersOnly ? TRANSFER_SCAN_LIMIT : STOP_LIMIT);
    TripSheet sheet =
        TripSheet.build(
            TripSheet.Texts.load(locale::text, locale::stringList),
            placeholders,
            upcoming.stops(),
            upcoming.total(),
            STOP_LIMIT,
            transfersOnly,
            context.currentLine(),
            (stop, sequence) ->
                resolver.stopPlaceholders(
                    placeholders, Optional.of(stop), sequence, context.currentLine()));
    player.showDialog(dialog(sheet));
    viewers.put(player.getUniqueId(), Instant.now());
    player.getPersistentDataContainer().set(usedKey, PersistentDataType.BYTE, (byte) 1);
  }

  private Dialog dialog(TripSheet sheet) {
    List<DialogBody> body = new ArrayList<>();
    body.add(DialogBody.plainMessage(sheet.summary(), SUMMARY_WIDTH));
    for (TripSheet.Row row : sheet.rows()) {
      body.add(
          DialogBody.item(new ItemStack(icon(row.kind())))
              .description(DialogBody.plainMessage(row.text(), ROW_WIDTH))
              .showDecorations(false)
              .showTooltip(false)
              .build());
    }
    if (sheet.rows().isEmpty()) {
      body.add(DialogBody.plainMessage(locale.component("display.trip.empty"), SUMMARY_WIDTH));
    }
    if (sheet.hidden() > 0) {
      body.add(
          DialogBody.plainMessage(
              locale.component(
                  "display.trip.more", Map.of("count", String.valueOf(sheet.hidden()))),
              SUMMARY_WIDTH));
    }
    boolean transfersOnly = sheet.transfersOnly();
    ActionButton refresh =
        button(
            "display.trip.button.refresh", (dialog, player) -> dialog.open(player, transfersOnly));
    ActionButton toggle =
        button(
            transfersOnly ? "display.trip.button.show-all" : "display.trip.button.transfers-only",
            (dialog, player) -> dialog.open(player, !transfersOnly));
    ActionButton close =
        button(
            "display.trip.button.close",
            (dialog, player) -> dialog.viewers.remove(player.getUniqueId()));
    return Dialog.create(
        factory ->
            factory
                .empty()
                .base(
                    DialogBase.builder(sheet.title())
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(
                    DialogType.multiAction(List.of(refresh, toggle))
                        .exitAction(close)
                        .columns(2)
                        .build()));
  }

  /** 带回调的按钮。回调只捕获插件与动作本身（不捕获本服务和对话框内容），执行时回到主线程、现取当前的服务实例。 */
  private ActionButton button(String labelKey, BiConsumer<TripDialogService, Player> action) {
    FetaruteTCAddon owner = plugin;
    DialogAction callback =
        DialogAction.customClick(
            (response, audience) -> {
              if (audience instanceof Player player) {
                Bukkit.getScheduler()
                    .runTask(
                        owner,
                        () ->
                            owner
                                .getDisplayService()
                                .flatMap(DisplayService::tripDialog)
                                .ifPresent(dialog -> action.accept(dialog, player)));
              }
            },
            ClickCallback.Options.builder().uses(1).lifetime(CALLBACK_LIFETIME).build());
    return ActionButton.create(locale.component(labelKey), null, BUTTON_WIDTH, callback);
  }

  private static Material icon(TripSheet.Kind kind) {
    return switch (kind) {
      case NEXT -> Material.MINECART;
      case TRANSFER -> Material.POWERED_RAIL;
      case TERMINAL -> Material.RED_BANNER;
      case STOP -> Material.RAIL;
    };
  }

  private record Ride(MinecartGroup group, TrainHudContext context) {}
}
