package org.fetarute.fetaruteTCAddon.drive.crew;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("呼叫信号员的对话框：原因的顺序与文案")
class SignallerCallDialogTest {

  @Test
  @DisplayName("在入口等候时“信号前等候”排第一；其余时候排在车门故障、线路异常之后；一般联系总在最后，四个原因都有")
  void reasonsAreOrderedByWhetherTheTrainWaitsAtTheEntry() {
    List<CallReason> waiting = SignallerCallDialog.order(true);
    List<CallReason> elsewhere = SignallerCallDialog.order(false);

    assertEquals(CallReason.SIGNAL, waiting.get(0));
    assertEquals(
        List.of(CallReason.DOORS, CallReason.OBSTRUCTION, CallReason.SIGNAL, CallReason.GENERAL),
        elsewhere);
    for (List<CallReason> order : List.of(waiting, elsewhere)) {
      assertEquals(EnumSet.copyOf(CallReason.crewReasons()), EnumSet.copyOf(order), "询问与报告只给模拟事件用");
      assertEquals(CallReason.GENERAL, order.get(order.size() - 1));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  @DisplayName("标题、说明、取消与每个原因都有文案")
  void everyLabelHasAMessage(String localeTag) throws Exception {
    YamlConfiguration lang = new YamlConfiguration();
    try (InputStream stream =
        getClass().getClassLoader().getResourceAsStream("lang/" + localeTag + ".yml")) {
      lang.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
    List<String> keys =
        new ArrayList<>(
            List.of(
                SignallerCallDialog.KEYS + ".title",
                SignallerCallDialog.KEYS + ".body",
                SignallerCallDialog.KEYS + ".cancel"));
    for (CallReason reason : CallReason.crewReasons()) {
      keys.add(SignallerCallDialog.KEYS + ".reason." + reason.key());
    }
    for (String key : keys) {
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
    assertTrue(lang.getString(SignallerCallDialog.KEYS + ".body").contains("<zone>"));
  }
}
