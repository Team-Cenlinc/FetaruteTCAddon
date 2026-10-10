package org.fetarute.fetaruteTCAddon.call;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 叫车对话框：按方向列出能叫的车，可叫的按钮写约几分钟到站，叫不了的灰字、点了在聊天栏说原因。
 *
 * <p>回调只捕获插件与动作本身，执行时回到主线程、现取当前的叫车服务（重载后照样能用）。
 */
public final class CallDialog {

  private static final int BODY_WIDTH = 360;
  private static final int BUTTON_WIDTH = 360;
  private static final int MAX_OPTIONS = 12;
  private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(5);
  private static final Duration CANCEL_LIFETIME = Duration.ofMinutes(15);

  private CallDialog() {}

  /**
   * 打开叫车对话框。
   *
   * @param plugin 插件
   * @param player 玩家
   * @param station 车站
   * @param platforms 屏幕绑定的站台；空表示全站
   */
  public static void open(
      FetaruteTCAddon plugin, Player player, PidsStationKey station, Set<String> platforms) {
    open(plugin, player, station, platforms, false);
  }

  /**
   * 同上。
   *
   * @param quietWhenNone 本站没有能叫的车时不提示（右键站台屏：大多数车站不开放叫车，不能每点一下都说一句）
   */
  public static void open(
      FetaruteTCAddon plugin,
      Player player,
      PidsStationKey station,
      Set<String> platforms,
      boolean quietWhenNone) {
    Optional<CallService> serviceOpt = plugin.getCallService();
    LocaleManager locale = plugin.getLocaleManager();
    if (serviceOpt.isEmpty()) {
      player.sendMessage(locale.component("call.error"));
      return;
    }
    CallService service = serviceOpt.get();
    String stationName = service.stationName(station);
    List<CallService.CallOption> options =
        service
            .options(station, platforms, Optional.of(player.getUniqueId()), Instant.now())
            .stream()
            .filter(option -> option.verdict().outcome() != CallRules.Outcome.NO_SOURCE)
            .limit(MAX_OPTIONS)
            .toList();
    if (options.isEmpty()) {
      if (!quietWhenNone) {
        player.sendMessage(locale.component("call.none", Map.of("station", stationName)));
      }
      return;
    }
    Set<String> shownPlatforms = new TreeSet<>();
    for (CallService.CallOption option : options) {
      shownPlatforms.add(option.direction().platformLabel());
    }
    boolean platformPrefix = shownPlatforms.size() > 1;
    List<ActionButton> buttons = new ArrayList<>(options.size());
    for (CallService.CallOption option : options) {
      buttons.add(button(plugin, locale, service, station, platforms, option, platformPrefix));
    }
    Component title =
        platforms.isEmpty()
            ? locale.component("call.dialog.title", Map.of("station", stationName))
            : locale.component(
                "call.dialog.title-platforms",
                Map.of(
                    "station",
                    stationName,
                    "platforms",
                    String.join("/", new TreeSet<>(platforms))));
    ActionButton close =
        ActionButton.create(locale.component("call.dialog.close"), null, BUTTON_WIDTH, null);
    Dialog dialog =
        Dialog.create(
            factory ->
                factory
                    .empty()
                    .base(
                        DialogBase.builder(title)
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(
                                List.of(
                                    DialogBody.plainMessage(
                                        locale.component("call.dialog.body"), BODY_WIDTH)))
                            .build())
                    .type(DialogType.multiAction(buttons).exitAction(close).columns(1).build()));
    player.showDialog(dialog);
  }

  private static ActionButton button(
      FetaruteTCAddon plugin,
      LocaleManager locale,
      CallService service,
      PidsStationKey station,
      Set<String> platforms,
      CallService.CallOption option,
      boolean platformPrefix) {
    Map<String, String> placeholders =
        new HashMap<>(service.directionPlaceholders(option.direction()));
    Component prefix =
        platformPrefix && !option.direction().platformLabel().isEmpty()
            ? locale.component("call.dialog.platform-prefix", placeholders)
            : Component.empty();
    String key = option.direction().key();
    if (option.verdict().callable()) {
      OptionalInt minutes = etaMinutes(option);
      minutes.ifPresent(m -> placeholders.put("minutes", String.valueOf(m)));
      Component label =
          prefix.append(
              locale.component(
                  minutes.isPresent() ? "call.dialog.option" : "call.dialog.option-no-eta",
                  placeholders));
      DialogAction action =
          callback(
              plugin,
              player -> {
                Optional<CallService> current = plugin.getCallService();
                if (current.isEmpty()) {
                  player.sendMessage(plugin.getLocaleManager().component("call.error"));
                  return;
                }
                handleCall(plugin, current.get(), player, station, platforms, key);
              });
      return ActionButton.create(label, null, BUTTON_WIDTH, action);
    }
    Component reason = reason(locale, option.verdict());
    Component label =
        prefix.append(locale.component("call.dialog.option-unavailable", placeholders));
    Map<String, String> unavailable = new HashMap<>(placeholders);
    DialogAction action =
        callback(
            plugin,
            player ->
                player.sendMessage(
                    plugin
                        .getLocaleManager()
                        .component(
                            "call.unavailable",
                            withReason(
                                unavailable,
                                reason(plugin.getLocaleManager(), option.verdict())))));
    return ActionButton.create(label, reason, BUTTON_WIDTH, action);
  }

