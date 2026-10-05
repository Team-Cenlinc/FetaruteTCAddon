package org.fetarute.fetaruteTCAddon.display.pids.bulletin;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 公告用例的样本：生存中部铁路（SURC）蒲塘桥港的两条公告。 */
public final class PidsBulletinFixtures {

  public static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  public static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

  private PidsBulletinFixtures() {}

  /** 一般公告：蒲塘桥港 2 号出入口临时关闭。 */
  public static PidsBulletin exitClosed() {
    return bulletin(
        Set.of("PPK"),
        Set.of(),
        PidsBulletin.Level.NORMAL,
        new PidsBulletin.Text("2 号出入口临时关闭", "Exit 2 temporarily closed"),
        new PidsBulletin.Text(
            "10 月 5 日 22:00 至次日 06:00 进行设备检修，期间 2 号出入口关闭，请经 1 号出入口进出车站。",
            "Exit 2 is closed for maintenance from 22:00 on 5 Oct to 06:00 the next day."
                + " Please use Exit 1."),
        Optional.empty(),
        Optional.empty());
  }

  /** 重要公告：MT 线部分停运。 */
  public static PidsBulletin lineSuspended() {
    return bulletin(
        Set.of(),
        Set.of("MT"),
        PidsBulletin.Level.IMPORTANT,
        new PidsBulletin.Text("MT 线部分停运", "Part of Line MT suspended"),
        new PidsBulletin.Text(
            "因信号设备故障，蒲塘桥港至南渡区段暂停运营，恢复时间另行通知。请改乘 WS 线前往南渡。",
            "No service between Port Pyutocor and Nam Toa due to a signal fault."
                + " Please take the Waterside Line to Nam Toa."),
        Optional.empty(),
        Optional.empty());
  }

  public static PidsBulletin bulletin(
      Set<String> stations,
      Set<String> lines,
      PidsBulletin.Level level,
      PidsBulletin.Text title,
      PidsBulletin.Text body,
      Optional<Instant> startsAt,
      Optional<Instant> endsAt) {
    return new PidsBulletin(
        UUID.randomUUID(),
        COMPANY,
        "surc",
        stations,
        lines,
        level,
        title,
        body,
        startsAt,
        endsAt,
        Optional.empty(),
        NOW,
        NOW);
  }
}
