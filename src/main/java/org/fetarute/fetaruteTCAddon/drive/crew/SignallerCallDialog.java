package org.fetarute.fetaruteTCAddon.drive.crew;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 呼叫信号员时选原因的对话框：驾驶员或车掌在列车停着时按两短铃（车掌也可点菜单的呼叫按钮）弹出，点一个原因就带着它呼叫。行驶中不弹，直接按一般联系呼叫，免得对话框挡住手柄与按钮。
 *
 * <p>对话框在服务器认出两短之后才打开，点选回到主线程处理；每个按钮只认一次，两分钟后失效。
 */
public final class SignallerCallDialog {

  private static final int BUTTON_WIDTH = 200;
  private static final int BODY_WIDTH = 240;
  private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(2);

  /** 语言键前缀。 */
  static final String KEYS = "drive.signaller.call-dialog";

  private SignallerCallDialog() {}

  /**
   * 原因按钮的顺序：车正在控制区入口等候时“信号前等候”排第一，其余时候排在车门故障、线路异常之后；一般联系总在最后。
   *
   * @param waitingAtEntry 列车正停在人工控制的控制区入口等信号员排列进路
   */
  static List<CallReason> order(boolean waitingAtEntry) {
    return waitingAtEntry
        ? List.of(CallReason.SIGNAL, CallReason.DOORS, CallReason.OBSTRUCTION, CallReason.GENERAL)
        : List.of(CallReason.DOORS, CallReason.OBSTRUCTION, CallReason.SIGNAL, CallReason.GENERAL);
  }

  /**
   * 弹出对话框。
   *
   * @param zone 接呼叫的控制区名（写在说明里）
   * @param waitingAtEntry 列车正停在控制区入口等候（见 {@link #order}）
   * @param chosen 点了原因之后在主线程调用（点的玩家、原因）
   */
  public static void open(
      Plugin plugin,
      LocaleManager locale,
      Player player,
      String zone,
      boolean waitingAtEntry,
      BiConsumer<Player, CallReason> chosen) {
    List<ActionButton> buttons = new ArrayList<>();
    for (CallReason reason : order(waitingAtEntry)) {
      DialogAction action =
          DialogAction.customClick(
              (response, audience) -> {
                if (audience instanceof Player clicker) {
                  Bukkit.getScheduler().runTask(plugin, () -> chosen.accept(clicker, reason));
                }
              },
              ClickCallback.Options.builder().uses(1).lifetime(CALLBACK_LIFETIME).build());
      buttons.add(
          ActionButton.create(
              locale.component(KEYS + ".reason." + reason.key()), null, BUTTON_WIDTH, action));
    }
    ActionButton close =
        ActionButton.create(locale.component(KEYS + ".cancel"), null, BUTTON_WIDTH, null);
    Dialog dialog =
        Dialog.create(
            factory ->
                factory
                    .empty()
                    .base(
                        DialogBase.builder(locale.component(KEYS + ".title"))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(
                                List.of(
                                    DialogBody.plainMessage(
                                        locale.component(KEYS + ".body", Map.of("zone", zone)),
                                        BODY_WIDTH)))
                            .build())
                    .type(DialogType.multiAction(buttons).exitAction(close).columns(1).build()));
    player.showDialog(dialog);
  }
}
