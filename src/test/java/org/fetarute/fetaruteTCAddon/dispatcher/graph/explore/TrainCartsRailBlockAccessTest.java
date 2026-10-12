package org.fetarute.fetaruteTCAddon.dispatcher.graph.explore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.rails.RailLookup;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedRealSign;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** 剪枝必须重新发现节点关联牌子，不受 TrainCarts 尚未刷新的轨道缓存影响。 */
final class TrainCartsRailBlockAccessTest {

  @Test
  void readsThePhysicalSignEvenWhenFreshDiscoveryReturnsAnOldTextTracker() throws Exception {
    World world = mock(World.class);
    when(world.isChunkLoaded(0, 0)).thenReturn(true);
    Block railBlock = mock(Block.class);
    when(world.getBlockAt(1, 64, 2)).thenReturn(railBlock);
    Block signBlock = mock(Block.class);
    when(signBlock.getWorld()).thenReturn(world);
    Sign physical = mock(Sign.class);
    when(signBlock.getState()).thenReturn(physical);
    SignSide front = mock(SignSide.class);
    when(physical.getSide(Side.FRONT)).thenReturn(front);
    when(front.line(0)).thenReturn(Component.text("[train]"));
    when(front.line(1)).thenReturn(Component.text("switcher"));
    when(front.line(2)).thenReturn(Component.text("express"));
    when(front.line(3)).thenReturn(Component.empty());
    TrackedRealSign tracked = mock(TrackedRealSign.class);
    var blockField = TrackedSign.class.getField("signBlock");
    assertTrue(blockField.trySetAccessible());
    blockField.set(tracked, signBlock);
    when(tracked.isFrontText()).thenReturn(true);
    SignActionHeader header = mock(SignActionHeader.class);
    when(header.isTrain()).thenReturn(true);
    when(tracked.getHeader()).thenReturn(header);
    when(tracked.getLine(1)).thenReturn("switcher");
    when(tracked.getLine(2)).thenReturn("");
    when(tracked.getLine(3)).thenReturn("");
    RailPiece piece = mock(RailPiece.class);
    try (MockedStatic<RailPiece> pieces = mockStatic(RailPiece.class);
        MockedStatic<RailLookup> lookup = mockStatic(RailLookup.class);
        MockedStatic<SignActionHeader> headers = mockStatic(SignActionHeader.class)) {
      pieces.when(() -> RailPiece.create(railBlock)).thenReturn(piece);
      lookup
          .when(() -> RailLookup.discoverSignsAtRailPiece(piece))
          .thenReturn(new TrackedSign[] {tracked});
      headers.when(() -> SignActionHeader.parse("[train]")).thenReturn(header);
      assertTrue(
          new TrainCartsRailBlockAccess(world)
              .hasOnlyConditionalSwitchers(new RailBlockPos(1, 64, 2)));
    }
  }

  @Test
  void detectsEditedConditionalSignEvenWhenRailCacheStillContainsPathfindingSign() {
    World world = mock(World.class);
    when(world.isChunkLoaded(0, 0)).thenReturn(true);
    Block block = mock(Block.class);
    when(world.getBlockAt(1, 64, 2)).thenReturn(block);
    RailPiece piece = mock(RailPiece.class);
    TrackedSign cached = switcher("");
    TrackedSign current = switcher("express");
    when(piece.signs()).thenReturn(new TrackedSign[] {cached});

    try (MockedStatic<RailPiece> pieces = mockStatic(RailPiece.class);
        MockedStatic<RailLookup> lookup = mockStatic(RailLookup.class)) {
      pieces.when(() -> RailPiece.create(block)).thenReturn(piece);
      lookup
          .when(() -> RailLookup.discoverSignsAtRailPiece(piece))
          .thenReturn(new TrackedSign[] {current});

      assertTrue(
          new TrainCartsRailBlockAccess(world)
              .hasOnlyConditionalSwitchers(new RailBlockPos(1, 64, 2)));
    }
  }

