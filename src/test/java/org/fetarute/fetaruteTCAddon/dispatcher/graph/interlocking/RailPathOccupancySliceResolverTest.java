package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bergerkiller.bukkit.tc.controller.components.RailPath;
import java.util.List;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.junit.jupiter.api.Test;

class RailPathOccupancySliceResolverTest {

  @Test
  void longTccPathOnlyKeepsTheTrainSpanAndEndPadding() {
    RailPath path = RailPath.create(new Vector(0.0, 0.5, 0.5), new Vector(100.0, 0.5, 0.5));

    Set<RailFootprintCell> cells =
        RailPathOccupancySliceResolver.resolve(
                path,
                new RailBlockPos(10, 20, 30),
                List.of(new Vector(40.0, 0.5, 0.5), new Vector(46.0, 0.5, 0.5)),
                1.0)
            .orElseThrow();

    assertTrue(cells.contains(new RailFootprintCell(49, 20, 30)));
    assertTrue(cells.contains(new RailFootprintCell(56, 20, 30)));
    assertFalse(cells.contains(new RailFootprintCell(10, 20, 30)));
    assertFalse(cells.contains(new RailFootprintCell(109, 20, 30)));
  }

  @Test
  void ambiguousSelfCrossingPositionFailsClosed() {
    RailPath path =
        RailPath.create(
            new Vector(0.0, 0.5, 0.0),
            new Vector(2.0, 0.5, 2.0),
            new Vector(0.0, 0.5, 2.0),
            new Vector(2.0, 0.5, 0.0));
    assertTrue(
        RailPathOccupancySliceResolver.resolve(
                path, new RailBlockPos(10, 20, 30), List.of(new Vector(1.0, 0.5, 1.0)), 1.0)
            .isEmpty());
  }
}
