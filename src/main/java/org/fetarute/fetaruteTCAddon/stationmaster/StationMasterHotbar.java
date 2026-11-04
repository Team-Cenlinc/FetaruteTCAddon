package org.fetarute.fetaruteTCAddon.stationmaster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.drive.inventory.ClientClock;
import org.fetarute.fetaruteTCAddon.drive.inventory.CrewHotbar;
import org.fetarute.fetaruteTCAddon.drive.inventory.HotbarRewriter;
import org.fetarute.fetaruteTCAddon.drive.inventory.InputSignal;
import org.fetarute.fetaruteTCAddon.drive.inventory.UseClicks;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.fetarute.fetaruteTCAddon.utils.PackModel;

/**
 * 站长的快捷栏：在岗时发往客户端的快捷栏换成站台作业按钮，背包本身不动（与车掌同一套数据包层改写）。F 开菜单（呼叫、下岗）。
 *
 * <p>贴图键为 {@code fetarute:station/<键>}，紧急停车沿用车掌的；没装材质包时按染料颜色区分，“亮起”加附魔光效。其余几格空着，空手可以开门。
 * 右键的判定与驾驶员、车掌相同：时刻按客户端时钟读，按住时的重发不算再按一下，网络抖动不会把一下算成两下。改写规则在主线程换、在网络线程读。
 */
final class StationMasterHotbar implements CrewHotbar {

  /** 快捷栏上的按钮。 */
  enum Button {
    SIGNAL(0),
    HOLD(1),
    CLOSE(2),
    EMERGENCY(8);

    private final int slot;

    Button(int slot) {
      this.slot = slot;
    }

    int slot() {
      return slot;
    }

    static Optional<Button> fromSlot(int slot) {
      for (Button button : values()) {
        if (button.slot == slot) {
          return Optional.of(button);
        }
      }
      return Optional.empty();
    }
  }

  private record Installed(
      StationMasterDisplay.View view, List<ItemStack> items, HotbarRewriter<ItemStack> rewriter) {}

  /** 两个右键包相隔超过这么久算新按下（按住时客户端每 4 tick 重发）。 */
  private static final long FRESH_PRESS_GAP_MILLIS = 8L * ClientClock.MILLIS_PER_TICK;

  private final Plugin plugin;
  private final Supplier<LocaleManager> locale;
  private final BiConsumer<Player, Button> pressed;
  private final Consumer<Player> openMenu;
  private final ToLongFunction<UUID> clock;
  private final Map<UUID, Installed> installed = new ConcurrentHashMap<>();
  private final Map<UUID, Long> lastUseMillis = new ConcurrentHashMap<>();
  private final Map<UUID, Integer> menus = new ConcurrentHashMap<>();

  /**
   * @param plugin 插件（调度回主线程）
   * @param locale 语言
   * @param pressed 主线程上处理按下的按钮
   * @param openMenu 主线程上打开菜单
   * @param clock 读玩家的客户端时钟（毫秒）
   */
  StationMasterHotbar(
      Plugin plugin,
      Supplier<LocaleManager> locale,
      BiConsumer<Player, Button> pressed,
      Consumer<Player> openMenu,
      ToLongFunction<UUID> clock) {
    this.plugin = plugin;
    this.locale = locale;
    this.pressed = pressed;
    this.openMenu = openMenu;
    this.clock = clock;
  }

  /** 记下菜单开着（上半部分的格数）或已关（0）：菜单按钮的点击才放行到服务器。 */
  void setMenuOpen(UUID player, int topSize) {
    if (topSize > 0) {
      menus.put(player, topSize);
    } else {
      menus.remove(player);
    }
  }

  /** 按此刻的样子装上或换掉快捷栏（主线程）；样子没变时不动。 */
  void show(Player player, StationMasterDisplay.View view) {
    Installed current = installed.get(player.getUniqueId());
    if (current != null && current.view().equals(view)) {
      return;
    }
    LocaleManager manager = locale.get();
    if (manager == null) {
      return;
    }
    List<ItemStack> items = build(manager, view);
    installed.put(
        player.getUniqueId(), new Installed(view, items, new HotbarRewriter<>(items, () -> 0)));
    player.updateInventory();
  }

  /** 让看得见这名站长的玩家看到他手里选中的那一格（手持信号），而不是他背包里的真实物品；新进入视野的玩家下一次刷新就看到。 */
  void showHand(Player player) {
    Installed current = installed.get(player.getUniqueId());
    if (current != null) {
      showHand(player, current.items().get(player.getInventory().getHeldItemSlot()));
    }
  }

  private static void showHand(Player player, ItemStack item) {
    for (Player viewer : player.getTrackedBy()) {
      viewer.sendEquipmentChange(player, EquipmentSlot.HAND, item);
    }
  }

