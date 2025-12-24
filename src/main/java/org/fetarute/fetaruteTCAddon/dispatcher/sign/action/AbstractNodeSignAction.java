package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.SignChangeActionEvent;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 统一的节点牌子逻辑：匹配头、解析节点并在建牌时写入注册表。
 *
 * <p>注意：本插件采用“调度层动态下发下一跳 destination”的模式，因此 execute 阶段不再改写经过列车的 destination； 牌子体系的职责是：解析/注册节点，提供
 * TrainCarts destination 名称供 TC 清缓存与诊断使用。
 */
abstract class AbstractNodeSignAction extends SignAction {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private final String header;
  protected final SignNodeRegistry registry;
  private final NodeType nodeType;
  private final Consumer<String> debugLogger;
  private final LocaleManager locale;

  AbstractNodeSignAction(
      String header,
      SignNodeRegistry registry,
      NodeType nodeType,
      Consumer<String> debugLogger,
      LocaleManager locale) {
    this.header = header;
    this.registry = registry;
    this.nodeType = nodeType;
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.locale = locale;
  }

  @Override
  public boolean match(SignActionEvent info) {
    return info.isType(header);
  }

  @Override
  public boolean build(SignChangeActionEvent event) {
    if (!event.isTrainSign() && !event.isCartSign()) {
      return false;
    }
    // 仅在建牌阶段写入注册表，后续执行阶段直接复用解析结果。
    Optional<SignNodeDefinition> definition = parseDefinition(event);
    if (definition.isPresent()) {
      // 同一个 NodeId 不允许被多个方块占用：否则调度层/诊断会出现“同名不同位”的歧义。
      Optional<SignNodeRegistry.SignNodeInfo> conflict =
          registry.findByNodeId(definition.get().nodeId(), event.getBlock());
      if (conflict.isPresent()) {
        SignNodeRegistry.SignNodeInfo conflictInfo = conflict.get();
        String playerName = event.getPlayer() == null ? "unknown" : event.getPlayer().getName();
        debugLogger.accept(
            "节点 ID 冲突: "
                + definition.get().nodeId().value()
                + " 已被 "
                + conflictInfo.definition().nodeType().name()
                + " 使用 @ "
                + conflictInfo.locationText()
                + " (player="
                + playerName
                + ")");
        if (event.getPlayer() != null && locale != null) {
          String tpCommand =
              "/tp " + conflictInfo.x() + " " + conflictInfo.y() + " " + conflictInfo.z();
          Component location =
              Component.text(conflictInfo.locationText(), NamedTextColor.WHITE)
                  .decorate(TextDecoration.BOLD)
                  .clickEvent(ClickEvent.runCommand(tpCommand))
                  .hoverEvent(
                      HoverEvent.showText(
                          locale.component(
                              "sign.conflict-hover-teleport", java.util.Map.of("cmd", tpCommand))));
          TagResolver resolver =
              TagResolver.builder()
                  .resolver(Placeholder.unparsed("node", definition.get().nodeId().value()))
                  .resolver(
                      Placeholder.unparsed("type", localizedTypeName(conflictInfo.definition())))
                  .resolver(Placeholder.component("location", location))
                  .build();
          event.getPlayer().sendMessage(locale.component("sign.conflict", resolver));
        }
        return true;
      }
      registry.put(event.getBlock(), definition.get());
      String playerName = event.getPlayer() == null ? "unknown" : event.getPlayer().getName();
      debugLogger.accept(
          "注册 "
              + nodeType
              + " 节点 "
              + definition.get().nodeId().value()
              + " @ "
              + formatLocation(event)
              + " (player="
              + playerName
              + ")");
      if (event.getPlayer() != null && locale != null) {
        event
            .getPlayer()
            .sendMessage(
                locale.component(
                    "sign.created",
                    java.util.Map.of(
                        "node",
                        definition.get().nodeId().value(),
                        "type",
                        localizedTypeName(definition.get()))));
      }
    } else if (event.getPlayer() != null && locale != null) {
      String invalidKey = "sign.invalid." + header.toLowerCase(java.util.Locale.ROOT);
      event.getPlayer().sendMessage(locale.component(invalidKey));
      debugLogger.accept(
          "节点解析失败，原始内容: "
              + event.getLine(2)
              + " / "
              + event.getLine(3)
              + " @ "
              + formatLocation(event)
              + " (player="
              + event.getPlayer().getName()
              + ", type="
              + header
              + ")");
    }
    return true;
  }