  private static OptionalInt etaMinutes(CallService.CallOption option) {
    return option.plan().map(CallService.CallPlan::etaMinutes).orElse(OptionalInt.empty());
  }

  /** 点了能叫的按钮：再判一次（对话框开着的这段时间情况可能变了），出票、发回执。 */
  static void handleCall(
      FetaruteTCAddon plugin,
      CallService service,
      Player player,
      PidsStationKey station,
      Set<String> platforms,
      String directionKey) {
    LocaleManager locale = plugin.getLocaleManager();
    CallService.CallResult result = service.call(player, station, platforms, directionKey);
    switch (result.outcome()) {
      case ISSUED -> {
        CallService.CallOption option = result.option().orElseThrow();
        Map<String, String> placeholders =
            new HashMap<>(service.directionPlaceholders(option.direction()));
        OptionalInt minutes = etaMinutes(option);
        minutes.ifPresent(m -> placeholders.put("minutes", String.valueOf(m)));
        Component receipt =
            locale.component(
                minutes.isPresent() ? "call.issued" : "call.issued-no-eta", placeholders);
        UUID callId = result.callId().orElseThrow();
        Component cancel =
            locale
                .component("call.cancel-button")
                .hoverEvent(HoverEvent.showText(locale.component("call.cancel-hover")))
                .clickEvent(
                    ClickEvent.callback(
                        audience -> {
                          if (audience instanceof Player clicker) {
                            Bukkit.getScheduler()
                                .runTask(plugin, () -> handleCancel(plugin, clicker, callId));
                          }
                        },
                        ClickCallback.Options.builder().uses(1).lifetime(CANCEL_LIFETIME).build()));
        player.sendMessage(receipt.append(Component.space()).append(cancel));
      }
      case UNAVAILABLE -> {
        CallService.CallOption option = result.option().orElseThrow();
        Map<String, String> placeholders =
            new HashMap<>(service.directionPlaceholders(option.direction()));
        player.sendMessage(
            locale.component(
                "call.unavailable", withReason(placeholders, reason(locale, option.verdict()))));
      }
      case NOT_FOUND -> player.sendMessage(locale.component("call.not-found"));
      case FAILED -> player.sendMessage(locale.component("call.error"));
    }
  }

  private static void handleCancel(FetaruteTCAddon plugin, Player player, UUID callId) {
    LocaleManager locale = plugin.getLocaleManager();
    Optional<CallService> service = plugin.getCallService();
    if (service.isEmpty()) {
      player.sendMessage(locale.component("call.error"));
      return;
    }
    switch (service.get().cancel(player.getUniqueId(), callId)) {
      case CANCELLED -> player.sendMessage(locale.component("call.cancelled"));
      case ALREADY_DEPARTED, NOT_FOUND -> player.sendMessage(
          locale.component("call.cancel-too-late"));
    }
  }

  /** 叫不了的原因。 */
  static Component reason(LocaleManager locale, CallRules.Verdict verdict) {
    String minutes = String.valueOf(verdict.minutes().orElse(0));
    return switch (verdict.outcome()) {
      case NEXT_TRAIN_SOON -> locale.component(
          "call.reason.next-train", Map.of("minutes", minutes));
      case ALREADY_CALLED -> locale.component(
          "call.reason.already-called", Map.of("minutes", minutes));
      case LINE_LIMIT -> locale.component("call.reason.line-limit");
      case COOLDOWN -> locale.component(
          "call.reason.cooldown", Map.of("seconds", String.valueOf(verdict.seconds())));
      case NO_SOURCE -> locale.component("call.reason.no-source");
      case AVAILABLE -> Component.empty();
    };
  }

  private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver withReason(
      Map<String, String> placeholders, Component reason) {
    List<net.kyori.adventure.text.minimessage.tag.resolver.TagResolver> resolvers =
        new ArrayList<>();
    placeholders.forEach(
        (name, value) ->
            resolvers.add(
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                    name, value)));
    resolvers.add(
        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("reason", reason));
    return net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(resolvers);
  }

  private static DialogAction callback(FetaruteTCAddon plugin, Consumer<Player> action) {
    return DialogAction.customClick(
        (response, audience) -> {
          if (audience instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> action.accept(player));
          }
        },
        ClickCallback.Options.builder().uses(1).lifetime(CALLBACK_LIFETIME).build());
  }
}
