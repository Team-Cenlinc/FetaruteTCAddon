package org.fetarute.fetaruteTCAddon.drive.tutorial;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.hud.DriverStationHint;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;

/** 从驾驶会话取出教程判定要看的值。只读会话，不碰列车与玩家。 */
public final class TutorialSnapshots {

  private TutorialSnapshots() {}

  /**
   * 此刻的会话快照。
   *
   * @param nowTick 当前服务器 tick（关门动画与警惕装置按它判断）
   */
  public static TutorialSnapshot of(DriveSession session, long nowTick) {
    DriverLink link = session.driverLink();
    CabSystems cab = session.cab();
    Optional<DriverStationHint.Hint> hint =
        link == null ? Optional.empty() : DriverStationHint.of(link, session.isStopped());
    return TutorialSnapshot.builder()
        .setupMode(session.setupMode())
        .dispatch(link != null)
        .ato(session.isAto())
        .setupReady(session.setup().ready())
        .cab(cab.enabled())
        .manualCompressor(cab.enabled() && cab.air().manualCompressor())
        .compressorOn(cab.air().compressorSwitch())
        .brakeTestPassed(!cab.enabled() || cab.brakeTest().passed())
        .parkingApplied(cab.enabled() && cab.air().parkingApplied())
        .reverser(session.reverser())
        .handle(session.selector().current())
        .speedBps(session.speedBps())
        .stopped(session.isStopped())
        .doorsOpen(session.anyDoorOpen())
        .doorsClosing(session.doorsClosing(nowTick))
        .signalAcknowledgePending(link != null && link.signalAcknowledge().pending())
        .stationHint(hint.map(DriverStationHint.Hint::kind).orElse(null))
        .doorsRequired(
            hint.filter(found -> found.kind() == DriverStationHint.Kind.OPEN_DOORS)
                .map(found -> !"none".equals(found.variant()))
                .orElse(false))
        .departurePending(link != null && link.departurePrompt())
        .vigilanceWarning(cab.enabled() && cab.vigilance().warning(nowTick))
        .build();
  }
}
