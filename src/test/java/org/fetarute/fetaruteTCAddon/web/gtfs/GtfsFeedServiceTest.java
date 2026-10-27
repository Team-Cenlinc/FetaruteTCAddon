package org.fetarute.fetaruteTCAddon.web.gtfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("静态 GTFS 缓存")
class GtfsFeedServiceTest {

  private static final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return GtfsTestFixtures.ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static GtfsSnapshot copy(
      GtfsSnapshot snapshot,
      boolean timetableEnabled,
      List<org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable> timetables) {
    return new GtfsSnapshot(
        snapshot.serverId(),
        snapshot.zone(),
        snapshot.publisherName(),
        snapshot.publisherUrl(),
        snapshot.generatedAt(),
        timetableEnabled,
        snapshot.operators(),
        snapshot.lines(),
        snapshot.stations(),
        snapshot.groups(),
        snapshot.platforms(),
        timetables,
        snapshot.routes(),
        snapshot.plannedPlatforms(),
        snapshot.stationsOfPublishedLines(),
        snapshot.platformPlansVersion());
  }

  @Test
  @DisplayName("到期后内容没变：只续期，同一个 feed 实例，不重新打包")
  void unchangedContentExtendsTheCacheWithoutRebuilding() {
    MutableClock clock = new MutableClock(GtfsTestFixtures.NOW);
    AtomicReference<GtfsSnapshot> snapshot = new AtomicReference<>(GtfsTestFixtures.snapshot());
    AtomicInteger calls = new AtomicInteger();
    GtfsFeedService service =
        new GtfsFeedService(
            () -> {
              calls.incrementAndGet();
              return Optional.of(snapshot.get());
            },
            clock,
            message -> {});

    GtfsFeed first = service.feed().orElseThrow();
    clock.advance(Duration.ofSeconds(GtfsFeedTables.MAX_AGE_SECONDS + 1));
    GtfsFeed second = service.feed().orElseThrow();

    assertSame(first, second, "内容没变时复用同一份 feed");
    assertEquals(2, calls.get(), "到期会取一次新快照做对比");
    clock.advance(Duration.ofSeconds(1));
    assertSame(first, service.feed().orElseThrow());
    assertEquals(2, calls.get(), "对比通过后重新获得完整的 300 秒有效期");

    clock.advance(Duration.ofMinutes(31));
    GtfsFeed forced = service.feed().orElseThrow();
    assertNotSame(first, forced, "30 分钟兜底：指纹没变也重建一次");
    assertEquals(3, calls.get());
  }

  @Test
  @DisplayName("到期后内容变了：重新构建并换版本")
  void changedContentRebuilds() {
    MutableClock clock = new MutableClock(GtfsTestFixtures.NOW);
    AtomicReference<GtfsSnapshot> snapshot = new AtomicReference<>(GtfsTestFixtures.snapshot());
    AtomicInteger calls = new AtomicInteger();
    GtfsFeedService service =
        new GtfsFeedService(
            () -> {
              calls.incrementAndGet();
              return Optional.of(snapshot.get());
            },
            clock,
            message -> {});

    GtfsFeed first = service.feed().orElseThrow();
    snapshot.set(copy(snapshot.get(), false, List.of()));
    clock.advance(Duration.ofSeconds(GtfsFeedTables.MAX_AGE_SECONDS + 1));
    GtfsFeed second = service.feed().orElseThrow();

    assertNotSame(first, second);
    assertNotEquals(first.version(), second.version());
    assertEquals(2, calls.get());
  }

  @Test
  @DisplayName("两个线程同时要包时只生成一次：后到的等前一次的结果")
  void buildsOnceForConcurrentRequests() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    GtfsSource slow =
        () -> {
          calls.incrementAndGet();
          entered.countDown();
          try {
            if (!release.await(5, TimeUnit.SECONDS)) {
              Thread.currentThread().interrupt();
            }
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          return Optional.of(GtfsTestFixtures.snapshot());
        };
    GtfsFeedService service =
        new GtfsFeedService(
            slow, Clock.fixed(GtfsTestFixtures.NOW, GtfsTestFixtures.ZONE), message -> {});
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Optional<GtfsFeed>> first = pool.submit(service::feed);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      Future<Optional<GtfsFeed>> second = pool.submit(service::feed);
      release.countDown();
      assertTrue(first.get(5, TimeUnit.SECONDS).isPresent());
      assertTrue(second.get(5, TimeUnit.SECONDS).isPresent());
      assertEquals(1, calls.get());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("server-id 变了旧缓存立即作废：包内前缀与文件名一起换")
  void serverIdChangeInvalidatesTheCache() {
    AtomicInteger calls = new AtomicInteger();
    java.util.concurrent.atomic.AtomicReference<Optional<String>> identity =
        new java.util.concurrent.atomic.AtomicReference<>(Optional.of("sv"));
    GtfsFeedService service =
        new GtfsFeedService(
            () -> {
              calls.incrementAndGet();
              return Optional.of(GtfsTestFixtures.snapshot());
            },
            Clock.fixed(GtfsTestFixtures.NOW, GtfsTestFixtures.ZONE),
            message -> {},
            identity::get);

    assertTrue(service.feed().isPresent());
    assertTrue(service.feed().isPresent());
    assertEquals(1, calls.get());
    identity.set(Optional.of("b"));
    assertTrue(service.feed().isPresent());
    assertEquals(2, calls.get());
  }

  @Test
  @DisplayName("生成失败不缓存：同一条告警一分钟内只记一次")
  void failureIsNotCachedAndWarnedOnce() {
    AtomicInteger calls = new AtomicInteger();
    java.util.List<String> warnings = new java.util.ArrayList<>();
    GtfsFeedService service =
        new GtfsFeedService(
            () -> {
              calls.incrementAndGet();
              throw new IllegalStateException("boom");
            },
            Clock.fixed(GtfsTestFixtures.NOW, GtfsTestFixtures.ZONE),
            warnings::add);

    assertTrue(service.feed().isEmpty());
    assertTrue(service.feed().isEmpty());
    assertTrue(service.packaged().isEmpty());
    assertEquals(3, calls.get(), "每次请求都重新尝试，不缓存失败");
    assertEquals(1, warnings.size(), "冷却期内只记一次");
  }
}
