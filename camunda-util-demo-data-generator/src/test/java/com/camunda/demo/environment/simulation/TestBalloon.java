package com.camunda.demo.environment.simulation;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class TestBalloon {
  static Map<String, AtomicLong> registry;

  static {
    reset();
  }

  public static void reset() {
    registry = new HashMap<>();
  }

  public static void callCounter(String topic) {
    if (!registry.containsKey(topic)) {
      registry.put(topic, new AtomicLong());
    }
    registry.get(topic).incrementAndGet();
  }

  public static long getCounter(String name) {
    if (!registry.containsKey(name)) {
      return 0;
    } else {
      return registry.get(name).get();
    }
  }
}
