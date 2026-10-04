package org.fetarute.fetaruteTCAddon.drive.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("新手教程的步骤推进与裁剪")
class TutorialEngineTest {

  private final TutorialEngine engine = new TutorialEngine();
  private long now;

  /** 按驾驶会话管理器的节奏（每 5 tick）推进一段时间。 */
  private List<TutorialEvent> run(TutorialSnapshot snapshot, long ticks) {
    List<TutorialEvent> events = new ArrayList<>();
    for (long elapsed = 0; elapsed < ticks; elapsed += 5) {
      events.addAll(engine.tick(snapshot, now));
      now += 5;
    }
    return events;
  }

  private static TutorialSnapshot.Builder free() {
    return TutorialSnapshot.builder();
  }

  private static TutorialSnapshot.Builder dispatch() {
    return TutorialSnapshot.builder().dispatch(true);
  }

  private static TutorialEvent.StepStarted lastStarted(List<TutorialEvent> events) {
    TutorialEvent.StepStarted found = null;
    for (TutorialEvent event : events) {
      if (event instanceof TutorialEvent.StepStarted started) {
        found = started;
      }
    }
    if (found == null) {
      throw new AssertionError("没有开始新的一步: " + events);
    }
    return found;
  }

  private static void assertStarted(
      List<TutorialEvent> events, TutorialStep step, int index, int total) {
    TutorialEvent.StepStarted started = lastStarted(events);
    assertEquals(step, started.step(), events.toString());
    assertEquals(index, started.index(), "第几步");
    assertEquals(total, started.total(), "总步数");
  }

  private TutorialStep current() {
    return engine.current().orElseThrow();
  }

  private List<TutorialStep> plan(TutorialSnapshot snapshot) {
    return engine.pending(snapshot);
  }

  @Test
  @DisplayName("standard 级非调度列车：从启动到结束驾驶，每一步都要真的做到")
  void standardFreeTrainWalkthrough() {
    TutorialSnapshot cold = free().setupReady(false).build();
    List<TutorialEvent> first = run(cold, 5);
    assertStarted(first, TutorialStep.START_BUTTON, 1, 9);
    assertEquals("start-button", lastStarted(first).key());
    run(cold, 200);
    assertEquals(TutorialStep.START_BUTTON, current(), "没按启动按钮就不前进");

    List<TutorialEvent> started = run(free().build(), 5);
    assertTrue(started.contains(new TutorialEvent.StepCompleted(TutorialStep.START_BUTTON, false)));
    assertStarted(started, TutorialStep.REVERSER, 2, 9);

    run(free().build(), 30);
    assertEquals(TutorialStep.REVERSER, current(), "已经满足的操作步骤也至少显示一会儿");
    assertStarted(run(free().build(), 20), TutorialStep.TRACTION, 3, 9);

    run(free().handle(Notch.P2).build(), 100);
    assertEquals(TutorialStep.TRACTION, current(), "拉了牵引但车没动（例如车门开着）不算起步");
    assertStarted(run(free().handle(Notch.P2).speedBps(2.0).build(), 5), TutorialStep.COAST, 4, 9);

    run(free().handle(Notch.N).speedBps(3.0).build(), 60);
    assertEquals(TutorialStep.SERVICE_BRAKE, current());

    run(free().handle(Notch.B2).speedBps(2.0).build(), 60);
    assertEquals(TutorialStep.SERVICE_BRAKE, current(), "还没停稳");
    assertStarted(
        run(free().handle(Notch.B2).speedBps(0).build(), 5), TutorialStep.EMERGENCY_BRAKE, 6, 9);

    run(free().build(), TutorialEngine.INFO_STEP_TICKS - 10);
    assertEquals(TutorialStep.EMERGENCY_BRAKE, current(), "说明步骤读完才继续");
    assertStarted(run(free().build(), 10), TutorialStep.OPEN_DOORS, 7, 9);

    assertStarted(run(free().doorsOpen(true).build(), 45), TutorialStep.CLOSE_DOORS, 8, 9);
    run(free().doorsClosing(true).build(), 100);
    assertEquals(TutorialStep.CLOSE_DOORS, current(), "关门动画放完才算关好");
    List<TutorialEvent> last = run(free().build(), 5);
    assertStarted(last, TutorialStep.FINISH, 9, 9);
    assertEquals("finish", lastStarted(last).key());

    List<TutorialEvent> waiting = run(free().build(), TutorialEngine.REMINDER_TICKS * 3);
    assertTrue(waiting.isEmpty(), "结束驾驶一步不靠判定，也不反复提醒: " + waiting);
    assertEquals(List.of(new TutorialEvent.Finished()), engine.sessionEnded());
    assertTrue(engine.ended());
    assertTrue(engine.tick(free().build(), now).isEmpty());
  }

  @Test
  @DisplayName("列车已启动时不教启动；第一步就是换向手柄")
  void alreadyStartedSkipsStartStep() {
    TutorialSnapshot ready = free().build();
    assertStarted(run(ready, 5), TutorialStep.REVERSER, 1, 8);
    assertFalse(plan(ready).contains(TutorialStep.START_BUTTON));
  }

