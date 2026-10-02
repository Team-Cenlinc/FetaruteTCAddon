package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildOptions;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDutyPlanner;
import org.junit.jupiter.api.Test;

/**
 * 计划窗口的命令层校验。
 *
 * <p>{@code HH:mm} 解析为了让末班能写成 {@code 25:00} 放宽到了 47 点，而 {@link TimetableBuildOptions} 要求首班在一天之内。
 * 此前两者之间没有校验，{@code --start 24:00} 越过命令层，在主线程构造选项时抛出没人接的异常。这里钉住的是：
 * 命令层放行的窗口，选项层一定接受；选项层会拒绝的，命令层先拦下并给出可读的提示。
 */
class FtaTimetableCommandServiceWindowTest {

  private static final int HOUR = 3600;

  private static TimetableBuildOptions options(int start, int end) {
    return new TimetableBuildOptions(
        start,
        end,
        Duration.ofSeconds(TimetableBuildOptions.DEFAULT_HEADWAY_SECONDS),
        Duration.ofSeconds(TimetableBuildOptions.DEFAULT_DWELL_SECONDS),
        VehicleDutyPlanner.Limits.defaults(),
        "",
        ZoneOffset.UTC);
  }

  /** 首班过了午夜就拦下，并告诉对方跨零点该写在哪里；这些值正是选项层会拒绝的。 */
  @Test
  void startAtOrAfterMidnightIsRejectedBeforeOptionsAreBuilt() {
    for (int start : new int[] {24 * HOUR, 25 * HOUR + 30 * 60, 47 * HOUR + 59 * 60}) {
      Optional<String> problem = FtaTimetableCommand.serviceWindowProblem(start, start + HOUR);

      assertTrue(problem.isPresent(), "首班 " + start + " 秒应被命令层拦下");
      assertTrue(problem.get().contains("23:59"), problem.get());
      assertTrue(problem.get().contains("--end 25:00"), "提示里要说明跨零点写在末班上：" + problem.get());
      assertThrows(IllegalArgumentException.class, () -> options(start, start + HOUR));
    }
  }

  /** 命令层放行的窗口（含首班 23:59、末班跨零点到 47:59），选项层都能构造。 */
  @Test
  void everyWindowTheCommandAcceptsIsAcceptedByTheOptions() {
    int[][] windows = {
      {0, 60},
      {5 * HOUR, 23 * HOUR},
      {5 * HOUR, 25 * HOUR},
      {23 * HOUR + 59 * 60, 47 * HOUR + 59 * 60},
    };
    for (int[] window : windows) {
      assertEquals(
          Optional.empty(),
          FtaTimetableCommand.serviceWindowProblem(window[0], window[1]),
          "窗口 " + window[0] + "–" + window[1] + " 应被放行");
      assertDoesNotThrow(() -> options(window[0], window[1]));
    }
  }

  /** 末班不晚于首班仍按原来的提示拒绝。 */
  @Test
  void endNotAfterStartIsRejected() {
    for (int[] window : new int[][] {{5 * HOUR, 5 * HOUR}, {6 * HOUR, 5 * HOUR}}) {
      Optional<String> problem = FtaTimetableCommand.serviceWindowProblem(window[0], window[1]);

      assertTrue(problem.isPresent(), "末班不晚于首班应被拒绝");
      assertTrue(problem.get().contains("25:00"), problem.get());
    }
  }
}
