package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * 普通 waypoint 牌子，解析区间节点并传递 destination。
 */
public final class WaypointSignAction extends AbstractNodeSignAction {

    public WaypointSignAction(SignNodeRegistry registry, Consumer<String> debugLogger, LocaleManager locale) {
        super("waypoint", registry, NodeType.WAYPOINT, debugLogger, locale);
    }

    @Override
    protected Optional<SignNodeDefinition> parseDefinition(SignActionEvent info) {
        return super.parseDefinition(info)
                .filter(definition -> definition.waypointMetadata()
                        .map(metadata -> metadata.kind() == WaypointKind.INTERVAL)
                        .orElse(false));
    }
}
