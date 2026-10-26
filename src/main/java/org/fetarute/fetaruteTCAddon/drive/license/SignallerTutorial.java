package org.fetarute.fetaruteTCAddon.drive.license;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerShift;
import org.fetarute.fetaruteTCAddon.signaller.SignallerExaminer;

/**
 * 信号员练习的带练：在真实的控制台、真实的车流上一步步提示，做对一步才进下一步。本类不依赖服务器对象。
 *
 * <p>先做的事也算：例如还没讲到扣车就扣过车，讲到扣车时这一步当场完成。控制区里没有车站时，扣车与放行两步跳过（没有能扣的车）。
 * 全部步骤做完、最后用下岗命令下岗，练习才算完成；中途以别的方式下岗（离开控制台、下线）不算。
 */
final class SignallerTutorial {

  /** 认识控制台一步读多久后自动进入下一步。 */
  static final Duration SCREEN_READ = Duration.ofSeconds(12);

  /** 一步多久没完成就再提示一次。 */
  static final Duration REMINDER = Duration.ofSeconds(60);

  /** 练习的步骤，按先后。 */
  enum Step {
    /** 到控制台点一下，确认上岗。 */
    ON_DUTY,
    /** 认识控制台：标题栏、站场图、列车表、操作区（读一会儿自动继续）。 */
    SCREEN,
    /** 控制区在人工模式（上岗时已自动转为人工）。 */
    MANUAL,
    /** 在列车表里点一列车选中它。 */
    SELECT,
    /** 给在入口等候或正接近的车排列进路。 */
    SET_ROUTE,
    /** 扣住一列在站里停着的车。 */
    HOLD,
    /** 放行扣着的车。 */
    RELEASE,
    /** 给一条进路设自动通过。 */
    AUTO_PASS,
    /** 取消自动通过。 */
    AUTO_PASS_OFF,
    /** 回复一次呼叫（讲到这一步时会来一次模拟呼叫）。 */
    ANSWER_CALL,
    /** 用下岗命令下岗。 */
    OFF_DUTY;

    /** 语言键里的写法。 */
    String key() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
  }

  /** 推进的结果，按发生先后。 */
  sealed interface Change {
    /** 开始一步：讲这一步怎么做。上岗前还不知道控制区里有没有车站（总步数要跳过哪几步），总数为 0。 */
    record Started(Step step, int index, int total) implements Change {}

    /** 完成一步。 */
    record Completed(Step step) implements Change {}

    /** 这一步这次做不了，跳过（控制区里没有车站时的扣车、放行）。 */
    record Skipped(Step step) implements Change {}

    /** 这一步好一阵没完成：再提示一次。 */
    record Reminder(Step step) implements Change {}

    /** 全部做完。 */
    record Finished() implements Change {}
  }

  private final Set<Step> done = EnumSet.noneOf(Step.class);
  private boolean stations = true;
  private boolean onDutyOnce;
  private boolean manual;
  private Step current;
  private Instant since;
  private Instant reminded;
  private boolean finished;

  /** 开始带练：讲第一步（上岗）。 */
  List<Change> begin(Instant now) {
    List<Change> out = new ArrayList<>();
    start(Step.ON_DUTY, now, out);
    return out;
  }

  /**
   * 上岗了。
   *
   * @param manual 控制区已转为人工
   * @param stations 控制区里有车站
   */
  List<Change> dutyStarted(boolean manual, boolean stations, Instant now) {
    this.manual = manual;
    this.stations = stations;
    onDutyOnce = true;
    done.add(Step.ON_DUTY);
    return advance(now);
  }

  /** 值守中的一件事。 */
  List<Change> event(SignallerExaminer.Event event, Instant now) {
    switch (Objects.requireNonNull(event, "event")) {
      case MANUAL -> manual = true;
      case AUTO, IDLE_AUTO -> manual = false;
      case SELECTED -> done.add(Step.SELECT);
      case ROUTE_SET -> done.add(Step.SET_ROUTE);
      case HELD -> done.add(Step.HOLD);
      case RELEASED -> done.add(Step.RELEASE);
      case AUTO_PASS_ON -> done.add(Step.AUTO_PASS);
      case AUTO_PASS_OFF -> done.add(Step.AUTO_PASS_OFF);
      case CALL_ANSWERED, DRILL_ANSWERED -> done.add(Step.ANSWER_CALL);
      default -> {}
    }
    return advance(now);
  }

  /** 下岗了：用下岗命令下岗的才完成最后一步。 */
  List<Change> dutyEnded(SignallerShift.EndReason reason, Instant now) {
    if (reason == SignallerShift.EndReason.OFF_DUTY) {
      done.add(Step.OFF_DUTY);
    }
    return advance(now);
  }

  /** 每隔一会儿调一次：认识控制台一步按时间继续，久未完成的一步再提示。 */
  List<Change> tick(Instant now) {
    List<Change> out = new ArrayList<>(advance(now));
    if (current != null && !now.isBefore(reminded.plus(REMINDER))) {
      reminded = now;
      out.add(new Change.Reminder(current));
    }
    return out;
  }

  /** 全部步骤都做完了。 */
  boolean finished() {
    return finished;
  }

  /** 正在讲的一步；做完时为空。 */
  Step current() {
    return current;
  }

  /** 还没完成的步骤（跳过的不算），按先后。 */
  List<Step> remaining() {
    List<Step> out = new ArrayList<>();
    for (Step step : Step.values()) {
      if (!done.contains(step) && relevant(step) && !(step == Step.MANUAL && manual)) {
        out.add(step);
      }
    }
    return out;
  }

  private List<Change> advance(Instant now) {
    List<Change> out = new ArrayList<>();
    while (current != null && satisfied(current, now)) {
      done.add(current);
      out.add(new Change.Completed(current));
      Step next = next(current, out);
      if (next == null) {
        current = null;
        finished = true;
        out.add(new Change.Finished());
        break;
      }
      start(next, now, out);
    }
    return out;
  }

  /** 下一步；这次做不了的跳过。 */
  private Step next(Step step, List<Change> out) {
    for (int i = step.ordinal() + 1; i < Step.values().length; i++) {
      Step candidate = Step.values()[i];
      if (relevant(candidate)) {
        return candidate;
      }
      out.add(new Change.Skipped(candidate));
    }
    return null;
  }

  private void start(Step step, Instant now, List<Change> out) {
    current = step;
    since = now;
    reminded = now;
    out.add(new Change.Started(step, index(step), total()));
  }

  private boolean satisfied(Step step, Instant now) {
    return switch (step) {
      case SCREEN -> !now.isBefore(since.plus(SCREEN_READ));
      case MANUAL -> manual;
      default -> done.contains(step);
    };
  }

  private boolean relevant(Step step) {
    return stations || (step != Step.HOLD && step != Step.RELEASE);
  }

  /** 这一步是第几步（从 1 起，跳过的不数）。 */
  private int index(Step step) {
    int index = 0;
    for (Step candidate : Step.values()) {
      if (relevant(candidate)) {
        index++;
      }
      if (candidate == step) {
        return index;
      }
    }
    return index;
  }

  private int total() {
    if (!onDutyOnce) {
      return 0;
    }
    int total = 0;
    for (Step candidate : Step.values()) {
      if (relevant(candidate)) {
        total++;
      }
    }
    return total;
  }
}
