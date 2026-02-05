package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalChangedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TrainController 单元测试。 */
class TrainControllerTest {

  private SignalEventBus eventBus;
  private List<SignalCommand> receivedCommands;
  private TrainController controller;

  @BeforeEach
  void setUp() {
    eventBus = new SignalEventBus();
    receivedCommands = new ArrayList<>();
    controller =
        new TrainController(
            eventBus, (train, signal) -> receivedCommands.add(new SignalCommand(train, signal)));
  }

  @Test
  void startSubscribesToSignalChangedEvent() {
    controller.start();
    assertEquals(1, eventBus.subscriberCount(SignalChangedEvent.class));
  }

  @Test
  void stopUnsubscribes() {
    controller.start();
    controller.stop();
    assertEquals(0, eventBus.subscriberCount(SignalChangedEvent.class));
  }

  @Test
  void signalChangedEventTriggersHandler() {
    controller.start();
    Instant now = Instant.now();

    SignalChangedEvent event =
        new SignalChangedEvent(now, "train-A", SignalAspect.STOP, SignalAspect.PROCEED);
    eventBus.publish(event);

    assertEquals(1, receivedCommands.size());
    SignalCommand cmd = receivedCommands.get(0);
    assertEquals("train-A", cmd.trainName);
    assertEquals(SignalAspect.PROCEED, cmd.signal);
  }

  @Test
  void multipleEventsAllProcessed() {
    controller.start();
    Instant now = Instant.now();

    eventBus.publish(new SignalChangedEvent(now, "train-1", null, SignalAspect.PROCEED));
    eventBus.publish(
        new SignalChangedEvent(now, "train-2", SignalAspect.PROCEED, SignalAspect.STOP));
    eventBus.publish(
        new SignalChangedEvent(now, "train-3", SignalAspect.STOP, SignalAspect.CAUTION));

    assertEquals(3, receivedCommands.size());
    assertEquals("train-1", receivedCommands.get(0).trainName);
    assertEquals("train-2", receivedCommands.get(1).trainName);
    assertEquals("train-3", receivedCommands.get(2).trainName);
  }

  @Test
  void handlerExceptionDoesNotStopProcessing() {
    List<String> processed = new ArrayList<>();
    TrainController failingController =
        new TrainController(
            eventBus,
            (train, signal) -> {
              if (train.equals("fail")) {
                throw new RuntimeException("Test failure");
              }
              processed.add(train);
            });
    failingController.start();

    Instant now = Instant.now();
    eventBus.publish(new SignalChangedEvent(now, "train-ok", null, SignalAspect.PROCEED));
    eventBus.publish(new SignalChangedEvent(now, "fail", null, SignalAspect.STOP));
    eventBus.publish(new SignalChangedEvent(now, "train-after", null, SignalAspect.CAUTION));

    // 第一个和第三个应该被处理，第二个失败但不影响后续
    assertEquals(2, processed.size());
    assertTrue(processed.contains("train-ok"));
    assertTrue(processed.contains("train-after"));
  }

  private record SignalCommand(String trainName, SignalAspect signal) {}
}
