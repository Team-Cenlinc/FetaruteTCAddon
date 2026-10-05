package org.fetarute.fetaruteTCAddon.display.pids.bulletin;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 发布、修改公告的对话框：一项一个输入框，正文用多行输入框；按“发布”后把填的内容交给 {@code submit}（回到主线程执行）。
 *
 * <p>校验不通过时由调用方带着原来填的内容与问题重新打开，玩家不必重填。对话框需要 1.21.6 及以上的客户端。
 */
public final class PidsBulletinDialog {

  /** 按钮回调的有效期：对话框开着不动这么久之后按钮失效。 */
  private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(30);

  private static final int WIDTH = 320;
  private static final int BODY_LINES = 6;
  private static final int BODY_HEIGHT = 80;
  private static final int BUTTON_WIDTH = 150;

  private static final String LEVEL = "level";
  private static final String TITLE = "title";
  private static final String TITLE_SECONDARY = "title_secondary";
  private static final String BODY = "body";
  private static final String BODY_SECONDARY = "body_secondary";
  private static final String STATIONS = "stations";
  private static final String LINES = "lines";
  private static final String STARTS = "starts";
  private static final String ENDS = "ends";

  /**
   * 对话框发布到哪里。
   *
   * @param companyId 公司
   * @param companyName 公司名（显示用）
   * @param operatorCode 运营商代码
   * @param operatorName 运营商名（显示用）
   * @param editing 修改哪一条；新建时为空
   */
  public record Target(
      UUID companyId,
      String companyName,
      String operatorCode,
      String operatorName,
      Optional<UUID> editing) {

    public Target {
      Objects.requireNonNull(companyId, "companyId");
      Objects.requireNonNull(companyName, "companyName");
      Objects.requireNonNull(operatorCode, "operatorCode");
      Objects.requireNonNull(operatorName, "operatorName");
      editing = editing == null ? Optional.empty() : editing;
    }
  }

  private PidsBulletinDialog() {}

  /**
   * 打开对话框。
   *
   * @param plugin 插件（按钮回调回到主线程）
   * @param locale 文案
   * @param player 玩家
   * @param target 发布到哪里
   * @param form 预先填好的内容
   * @param problems 上一次提交的问题（已渲染好的文字）；没有时为空
   * @param submit 按“发布”后在主线程执行；回调只捕获它与目标，不捕获服务实例
   */
  public static void open(
      Plugin plugin,
      LocaleManager locale,
      Player player,
      Target target,
      PidsBulletinForm form,
      List<Component> problems,
      BiConsumer<Player, PidsBulletinForm> submit) {
    List<DialogBody> body = new ArrayList<>();
    body.add(
        DialogBody.plainMessage(
            locale.component(
                "pids.bulletin.dialog.subtitle",
                Map.of(
                    "company", target.companyName(),
                    "operator", target.operatorName(),
                    "code", target.operatorCode())),
            WIDTH));
    for (Component problem : problems) {
      body.add(DialogBody.plainMessage(problem, WIDTH));
    }
    // 对话框的输入框没有占位提示：写法示例放在各项标题里，这里再给出服务器此刻的时间，时间按服务器时区填。
    body.add(
        DialogBody.plainMessage(
            locale.component(
                "pids.bulletin.dialog.hint",
                Map.of(
                    "now",
                    PidsBulletinForm.TIME.format(
                        java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())))),
            WIDTH));

    List<DialogInput> inputs = new ArrayList<>();
    inputs.add(
        DialogInput.singleOption(
                LEVEL,
                locale.component("pids.bulletin.dialog.level"),
                List.of(
                    SingleOptionDialogInput.OptionEntry.create(
                        PidsBulletinForm.NORMAL,
                        locale.component("pids.bulletin.level.normal"),
                        !PidsBulletinForm.IMPORTANT.equals(form.level())),
                    SingleOptionDialogInput.OptionEntry.create(
                        PidsBulletinForm.IMPORTANT,
                        locale.component("pids.bulletin.level.important"),
                        PidsBulletinForm.IMPORTANT.equals(form.level()))))
            .width(WIDTH)
            .build());
    inputs.add(
        text(locale, TITLE, "title", form.titlePrimary(), PidsBulletinForm.TITLE_MAX, false));
    inputs.add(
        text(
            locale,
            TITLE_SECONDARY,
            "title-secondary",
            form.titleSecondary(),
            PidsBulletinForm.TITLE_SECONDARY_MAX,
            false));
    inputs.add(text(locale, BODY, "body", form.bodyPrimary(), PidsBulletinForm.BODY_MAX, true));
    inputs.add(
        text(
            locale,
            BODY_SECONDARY,
            "body-secondary",
            form.bodySecondary(),
            PidsBulletinForm.BODY_SECONDARY_MAX,
            true));
    inputs.add(
        text(locale, STATIONS, "stations", form.stations(), PidsBulletinForm.CODES_MAX, false));
    inputs.add(text(locale, LINES, "lines", form.lines(), PidsBulletinForm.CODES_MAX, false));
    inputs.add(text(locale, STARTS, "starts", form.startsAt(), PidsBulletinForm.TIME_MAX, false));
    inputs.add(text(locale, ENDS, "ends", form.endsAt(), PidsBulletinForm.TIME_MAX, false));

    ActionButton publish =
        ActionButton.create(
            locale.component(
                target.editing().isPresent()
                    ? "pids.bulletin.dialog.save"
                    : "pids.bulletin.dialog.publish"),
            null,
            BUTTON_WIDTH,
            DialogAction.customClick(
                (response, audience) -> {
                  if (audience instanceof Player clicked) {
                    PidsBulletinForm filled = read(response);
                    Bukkit.getScheduler().runTask(plugin, () -> submit.accept(clicked, filled));
                  }
                },
                ClickCallback.Options.builder().uses(1).lifetime(CALLBACK_LIFETIME).build()));
    ActionButton cancel =
        ActionButton.create(
            locale.component("pids.bulletin.dialog.cancel"), null, BUTTON_WIDTH, null);
    String titleKey =
        target.editing().isPresent()
            ? "pids.bulletin.dialog.title-edit"
            : "pids.bulletin.dialog.title-new";
    Dialog dialog =
        Dialog.create(
            factory ->
                factory
                    .empty()
                    .base(
                        DialogBase.builder(locale.component(titleKey))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(body)
                            .inputs(inputs)
                            .build())
                    .type(DialogType.confirmation(publish, cancel)));
    player.showDialog(dialog);
  }

  private static TextDialogInput text(
      LocaleManager locale,
      String key,
      String labelKey,
      String initial,
      int maxLength,
      boolean multiline) {
    TextDialogInput.Builder builder =
        DialogInput.text(key, locale.component("pids.bulletin.dialog.field." + labelKey))
            .width(WIDTH)
            .maxLength(maxLength)
            .initial(truncate(initial, maxLength));
    if (multiline) {
      builder.multiline(TextDialogInput.MultilineOptions.create(BODY_LINES, BODY_HEIGHT));
    }
    return builder.build();
  }

  /** 输入框的初始内容不得超过上限（客户端按字符计）。 */
  private static String truncate(String value, int maxLength) {
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }

  private static PidsBulletinForm read(DialogResponseView response) {
    return new PidsBulletinForm(
        response.getText(LEVEL),
        response.getText(TITLE),
        response.getText(TITLE_SECONDARY),
        response.getText(BODY),
        response.getText(BODY_SECONDARY),
        response.getText(STATIONS),
        response.getText(LINES),
        response.getText(STARTS),
        response.getText(ENDS));
  }
}
