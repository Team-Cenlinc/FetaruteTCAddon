package org.fetarute.fetaruteTCAddon.drive.session;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.coupler.DecoupleCrew;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;

/**
 * 解编站上驾驶员（或车掌）选继续哪一段：只记状态，动列车、动会话由会话管理器做。
 *
 * <p>一名值乘的人依次经过三步：到站开过门、这一趟结算后记为 {@link Ready}（解编等它才拆）；拆开后记为 {@link Choice}，限时 {@link #WINDOW}
 * 内选留下的一段、改驾解下的一段或结束；选了改驾、还没在那一段开始值乘时记为 {@link Switch}。期间两段车都扣着（{@link #holding}）。只在服务器主线程使用。
 */
public final class SplitChoices {

  /** 拆开后等选择的时限：过了就结束值乘（防挂机），两段车都交给调度。 */
  public static final Duration WINDOW = Duration.ofSeconds(60);

  /** 选了改驾（或连挂后交还）后开始值乘的时限：送不进驾驶室时期间可以自己入座后开始。 */
  public static final Duration SWITCH_WINDOW = Duration.ofSeconds(30);

  /** 选项。 */
  public enum Option {
    /** 继续值乘留下的一段（保留原车身份、继续本交路）。 */
    KEEP,
    /** 改为值乘解下的一段；留下的一段交给调度。 */
    SWITCH,
    /** 结束值乘，两段车都交给调度。 */
    END;

    public static Optional<Option> parse(String raw) {
      if (raw == null) {
        return Optional.empty();
      }
      try {
        return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ex) {
        return Optional.empty();
      }
    }
  }

  /**
   * 已在解编站开过门、结算过，等解编动手。
   *
   * @param trainName 列车
   * @param resume 在这一站中途结算的车次（选留下时从这一站接着记）；这一站就是终点、或没有任务时为空
   */
  public record Ready(String trainName, Optional<TaskKey> resume) {
    public Ready {
      Objects.requireNonNull(trainName, "trainName");
      resume = resume == null ? Optional.empty() : resume;
    }
  }

  /**
   * 拆开了，等选择。
   *
   * @param split 这次拆分
   * @param resume 同 {@link Ready#resume}
   * @param deadline 选择期限
   */
  public record Choice(DecoupleCrew.Split split, Optional<TaskKey> resume, Instant deadline) {
    public Choice {
      Objects.requireNonNull(split, "split");
      resume = resume == null ? Optional.empty() : resume;
      Objects.requireNonNull(deadline, "deadline");
    }

    public boolean involves(String trainName) {
      return trainName.equalsIgnoreCase(split.keptName())
          || trainName.equalsIgnoreCase(split.detachedName());
    }
  }

  /**
   * 送进另一列车的驾驶室、还没开始驾驶：分行后选了改驾解下的一段，或联挂后交还驾驶。
   *
   * @param trainName 要开始驾驶的列车
   * @param label 那一段的说明（分行时）
   * @param after 改驾前跑的车次：那一段开出别的车次时再记成任务；没有任务时为空
   * @param takeover 原来是直接接管（没有接续车次也接着开）
   * @param deadline 开始驾驶的期限
   * @param resumeTrip 开始驾驶后从这一站接着记列车此刻跑的车次（联挂后交还）；否则等开出下一趟
   * @param ato 原来以 ATO 驾驶：开始后照旧转为 ATO
   */
  public record Switch(
      String trainName,
      String label,
      Optional<TaskKey> after,
      boolean takeover,
      Instant deadline,
      boolean resumeTrip,
      boolean ato) {
    public Switch {
      Objects.requireNonNull(trainName, "trainName");
      label = label == null ? "" : label;
      after = after == null ? Optional.empty() : after;
      Objects.requireNonNull(deadline, "deadline");
    }
  }

  private final Map<UUID, Ready> ready = new HashMap<>();
  private final Map<UUID, Choice> choices = new HashMap<>();
  private final Map<UUID, Switch> switches = new HashMap<>();

  public void markReady(UUID playerId, Ready entry) {
    ready.put(playerId, entry);
  }

  /** 这名值乘的人已在这列车上结算过、等解编动手。 */
  public boolean readyFor(UUID playerId, String trainName) {
    Ready entry = ready.get(playerId);
    return entry != null && entry.trainName().equalsIgnoreCase(trainName);
  }

  public Optional<Ready> takeReady(UUID playerId) {
    return Optional.ofNullable(ready.remove(playerId));
  }

  /** 拆开了：开始限时选择。 */
  public Choice open(UUID playerId, DecoupleCrew.Split split, Instant now) {
    Optional<TaskKey> resume = takeReady(playerId).flatMap(Ready::resume);
    Choice choice = new Choice(split, resume, now.plus(WINDOW));
    choices.put(playerId, choice);
    return choice;
  }

  public Optional<Choice> choiceOf(UUID playerId) {
    return Optional.ofNullable(choices.get(playerId));
  }

  public boolean choosing(UUID playerId) {
    return choices.containsKey(playerId);
  }

  public Optional<Choice> closeChoice(UUID playerId) {
    return Optional.ofNullable(choices.remove(playerId));
  }

  /** 正在选择的人（副本，可边遍历边关闭）。 */
  public List<UUID> choosers() {
    return List.copyOf(choices.keySet());
  }

  public void beginSwitch(UUID playerId, Switch entry) {
    switches.put(playerId, entry);
  }

  /** 这名值乘的人正改驾这列车。 */
  public Optional<Switch> switchTo(UUID playerId, String trainName) {
    Switch entry = switches.get(playerId);
    return entry != null && entry.trainName().equalsIgnoreCase(trainName)
        ? Optional.of(entry)
        : Optional.empty();
  }

  public Optional<Switch> takeSwitch(UUID playerId) {
    return Optional.ofNullable(switches.remove(playerId));
  }

  /** 去掉已过期的改驾（送不进驾驶室、开不了值乘）。 */
  public void pruneSwitches(Instant now) {
    switches.values().removeIf(entry -> !now.isBefore(entry.deadline()));
  }

  /** 列车正等值乘的人选择或改驾时扣到几点；不扣时为空。 */
  public Optional<Instant> holding(String trainName) {
    if (trainName == null) {
      return Optional.empty();
    }
    for (Choice choice : choices.values()) {
      if (choice.involves(trainName)) {
        return Optional.of(choice.deadline());
      }
    }
    for (Switch entry : switches.values()) {
      if (entry.trainName().equalsIgnoreCase(trainName)) {
        return Optional.of(entry.deadline());
      }
    }
    return Optional.empty();
  }

  /** 值乘结束：这名值乘的人的结算、选择都作废（改驾进行中的保留：改驾本身就要先结束原来那一段）。 */
  public void forget(UUID playerId) {
    ready.remove(playerId);
    choices.remove(playerId);
  }
}
