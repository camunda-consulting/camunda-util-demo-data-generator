package com.camunda.demo.environment.simulation;

import java.util.Date;

abstract public class ContentGenerator {

  long activityVisitCount = 0;
  Date currentSimulationTime = null;
  Date firstStartTime = null;
  Date previousStartTime = null;
  Date nextStartTime = null;
  Date stopTime = null;
  Double percentDone = null;

  public ContentGenerator() {
    super();
  }

  public Long getActivityVisitCount() {
    return activityVisitCount;
  }

  protected void incActivityVisitCount() {
    activityVisitCount++;
  }

  public Date getCurrentSimulationTime() {
    return currentSimulationTime;
  }

  public Date getFirstStartTime() {
    return firstStartTime;
  }

  public Date getPreviousStartTime() {
    return previousStartTime;
  }

  public Date getNextStartTime() {
    return nextStartTime;
  }

  public Date getStopTime() {
    return stopTime;
  }

  public Double getPercentDone() {
    return percentDone;
  }

  protected void setCurrentSimulationTime(Date currentSimulationTime) {
    this.currentSimulationTime = currentSimulationTime;
  }

  protected void setFirstStartTime(Date firstStartTime) {
    this.firstStartTime = firstStartTime;
  }

  protected void setPreviousStartTime(Date previousStartTime) {
    this.previousStartTime = previousStartTime;
  }

  protected void setNextStartTime(Date nextStartTime) {
    this.nextStartTime = nextStartTime;
  }

  protected void setStopTime(Date stopTime) {
    this.stopTime = stopTime;
  }

  protected void setPercentDone(Double percentDone) {
    this.percentDone = percentDone;
  }

}