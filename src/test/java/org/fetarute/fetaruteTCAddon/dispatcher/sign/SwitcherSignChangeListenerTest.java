package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.rails.RailLookup;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.event.block.SignChangeEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** 道岔改牌只清理由本次编辑作废的节点，保留同轨道上仍然有效的道岔。 */
final class SwitcherSignChangeListenerTest {

  @Test
  void creatingAConditionalSignDoesNotDeleteAnExistingSwitcherNode() {
    SignNodeStorageSynchronizer sync = mock(SignNodeStorageSynchronizer.class);
    Block block = mock(Block.class);
    Sign sign = mock(Sign.class);
    when(block.getState()).thenReturn(sign);
    SignSide front = side("tag", "");
    SignSide back = side("", "");
    when(sign.getSide(Side.FRONT)).thenReturn(front);
    when(sign.getSide(Side.BACK)).thenReturn(back);
    SignChangeEvent event = edit(block);
    RailPiece rail = rail();

    try (MockedStatic<SignActionHeader> headers = headers();
        MockedStatic<RailLookup> lookup = mockStatic(RailLookup.class)) {
      lookup.when(() -> RailLookup.discoverRailPieceFromSign(block)).thenReturn(rail);
      new SwitcherSignChangeListener(sync, ignored -> {}).onSignChange(event);
    }

    verify(sync, never()).delete(any(), any());
  }

  @Test
  void editingTheLastPathfindingSideDeletesItsRailNodeThroughIncrementalSync() {
    Fixture fixture = fixture();
    try (MockedStatic<SignActionHeader> headers = headers();
        MockedStatic<RailLookup> lookup = lookup(fixture, new TrackedSign[0])) {
      fixture.listener().onSignChange(fixture.event());
    }

    NodeId id = SwitcherSignDefinitionParser.nodeIdForRail("world", new RailBlockPos(12, 64, 3));
    verify(fixture.sync())
        .delete(fixture.rail().block(), SwitcherSignDefinitionParser.definitionOf(id));
  }

  @Test
  void keepsNodeWhenOtherSideStillPathfinds() {
    Fixture fixture = fixture();
    SignSide back = side("switcher", "");
    when(fixture.sign().getSide(Side.BACK)).thenReturn(back);
    try (MockedStatic<SignActionHeader> headers = headers()) {
      fixture.listener().onSignChange(fixture.event());
    }
    verify(fixture.sync(), never()).delete(any(), any());
  }

  @Test
  void keepsNodeWhenAnUncachedOtherSignStillPathfinds() {
    Fixture fixture = fixture();
    TrackedSign other = mock(TrackedSign.class);
    SignActionHeader header = mock(SignActionHeader.class);
    when(header.isTrain()).thenReturn(true);
    when(other.getHeader()).thenReturn(header);
    when(other.getLine(1)).thenReturn("switcher");
    when(other.getLine(2)).thenReturn("");
    when(other.getLine(3)).thenReturn("");
    try (MockedStatic<SignActionHeader> headers = headers();
        MockedStatic<RailLookup> lookup = lookup(fixture, new TrackedSign[] {other})) {
      fixture.listener().onSignChange(fixture.event());
    }
    verify(fixture.sync(), never()).delete(any(), any());
  }

  /** 同轨道上另一块牌子读不出来（区块未加载、读牌面出错）时判不了它是不是寻路道岔：保留节点。 */
  @Test
  void keepsNodeWhenAnotherSignOnTheRailCannotBeRead() {
    Fixture fixture = fixture();
    TrackedSign unreadable = mock(TrackedSign.class);
    when(unreadable.getHeader()).thenThrow(new IllegalStateException("unreadable"));
    try (MockedStatic<SignActionHeader> headers = headers();
        MockedStatic<RailLookup> lookup = lookup(fixture, new TrackedSign[] {unreadable})) {
      fixture.listener().onSignChange(fixture.event());
    }
    verify(fixture.sync(), never()).delete(any(), any());
  }

  @Test
  void cancelledEditLeavesNodeUntouched() {
    Fixture fixture = fixture();
    when(fixture.event().isCancelled()).thenReturn(true);
    fixture.listener().onSignChange(fixture.event());
    verify(fixture.sync(), never()).delete(any(), any());
  }

