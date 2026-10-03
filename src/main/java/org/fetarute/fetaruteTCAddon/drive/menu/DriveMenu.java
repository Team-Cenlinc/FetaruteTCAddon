package org.fetarute.fetaruteTCAddon.drive.menu;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;
import org.fetarute.fetaruteTCAddon.drive.cab.AirSystem;
import org.fetarute.fetaruteTCAddon.drive.cab.BrakeTest;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.dynamics.ReverserPosition;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupSystem;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetup;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 停车后菜单：打开、按会话状态刷新按钮。
 *
 * <p>菜单是虚拟界面，物品不会进入玩家背包；点击由事件监听取消后交给会话管理器处理。
 */
public final class DriveMenu {

  private static final long TICKS_PER_SECOND = 20L;

  private final LocaleManager locale;

  public DriveMenu(LocaleManager locale) {
    this.locale = Objects.requireNonNull(locale, "locale");
  }

  /**
   * 给玩家打开菜单。
   *
   * @return 是否已打开；被其它插件取消时为 {@code false}
   */
  public boolean open(Player player, DriveSession session) {
    DriveMenuHolder holder = new DriveMenuHolder(player.getUniqueId());
    Inventory inventory =
        Bukkit.createInventory(holder, MenuLayout.SIZE, locale.component("drive.menu.title"));
    holder.bind(inventory);
    render(inventory, session);
    // 先告诉数据包层菜单窗口有多大，这样打开后的第一批背包数据包里的快捷栏就会被改写。
    session.setMenuTopSize(MenuLayout.SIZE);
    if (player.openInventory(inventory) == null) {
      session.setMenuTopSize(0);
      return false;
    }
    return true;
  }

  /** 按会话状态刷新全部按钮。 */
  public void render(Inventory inventory, DriveSession session) {
    long now = Bukkit.getCurrentTick();
    for (MenuAction action : MenuAction.values()) {
      ButtonView view = viewOf(action, session, now);
      inventory.setItem(
          MenuLayout.slotOf(action), view == null ? null : DriveMenuItems.build(locale, view));
    }
  }

  /** 菜单是不是驾驶台菜单。 */
  public static boolean isMenu(Inventory inventory) {
    return inventory != null && inventory.getHolder() instanceof DriveMenuHolder;
  }

  /** 按钮此刻的样子；该等级或该受电方式下没有这个按钮时为 {@code null}。 */
  private static ButtonView viewOf(MenuAction action, DriveSession session, long now) {
    TrainSetup setup = session.setup();
    SimulationLevel.SetupMode mode = session.setupMode();
    Optional<SetupSystem> system = action.system();
    if (system.isPresent()) {
      if (!setup.applies(system.get())) {
        return null;
      }
      TrainSetup.State state = setup.state(system.get());
      boolean busy = state == TrainSetup.State.STARTING;
      return new ButtonView(
          action,
          state == TrainSetup.State.ON,
          busy,
          setup.supply(),
          busy ? remainingSeconds(setup, now) : -1,
          mode == SimulationLevel.SetupMode.MANUAL);
    }
    CabSystems cab = session.cab();
    switch (action) {
      case COMPRESSOR, PARKING_BRAKE, BRAKE_TEST -> {
        return cab.enabled() ? cabView(action, cab, setup) : null;
      }
      default -> {}
    }
    if (action == MenuAction.START) {
      if (mode != SimulationLevel.SetupMode.ONE_CLICK) {
        return null;
      }
      boolean busy = setup.busy();
      return new ButtonView(
          action,
          setup.ready(),
          busy,
          setup.supply(),
          busy ? remainingSeconds(setup, now) : -1,
          true);
    }
    return ButtonView.simple(action, isActive(action, session));
  }

  /** simulation 级的压缩机、停放制动与制动试验按钮。 */
  private static ButtonView cabView(MenuAction action, CabSystems cab, TrainSetup setup) {
    AirSystem air = cab.air();
    String reservoir = String.valueOf(Math.round(air.mainReservoirKpa()));
    return switch (action) {
      case COMPRESSOR -> new ButtonView(
          action,
          air.manualCompressor() ? air.compressorSwitch() : air.compressorRunning(),
          false,
          setup.supply(),
          -1,
          air.manualCompressor(),
          "drive.menu.detail.reservoir",
          Map.of("mr", reservoir));
      case PARKING_BRAKE -> new ButtonView(
          action,
          !air.parkingApplied(),
          false,
          setup.supply(),
          -1,
          true,
          air.parkingApplied() ? "drive.menu.detail.parking-needs" : null,
          Map.of(
              "need",
              String.valueOf(Math.round(cab.config().parkingReleaseKpa())),
              "mr",
              reservoir));
      default -> {
        BrakeTest test = cab.brakeTest();
        yield new ButtonView(
            action,
            test.passed(),
            test.inProgress(),
            setup.supply(),
            -1,
            true,
            "drive.menu.detail.brake-test-"
                + test.stage().name().toLowerCase(Locale.ROOT).replace('_', '-'),
            Map.of("bc", String.valueOf(Math.round(air.brakeCylinderKpa()))));
      }
    };
  }

  private static long remainingSeconds(TrainSetup setup, long now) {
    return setup
        .progress(now)
        .map(progress -> (progress.remainingTicks() + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND)
        .orElse(-1L);
  }

  private static boolean isActive(MenuAction action, DriveSession session) {
    return switch (action) {
      case REVERSER_FORWARD -> session.reverser() == ReverserPosition.FORWARD;
      case REVERSER_NEUTRAL -> session.reverser() == ReverserPosition.NEUTRAL;
      case REVERSER_REVERSE -> session.reverser() == ReverserPosition.REVERSE;
      case DOOR_LEFT -> session.isLeftDoorOpen();
      case DOOR_RIGHT -> session.isRightDoorOpen();
      default -> false;
    };
  }
}
