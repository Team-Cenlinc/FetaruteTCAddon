package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives.End;
import org.junit.jupiter.api.Test;

/** 解编切点：落在来源编组分界上、切下的全是完整编组，指定那头不行改拆另一头。 */
class DecoupleCutTest {

  private static List<Optional<CarConsist.Origin>> train(Object... blocks) {
    List<Optional<CarConsist.Origin>> out = new ArrayList<>();
    for (int i = 0; i < blocks.length; i += 2) {
      String name = (String) blocks[i];
      int size = (Integer) blocks[i + 1];
      CarConsist.Origin origin = new CarConsist.Origin(name, name, size);
      for (int car = 0; car < size; car++) {
        out.add(Optional.of(origin));
      }
    }
    return out;
  }

  @Test
  void cutsTheRearAtABlockBoundary() {
    DecoupleCut.Cut cut = DecoupleCut.resolve(train("A", 4, "B", 4), End.REAR, 4).orElseThrow();

    assertEquals(new DecoupleCut.Cut(4, End.REAR), cut);
  }

  @Test
  void cutsTheFrontAtABlockBoundary() {
    DecoupleCut.Cut cut = DecoupleCut.resolve(train("A", 2, "B", 6), End.FRONT, 2).orElseThrow();

    assertEquals(new DecoupleCut.Cut(2, End.FRONT), cut);
  }

  @Test
  void fallsBackToTheOtherEndWhenItIsAWholeBlockOfTheSameLength() {
    // 车头 6 节、车尾 2 节：写的是切车头 2 节，切不开；车尾正好是 2 节一个完整编组
    DecoupleCut.Cut cut = DecoupleCut.resolve(train("A", 6, "B", 2), End.FRONT, 2).orElseThrow();

    assertEquals(new DecoupleCut.Cut(6, End.REAR), cut);
  }

  @Test
  void refusesWhenNeitherEndIsABoundary() {
    assertTrue(DecoupleCut.resolve(train("A", 3, "B", 5), End.REAR, 4).isEmpty());
    assertTrue(DecoupleCut.resolve(train("A", 8), End.REAR, 4).isEmpty(), "一整块编组切不开");
  }

  @Test
  void refusesToCutOffAPartialBlock() {
    // 车尾 4 节由一个 2 节的完整编组与一个只剩 2 节的 4 节编组组成：切点在分界上，但切下的不完整；
    // 车头 4 节是只剩 4 节的 5 节编组，另一头同样不完整
    List<Optional<CarConsist.Origin>> cars = new ArrayList<>();
    CarConsist.Origin head = new CarConsist.Origin("A", "a", 5);
    CarConsist.Origin broken = new CarConsist.Origin("C", "c", 4);
    for (int i = 0; i < 4; i++) {
      cars.add(Optional.of(head));
    }
    cars.add(Optional.of(broken));
    cars.add(Optional.of(broken));
    cars.addAll(train("B", 2));

    assertTrue(DecoupleCut.resolve(cars, End.REAR, 4).isEmpty());
  }

  @Test
  void refusesWhenACarHasNoOrigin() {
    List<Optional<CarConsist.Origin>> cars = new ArrayList<>(train("A", 4, "B", 4));
    cars.set(0, Optional.empty());

    assertTrue(DecoupleCut.resolve(cars, End.REAR, 4).isEmpty());
  }
}
