package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.SignChangeActionEvent;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.util.Objects;
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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.NodeSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeStorageSynchronizer;
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
  private final SignNodeStorageSynchronizer storageSync;

  AbstractNodeSignAction(
      String header,
      SignNodeRegistry registry,
      NodeType nodeType,
      Consumer<String> debugLogger,
      LocaleManager locale,
      SignNodeStorageSynchronizer storageSync) {
    this.header = header;
    this.registry = registry;
    this.nodeType = nodeType;
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.locale = locale;
    this.storageSync = storageSync != null ? storageSync : SignNodeStorageSynchronizer.noop();
  }

  /** 输出调试日志（由调用方决定是否启用）。 */
  protected void debug(String message) {
    if (message == null || message.isBlank()) {
      return;
    }
    debugLogger.accept(message);
  }

  AbstractNodeSignAction(
      String header,
      SignNodeRegistry registry,
      NodeType nodeType,
      Consumer<String> debugLogger,
      LocaleManager locale) {
    this(header, registry, nodeType, debugLogger, locale, SignNodeStorageSynchronizer.noop());
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
    // 防止重复注册：如果方块已在注册表中，跳过（可能是 TC loadSign 触发）
    if (registry.get(event.getBlock()).isPresent()) {
      return true;
    }
    // 仅在建牌阶段写入注册表，后续执行阶段直接复用解析结果。
    Optional<SignNodeDefinition> definition = parseDefinition(event);
    if (definition.isPresent()) {
      // 同一个 NodeId 不允许被多个方块占用：否则调度层/诊断会出现“同名不同位”的歧义。
      Optional<SignNodeRegistry.SignNodeInfo> conflict =
          registry.findByNodeId(definition.get().nodeId(), event.getBlock());
      if (conflict.isPresent()) {
        World currentWorld = event.getBlock().getWorld();
        if (tryRepairStaleConflict(currentWorld, conflict.get())) {
          conflict = registry.findByNodeId(definition.get().nodeId(), event.getBlock());
        }
      }
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
        // 冲突牌子必须拒绝写入：否则牌子会留在世界里但不在注册表/存储中，后续“拆旧再放新”会出现幽灵冲突行为。
        event.setCancelled(true);
        return true;
      }
      registry.put(event.getBlock(), definition.get());
      storageSync.upsert(event.getBlock(), definition.get());
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

  /**
   * 尝试在“节点 ID 冲突”时自愈：若注册表中记录的旧位置已不存在节点牌子，则清理陈旧记录并同步到存储。
   *
   * <p>常见触发场景：WorldEdit/setblock 移除牌子、插件异常导致 destroy/break 未执行等。
   *
   * <p>注意：不会主动加载区块；仅在冲突位置所在 chunk 已加载时进行验证与修复，避免卡服。
   *
   * @return 若检测到陈旧记录并完成清理/修复则返回 true
   */
  private boolean tryRepairStaleConflict(
      World currentWorld, SignNodeRegistry.SignNodeInfo conflict) {
    if (currentWorld == null || conflict == null) {
      return false;
    }
    if (!currentWorld.getUID().equals(conflict.worldId())) {
      return false;
    }

    int chunkX = conflict.x() >> 4;
    int chunkZ = conflict.z() >> 4;
    if (!currentWorld.isChunkLoaded(chunkX, chunkZ)) {
      return false;
    }

    Block conflictBlock = currentWorld.getBlockAt(conflict.x(), conflict.y(), conflict.z());
    BlockState state = conflictBlock.getState();
    if (!(state instanceof Sign sign)) {
      // 兼容“图构建/快照”写入的节点坐标并非牌子方块坐标的历史数据：尝试在邻域内找到真实的节点牌子并修复坐标。
      java.util.Optional<Block> repaired =
          findNearbyNodeSignByNodeId(
              currentWorld,
              conflict.definition().nodeId(),
              conflict.x(),
              conflict.y(),
              conflict.z(),
              6,
              4);
      if (repaired.isPresent()) {
        Block signBlock = repaired.get();
        BlockState repairedState = signBlock.getState();
        if (repairedState instanceof Sign repairedSign) {
          Optional<SignNodeDefinition> actualOpt = NodeSignDefinitionParser.parse(repairedSign);
          if (actualOpt.isPresent()
              && actualOpt.get().nodeId().equals(conflict.definition().nodeId())) {
            registry.remove(conflictBlock);
            registry.put(signBlock, actualOpt.get());
            storageSync.upsert(signBlock, actualOpt.get());
            debugLogger.accept(
                "修复节点注册(纠正坐标): node="
                    + conflict.definition().nodeId().value()
                    + " from="
                    + conflict.locationText()
                    + " to="
                    + signBlock.getLocation());
            return false;
          }
        }
      }
      cleanupStaleConflict(conflictBlock, conflict.definition(), "方块已不存在或不再是牌子");
      return true;
    }

    Optional<SignNodeDefinition> actualOpt = NodeSignDefinitionParser.parse(sign);
    if (actualOpt.isEmpty()) {
      cleanupStaleConflict(conflictBlock, conflict.definition(), "牌子不再是节点牌子");
      return true;
    }

    SignNodeDefinition actual = actualOpt.get();
    if (actual.nodeId().equals(conflict.definition().nodeId())) {
      // 冲突真实存在（仍占用同一个 NodeId）。顺手修复 definition 的其他差异，避免诊断输出与存储不一致。
      if (!actual.equals(conflict.definition())) {
        registry.put(conflictBlock, actual);
        storageSync.upsert(conflictBlock, actual);
        debugLogger.accept(
            "修复节点注册(同 ID 不同定义): node=" + actual.nodeId().value() + " @ " + conflict.locationText());
      }
      return false;
    }

    // 旧位置仍是节点牌子，但 nodeId 已改变：修复 registry + 存储，解除对旧 nodeId 的占用。
    registry.put(conflictBlock, actual);
    storageSync.upsert(conflictBlock, actual);
    debugLogger.accept(
        "修复节点注册(节点已改名): old="
            + conflict.definition().nodeId().value()
            + " new="
            + actual.nodeId().value()
            + " @ "
            + conflict.locationText());
    return true;
  }

  private static java.util.Optional<Block> findNearbyNodeSignByNodeId(
      World world, NodeId nodeId, int baseX, int baseY, int baseZ, int radiusXZ, int radiusY) {
    if (world == null || nodeId == null) {
      return java.util.Optional.empty();
    }
    if (radiusXZ <= 0 || radiusY < 0) {
      return java.util.Optional.empty();
    }

    for (int dy = -radiusY; dy <= radiusY; dy++) {
      int y = baseY + dy;
      for (int dx = -radiusXZ; dx <= radiusXZ; dx++) {
        int x = baseX + dx;
        for (int dz = -radiusXZ; dz <= radiusXZ; dz++) {
          int z = baseZ + dz;
          if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            continue;
          }
          BlockState state = world.getBlockAt(x, y, z).getState();
          if (!(state instanceof Sign sign)) {
            continue;
          }
          Optional<SignNodeDefinition> parsed = NodeSignDefinitionParser.parse(sign);
          if (parsed.isPresent() && nodeId.equals(parsed.get().nodeId())) {
            return java.util.Optional.of(sign.getBlock());
          }
        }
      }
    }
    return java.util.Optional.empty();
  }

  private void cleanupStaleConflict(Block conflictBlock, SignNodeDefinition stale, String reason) {
    Objects.requireNonNull(conflictBlock, "conflictBlock");
    Objects.requireNonNull(stale, "stale");
    reason = reason == null ? "" : reason;

    registry.remove(conflictBlock);
    storageSync.delete(conflictBlock, stale);
    debugLogger.accept(
        "清理陈旧节点注册: reason="
            + reason
            + " node="
            + stale.nodeId().value()
            + " @ "
            + conflictBlock.getLocation());
  }

  @Override
  public void execute(SignActionEvent info) {
    if (!info.isAction(SignActionType.MEMBER_ENTER)) {
      return;
    }
    if (!info.hasGroup()) {
      return;
    }
    // 仅在车头触发时记录，避免多节车厢重复输出
    if (!isHeadMember(info)) {
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

  /** 判断事件是否由车头触发。 */
  private boolean isHeadMember(SignActionEvent event) {
    MinecartGroup group = event.getGroup();
    if (group == null) {
      return false;
    }
    MinecartMember<?> member = event.getMember();
    if (member == null) {
      return false;
    }
    return group.head() == member;
  }

  @Override
  public void destroy(SignActionEvent event) {
    // 删除牌子时清理注册表，避免陈旧目标干扰路由。
    var definitionOpt = registry.remove(event.getBlock());
    if (definitionOpt.isEmpty()) {
      definitionOpt = parseDefinition(event);
    }
    if (definitionOpt.isEmpty()) {
      return;
    }
    storageSync.delete(event.getBlock(), definitionOpt.get());
    debugLogger.accept("销毁 " + nodeType + " 节点牌子 @ " + formatLocation(event));
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
