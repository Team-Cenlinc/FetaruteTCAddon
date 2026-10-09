package org.fetarute.fetaruteTCAddon.drive.guard;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/** 异常情况报告的对话框：选原因，当前这一步的时限延长一次。 */
final class GuardReportDialog {

  private static final int BUTTON_WIDTH = 200;
  private static final int BODY_WIDTH = 240;
  private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(2);

  private GuardReportDialog() {}

  static void open(
      Plugin plugin, LocaleManager locale, Player player, GuardSessionManager manager) {
    List<ActionButton> buttons = new ArrayList<>();
    for (GuardSessionManager.IncidentReason reason : GuardSessionManager.IncidentReason.values()) {
      DialogAction action =
          DialogAction.customClick(
              (response, audience) -> {
                if (audience instanceof Player clicker) {
                  Bukkit.getScheduler().runTask(plugin, () -> manager.report(clicker, reason));
                }
              },
              ClickCallback.Options.builder().uses(1).lifetime(CALLBACK_LIFETIME).build());
      buttons.add(
          ActionButton.create(
              locale.component(
                  "drive.guard.report.reason." + reason.name().toLowerCase(Locale.ROOT)),
              null,
              BUTTON_WIDTH,
              action));
    }
    ActionButton close =
        ActionButton.create(
            locale.component("drive.guard.report.dialog.cancel"), null, BUTTON_WIDTH, null);
    Dialog dialog =
        Dialog.create(
            factory ->
                factory
                    .empty()
                    .base(
                        DialogBase.builder(locale.component("drive.guard.report.dialog.title"))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(
                                List.of(
                                    DialogBody.plainMessage(
                                        locale.component("drive.guard.report.dialog.body"),
                                        BODY_WIDTH)))
                            .build())
                    .type(DialogType.multiAction(buttons).exitAction(close).columns(1).build()));
    player.showDialog(dialog);
  }
}
