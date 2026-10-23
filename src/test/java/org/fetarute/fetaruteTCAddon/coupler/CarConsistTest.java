package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 由各节车厢的来源推出编组键。 */
class CarConsistTest {

  private static List<Optional<CarConsist.Origin>> cars(CarConsist.Origin origin, int count) {
    List<Optional<CarConsist.Origin>> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      out.add(Optional.of(origin));
    }
    return out;
  }

  @Test
  void aWholeBlockKeepsItsPattern() {
    CarConsist.Origin a = new CarConsist.Origin("EMU8", "a", 8);

    CarConsist.Derived derived = CarConsist.derive(cars(a, 8)).orElseThrow();

    assertEquals("EMU8", derived.pattern());
    assertFalse(derived.partial());
  }

  @Test
  void twoWholeBlocksJoinHeadToTail() {
    List<Optional<CarConsist.Origin>> train = new ArrayList<>();
    train.addAll(cars(new CarConsist.Origin("EMU4", "a", 4), 4));
    train.addAll(cars(new CarConsist.Origin("EMU6", "b", 6), 6));

    CarConsist.Derived derived = CarConsist.derive(train).orElseThrow();

    assertEquals("EMU4+EMU6", derived.pattern());
    assertFalse(derived.partial());
  }

  @Test
  void aPartOfABlockIsMarkedPartial() {
    CarConsist.Origin a = new CarConsist.Origin("EMU8", "a", 8);

    CarConsist.Derived derived = CarConsist.derive(cars(a, 3)).orElseThrow();

    assertEquals("EMU8(3/8)", derived.pattern());
    assertTrue(derived.partial());
  }

  @Test
  void theSamePatternFromTwoTrainsIsTwoBlocks() {
    List<Optional<CarConsist.Origin>> train = new ArrayList<>();
    train.addAll(cars(new CarConsist.Origin("EMU4", "a", 4), 4));
    train.addAll(cars(new CarConsist.Origin("EMU4", "b", 4), 4));

    assertEquals("EMU4+EMU4", CarConsist.derive(train).orElseThrow().pattern());
  }

  @Test
  void aCarWithoutAnOriginLeavesThePatternAlone() {
    List<Optional<CarConsist.Origin>> train =
        new ArrayList<>(cars(new CarConsist.Origin("EMU4", "a", 4), 3));
    train.add(Optional.empty());

    assertTrue(CarConsist.derive(train).isEmpty());
    assertTrue(CarConsist.derive(List.of()).isEmpty());
  }

  @Test
  void theOriginTagRoundTrips() {
    CarConsist.Origin origin = new CarConsist.Origin("A|B", "block-1", 6);

    CarConsist.Origin parsed = CarConsist.Origin.parse(origin.format()).orElseThrow();

    assertEquals("A/B", parsed.pattern(), "编组键里的竖线换成斜线，免得拆错");
    assertEquals("block-1", parsed.block());
    assertEquals(6, parsed.size());
    assertTrue(CarConsist.Origin.parse("A|b").isEmpty());
    assertTrue(CarConsist.Origin.parse("A|b|x").isEmpty());
    assertTrue(CarConsist.Origin.parse(null).isEmpty());
  }
}