  @Test
  void keepsNodeWhenAttachedRailCannotBeDetermined() {
    Fixture fixture = fixture();
    try (MockedStatic<SignActionHeader> headers = headers();
        MockedStatic<RailLookup> lookup = mockStatic(RailLookup.class)) {
      fixture.listener().onSignChange(fixture.event());
    }
    verify(fixture.sync(), never()).delete(any(), any());
  }

  /** 牌子柱跨进未加载的相邻区块时判不全：保留节点，也不去查牌子（那会同步加载区块）。 */
  @Test
  void keepsNodeWhenTheSignColumnReachesAnUnloadedChunk() {
    Fixture base = fixture();
    Fixture fixture = new Fixture(base.sync(), base.block(), base.sign(), base.event(), rail(15));
    try (MockedStatic<SignActionHeader> headers = headers();
        MockedStatic<RailLookup> lookup = lookup(fixture, new TrackedSign[0])) {
      fixture.listener().onSignChange(fixture.event());
      lookup.verify(() -> RailLookup.discoverSignsAtRailPiece(any()), never());
    }
    verify(fixture.sync(), never()).delete(any(), any());
  }

  private static Fixture fixture() {
    Block block = mock(Block.class);
    Sign sign = mock(Sign.class);
    when(block.getState()).thenReturn(sign);
    SignSide front = side("switcher", "");
    SignSide back = side("", "");
    when(sign.getSide(Side.FRONT)).thenReturn(front);
    when(sign.getSide(Side.BACK)).thenReturn(back);
    return new Fixture(mock(SignNodeStorageSynchronizer.class), block, sign, edit(block), rail());
  }

  private static MockedStatic<RailLookup> lookup(Fixture fixture, TrackedSign[] signs) {
    MockedStatic<RailLookup> lookup = mockStatic(RailLookup.class);
    lookup
        .when(() -> RailLookup.discoverRailPieceFromSign(fixture.block()))
        .thenReturn(fixture.rail());
    lookup.when(() -> RailLookup.discoverSignsAtRailPiece(fixture.rail())).thenReturn(signs);
    return lookup;
  }

  private record Fixture(
      SignNodeStorageSynchronizer sync,
      Block block,
      Sign sign,
      SignChangeEvent event,
      RailPiece rail) {
    SwitcherSignChangeListener listener() {
      return new SwitcherSignChangeListener(sync, ignored -> {});
    }
  }

  private static SignChangeEvent edit(Block block) {
    SignChangeEvent event = mock(SignChangeEvent.class);
    when(event.getBlock()).thenReturn(block);
    when(event.getSide()).thenReturn(Side.FRONT);
    when(event.line(0)).thenReturn(Component.text("[train]"));
    when(event.line(1)).thenReturn(Component.text("switcher"));
    when(event.line(2)).thenReturn(Component.text("express"));
    when(event.line(3)).thenReturn(Component.empty());
    return event;
  }

  private static SignSide side(String action, String condition) {
    SignSide side = mock(SignSide.class);
    when(side.line(0)).thenReturn(Component.text("[train]"));
    when(side.line(1)).thenReturn(Component.text(action));
    when(side.line(2)).thenReturn(Component.text(condition));
    when(side.line(3)).thenReturn(Component.empty());
    return side;
  }

  private static MockedStatic<SignActionHeader> headers() {
    SignActionHeader header = mock(SignActionHeader.class);
    when(header.isTrain()).thenReturn(true);
    MockedStatic<SignActionHeader> headers = mockStatic(SignActionHeader.class);
    headers.when(() -> SignActionHeader.parse("[train]")).thenReturn(header);
    return headers;
  }

  private static RailPiece rail() {
    return rail(12);
  }

  /** 轨道在 (x, 64, 3)；只有区块 (0, 0) 已加载。 */
  private static RailPiece rail(int x) {
    World world = mock(World.class);
    when(world.getName()).thenReturn("world");
    when(world.isChunkLoaded(0, 0)).thenReturn(true);
    Block block = mock(Block.class);
    when(block.getWorld()).thenReturn(world);
    when(block.getX()).thenReturn(x);
    when(block.getY()).thenReturn(64);
    when(block.getZ()).thenReturn(3);
    RailPiece piece = mock(RailPiece.class);
    when(piece.block()).thenReturn(block);
    when(piece.signs()).thenReturn(new TrackedSign[0]);
    return piece;
  }
}
