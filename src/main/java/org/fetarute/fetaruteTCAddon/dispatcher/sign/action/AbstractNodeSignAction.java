package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.SignChangeActionEvent;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

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
    private final LocaleManager locale;

    AbstractNodeSignAction(String header, SignNodeRegistry registry, NodeType nodeType, Consumer<String> debugLogger,
                           LocaleManager locale) {
        this.header = header;
        this.registry = registry;
        this.nodeType = nodeType;
        this.debugLogger = debugLogger != null ? debugLogger : message -> {
        };
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
        // 仅在建牌阶段写入注册表，后续执行阶段直接复用解析结果
        Optional<SignNodeDefinition> definition = parseDefinition(event);
        if (definition.isPresent()) {
            registry.put(event.getBlock(), definition.get());
            debugLogger.accept("注册 " + nodeType + " 节点 " + definition.get().nodeId().value()
                    + " @ " + formatLocation(event));
            if (event.getPlayer() != null && locale != null) {
                event.getPlayer().sendMessage(locale.component(
                        "sign.created", java.util.Map.of("node", definition.get().nodeId().value())));
            }
        } else if (event.getPlayer() != null && locale != null) {
            event.getPlayer().sendMessage(locale.component("sign.invalid"));
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
        // 仅记录触发，不再改写 TrainCarts destination，保持调度层与 TC 路由解耦
        registry.get(info.getBlock())
                .ifPresent(definition -> debugLogger.accept("触发节点 " + definition.nodeId().value()
                        + " @ " + formatLocation(info)));
    }

    @Override
    public void destroy(SignActionEvent event) {
        // 删除牌子时清理注册表，避免陈旧目标干扰路由
        registry.remove(event.getBlock())
                .ifPresent(definition -> debugLogger.accept("销毁 " + nodeType + " 节点牌子 @ " + formatLocation(event)));
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
