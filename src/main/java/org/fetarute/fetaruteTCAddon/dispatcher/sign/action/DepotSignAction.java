package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;

import java.util.Optional;

/**
 * Depot throat 牌子，写入 Depot 节点并把 destination 提供给 TC。
 */
public final class DepotSignAction extends AbstractNodeSignAction {

    public DepotSignAction(SignNodeRegistry registry) {
        super("depot", registry, NodeType.DEPOT);
    }

    @Override
    protected Optional<SignNodeDefinition> parseDefinition(SignActionEvent info) {
        return super.parseDefinition(info)
                .filter(definition -> definition.waypointMetadata()
                        .map(metadata -> metadata.kind() == WaypointKind.DEPOT)
                        .orElse(false));
    }
}
