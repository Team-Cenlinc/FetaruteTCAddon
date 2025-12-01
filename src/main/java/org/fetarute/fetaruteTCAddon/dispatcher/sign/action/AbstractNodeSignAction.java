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

/**
 * 统一的节点牌子逻辑：匹配头、解析节点并在列车经过时写入 destination。
 */
abstract class AbstractNodeSignAction extends SignAction {

    private final String header;
    protected final SignNodeRegistry registry;
    private final NodeType nodeType;

    AbstractNodeSignAction(String header, SignNodeRegistry registry, NodeType nodeType) {
        this.header = header;
        this.registry = registry;
        this.nodeType = nodeType;
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
            if (event.getPlayer() != null) {
                event.getPlayer().sendMessage("§b[FTA] 已注册节点: §f" + definition.get().nodeId().value());
            }
        } else if (event.getPlayer() != null) {
            event.getPlayer().sendMessage("§c[FTA] 节点格式无效，使用: §fOperator:From:To:Track:Seq");
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
        registry.get(info.getBlock())
                .flatMap(SignNodeDefinition::trainCartsDestination)
                .ifPresent(destination -> info.getGroup().getProperties().setDestination(destination));
    }

    @Override
    public void destroy(SignActionEvent event) {
        // 删除牌子时清理注册表，避免陈旧目标干扰路由
        registry.remove(event.getBlock());
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
}
