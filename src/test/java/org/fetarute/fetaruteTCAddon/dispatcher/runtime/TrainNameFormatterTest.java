package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.junit.jupiter.api.Test;

class TrainNameFormatterTest {

  @Test
  void sequenceIsTheFourDigitTailOfAGeneratedName() {
    String name =
        TrainNameFormatter.buildTrainName(
            "SURC",
            "MT",
            RoutePatternType.LOCAL,
            "HHU",
            UUID.fromString("00000000-0000-0000-0000-000000000001"));

    assertEquals(Optional.of(name.substring(name.length() - 4)), TrainNameFormatter.sequence(name));
    assertEquals(Optional.of("4079"), TrainNameFormatter.sequence("SURC-MT-LP-4079"));
  }

  @Test
  void otherNamesHaveNoSequence() {
    assertEquals(Optional.empty(), TrainNameFormatter.sequence("train12"));
    assertEquals(Optional.empty(), TrainNameFormatter.sequence("MT-1N_Short-228"));
    assertEquals(Optional.empty(), TrainNameFormatter.sequence("SURC-MT-LH-12345"));
    assertEquals(Optional.empty(), TrainNameFormatter.sequence("SURC-MT-LH-12a4"));
    assertEquals(Optional.empty(), TrainNameFormatter.sequence("-1234"));
    assertEquals(Optional.empty(), TrainNameFormatter.sequence(null));
  }
}
