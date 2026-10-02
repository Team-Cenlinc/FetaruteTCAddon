package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** 出库编组的来源：交路 metadata、车库牌子第 4 行，以及只读已加载区块的车库牌子读取。 */
class DepotSpawnPatternTest {

  private static final NodeId DEPOT = NodeId.of("SURC:D:OFL:1");
  private static final UUID WORLD_ID = UUID.randomUUID();
  // 负坐标：区块坐标必须向下取整（-17 → -2），不能截断成 -1。
  private static final int X = -17;
  private static final int Y = 64;
  private static final int Z = 50;

  @Test
  void routeMetadataIsTrimmedAndBlankMeansAbsent() {
    assertEquals(
        Optional.of("metro6"),
        DepotSpawnPattern.fromRoute(
            route(Map.of(DepotSpawnPattern.ROUTE_METADATA_KEY, "  metro6 "))));
    assertTrue(
        DepotSpawnPattern.fromRoute(route(Map.of(DepotSpawnPattern.ROUTE_METADATA_KEY, " ")))
            .isEmpty());
    assertTrue(
        DepotSpawnPattern.fromRoute(route(Map.of(DepotSpawnPattern.ROUTE_METADATA_KEY, 6)))
            .isEmpty());
    assertTrue(DepotSpawnPattern.fromRoute(null).isEmpty());
  }

  @Test
  void signPatternFallsBackToTheBackSide() {
    Sign sign = sign(side("", "", "", ""), side("[train]", "depot", "", " dmu3 "));

    try (MockedStatic<SignActionHeader> headers = trainHeader()) {
      assertEquals(Optional.of("dmu3"), DepotSpawnPattern.fromSign(sign));
    }
  }

  @Test
  void nonDepotSignHasNoPattern() {
    Sign sign = sign(side("[train]", "station", "", "metro6"), side("", "", "", ""));

    try (MockedStatic<SignActionHeader> headers = trainHeader()) {
      assertTrue(DepotSpawnPattern.fromSign(sign).isEmpty());
    }
  }

  @Test
  void unregisteredOrNonDepotNodeIsMissing() {
    SignNodeRegistry registry = new SignNodeRegistry();
    registry.put(WORLD_ID, "world", X, Y, Z, definition(NodeType.STATION));

    DepotSpawnPattern.SignRead read = DepotSpawnPattern.readLoaded(registry, DEPOT);

    assertTrue(read.loaded());
    assertTrue(read.pattern().isEmpty());
  }

  @Test
  // 估算类调用在主线程高频执行：区块未加载时直接返回，连方块都不取，避免同步加载区块。
  void unloadedChunkIsNotTouched() {
    SignNodeRegistry registry = depotRegistry();
    World world = mock(World.class);
    when(world.isChunkLoaded(-2, 3)).thenReturn(false);

    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(world);

      DepotSpawnPattern.SignRead read = DepotSpawnPattern.readLoaded(registry, DEPOT);

      assertEquals(DepotSpawnPattern.SignRead.unloaded(), read);
      verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }
  }

  @Test
  void unloadedWorldCountsAsUnloaded() {
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(null);

      assertEquals(
          DepotSpawnPattern.SignRead.unloaded(),
          DepotSpawnPattern.readLoaded(depotRegistry(), DEPOT));
    }
  }

  @Test
  void loadedChunkReadsTheSign() {
    World world = mock(World.class);
    Block block = mock(Block.class);
    Sign sign = sign(side("[train]", "depot", "", "metro6"), side("", "", "", ""));
    when(world.isChunkLoaded(-2, 3)).thenReturn(true);
    when(world.getBlockAt(X, Y, Z)).thenReturn(block);
    when(block.getState()).thenReturn(sign);

    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        MockedStatic<SignActionHeader> headers = trainHeader()) {
      bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(world);

      assertEquals(
          DepotSpawnPattern.SignRead.loaded(Optional.of("metro6")),
          DepotSpawnPattern.readLoaded(depotRegistry(), DEPOT));
    }
  }

  private static SignNodeRegistry depotRegistry() {
    SignNodeRegistry registry = new SignNodeRegistry();
    registry.put(WORLD_ID, "world", X, Y, Z, definition(NodeType.DEPOT));
    return registry;
  }

  private static SignNodeDefinition definition(NodeType type) {
    return new SignNodeDefinition(DEPOT, type, Optional.empty(), Optional.empty());
  }

  private static MockedStatic<SignActionHeader> trainHeader() {
    SignActionHeader header = mock(SignActionHeader.class);
    when(header.isTrain()).thenReturn(true);
    MockedStatic<SignActionHeader> headers = mockStatic(SignActionHeader.class);
    headers.when(() -> SignActionHeader.parse("[train]")).thenReturn(header);
    return headers;
  }

  private static Sign sign(SignSide front, SignSide back) {
    Sign sign = mock(Sign.class);
    when(sign.getSide(Side.FRONT)).thenReturn(front);
    when(sign.getSide(Side.BACK)).thenReturn(back);
    return sign;
  }

  private static SignSide side(String... lines) {
    SignSide side = mock(SignSide.class);
    for (int i = 0; i < lines.length; i++) {
      when(side.line(i)).thenReturn(Component.text(lines[i]));
    }
    return side;
  }

  private static Route route(Map<String, Object> metadata) {
    Instant now = Instant.parse("2026-09-30T12:00:00Z");
    return new Route(
        UUID.randomUUID(),
        "R1",
        UUID.randomUUID(),
        "Local",
        Optional.empty(),
        RoutePatternType.LOCAL,
        RouteOperationType.OPERATION,
        Optional.empty(),
        Optional.empty(),
        metadata,
        now,
        now);
  }
}