  @Override
  public void execute(SignActionEvent info) {
    if (!info.isAction(SignActionType.MEMBER_ENTER)) {
      return;
    }
    if (!info.hasGroup()) {
      return;
    }
    // 仅记录触发，不再改写 TrainCarts destination，保持调度层与 TC 路由解耦。
    registry
        .get(info.getBlock())
        .ifPresent(
            definition ->
                debugLogger.accept(
                    "触发节点 " + definition.nodeId().value() + " @ " + formatLocation(info)));
  }

  @Override
  public void destroy(SignActionEvent event) {
    // 删除牌子时清理注册表，避免陈旧目标干扰路由。
    registry
        .remove(event.getBlock())
        .ifPresent(
            definition ->
                debugLogger.accept("销毁 " + nodeType + " 节点牌子 @ " + formatLocation(event)));
  }

  @Override
  public String getRailDestinationName(SignActionEvent event) {
    // 重要：TC 在拆牌时会先读一次 destination name 再 destroy；因此 registry 不命中时必须回退解析牌子文本。
    return registry
        .get(event.getBlock())
        .flatMap(SignNodeDefinition::trainCartsDestination)
        .or(() -> parseDefinition(event).flatMap(SignNodeDefinition::trainCartsDestination))
        .orElse(null);
  }

  protected Optional<SignNodeDefinition> parseDefinition(SignActionEvent info) {
    // TC 牌子格式：第一行 [train]/[cart]，第二行为类型，节点 ID 放在第三行；若第三行为空则尝试第四行。
    String primary = info.getLine(2);
    String fallback = info.getLine(3);
    return SignTextParser.parseWaypointLike(
        primary != null && !primary.isEmpty() ? primary : fallback, nodeType);
  }

  private String formatLocation(SignActionEvent info) {
    var location = info.getBlock().getLocation();
    var world = location.getWorld();
    return (world != null ? world.getName() : "unknown")
        + " ("
        + location.getBlockX()
        + ","
        + location.getBlockY()
        + ","
        + location.getBlockZ()
        + ")";
  }

  /**
   * 将节点类型翻译为人类可读文本，用于 sign.created / sign.conflict 等提示。
   *
   * <p>语言文件中的 sign.type.* 是文本值而不是语言键；因此需要在这里解析为组件后再转为纯文本字符串填入占位符。
   */
  private String localizedTypeName(SignNodeDefinition definition) {
    if (definition == null) {
      return "";
    }
    if (locale == null) {
      return definition.nodeType().name();
    }

    String key;
    // 节点的“显示类型”优先使用 WaypointKind（更细粒度，例如站咽喉/车库咽喉）；缺失时回退到 NodeType。
    WaypointKind kind = definition.waypointMetadata().map(metadata -> metadata.kind()).orElse(null);
    if (kind == WaypointKind.STATION_THROAT) {
      key = "sign.type.station_throat";
    } else if (kind == WaypointKind.DEPOT_THROAT) {
      key = "sign.type.depot_throat";
    } else if (kind == WaypointKind.STATION) {
      key = "sign.type.station";
    } else if (kind == WaypointKind.DEPOT) {
      key = "sign.type.depot";
    } else {
      key = "sign.type." + definition.nodeType().name().toLowerCase(java.util.Locale.ROOT);
    }
    return PLAIN_TEXT.serialize(locale.component(key));
  }
}
