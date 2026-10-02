package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bergerkiller.bukkit.tc.controller.components.RailPath;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.junit.jupiter.api.Test;

class RailPathFootprintRasterizerTest {

  @Test
  void locatesRelativeCoordinateOnSegmentAndReportsCumulativeDistance() {
    RailPath path =
        RailPath.create(
            new Vector(0.0, 0.5, 0.5), new Vector(2.0, 0.5, 0.5), new Vector(2.0, 3.5, 0.5));

    var location = RailPathFootprintRasterizer.locate(path, new Vector(2.0, 2.0, 0.5));

    assertTrue(location.isPresent());
    assertEquals(1, location.orElseThrow().segmentIndex());
    assertEquals(0.5, location.orElseThrow().segmentTheta(), 1.0e-12);
    assertEquals(3.5, location.orElseThrow().distanceFromStart(), 1.0e-12);
  }

  @Test
  void locatesRelativeTrainCartsPositionWithoutDependingOnPrivateWheelState() {
    RailPath path =
        RailPath.create(
            new Vector(0.0, 0.5, 0.5), new Vector(2.0, 0.5, 0.5), new Vector(2.0, 3.5, 0.5));
    RailPath.Position position = new RailPath.Position();
    position.relative = true;
    position.posX = 2.0;
    position.posY = 2.0;
    position.posZ = 0.5;

    var location = RailPathFootprintRasterizer.locate(path, position);

    assertTrue(location.isPresent());
    assertEquals(1, location.orElseThrow().segmentIndex());
    assertEquals(3.5, location.orElseThrow().distanceFromStart(), 1.0e-12);
  }

  @Test
  void selfIntersectionWithDifferentCumulativeDistancesIsAmbiguous() {
    RailPath path =
        RailPath.create(
            new Vector(-1.0, 0.5, -1.0),
            new Vector(1.0, 0.5, 1.0),
            new Vector(-1.0, 0.5, 1.0),
            new Vector(1.0, 0.5, -1.0));

    var location = RailPathFootprintRasterizer.locate(path, new Vector(0.0, 0.5, 0.0));

    assertTrue(location.isEmpty());
  }

  @Test
  void rasterizesOnlyTheRequestedCumulativeDistanceInterval() {
    RailPath path = RailPath.create(new Vector(0.25, 0.5, 0.5), new Vector(3.25, 0.5, 0.5));

    var footprint =
        RailPathFootprintRasterizer.rasterize(path, new RailBlockPos(10, 20, 30), 0.5, 2.5);

    assertTrue(footprint.isPresent());
    assertEquals(
        Set.of(
            new RailFootprintCell(10, 20, 30),
            new RailFootprintCell(11, 20, 30),
            new RailFootprintCell(12, 20, 30)),
        footprint.orElseThrow());
  }

  @Test
  void rasterizesEverySegmentOfThreeDimensionalTrainCartsPath() {
    RailPath path =
        RailPath.create(
            new Vector(0.25, 0.25, 0.25),
            new Vector(0.25, 1.25, 0.25),
            new Vector(1.25, 1.25, 0.25));

    Set<RailFootprintCell> footprint =
        RailPathFootprintRasterizer.rasterize(path, new RailBlockPos(10, 20, 30));

    assertEquals(
        Set.of(
            new RailFootprintCell(10, 20, 30),
            new RailFootprintCell(10, 21, 30),
            new RailFootprintCell(11, 21, 30)),
        footprint);
  }

  @Test
  void positiveIntegerEndpointDoesNotClaimTheNextBlock() {
    RailPath path = RailPath.create(new Vector(0.0, 0.5, 0.5), new Vector(1.0, 0.5, 0.5));

    Set<RailFootprintCell> footprint =
        RailPathFootprintRasterizer.rasterize(path, new RailBlockPos(10, 20, 30));

    assertEquals(Set.of(new RailFootprintCell(10, 20, 30)), footprint);
  }

  @Test
  void negativeIntegerBoundaryStartsInTheBlockBehindTheBoundary() {
    RailPath path = RailPath.create(new Vector(1.0, 0.5, 0.5), new Vector(0.0, 0.5, 0.5));

    Set<RailFootprintCell> footprint =
        RailPathFootprintRasterizer.rasterize(path, new RailBlockPos(10, 20, 30));

    assertEquals(Set.of(new RailFootprintCell(10, 20, 30)), footprint);
  }

  @Test
  void sharedAdjacentEndpointHasOneStableCumulativeLocation() {
    RailPath path =
        RailPath.create(
            new Vector(0.0, 0.5, 0.5), new Vector(1.0, 0.5, 0.5), new Vector(2.0, 0.5, 0.5));

    RailPathFootprintRasterizer.PathLocation location =
        RailPathFootprintRasterizer.locate(path, new Vector(1.0, 0.5, 0.5)).orElseThrow();

    assertEquals(1.0, location.distanceFromStart(), 1.0e-9);
  }

  @Test
  void skippedMicroSegmentStillContributesToCumulativeDistance() {
    RailPath path =
        RailPath.create(
            new Vector(0.0, 0.5, 0.5),
            new Vector(1.0e-6, 0.5, 0.5),
            new Vector(1.000001, 0.5, 0.5));

    RailPathFootprintRasterizer.PathLocation location =
        RailPathFootprintRasterizer.locate(path, new Vector(0.500001, 0.5, 0.5)).orElseThrow();

    assertEquals(0.500001, location.distanceFromStart(), 1.0e-12);
  }

  @Test
  void rasterizesOnlyTheRequestedPartOfALongTccLikePath() {
    RailPath path = RailPath.create(new Vector(0.0, 0.5, 0.5), new Vector(100.0, 0.5, 0.5));

    Set<RailFootprintCell> footprint =
        RailPathFootprintRasterizer.rasterize(path, new RailBlockPos(10, 20, 30), 40.0, 47.0)
            .orElseThrow();

    assertTrue(footprint.contains(new RailFootprintCell(50, 20, 30)));
    assertTrue(footprint.contains(new RailFootprintCell(56, 20, 30)));
    assertFalse(footprint.contains(new RailFootprintCell(10, 20, 30)));
    assertFalse(footprint.contains(new RailFootprintCell(109, 20, 30)));
  }

  @Test
  void partialRangeKeepsPositiveIntegerEndpointSemantics() {
    RailPath path = RailPath.create(new Vector(0.0, 0.5, 0.5), new Vector(10.0, 0.5, 0.5));

    Set<RailFootprintCell> footprint =
        RailPathFootprintRasterizer.rasterize(path, new RailBlockPos(10, 20, 30), 4.0, 5.0)
            .orElseThrow();

    assertEquals(Set.of(new RailFootprintCell(14, 20, 30)), footprint);
  }
}
