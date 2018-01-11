package com.camunda.demo.environment.simulation;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

public class TestDelegate1 implements JavaDelegate {

  @Override
  public void execute(DelegateExecution execution) throws Exception {
    TestBalloon.callCounter(this.getClass().getName());
  }

  public long count() {
    return TestBalloon.getCounter(this.getClass().getName());
  }
}
