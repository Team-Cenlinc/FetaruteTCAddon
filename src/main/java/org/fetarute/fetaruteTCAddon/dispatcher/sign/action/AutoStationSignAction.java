package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * 自动站台牌子，区别于 TC 原生 Station，专注调度层节点与 waypoint 接管。
 */
public final class AutoStationSignAction extends AbstractNodeSignAction {

    public AutoStationSignAction(SignNodeRegistry registry, Consumer<String> debugLogger) {
        super("autostation", registry, NodeType.STATION, debugLogger);
    }

    @Override
    protected Optional<SignNodeDefinition> parseDefinition(SignActionEvent info) {
        return super.parseDefinition(info)
                .filter(definition -> definition.waypointMetadata()
                        .map(metadata -> metadata.kind() == WaypointKind.STATION_THROAT)
                        .orElse(false));
    }
}