  @Test
  @DisplayName("simulation 级：逐项接通、制动试验、缓解停放制动；机车另教打开压缩机")
  void simulationLevelSteps() {
    TutorialSnapshot cold =
        free()
            .setupMode(SimulationLevel.SetupMode.MANUAL)
            .setupReady(false)
            .cab(true)
            .brakeTestPassed(false)
            .parkingApplied(true)
            .build();
    assertStarted(run(cold, 5), TutorialStep.SETUP_SWITCHES, 1, 11);
    assertEquals(
        List.of(
            TutorialStep.SETUP_SWITCHES,
            TutorialStep.BRAKE_TEST,
            TutorialStep.RELEASE_PARKING,
            TutorialStep.REVERSER),
        plan(cold).subList(0, 4));

    TutorialEngine loco = new TutorialEngine();
    TutorialSnapshot locoCold =
        free()
            .setupMode(SimulationLevel.SetupMode.MANUAL)
            .setupReady(false)
            .cab(true)
            .manualCompressor(true)
            .compressorOn(false)
            .brakeTestPassed(false)
            .parkingApplied(true)
            .build();
    TutorialEvent.StepStarted first = lastStarted(loco.tick(locoCold, 0));
    assertEquals(12, first.total());
    assertEquals(TutorialStep.COMPRESSOR, loco.pending(locoCold).get(1));
  }

  @Test
  @DisplayName("前提步骤提前做完时轮到它就略过，总步数随之减少")
  void prerequisiteDoneEarlyIsSkipped() {
    TutorialSnapshot.Builder sim =
        free()
            .setupMode(SimulationLevel.SetupMode.MANUAL)
            .setupReady(false)
            .cab(true)
            .brakeTestPassed(false)
            .parkingApplied(true);
    run(sim.build(), 5);
    // 接通全部系统之前先缓解了停放制动。
    run(sim.parkingApplied(false).build(), 20);
    List<TutorialEvent> events = run(sim.setupReady(true).build(), 30);
    assertStarted(events, TutorialStep.BRAKE_TEST, 2, 10);
    assertStarted(run(sim.brakeTestPassed(true).build(), 45), TutorialStep.REVERSER, 3, 10);
  }

  @Test
  @DisplayName("调度列车（人工驾驶）：不教换向与开关门，起步与结束用调度列车的说法")
  void dispatchManualSteps() {
    TutorialSnapshot hot = dispatch().setupReady(false).build();
    assertStarted(run(hot, 5), TutorialStep.START_BUTTON, 1, 6);
    assertEquals(
        List.of(
            TutorialStep.START_BUTTON,
            TutorialStep.TRACTION,
            TutorialStep.COAST,
            TutorialStep.SERVICE_BRAKE,
            TutorialStep.EMERGENCY_BRAKE,
            TutorialStep.FINISH),
        plan(hot));
    TutorialEvent.StepStarted traction = lastStarted(run(dispatch().build(), 45));
    assertEquals(TutorialStep.TRACTION, traction.step());
    assertEquals("traction-dispatch", traction.key());
    assertEquals("finish-dispatch", TutorialStep.FINISH.key(dispatch().build()));
    assertEquals("service-brake-dispatch", TutorialStep.SERVICE_BRAKE.key(dispatch().build()));
    assertEquals("coast", TutorialStep.COAST.key(dispatch().build()));
  }

  @Test
  @DisplayName("ATO：只讲 ATO 与结束；中途转 ATO 时人工驾驶的步骤让开，转回人工后接着教")
  void atoSteps() {
    TutorialSnapshot ato = dispatch().ato(true).setupReady(false).build();
    assertStarted(run(ato, 5), TutorialStep.ATO_OVERVIEW, 1, 2);
    assertStarted(run(ato, TutorialEngine.INFO_STEP_TICKS), TutorialStep.FINISH, 2, 2);

    TutorialEngine switching = new TutorialEngine();
    switching.tick(dispatch().build(), 0);
    assertEquals(TutorialStep.TRACTION, switching.current().orElseThrow());
    TutorialEvent.StepStarted toAto = lastStarted(switching.tick(dispatch().ato(true).build(), 5));
    assertEquals(TutorialStep.ATO_OVERVIEW, toAto.step());
    TutorialEvent.StepStarted back = lastStarted(switching.tick(dispatch().build(), 10));
    assertEquals(TutorialStep.TRACTION, back.step(), "转回人工驾驶后回到起步");
  }

  @Test
  @DisplayName("惰行要在行驶中保持 N 一段时间；一碰就走不算")
  void coastNeedsSustainedNeutral() {
    run(free().build(), 5);
    engine.skip(free().build(), now);
    assertEquals(TutorialStep.TRACTION, current());
    run(free().handle(Notch.P1).speedBps(2.0).build(), 45);
    assertEquals(TutorialStep.COAST, current());

    run(free().handle(Notch.N).speedBps(3.0).build(), 10);
    run(free().handle(Notch.B1).speedBps(2.5).build(), 40);
    run(free().handle(Notch.N).speedBps(2.5).build(), 10);
    assertEquals(TutorialStep.COAST, current(), "每次保持都不够久");
    run(free().handle(Notch.N).speedBps(2.5).build(), 25);
    assertEquals(TutorialStep.SERVICE_BRAKE, current());

    TutorialEngine stopped = new TutorialEngine();
    stopped.tick(free().build(), 0);
    stopped.skip(free().build(), 0);
    stopped.skip(free().build(), 0);
    assertEquals(TutorialStep.COAST, stopped.current().orElseThrow());
    for (long tick = 5; tick < 200; tick += 5) {
      stopped.tick(free().handle(Notch.N).build(), tick);
    }
    assertEquals(TutorialStep.COAST, stopped.current().orElseThrow(), "停着回 N 不算惰行");
  }

