package org.fetarute.fetaruteTCAddon.drive.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.crew.CrewDuty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("报名路考与练习前按在岗登记拦下")
class LicenseCrewBusyTest {

  private final CrewDuty crew = new CrewDuty();
  private final Set<UUID> driving = new HashSet<>();
  private final Set<UUID> driverTasks = new HashSet<>();
  private final Set<UUID> guarding = new HashSet<>();
  private final Set<UUID> guardTasks = new HashSet<>();
  private final Set<UUID> signallers = new HashSet<>();
  private final UUID id = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    crew.register(CrewDuty.Role.DRIVER, p -> driving.contains(p) || driverTasks.contains(p));
    crew.register(CrewDuty.Role.GUARD, p -> guarding.contains(p) || guardTasks.contains(p));
    crew.register(CrewDuty.Role.SIGNALLER, signallers::contains);
  }

  private String key() {
    return LicenseService.crewBusyKey(crew, id, driving::contains, guarding::contains);
  }

  @Test
  @DisplayName("没担任任何角色时可以报名")
  void free() {
    assertNull(key());
  }

  @Test
  @DisplayName("驾驶员与车掌：按正在当班还是只领了任务给出原来的说法")
  void driverAndGuardKeepTheirMessages() {
    driving.add(id);
    assertEquals("drive.license.exam.driving", key());
    driving.clear();
    driverTasks.add(id);
    assertEquals("drive.license.exam.has-task", key());
    driverTasks.clear();
    guarding.add(id);
    assertEquals("drive.license.exam.guard-on-duty", key());
    guarding.clear();
    guardTasks.add(id);
    assertEquals("drive.license.exam.has-guard-task", key());
  }

  @Test
  @DisplayName("值守信号楼时不能报名路考、车掌考试或练习")
  void signallerCannotSignUp() {
    signallers.add(id);
    assertEquals("drive.license.exam.signaller-on-duty", key());
  }
}