  @Test
  void preservesNodeWhenFreshDiscoveryFindsAPathfindingSign() {
    World world = mock(World.class);
    when(world.isChunkLoaded(0, 0)).thenReturn(true);
    Block block = mock(Block.class);
    when(world.getBlockAt(1, 64, 2)).thenReturn(block);
    RailPiece piece = mock(RailPiece.class);
    TrackedSign conditional = switcher("express");
    TrackedSign pathfinding = switcher("");
    when(piece.signs()).thenReturn(new TrackedSign[] {conditional});
    try (MockedStatic<RailPiece> pieces = mockStatic(RailPiece.class);
        MockedStatic<RailLookup> lookup = mockStatic(RailLookup.class)) {
      pieces.when(() -> RailPiece.create(block)).thenReturn(piece);
      lookup
          .when(() -> RailLookup.discoverSignsAtRailPiece(piece))
          .thenReturn(new TrackedSign[] {conditional, pathfinding});
      assertFalse(
          new TrainCartsRailBlockAccess(world)
              .hasOnlyConditionalSwitchers(new RailBlockPos(1, 64, 2)));
    }
  }

  @Test
  void doesNotReadOrLoadAnUnloadedChunkForPruning() {
    World world = mock(World.class);
    assertFalse(
        new TrainCartsRailBlockAccess(world)
            .hasOnlyConditionalSwitchers(new RailBlockPos(1, 64, 2)));
    verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
  }

  @Test
  void preservesNodeWhenAnAssociatedRealSignIsInAnUnloadedChunk() throws Exception {
    World world = mock(World.class);
    when(world.isChunkLoaded(0, 0)).thenReturn(true);
    Block railBlock = mock(Block.class);
    when(world.getBlockAt(1, 64, 2)).thenReturn(railBlock);
    Block signBlock = mock(Block.class);
    when(signBlock.getWorld()).thenReturn(world);
    when(signBlock.getX()).thenReturn(32);
    TrackedRealSign tracked = mock(TrackedRealSign.class);
    var field = TrackedSign.class.getField("signBlock");
    assertTrue(field.trySetAccessible());
    field.set(tracked, signBlock);
    RailPiece piece = mock(RailPiece.class);
    TrackedSign conditional = switcher("express");
    try (MockedStatic<RailPiece> pieces = mockStatic(RailPiece.class);
        MockedStatic<RailLookup> lookup = mockStatic(RailLookup.class)) {
      pieces.when(() -> RailPiece.create(railBlock)).thenReturn(piece);
      lookup
          .when(() -> RailLookup.discoverSignsAtRailPiece(piece))
          .thenReturn(new TrackedSign[] {conditional, tracked});
      assertFalse(
          new TrainCartsRailBlockAccess(world)
              .hasOnlyConditionalSwitchers(new RailBlockPos(1, 64, 2)));
    }
    verify(signBlock, never()).getState();
  }

  @Test
  void absenceOfSwitcherSignsDoesNotProveThatNodeShouldBeRemoved() {
    World world = mock(World.class);
    when(world.isChunkLoaded(0, 0)).thenReturn(true);
    Block block = mock(Block.class);
    when(world.getBlockAt(1, 64, 2)).thenReturn(block);
    RailPiece piece = mock(RailPiece.class);
    try (MockedStatic<RailPiece> pieces = mockStatic(RailPiece.class);
        MockedStatic<RailLookup> lookup = mockStatic(RailLookup.class)) {
      pieces.when(() -> RailPiece.create(block)).thenReturn(piece);
      lookup.when(() -> RailLookup.discoverSignsAtRailPiece(piece)).thenReturn(new TrackedSign[0]);
      assertFalse(
          new TrainCartsRailBlockAccess(world)
              .hasOnlyConditionalSwitchers(new RailBlockPos(1, 64, 2)));
    }
  }

  private static TrackedSign switcher(String condition) {
    SignActionHeader header = mock(SignActionHeader.class);
    when(header.isTrain()).thenReturn(true);
    TrackedSign sign = mock(TrackedSign.class);
    when(sign.getHeader()).thenReturn(header);
    when(sign.getLine(1)).thenReturn("switcher");
    when(sign.getLine(2)).thenReturn(condition);
    when(sign.getLine(3)).thenReturn("");
    return sign;
  }
}
