package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("车型最高速度的补全")
class FtaTrainCommandMaxSpeedTest {

  @Test
  @DisplayName("写了数字给出各单位，否则给提示与带单位的示例")
  void suggestsUnits() {
    assertEquals(List.of("80kmh", "80bps", "80bpt"), FtaTrainCommand.maxSpeedSuggestions("80"));
    assertEquals(List.of("1.5kmh", "1.5bps", "1.5bpt"), FtaTrainCommand.maxSpeedSuggestions("1.5"));
    assertEquals(
        List.of("<speed>", "80kmh", "22bps", "1.1bpt"), FtaTrainCommand.maxSpeedSuggestions(""));
    assertEquals(
        List.of("<speed>", "80kmh", "22bps", "1.1bpt"), FtaTrainCommand.maxSpeedSuggestions("80k"));
  }
}
