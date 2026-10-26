package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeedService;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GTFS-Realtime 生成入口")
class GtfsRealtimeServiceTest {

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

  private static GtfsFeedService feeds(Clock clock) {
    return new GtfsFeedService(
        () -> Optional.of(GtfsTestFixtures.snapshot()), clock, message -> {});
  }

  @Test
  @DisplayName("报文头时间戳参与 ETag：过了缓存周期重新生成时，条件请求会拿到 200 而不是被冻结的旧时间戳")
  void identityIncludesTheGenerationTimestamp() {
    MutableClock clock = new MutableClock(GtfsTestFixtures.NOW);
    GtfsFeedService feeds = feeds(clock);
    GtfsRealtimeService service =
        new GtfsRealtimeService(
            feeds,
            now -> Optional.of(GtfsRealtimeSnapshot.empty(now)),
            clock,
            () -> key -> "",
            message -> {});

    GtfsRealtimeService.Rendered first =
        service.render(GtfsRealtimeService.Feed.ALERTS, true).orElseThrow();
    clock.advance(Duration.ofSeconds(31));
    GtfsRealtimeService.Rendered second =
        service.render(GtfsRealtimeService.Feed.ALERTS, true).orElseThrow();

    assertNotEquals(first.identity(), second.identity());
    assertEquals(GtfsTestFixtures.NOW.getEpochSecond(), headerTimestamp(first.body()));
    assertEquals(
        GtfsTestFixtures.NOW.plusSeconds(31).getEpochSecond(), headerTimestamp(second.body()));
  }

  @Test
  @DisplayName("运行时快照抛异常：这一轮不生成（路由回 503），同一条告警在一分钟内只记一次")
  void sourceFailureIsReportedOnce() {
    MutableClock clock = new MutableClock(GtfsTestFixtures.NOW);
    GtfsFeedService feeds = feeds(clock);
    List<String> warnings = new ArrayList<>();
    AtomicInteger calls = new AtomicInteger();
    GtfsRealtimeSource broken =
        now -> {
          calls.incrementAndGet();
          throw new IllegalStateException("boom");
        };
    GtfsRealtimeService service =
        new GtfsRealtimeService(feeds, broken, clock, () -> key -> "", warnings::add);

    assertTrue(service.render(GtfsRealtimeService.Feed.TRIP_UPDATES, false).isEmpty());
    assertTrue(service.render(GtfsRealtimeService.Feed.TRIP_UPDATES, false).isEmpty());
    assertEquals(2, calls.get());
    assertEquals(1, warnings.size(), "一分钟内同一条告警只记一次");
  }

  private static long headerTimestamp(byte[] body) {
    JsonObject root =
        JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    return root.getAsJsonObject("header").get("timestamp").getAsLong();
  }
}
