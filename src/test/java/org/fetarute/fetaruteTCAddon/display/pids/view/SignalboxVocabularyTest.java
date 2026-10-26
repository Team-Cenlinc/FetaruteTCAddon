package org.fetarute.fetaruteTCAddon.display.pids.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import org.fetarute.fetaruteTCAddon.display.pids.fixtures.SignalboxFixtures;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.drive.crew.CallReason;
import org.fetarute.fetaruteTCAddon.drive.crew.CallerRole;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 控制台文案（{@code pids.board.signalbox.*}）中英文语言文件都齐全：每种状态、按钮、回复、提示都有文案。 */
class SignalboxVocabularyTest {

  private static final String PREFIX = "pids.board.signalbox.";

  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  void everyConsoleTextExists(String locale) {
    PidsVocabulary vocabulary = new PidsVocabulary(SignalboxFixtures.language(locale));
    SignalboxView.Labels labels = vocabulary.signalboxLabels();
    List<String> texts = new ArrayList<>();
    texts.add(labels.manual());
    texts.add(labels.auto());
    texts.add(labels.signaller());
    texts.add(labels.unattended());
    texts.add(labels.call());
    texts.add(labels.headerTrain());
    texts.add(labels.headerDestination());
    texts.add(labels.headerStatus());
    texts.add(labels.headerDelay());
    texts.add(labels.onTime());
    texts.add(labels.noTrains());
    texts.add(labels.routeBlockedBy());
    texts.addAll(labels.statuses().values());
    texts.addAll(labels.notices().values());
    for (Names names :
        List.of(
            labels.toAuto(),
            labels.toManual(),
            vocabulary.signalboxNotReady(),
            vocabulary.signalboxNoZone())) {
      texts.add(names.primary());
    }
    labels.buttons().values().forEach(names -> texts.add(names.primary()));
    labels.replies().values().forEach(names -> texts.add(names.primary()));

    assertEquals(SignalboxState.RowStatus.values().length, labels.statuses().size());
    assertEquals(SignalboxState.NoticeKind.values().length, labels.notices().size());
    assertEquals(SignalboxState.Button.values().length - 1, labels.buttons().size(), "模式按钮另有两条");
    assertEquals(SignalboxState.Reply.values().length, labels.replies().size());
    for (String text : texts) {
      assertFalse(text.startsWith(PREFIX), () -> locale + " 缺少文案键 " + text);
      assertFalse(text.isBlank(), () -> locale + " 有空文案");
    }
  }

  @Test
  void placeholdersAreKeptForTheScreenToFillIn() {
    SignalboxView.Labels labels =
        new PidsVocabulary(SignalboxFixtures.language("zh_CN")).signalboxLabels();
    assertEquals("值守：<name>", labels.signaller());
    assertEquals("<train> <caller>呼叫：<reason>", labels.call());
    assertEquals("车掌", labels.callers().get(CallerRole.GUARD));
    assertEquals("车门故障", labels.callReasons().get(CallReason.DOORS));
    assertEquals("入口等待 <seconds> 秒", labels.statuses().get(SignalboxState.RowStatus.WAITING));
    assertEquals("进路被 <train> 占用", labels.routeBlockedBy());
  }
}
