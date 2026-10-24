package org.fetarute.fetaruteTCAddon.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.event.SignallerDutyEndedEvent;
import org.fetarute.fetaruteTCAddon.api.event.SignallerDutyStartEvent;
import org.fetarute.fetaruteTCAddon.api.signaller.SignallerApi;
import org.fetarute.fetaruteTCAddon.signaller.SignallerService;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;
import org.junit.jupiter.api.Test;

/** 信号员 API 把信号员服务发布的概况换成对外的写法；占位实现什么都查不到。 */
class SignallerApiImplTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final Instant SINCE = Instant.parse("2026-10-22T08:00:00Z");

  private static SignallerService.BoxStatus status(SignalboxState.Mode mode) {
    return new SignallerService.BoxStatus(
        "SURC",
        "PPK-SPB",
        "蒲塘桥港—主城湾",
        mode,
        Optional.of("Sig"),
        2,
        1,
        1,
        Optional.of(PLAYER),
        Optional.of(SINCE),
        List.of("T-1234", "T-3310"),
        List.of("T-9001"));
  }

  @Test
  void aBoxIsReportedAsItIs() {
    SignallerApi.SignalBoxView view = SignallerApiImpl.view(status(SignalboxState.Mode.MANUAL));

    assertEquals("SURC", view.operatorCode());
    assertEquals("PPK-SPB", view.zoneCode());
    assertEquals("蒲塘桥港—主城湾", view.name());
    assertEquals(SignallerApi.Mode.MANUAL, view.mode());
    assertEquals(Optional.of(PLAYER), view.signallerId());
    assertEquals(Optional.of("Sig"), view.signallerName());
    assertEquals(Optional.of(SINCE), view.onDutySince());
    assertEquals(List.of("T-1234", "T-3310"), view.waitingTrains());
    assertEquals(1, view.permits());
    assertEquals(List.of("T-9001"), view.heldTrains());
  }

  @Test
  void aTrainIsWaitingOnlyInAManualBoxWhateverTheCase() {
    List<SignallerApi.SignalBoxView> manual =
        List.of(SignallerApiImpl.view(status(SignalboxState.Mode.MANUAL)));
    List<SignallerApi.SignalBoxView> auto =
        List.of(SignallerApiImpl.view(status(SignalboxState.Mode.AUTO)));

    assertTrue(SignallerApiImpl.waitingIn(manual, "t-1234"));
    assertFalse(SignallerApiImpl.waitingIn(manual, "T-9001"), "扣在站里的不是在入口等");
    assertFalse(SignallerApiImpl.waitingIn(auto, "T-1234"), "自动模式下不拦");
    assertFalse(SignallerApiImpl.waitingIn(manual, " "));
  }

  @Test
  void theUnavailableApiFindsNothing() {
    SignallerApi api = SignallerApi.UNAVAILABLE;

    assertFalse(api.enabled());
    assertEquals(List.of(), api.boxes());
    assertTrue(api.box("SURC", "PPK-SPB").isEmpty());
    assertTrue(api.dutyOf(PLAYER).isEmpty());
    assertFalse(api.waitingForSignaller("T-1234"));
  }

  @Test
  void eventsHaveTheirOwnHandlerLists() {
    SignallerDutyStartEvent start =
        new SignallerDutyStartEvent(PLAYER, "SURC", "PPK-SPB", "蒲塘桥港—主城湾", true);
    SignallerDutyEndedEvent ended =
        new SignallerDutyEndedEvent(
            PLAYER,
            "SURC",
            "PPK-SPB",
            "蒲塘桥港—主城湾",
            SINCE,
            SINCE.plusSeconds(600),
            "OFF_DUTY",
            12,
            9,
            1,
            2,
            3,
            1,
            0);

    assertTrue(start.isManual());
    assertEquals(SignallerDutyStartEvent.getHandlerList(), start.getHandlers());
    assertEquals(SignallerDutyEndedEvent.getHandlerList(), ended.getHandlers());
    assertEquals(600, ended.getDuration().getSeconds());
    assertEquals("OFF_DUTY", ended.getReason());
    assertEquals(12, ended.getTrainsHandled());
  }
}
