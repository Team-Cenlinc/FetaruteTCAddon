package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/** 监听牌子拆除，发送提示并清理注册表。 */
public final class SignRemoveListener implements Listener {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private final SignNodeRegistry registry;
  private final LocaleManager locale;
  private final Consumer<String> debugLogger;

  public SignRemoveListener(
      SignNodeRegistry registry, LocaleManager locale, Consumer<String> debugLogger) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.locale = Objects.requireNonNull(locale, "locale");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    if (event.isCancelled()) {
      return;
    }

    // 优先按 registry 清理：这是最可靠的“是否是我们的节点牌子”的判断。
    var removedOpt = registry.remove(event.getBlock());
    if (removedOpt.isPresent()) {
      SignNodeDefinition definition = removedOpt.get();
      event
          .getPlayer()
          .sendMessage(
              locale.component(
                  "sign.removed",
                  Map.of(
                      "node", definition.nodeId().value(), "type", localizedTypeName(definition))));
      debugLogger.accept(
          "玩家拆除节点牌子: player="
              + event.getPlayer().getName()
              + " node="
              + definition.nodeId().value()
              + " type="
              + definition.nodeType()
              + " @ "
              + event.getBlock().getLocation());
      return;
    }

    // 兜底：若 destroy() 已提前移除 registry，这里仍尝试从牌子文本解析并提示玩家。
    parseFromSignState(event.getBlock())
        .ifPresent(
            definition -> {
              event
                  .getPlayer()
                  .sendMessage(
                      locale.component(
                          "sign.removed",
                          Map.of(
                              "node",
                              definition.nodeId().value(),
                              "type",
                              localizedTypeName(definition))));
              debugLogger.accept(
                  "玩家拆除节点牌子(解析回退): player="
                      + event.getPlayer().getName()
                      + " node="
                      + definition.nodeId().value()
                      + " type="
                      + definition.nodeType()
                      + " @ "
                      + event.getBlock().getLocation());
            });
  }

  private java.util.Optional<SignNodeDefinition> parseFromSignState(org.bukkit.block.Block block) {
    BlockState state = block.getState();
    if (!(state instanceof Sign sign)) {
      return java.util.Optional.empty();
    }
    SignSide front = sign.getSide(Side.FRONT);
    String header = PLAIN_TEXT.serialize(front.line(1)).trim().toLowerCase(java.util.Locale.ROOT);
    NodeType nodeType;
    java.util.EnumSet<org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind> expectedKinds;
    switch (header) {
      case "waypoint" -> {
        nodeType = NodeType.WAYPOINT;
        expectedKinds =
            java.util.EnumSet.of(
                org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind.INTERVAL,
                org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind.STATION_THROAT,
                org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind.DEPOT_THROAT);
      }
      case "autostation" -> {
        nodeType = NodeType.STATION;
        expectedKinds =
            java.util.EnumSet.of(org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind.STATION);
      }
      case "depot" -> {
        nodeType = NodeType.DEPOT;
        expectedKinds =
            java.util.EnumSet.of(org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind.DEPOT);
      }
      default -> {
        return java.util.Optional.empty();
      }
    }

    String primary = PLAIN_TEXT.serialize(front.line(2));
    String fallback = PLAIN_TEXT.serialize(front.line(3));
    String rawId = !primary.isEmpty() ? primary : fallback;
    return SignTextParser.parseWaypointLike(rawId, nodeType)
        .filter(
            def ->
                def.waypointMetadata()
                    .map(metadata -> expectedKinds.contains(metadata.kind()))
                    .orElse(false));
  }

  private String localizedTypeName(SignNodeDefinition definition) {
    if (definition == null) {
      return "";
    }
    WaypointKind kind = definition.waypointMetadata().map(metadata -> metadata.kind()).orElse(null);
    String key =
        kind == WaypointKind.STATION_THROAT
            ? "sign.type.station_throat"
            : kind == WaypointKind.DEPOT_THROAT
                ? "sign.type.depot_throat"
                : kind == WaypointKind.STATION
                    ? "sign.type.station"
                    : kind == WaypointKind.DEPOT
                        ? "sign.type.depot"
                        : "sign.type."
                            + definition.nodeType().name().toLowerCase(java.util.Locale.ROOT);
    return PLAIN_TEXT.serialize(locale.component(key));
  }
}