  /** 撤下快捷栏，换回玩家自己的物品（主线程）。 */
  void remove(Player player) {
    if (installed.remove(player.getUniqueId()) != null) {
      player.updateInventory();
      showHand(player, player.getInventory().getItemInMainHand());
    }
    lastUseMillis.remove(player.getUniqueId());
    menus.remove(player.getUniqueId());
  }

  /** 撤下全部（插件停用）。 */
  void removeAll() {
    for (UUID id : List.copyOf(installed.keySet())) {
      Player online = Bukkit.getPlayer(id);
      if (online != null) {
        remove(online);
      } else {
        installed.remove(id);
      }
    }
  }

  @Override
  public boolean isOnDuty(UUID playerId) {
    return playerId != null && installed.containsKey(playerId);
  }

  @Override
  public Optional<HotbarRewriter<ItemStack>> rewriterFor(UUID playerId) {
    return Optional.ofNullable(playerId).map(installed::get).map(Installed::rewriter);
  }

  @Override
  public int menuTopSize(UUID playerId) {
    return playerId == null ? 0 : menus.getOrDefault(playerId, 0);
  }

  @Override
  public boolean allowsBlockUse(int heldSlot) {
    return Button.fromSlot(heldSlot).isEmpty();
  }

  @Override
  public void onInput(Player player, InputSignal signal) {
    switch (signal) {
      case SWAP_HANDS -> Bukkit.getScheduler().runTask(plugin, () -> openMenu.accept(player));
      case USE, USE_ON_BLOCK, USE_ENTITY -> {
        // 按下的时刻在收到数据包时按客户端时钟读。同一次右键客户端会连发几个包，按住时每 4 tick 重发：都只算一次
        long now = clock.applyAsLong(player.getUniqueId());
        Long previous = lastUseMillis.put(player.getUniqueId(), now);
        if (!UseClicks.fresh(previous, now, FRESH_PRESS_GAP_MILLIS)) {
          return;
        }
        // 选中的是哪一格留到主线程上看：切换快捷栏的数据包那时已经处理过
        Bukkit.getScheduler()
            .runTask(
                plugin,
                () ->
                    Button.fromSlot(player.getInventory().getHeldItemSlot())
                        .ifPresent(button -> pressed.accept(player, button)));
      }
      case RELEASE_USE -> lastUseMillis.remove(player.getUniqueId());
      default -> Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }
  }

  /** 按快捷栏槽位顺序构建九格。 */
  static List<ItemStack> build(LocaleManager locale, StationMasterDisplay.View view) {
    List<ItemStack> items = new ArrayList<>(9);
    for (int slot = 0; slot < 9; slot++) {
      items.add(
          Button.fromSlot(slot)
              .map(button -> build(locale, button, view))
              .orElseGet(() -> new ItemStack(Material.AIR)));
    }
    return items;
  }

  private static ItemStack build(
      LocaleManager locale, Button button, StationMasterDisplay.View view) {
    ItemStack stack = new ItemStack(materialOf(button, view));
    ItemMeta meta = stack.getItemMeta();
    String name = button.name().toLowerCase(java.util.Locale.ROOT);
    meta.displayName(
        locale
            .component("stationmaster.button." + name + (lit(button, view) ? "-on" : ""))
            .decoration(TextDecoration.ITALIC, false));
    meta.lore(
        List.of(
            locale
                .component("stationmaster.button.hint." + name)
                .decoration(TextDecoration.ITALIC, false)));
    PackModel.mark(meta, modelKey(button, view));
    if (lit(button, view)) {
      meta.setEnchantmentGlintOverride(true);
    }
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为站长按钮 " + button + " 设置物品元数据");
    }
    return stack;
  }

  /** 贴图键（{@code fetarute:} 之后的部分）。 */
  static String modelKey(Button button, StationMasterDisplay.View view) {
    if (button == Button.EMERGENCY) {
      return "drive/guard/emergency" + (lit(button, view) ? "_on" : "");
    }
    String base = "station/" + button.name().toLowerCase(java.util.Locale.ROOT);
    return lit(button, view) ? base + "_on" : base;
  }

  /** 这一格此刻亮着：当前这一列在等发车信号、扣着车、紧急停车扣着车。 */
  static boolean lit(Button button, StationMasterDisplay.View view) {
    return switch (button) {
      case SIGNAL -> view.waiting();
      case HOLD -> view.holding();
      case EMERGENCY -> view.emergency();
      case CLOSE -> false;
    };
  }

  /** 没装材质包时的染料。 */
  static Material materialOf(Button button, StationMasterDisplay.View view) {
    return switch (button) {
      case SIGNAL -> view.waiting() ? Material.LIME_DYE : Material.GREEN_DYE;
      case HOLD -> view.holding() ? Material.ORANGE_DYE : Material.GRAY_DYE;
      case CLOSE -> Material.YELLOW_DYE;
      case EMERGENCY -> Material.RED_DYE;
    };
  }
}
