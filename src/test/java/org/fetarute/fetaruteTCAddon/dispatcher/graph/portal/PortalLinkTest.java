package org.fetarute.fetaruteTCAddon.dispatcher.graph.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("传送门连接")
class PortalLinkTest {

  private static final UUID OVERWORLD = UUID.randomUUID();
  private static final UUID NETHER = UUID.randomUUID();

  private static PortalLink link(String from, String to, PortalLink.Source source) {
    return new PortalLink(
        OVERWORLD, NodeId.of(from), NETHER, NodeId.of(to), source, 0.0, Instant.EPOCH);
  }

  @Test
  @DisplayName("不能连到自己；过门长度缺省 4 格")
  void validation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> link("PORTAL:w:1:2:3", "PORTAL:w:1:2:3", PortalLink.Source.MANUAL));
    PortalLink link = link("PORTAL:w:1:2:3", "PORTAL:n:1:2:3", PortalLink.Source.MANUAL);
    assertEquals(PortalLink.DEFAULT_TRANSIT_BLOCKS, link.transitBlocks(), 1e-9);
    assertTrue(link.crossWorld());
  }

  @Test
  @DisplayName("按入口索引；重新扫描只清自动连接；每次变化递增版本")
  void registry() {
    PortalLinkRegistry registry = new PortalLinkRegistry();
    long start = registry.revision();
    registry.replaceAll(
        List.of(
            link("PORTAL:w:1:2:3", "PORTAL:n:1:2:3", PortalLink.Source.AUTO),
            link("PORTAL:w:9:2:3", "PORTAL:n:9:2:3", PortalLink.Source.MANUAL)));
    assertEquals(2, registry.links().size());
    registry.removeAuto();
    assertEquals(1, registry.links().size());
    assertTrue(registry.from(NodeId.of("PORTAL:w:9:2:3")).isPresent());
    assertTrue(registry.revision() > start);
  }
}
