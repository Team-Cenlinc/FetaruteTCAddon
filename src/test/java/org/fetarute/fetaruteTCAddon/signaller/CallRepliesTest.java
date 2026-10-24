package org.fetarute.fetaruteTCAddon.signaller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.drive.crew.CallReason;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState.Reply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 呼叫原因配哪几条回复。 */
class CallRepliesTest {

  @Test
  void eachReasonHasItsReplies() {
    assertEquals(Set.of(Reply.ROUTE_SETTING, Reply.WAIT), fitting(CallReason.SIGNAL));
    assertEquals(Set.of(Reply.ACK), fitting(CallReason.DOORS));
    assertEquals(Set.of(Reply.CAUTION), fitting(CallReason.OBSTRUCTION));
    assertEquals(EnumSet.allOf(Reply.class), fitting(CallReason.GENERAL));
  }

  /** 每种原因的来电提醒（聊天与动作栏）、回错时的说明（一般联系没有回错）、呼叫牌上的写法，以及提示音、结算明细与命令的文案都有。 */
  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  void everyReasonHasItsMessages(String localeTag) throws Exception {
    YamlConfiguration lang = new YamlConfiguration();
    try (InputStream stream =
        getClass().getClassLoader().getResourceAsStream("lang/" + localeTag + ".yml")) {
      lang.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
    List<String> keys = new ArrayList<>();
    for (CallReason reason : CallReason.values()) {
      keys.add("signaller.call.incoming-" + reason.key());
      keys.add("signaller.call.incoming-" + reason.key() + "-bar");
      keys.add("pids.board.signalbox.call-reason." + reason.key());
      if (reason != CallReason.GENERAL) {
        keys.add("signaller.call.wrong-" + reason.key());
      }
    }
    for (SignallerMessenger.Cue cue : SignallerMessenger.Cue.values()) {
      keys.add(cue.key());
    }
    keys.add("drive.signaller.result.detail");
    keys.add("drive.command.call.bad-reason");
    keys.add("drive.license.exam.signaller.deduct-call-wrong");
    for (String key : keys) {
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
  }

  private static Set<Reply> fitting(CallReason reason) {
    Set<Reply> out = EnumSet.noneOf(Reply.class);
    for (Reply reply : Reply.values()) {
      if (CallReplies.fits(reason, reply)) {
        out.add(reply);
      }
    }
    return out;
  }
}
