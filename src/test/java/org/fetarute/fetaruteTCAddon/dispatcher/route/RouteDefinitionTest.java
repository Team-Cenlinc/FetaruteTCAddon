package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.junit.jupiter.api.Test;

class RouteDefinitionTest {

  @Test
  void resolveModeDefaultsToDestroyWhenNoStops() {
    assertEquals(RouteLifecycleMode.DESTROY_AFTER_TERM, RouteDefinition.resolveMode(List.of()));
  }

  @Test
  void resolveModeDefaultsToDestroyWhenNoTerm() {
    RouteStop stop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    assertEquals(RouteLifecycleMode.DESTROY_AFTER_TERM, RouteDefinition.resolveMode(List.of(stop)));
  }

  @Test
  void resolveModeIsReuseWhenTermPresentAndNoDsty() {
    RouteStop term =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.TERMINATE,
            Optional.empty());
    assertEquals(RouteLifecycleMode.REUSE_AT_TERM, RouteDefinition.resolveMode(List.of(term)));
  }

  @Test
  void resolveModeIsDestroyWhenTermFollowedByDsty() {
    RouteStop term =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.TERMINATE,
            Optional.empty());
    RouteStop dsty =
        new RouteStop(
            UUID.randomUUID(),
            1,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("DSTY DEPOT"));
    assertEquals(
        RouteLifecycleMode.DESTROY_AFTER_TERM, RouteDefinition.resolveMode(List.of(term, dsty)));
  }

  @Test
  void resolveModeIsReuseWhenDstyPrecedesTerm() {
    // This is an edge case (DSTY before TERM), conceptually weird but per logic should be reuse (or
    // maybe destroy? Logic says if dsty > term then destroy).
    // Actually logic: "if termIndex == -1 or (dstyIndex != -1 and dstyIndex > termIndex) ->
    // destroy".
    // So if dstyIndex < termIndex, it falls through to REUSE_AT_TERM.
    RouteStop dsty =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("DSTY OLD_DEPOT"));
    RouteStop term =
        new RouteStop(
            UUID.randomUUID(),
            1,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.TERMINATE,
            Optional.empty());
    assertEquals(
        RouteLifecycleMode.REUSE_AT_TERM, RouteDefinition.resolveMode(List.of(dsty, term)));
  }
}
