package org.fetarute.fetaruteTCAddon.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildProgress;

/**
 * 正在进行的编表：{@code /fta timetable status} 从这里读进度。
 *
 * <p>同一条线的同一个 code 同时只编一份：两份编完会先后落库互相覆盖，而重复下令多半是以为上一次卡住了。
 *
 * <p>锁一直持有到落库事务结束（成功、被拒或出错），不是编完就放：落库期间再 build，读不到正在写的草稿，会另起一个表 ID， 编完保存时撞上同线同 code 的唯一约束。
 *
 * <p>取消立刻解锁：被取消的任务即使编表线程还没停下，也不会再落库（发起处在落库前用 {@link TimetableBuildProgress#beginSaving()}
 * 抢占），所以同一张表可以马上重新 build。已开始保存的取消不了。
 */
final class TimetableBuildJobs {

  /**
   * 一次编表。
   *
   * @param id 任务 ID
   * @param scope 显示用的范围（{@code 线路,线路/code}）
   * @param requester 发令者
   * @param lineIds 涉及的线路
   * @param code 时刻表 code（大小写不敏感）
   * @param progress 进度
   */
  record Job(
      UUID id,
      String scope,
      String requester,
      Set<UUID> lineIds,
      String code,
      TimetableBuildProgress progress) {
    Job {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(progress, "progress");
      lineIds = Set.copyOf(lineIds);
      code = code == null ? "" : code;
    }

    /** 命令里引用任务用的短号：ID 前 8 位。 */
    String shortId() {
      return id.toString().substring(0, 8);
    }

    boolean overlaps(Set<UUID> otherLines, String otherCode) {
      if (!code.equalsIgnoreCase(otherCode)) {
        return false;
      }
      for (UUID line : otherLines) {
        if (lineIds.contains(line)) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * 一次取消请求的结果。
   *
   * @param job 对上的任务
   * @param stopped 取消成功为 true；已开始保存、取消不了为 false（任务照常进行，锁不放）
   */
  record Cancellation(Job job, boolean stopped) {}

  private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();

  /**
   * 登记一次编表。
   *
   * @return 与已有任务撞了同一条线的同一个 code 时为空，并且不登记
   */
  synchronized Optional<Job> start(String scope, String requester, Set<UUID> lineIds, String code) {
    for (Job running : jobs.values()) {
      if (running.overlaps(lineIds, code)) {
        return Optional.empty();
      }
    }
    Job job =
        new Job(UUID.randomUUID(), scope, requester, lineIds, code, new TimetableBuildProgress());
    jobs.put(job.id(), job);
    return Optional.of(job);
  }

  /** 已在跑、与给定范围撞车的任务。 */
  Optional<Job> conflicting(Set<UUID> lineIds, String code) {
    for (Job running : jobs.values()) {
      if (running.overlaps(lineIds, code)) {
        return Optional.of(running);
      }
    }
    return Optional.empty();
  }

  void finish(UUID id) {
    jobs.remove(id);
  }

  /**
   * 取消一个任务并立刻解锁。
   *
   * @param ref 短号（ID 前缀）或范围（{@code 线路/code}，大小写不敏感）
   * @return 对上的任务与是否取消成功；没有或不唯一时为空
   */
  synchronized Optional<Cancellation> cancel(String ref) {
    if (ref == null || ref.isBlank()) {
      return Optional.empty();
    }
    String key = ref.trim();
    List<Job> matches = new ArrayList<>();
    for (Job job : jobs.values()) {
      if (job.id().toString().startsWith(key.toLowerCase(java.util.Locale.ROOT))
          || job.scope().equalsIgnoreCase(key)) {
        matches.add(job);
      }
    }
    if (matches.size() != 1) {
      return Optional.empty();
    }
    Job job = matches.get(0);
    if (!job.progress().cancel()) {
      return Optional.of(new Cancellation(job, false));
    }
    jobs.remove(job.id());
    return Optional.of(new Cancellation(job, true));
  }

  /** 全部任务，先开始的在前。 */
  List<Job> list() {
    List<Job> all = new ArrayList<>(jobs.values());
    all.sort(Comparator.comparing(job -> job.progress().startedAt()));
    return all;
  }
}
