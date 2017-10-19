package com.camunda.demo.environment.simulation;

/**
 * Indicates that while driving that process instance we are not finished but
 * all remaining work is scheduled after "new Date()".
 * 
 * @author Ragnar Nevries
 *
 */
public class ReachedCurrentTimeException extends Exception {
  /**
  * 
  */
  private static final long serialVersionUID = 3598230088686658979L;

  private String processInstanceId;

  public ReachedCurrentTimeException(String processInstanceId) {
    super();
    this.processInstanceId = processInstanceId;
  }

  public String getProcessInstanceId() {
    return processInstanceId;
  }

}
