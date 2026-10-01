package org.fetarute.fetaruteTCAddon.display.pids.announce;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 每位玩家听过哪些广播、ActionBar 还有哪些在排队。
 *
 * <ul>
 *   <li>同一条广播（{@link PidsAnnouncement#key()}）对同一玩家只播一次。上次检查时仍有效的广播算连续有效，一直记着——
 *       与记忆时长、两次检查隔了多久（服务器卡顿）都无关；失效后再记一段时间：取消、晚点记 {@code dedupe-seconds}， 进站、通过只记 {@link
 *       #ACTION_BAR_MEMORY}（进站状态的抖动是秒级的，而同一列车循环运行时隔几分钟就会再到同一站）。
 *   <li>ActionBar 一次显示一条，每条停留 {@link #ACTION_BAR_HOLD}；轮到时已经失效的（车已经到站）直接丢弃。
 *   <li>聊天一次最多 {@link #CHAT_LIMIT} 条，其余合成一条“另有 N 条”。
 * </ul>
 *
 * <p>每次检查都要为每位在线玩家调用 {@link #deliver}，不在站内、正在乘车或关掉了广播时传空列表：这样离站期间的记录才会按时长老化。 只在主线程使用。
 */
final class PidsAnnouncementLedger {

  /** ActionBar 每条的停留时间：原版 ActionBar 约 3 秒后淡出。 */
  static final Duration ACTION_BAR_HOLD = Duration.ofSeconds(3);

  /** 进站、通过广播失效后还记多久。 */
  static final Duration ACTION_BAR_MEMORY = Duration.ofMinutes(2);

  /** 一次最多发几条聊天广播。 */
  static final int CHAT_LIMIT = 3;

  /** ActionBar 排队上限：再多就丢最早的，排得太久的进站提示也没意义了。 */
  private static final int QUEUE_LIMIT = 6;

  /**
   * 本次要发给一位玩家的内容。
   *
   * @param chat 聊天广播（不超过 {@link #CHAT_LIMIT} 条）
   * @param overflow 超出上限、合并成一条的聊天广播条数
   * @param actionBar 此刻轮到显示的 ActionBar 广播
   */
  record Delivery(List<PidsAnnouncement> chat, int overflow, Optional<PidsAnnouncement> actionBar) {
    Delivery {
      chat = List.copyOf(chat);
    }

    boolean isEmpty() {
      return chat.isEmpty() && overflow == 0 && actionBar.isEmpty();
    }
  }

  private final Map<UUID, Listener> listeners = new HashMap<>();

  /**
   * 计算一位玩家此刻该收到的广播，并记下已播。
   *
   * @param player 玩家
   * @param active 玩家所在车站此刻有效的广播；不在站内、正在乘车或关掉了广播时为空列表
   * @param now 当前时刻
   * @param chatMemory 取消、晚点广播失效后还记多久（{@code dedupe-seconds}）
   */
  Delivery deliver(UUID player, List<PidsAnnouncement> active, Instant now, Duration chatMemory) {
    Listener listener = listeners.computeIfAbsent(player, ignored -> new Listener());
    Set<String> activeKeys = new HashSet<>();
    active.forEach(announcement -> activeKeys.add(announcement.key()));
    listener
        .heard
        .entrySet()
        .removeIf(entry -> !activeKeys.contains(entry.getKey()) && entry.getValue().expired(now));
    List<PidsAnnouncement> chat = new ArrayList<>();
    for (PidsAnnouncement announcement : active) {
      boolean chatKind = announcement.kind().channel() == PidsAnnouncement.Channel.CHAT;
      Heard previous =
          listener.heard.put(
              announcement.key(), new Heard(now, chatKind ? chatMemory : ACTION_BAR_MEMORY));
      if (previous != null
          && (previous.lastActive().equals(listener.lastCheck) || !previous.expired(now))) {
        continue;
      }
      if (chatKind) {
        chat.add(announcement);
      } else {
        if (listener.queue.size() >= QUEUE_LIMIT) {
          listener.queue.pollFirst();
        }
        listener.queue.addLast(announcement);
      }
    }
    listener.lastCheck = now;
    Optional<PidsAnnouncement> actionBar = Optional.empty();
    if (!now.isBefore(listener.shownAt.plus(ACTION_BAR_HOLD))) {
      while (!listener.queue.isEmpty() && actionBar.isEmpty()) {
        PidsAnnouncement next = listener.queue.pollFirst();
        if (activeKeys.contains(next.key())) {
          actionBar = Optional.of(next);
          listener.shownAt = now;
        }
      }
    }
    int shown = Math.min(chat.size(), CHAT_LIMIT);
    return new Delivery(chat.subList(0, shown), chat.size() - shown, actionBar);
  }

  /** 只保留在线玩家的记录。 */
  void retain(Set<UUID> online) {
    listeners.keySet().retainAll(online);
  }

  void clear() {
    listeners.clear();
  }

  /**
   * 一条广播最后一次有效的时刻与失效后的记忆时长。
   *
   * @param lastActive 最后一次有效（玩家在站内时）的检查时刻
   * @param memory 失效后还记多久
   */
  private record Heard(Instant lastActive, Duration memory) {
    boolean expired(Instant now) {
      return lastActive.plus(memory).isBefore(now);
    }
  }

  private static final class Listener {
    private final Map<String, Heard> heard = new HashMap<>();
    private final Deque<PidsAnnouncement> queue = new ArrayDeque<>();
    private Instant shownAt = Instant.EPOCH;
    private Instant lastCheck = Instant.EPOCH;
  }
}
