package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLink;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 传送门连接表：往返、按入口覆盖、只删自动连接。 */
class PortalLinkRepositoryTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private PortalLinkRepository links;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    links = storage.provider().portalLinks();
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  @Test
  void roundTrip() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    PortalLink auto =
        new PortalLink(
            a,
            NodeId.of("PORTAL:a:1:2:3"),
            b,
            NodeId.of("PORTAL:b:1:2:3"),
            PortalLink.Source.AUTO,
            4.0,
            now);
    PortalLink manual =
        new PortalLink(
            a,
            NodeId.of("PORTAL:a:9:2:3"),
            b,
            NodeId.of("PORTAL:b:9:2:3"),
            PortalLink.Source.MANUAL,
            6.0,
            now);
    links.upsert(auto);
    links.upsert(manual);
    PortalLink moved =
        new PortalLink(
            a,
            NodeId.of("PORTAL:a:9:2:3"),
            b,
            NodeId.of("PORTAL:b:8:2:3"),
            PortalLink.Source.MANUAL,
            6.0,
            now);
    links.upsert(moved);
    assertEquals(2, links.listAll().size());

    links.deleteAuto();
    assertEquals(List.of(moved), links.listAll());
    links.delete(a, NodeId.of("PORTAL:a:9:2:3"));
    assertEquals(List.of(), links.listAll());
  }
}