  @Test
  @DisplayName("制动停车要用常用制动；只用紧急制动停下不算")
  void serviceBrakeMustBeUsed() {
    run(free().build(), 5);
    engine.skip(free().build(), now);
    engine.skip(free().build(), now);
    engine.skip(free().build(), now);
    assertEquals(TutorialStep.SERVICE_BRAKE, current());

    run(free().handle(Notch.EB).speedBps(3.0).build(), 20);
    run(free().handle(Notch.EB).speedBps(0).build(), 60);
    assertEquals(TutorialStep.SERVICE_BRAKE, current(), "紧急制动停车不算");

    run(free().handle(Notch.P1).speedBps(2.0).build(), 20);
    run(free().handle(Notch.B3).speedBps(1.0).build(), 20);
    run(free().handle(Notch.B3).speedBps(0).build(), 5);
    assertEquals(TutorialStep.EMERGENCY_BRAKE, current());
  }

  @Test
  @DisplayName("跳过：跳过开门连关门一起跳过；在最后一步跳过即完成教程")
  void skipping() {
    run(free().build(), 5);
    List<TutorialEvent> skipped = engine.skip(free().build(), now);
    assertEquals(new TutorialEvent.StepCompleted(TutorialStep.REVERSER, true), skipped.get(0));
    assertStarted(skipped, TutorialStep.TRACTION, 2, 8);

    engine.skip(free().build(), now);
    engine.skip(free().build(), now);
    engine.skip(free().build(), now);
    List<TutorialEvent> info = engine.skip(free().build(), now);
    assertStarted(info, TutorialStep.OPEN_DOORS, 6, 8);
    List<TutorialEvent> doors = engine.skip(free().build(), now);
    assertStarted(doors, TutorialStep.FINISH, 8, 8);
    assertTrue(engine.finished().contains(TutorialStep.CLOSE_DOORS));

    assertEquals(List.of(new TutorialEvent.Finished()), engine.skip(free().build(), now));
    assertTrue(engine.ended());
    assertTrue(engine.sessionEnded().isEmpty(), "已完成的教程不再报中断");
  }

  @Test
  @DisplayName("没有车门动画：正在练开关门时提示并进入下一步；还没轮到时直接不教")
  void doorsUnavailable() {
    run(free().build(), 5);
    for (int i = 0; i < 5; i++) {
      engine.skip(free().build(), now);
    }
    assertEquals(TutorialStep.OPEN_DOORS, current());
    assertEquals(List.of(new TutorialEvent.DoorsUnavailable()), engine.doorsUnavailable());
    assertTrue(engine.doorsUnavailable().isEmpty(), "只提示一次");
    assertStarted(run(free().build(), 5), TutorialStep.FINISH, 6, 6);

    TutorialEngine early = new TutorialEngine();
    early.tick(free().build(), 0);
    assertTrue(early.doorsUnavailable().isEmpty());
    assertFalse(early.pending(free().build()).contains(TutorialStep.OPEN_DOORS));
    assertFalse(early.pending(free().build()).contains(TutorialStep.CLOSE_DOORS));
  }

  @Test
  @DisplayName("没到最后一步就结束驾驶：教程中断")
  void sessionEndBeforeFinishInterrupts() {
    run(free().build(), 5);
    assertInstanceOf(TutorialEvent.Interrupted.class, engine.sessionEnded().get(0));
    assertTrue(engine.current().isEmpty());
  }

  @Test
  @DisplayName("一步做了很久还没做到：只用副标题再提示一次")
  void reminders() {
    run(free().build(), 5);
    engine.skip(free().build(), now);
    List<TutorialEvent> events = run(free().build(), TutorialEngine.REMINDER_TICKS + 5);
    assertEquals(List.of(new TutorialEvent.Reminder(TutorialStep.TRACTION, "traction")), events);
  }

  @Test
  @DisplayName("原先略过的前提又不满足了（中途关机）：先回去教它，再接着原来的一步")
  void lostPrerequisiteComesBack() {
    run(free().build(), 5);
    assertEquals(TutorialStep.REVERSER, current());
    assertStarted(run(free().setupReady(false).build(), 5), TutorialStep.START_BUTTON, 1, 9);
    List<TutorialEvent> back = run(free().build(), 45);
    assertTrue(back.contains(new TutorialEvent.StepCompleted(TutorialStep.START_BUTTON, false)));
    assertStarted(back, TutorialStep.REVERSER, 2, 9);
  }
}
