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
  String businessKey = null;

  public ContentGenerator() {
    super();
  }

  protected void incActivityVisitCount() {
    activityVisitCount++;
  }

  public Long activityVisitCount() {
    return activityVisitCount;
  }

  public Date currentSimulationTime() {
    return currentSimulationTime;
  }

  public Date firstStartTime() {
    return firstStartTime;
  }

  public Date previousStartTime() {
    return previousStartTime;
  }

  public Date nextStartTime() {
    return nextStartTime;
  }

  public Date stopTime() {
    return stopTime;
  }

  public Double percentDone() {
    return percentDone;
  }

  public String businessKey() {
    return businessKey;
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

  protected void setBusinessKey(String businessKey) {
    this.businessKey = businessKey;
  }

}