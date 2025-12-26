package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;

/**
 * 从已加载区块扫描节点牌子（waypoint/autostation/depot），产出可持久化的 RailNodeRecord 列表。
 *
 * <p>注意：该扫描不会主动加载区块，因此“未加载区域”的节点不会被发现。建议配合预加载工具（如 Chunky）或在运营低峰期加载线路区域后再执行。
 */
public final class LoadedChunkNodeScanner {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private final Consumer<String> debugLogger;

  public LoadedChunkNodeScanner(Consumer<String> debugLogger) {
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  public List<RailNodeRecord> scan(World world) {
    Objects.requireNonNull(world, "world");
    UUID worldId = world.getUID();

    Map<String, RailNodeRecord> byNodeId = new HashMap<>();
    int scannedSigns = 0;

    for (Chunk chunk : world.getLoadedChunks()) {
      if (chunk == null) {
        continue;
      }
      for (BlockState state : chunk.getTileEntities()) {
        if (!(state instanceof Sign sign)) {
          continue;
        }
        scannedSigns++;
        Optional<SignNodeDefinition> defOpt = parseNodeSign(sign);
        if (defOpt.isEmpty()) {
          continue;
        }
        SignNodeDefinition def = defOpt.get();
        int x = sign.getLocation().getBlockX();
        int y = sign.getLocation().getBlockY();
        int z = sign.getLocation().getBlockZ();
        RailNodeRecord record =
            new RailNodeRecord(
                worldId,
                def.nodeId(),
                def.nodeType(),
                x,
                y,
                z,
                def.trainCartsDestination(),
                def.waypointMetadata());
        RailNodeRecord existing = byNodeId.putIfAbsent(def.nodeId().value(), record);
        if (existing != null) {
          debugLogger.accept(
              "扫描到重复 nodeId，已忽略: node="
                  + def.nodeId().value()
                  + " @ "
                  + world.getName()
                  + " ("
                  + x
                  + ","
                  + y
                  + ","
                  + z
                  + "), existing=("
                  + existing.x()
                  + ","
                  + existing.y()
                  + ","
                  + existing.z()
                  + ")");
        }
      }
    }

    debugLogger.accept(
        "节点扫描完成: world="
            + world.getName()
            + " loadedChunks="
            + world.getLoadedChunks().length
            + " scannedSigns="
            + scannedSigns
            + " nodes="
            + byNodeId.size());
    return List.copyOf(byNodeId.values());
  }

  private Optional<SignNodeDefinition> parseNodeSign(Sign sign) {
    SignSide front = sign.getSide(Side.FRONT);
    String header = PLAIN_TEXT.serialize(front.line(1)).trim().toLowerCase(java.util.Locale.ROOT);
    NodeType nodeType;
    EnumSet<WaypointKind> expectedKinds;
    switch (header) {
      case "waypoint" -> {
        nodeType = NodeType.WAYPOINT;
        expectedKinds =
            EnumSet.of(
                WaypointKind.INTERVAL, WaypointKind.STATION_THROAT, WaypointKind.DEPOT_THROAT);
      }
      case "autostation" -> {
        nodeType = NodeType.STATION;
        expectedKinds = EnumSet.of(WaypointKind.STATION);
      }
      case "depot" -> {
        nodeType = NodeType.DEPOT;
        expectedKinds = EnumSet.of(WaypointKind.DEPOT);
      }
      default -> {
        return Optional.empty();
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
}
