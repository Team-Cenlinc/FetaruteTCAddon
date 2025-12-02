package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.SignChangeActionEvent;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * 统一的节点牌子逻辑：匹配头、解析节点并在列车经过时写入 destination。
 */
abstract class AbstractNodeSignAction extends SignAction {

    private final String header;
    protected final SignNodeRegistry registry;
    private final NodeType nodeType;
    private final Consumer<String> debugLogger;

    AbstractNodeSignAction(String header, SignNodeRegistry registry, NodeType nodeType, Consumer<String> debugLogger) {
        this.header = header;
        this.registry = registry;
        this.nodeType = nodeType;
        this.debugLogger = debugLogger != null ? debugLogger : message -> {
        };
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
        // 仅在建牌阶段写入注册表，后续执行阶段直接复用解析结果
        Optional<SignNodeDefinition> definition = parseDefinition(event);
        if (definition.isPresent()) {
            registry.put(event.getBlock(), definition.get());
            debugLogger.accept("注册 " + nodeType + " 节点 " + definition.get().nodeId().value()
                    + " @ " + formatLocation(event));
            if (event.getPlayer() != null) {
                event.getPlayer().sendMessage("§b[FTA] 已注册节点: §f" + definition.get().nodeId().value());
            }
        } else if (event.getPlayer() != null) {
            event.getPlayer().sendMessage("§c[FTA] 节点格式无效，使用: §fOperator:From:To:Track:Seq");
            debugLogger.accept("节点解析失败，原始内容: " + event.getLine(2) + " / " + event.getLine(3)
                    + " @ " + formatLocation(event));
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
        // 列车经过时把节点 ID 写入 TC destination，便于路径规划和 reroute 识别
        registry.get(info.getBlock()).ifPresent(definition ->
                definition.trainCartsDestination().ifPresent(destination -> {
                    info.getGroup().getProperties().setDestination(destination);
                    debugLogger.accept("触发节点 " + definition.nodeId().value()
                            + " @ " + formatLocation(info) + "，写入 destination=" + destination);
                })
        );
    }

    @Override
    public void destroy(SignActionEvent event) {
        // 删除牌子时清理注册表，避免陈旧目标干扰路由
        registry.remove(event.getBlock());
        debugLogger.accept("销毁 " + nodeType + " 节点牌子 @ " + formatLocation(event));
    }

    @Override
    public String getRailDestinationName(SignActionEvent event) {
        return registry.get(event.getBlock())
                .flatMap(SignNodeDefinition::trainCartsDestination)
                .orElse(null);
    }

    protected Optional<SignNodeDefinition> parseDefinition(SignActionEvent info) {
        // TC 牌子格式：第一行 [train]/[cart]，第二行为类型，节点 ID 放在第三行；若第三行为空则尝试第四行。
        String primary = info.getLine(2);
        String fallback = info.getLine(3);
        return SignTextParser.parseWaypointLike(
                primary != null && !primary.isEmpty() ? primary : fallback,
                nodeType
        );
    }

    private String formatLocation(SignActionEvent info) {
        var location = info.getBlock().getLocation();
        var world = location.getWorld();
        return (world != null ? world.getName() : "unknown")
                + " (" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ() + ")";
    }
}
