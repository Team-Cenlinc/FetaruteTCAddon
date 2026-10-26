package org.fetarute.fetaruteTCAddon.drive.crew;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.junit.jupiter.api.Test;

/** 信号员越站不能越过乘务员上下车的站：领了任务在站台等这一班的人要上车，值乘到这一站交班的人要下车。 */
class CrewChangesTest {

  private static final TaskKey TRIP =
      new TaskKey(UUID.randomUUID(), "1234", LocalDate.of(2026, 10, 24));
  private static final TaskKey OTHER =
      new TaskKey(UUID.randomUUID(), "1236", LocalDate.of(2026, 10, 24));

  @Test
  void crewWaitingForThisTripBoardsAtTheirTakeoverStation() {
    List<CrewChanges.Duty> duties = List.of(CrewChanges.Duty.waiting(TRIP, "SURC", "PPK"));

    assertTrue(CrewChanges.at(duties, Optional.of(TRIP), "T1", "SURC", "PPK"));
    assertFalse(CrewChanges.at(duties, Optional.of(TRIP), "T1", "SURC", "HHU"));
    assertFalse(CrewChanges.at(duties, Optional.of(OTHER), "T1", "SURC", "PPK"), "等的是别的车次");
    assertFalse(CrewChanges.at(duties, Optional.empty(), "T1", "SURC", "PPK"), "列车没有绑定车次");
    assertFalse(CrewChanges.at(duties, Optional.of(TRIP), "T1", "OFL", "PPK"), "别的运营商的同码车站");
  }

  @Test
  void crewOnBoardLeaveAtTheirHandoverStation() {
    List<CrewChanges.Duty> duties = List.of(CrewChanges.Duty.onBoard("t1", "SURC", "HHU"));

    assertTrue(CrewChanges.at(duties, Optional.empty(), "T1", "SURC", "HHU"), "车名不分大小写");
    assertFalse(CrewChanges.at(duties, Optional.empty(), "T1", "SURC", "PPK"));
    assertFalse(CrewChanges.at(duties, Optional.empty(), "T2", "SURC", "HHU"));
    assertFalse(
        CrewChanges.at(
            List.of(CrewChanges.Duty.onBoard("T1", "SURC", "")),
            Optional.empty(),
            "T1",
            "SURC",
            "HHU"),
        "开到终点站的任务没有交班站");
  }
}
