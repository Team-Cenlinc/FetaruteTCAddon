package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

final class SwitcherSignDefinitionParserTest {

  @Test
  void ignoresTagSignsOnBukkitSign() {
    Sign sign = mock(Sign.class);
    SignSide view = mock(SignSide.class);
    when(sign.getSide(Side.FRONT)).thenReturn(view);
    when(sign.getSide(Side.BACK)).thenReturn(view);
    when(view.line(0)).thenReturn(Component.text("[train]"));
    when(view.line(1)).thenReturn(Component.text("tag"));

    SignActionHeader trainHeader = mock(SignActionHeader.class);
    when(trainHeader.isTrain()).thenReturn(true);
    when(trainHeader.isCart()).thenReturn(false);
    try (MockedStatic<SignActionHeader> mocked = mockStatic(SignActionHeader.class)) {
      mocked.when(() -> SignActionHeader.parse("[train]")).thenReturn(trainHeader);
      assertTrue(SwitcherSignDefinitionParser.parse(sign).isEmpty());
    }
  }

  @Test
  void ignoresTagSignsOnTrackedSign() {
    TrackedSign trackedSign = mock(TrackedSign.class);
    SignActionHeader header = mock(SignActionHeader.class);
    when(header.isTrain()).thenReturn(true);
    when(header.isCart()).thenReturn(false);
    when(trackedSign.getHeader()).thenReturn(header);
    when(trackedSign.getLine(1)).thenReturn("tag");

    assertTrue(SwitcherSignDefinitionParser.parse(trackedSign).isEmpty());
  }

  @Test
  void createsSwitcherNodeFromTrackedSignRailPiece() {
    TrackedSign trackedSign = mock(TrackedSign.class);
    SignActionHeader header = mock(SignActionHeader.class);
    when(header.isTrain()).thenReturn(true);
    when(header.isCart()).thenReturn(false);
    when(trackedSign.getHeader()).thenReturn(header);
    when(trackedSign.getLine(1)).thenReturn("switcher");

    World world = mock(World.class);
    when(world.getName()).thenReturn("world");
    Block railBlock = mock(Block.class);
    when(railBlock.getWorld()).thenReturn(world);
    when(railBlock.getX()).thenReturn(12);
    when(railBlock.getY()).thenReturn(65);
    when(railBlock.getZ()).thenReturn(-3);
    RailPiece railPiece = mock(RailPiece.class);
    when(railPiece.block()).thenReturn(railBlock);
    when(trackedSign.getRail()).thenReturn(railPiece);

    Optional<SignNodeDefinition> defOpt = SwitcherSignDefinitionParser.parse(trackedSign);
    assertTrue(defOpt.isPresent());
    SignNodeDefinition def = defOpt.get();
    assertEquals(NodeType.SWITCHER, def.nodeType());
    assertEquals(NodeId.of("SWITCHER:world:12:65:-3"), def.nodeId());
    assertEquals(
        Optional.of(SwitcherSignDefinitionParser.SWITCHER_SIGN_MARKER),
        def.trainCartsDestination());
    assertTrue(def.waypointMetadata().isEmpty());
  }

  @Test
  void parseRailPosFromSwitcherNodeId() {
    NodeId nodeId = SwitcherSignDefinitionParser.nodeIdForRail("world", new RailBlockPos(1, 2, 3));

    Optional<RailBlockPos> parsed = SwitcherSignDefinitionParser.tryParseRailPos(nodeId);

    assertTrue(parsed.isPresent());
    assertEquals(new RailBlockPos(1, 2, 3), parsed.get());
    assertTrue(
        SwitcherSignDefinitionParser.tryParseRailPos(NodeId.of("SURN:PTK:GPT:1:00")).isEmpty());
  }

  @Test
  void ignoresTrackedSignWhenLineMissing() {
    TrackedSign trackedSign = mock(TrackedSign.class);
    SignActionHeader header = mock(SignActionHeader.class);
    when(header.isTrain()).thenReturn(true);
    when(header.isCart()).thenReturn(false);
    when(trackedSign.getHeader()).thenReturn(header);
    when(trackedSign.getLine(1)).thenThrow(new IndexOutOfBoundsException());

    assertTrue(SwitcherSignDefinitionParser.parse(trackedSign).isEmpty());
  }
}
