package com.camunda.demo.environment.simulation;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.camunda.bpm.application.ProcessApplicationReference;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricCaseInstance;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.runtime.CaseExecution;
import org.camunda.bpm.engine.runtime.CaseInstance;
import org.camunda.bpm.engine.runtime.EventSubscription;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.model.bpmn.instance.BaseElement;

/**
 * Classloading: Currently everything is done as JavaScript - so no classes are
 * necessary
 */
public class TimeAwareDemoGenerator {

  private static final Logger log = Logger.getLogger(TimeAwareDemoGenerator.class.getName());

  private String processDefinitionKey;
  private int numberOfDaysInPast;
  private StatisticalDistribution timeBetweenStartsBusinessDays;
  private String startTimeBusinessDay = "08:30";
  private String endTimeBusinessDay = "18:00";

  private StatisticalDistribution timeBetweenStartsWeekend;

  private DemoModelInstrumentator instrumentator;
  
  private ProcessEngine engine;

  private Map<String, NormalDistribution> distributions = new HashMap<String, NormalDistribution>();

  private ProcessApplicationReference processApplicationReference;

  private String[] additionalModelKeys;

  public TimeAwareDemoGenerator(ProcessEngine engine, ProcessApplicationReference processApplicationReference) {
    this.engine = engine;
    this.processApplicationReference = processApplicationReference;
  }

  public TimeAwareDemoGenerator(ProcessEngine processEngine) {
    this.engine = processEngine;
  }  


  public void generateData() {
    instrumentator = new DemoModelInstrumentator(engine, processApplicationReference);
    instrumentator.tweakProcessDefinition(processDefinitionKey); // root process definition
    if (additionalModelKeys!=null) {
      instrumentator.addAdditionalModels(additionalModelKeys);
    }
    
    instrumentator.deployTweakedModels();

    synchronized (engine) {
      ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getJobExecutor().shutdown();
      try {
        startMultipleProcessInstances();
      } finally {
        instrumentator.restoreOriginalModels();
        ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getJobExecutor().start();
      }
    }
  }

  protected void copyTimeField(Calendar calFrom, Calendar calTo, int... calendarFieldConstant) {
    for (int i = 0; i < calendarFieldConstant.length; i++) {
      calTo.set(calendarFieldConstant[i], calFrom.get(calendarFieldConstant[i]));
    }
  }

  private boolean isInTimeFrame(Calendar cal, String startTime, String endTime) {
    try {
      // TODO: maybe cache?
      Date startDate = new SimpleDateFormat("HH:mm").parse(startTime);
      Date endDate = new SimpleDateFormat("HH:mm").parse(endTime);
      Calendar startCal = Calendar.getInstance();
      startCal.setTime(startDate);
      copyTimeField(cal, startCal, Calendar.YEAR, Calendar.DAY_OF_YEAR);

      Calendar endCal = Calendar.getInstance();
      endCal.setTime(endDate);
      copyTimeField(cal, endCal, Calendar.YEAR, Calendar.DAY_OF_YEAR);

      return (startCal.before(cal) && cal.before(endCal));
    } catch (ParseException ex) {
      throw new RuntimeException("Could not parse time format: '" + startTime + "' or '" + endTime + "'", ex);
    }
  }

  protected void startMultipleProcessInstances() {
    try {
      log.info("start multiple process instances for " + numberOfDaysInPast + " workingdays in the past");

      // for all desired days in past
      for (int i = numberOfDaysInPast; i >= 0; i--) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -1 * i);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);

        int dayOfYear = cal.get(Calendar.DAY_OF_YEAR);

        // now lets start process instances on that day
        if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
          // weekend
        } else {
          while (cal.get(Calendar.DAY_OF_YEAR) == dayOfYear) {
            // business day (OK - simplified - do not take holidays into
            // account)
            double time = timeBetweenStartsBusinessDays.nextSample();
            cal.add(Calendar.SECOND, (int) Math.round(time));
            if (isInTimeFrame(cal, startTimeBusinessDay, endTimeBusinessDay)) {
              ClockUtil.setCurrentTime(cal.getTime());

              System.out.print(".");
              runSingleProcessInstance();

              // Housekeeping for safety (as the run might have changed the
              // clock)
              ClockUtil.setCurrentTime(cal.getTime());
            }
          }
        }
      }

    } finally {
      ClockUtil.reset();
    }
  }

  protected void runSingleProcessInstance() {
    ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey(processDefinitionKey);
    driveProcessInstance(pi);

  }

  protected void driveProcessInstance(ProcessInstance pi) {
    boolean piRunning = true;

    while (piRunning) {
      // TODO:
      // engine.getRuntimeService().createEventSubscriptionQuery().processInstanceId(pi.getId()).eventType("signal").list();

      List<org.camunda.bpm.engine.task.Task> tasks = engine.getTaskService().createTaskQuery().processInstanceId(pi.getId()).list();
      handleTasks(engine, pi, tasks);

      List<EventSubscription> messages = engine.getRuntimeService().createEventSubscriptionQuery().processInstanceId(pi.getId()).eventType("message").list();
      handleMessages(engine, pi, messages);

      List<Job> jobs = engine.getManagementService().createJobQuery().processInstanceId(pi.getId()).list();
      handleJobs(jobs);

      List<HistoricActivityInstance> callActivities = engine.getHistoryService().createHistoricActivityInstanceQuery().unfinished()
          .processInstanceId(pi.getId()).activityType("callActivity").list();
      for (HistoricActivityInstance callActivityInstance : callActivities) {
        if (callActivityInstance.getCalledProcessInstanceId() != null) {
          driveProcessInstance(
              engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(callActivityInstance.getCalledProcessInstanceId()).singleResult());
        } else if (callActivityInstance.getCalledCaseInstanceId() != null) {
          driveCaseInstance(engine.getCaseService().createCaseInstanceQuery().caseInstanceId(callActivityInstance.getCalledCaseInstanceId()).singleResult());
        }
      }

      // do queries again if we have changed anything in the process instance
      // for the moment we do not query processInstance.isEnded as we are not
      // sure
      // if we have yet tackled all situations (read: we are sure we haven't
      // yet).
      // This will at least not lead to endless loops
      piRunning = (tasks.size() > 0 || messages.size() > 0 || jobs.size() > 0);

      // TODO: Stop when we reach the NOW time (might leave open tasks - but
      // that is OK!)
    }
  }

  private void driveCaseInstance(CaseInstance caseInstance) {
    boolean piRunning = true;
    while (piRunning) {
      piRunning = false;
      List<CaseExecution> activeExecutions = engine.getCaseService().createCaseExecutionQuery().caseInstanceId(caseInstance.getId()).active().list();
      if (activeExecutions.size() > 0) {
        for (CaseExecution activeExecution : activeExecutions) {
          if (activeExecution.getActivityType().equals("casePlanModel")) {
            // Do this if everything else is done
            if (activeExecutions.size() == 1) {
              engine.getCaseService().completeCaseExecution(caseInstance.getId());
            }
          } else if (activeExecution.getActivityType().equals("stage")) {
            // TODO
            if (activeExecutions.size() == 2) {
              engine.getCaseService().completeCaseExecution(activeExecution.getId());
              piRunning = true;
              break;
            }
          } else {
            // engine.getCaseService().
            engine.getCaseService().completeCaseExecution(activeExecution.getId());
            piRunning = true;
            break;
          }
        }
      }

      // piRunning = (activeExecutions.size() > 0);
    }

    HistoricCaseInstance historicCaseInstance = engine.getHistoryService().createHistoricCaseInstanceQuery().caseInstanceId(caseInstance.getId())
        .singleResult();
    if (historicCaseInstance.isActive()) {
      engine.getCaseService().completeCaseExecution(caseInstance.getId());
    }
    historicCaseInstance = engine.getHistoryService().createHistoricCaseInstanceQuery().caseInstanceId(caseInstance.getId()).singleResult();
    if (historicCaseInstance.isCompleted() || historicCaseInstance.isTerminated()) {
      engine.getCaseService().closeCaseInstance(caseInstance.getId());
    }
  }

  protected boolean handleTasks(ProcessEngine engine, ProcessInstance pi, List<org.camunda.bpm.engine.task.Task> tasks) {
    for (org.camunda.bpm.engine.task.Task task : tasks) {
      String id = task.getTaskDefinitionKey();

      if (!distributions.containsKey(id)) {
        NormalDistribution distribution = createDistributionForElement(pi, id);
        distributions.put(id, distribution);
      }

      Calendar cal = Calendar.getInstance();
      cal.setTime(task.getCreateTime());
      double timeToWait = distributions.get(task.getTaskDefinitionKey()).sample();
      if (timeToWait <= 0) {
        timeToWait = 1;
      }
      cal.add(Calendar.SECOND, (int) Math.round(timeToWait));

      if (timerDue(engine, pi, cal.getTime())) {
        if (engine.getTaskService().createTaskQuery().taskId(task.getId()).count() == 0) {
          // do nothing - timer was faster and canceled activity
          return true;
        }
      }

      ClockUtil.setCurrentTime(cal.getTime());

      engine.getTaskService().complete(task.getId());
      return true;
    }
    return false;
  }

  private boolean timerDue(ProcessEngine engine2, ProcessInstance pi, Date date) {

    List<Job> jobs = engine.getManagementService().createJobQuery() //
        .processInstanceId(pi.getProcessInstanceId()).timers().duedateLowerThan(date).list();

    if (jobs.size() > 0) {
      handleJobs(jobs);
      return true;
    } else {
      return false;
    }
  }

  protected boolean handleMessages(ProcessEngine engine, ProcessInstance pi, List<EventSubscription> messages) {
    for (EventSubscription eventSubscription : messages) {
      String id = eventSubscription.getActivityId();
      if (!distributions.containsKey(id)) {
        NormalDistribution distribution = createDistributionForElement(pi, id);
        distributions.put(id, distribution);
      }

      Calendar cal = Calendar.getInstance();
      cal.setTime(eventSubscription.getCreated());
      double timeToWait = distributions.get(id).sample();
      cal.add(Calendar.SECOND, (int) Math.round(timeToWait));

      if (timerDue(engine, pi, cal.getTime())) {
        if (engine.getRuntimeService().createEventSubscriptionQuery().eventSubscriptionId(eventSubscription.getId()).count() == 0) {
          // do nothing - timer was faster and canceled activity waiting for
          // message
          return true;
        }
      }

      ClockUtil.setCurrentTime(cal.getTime());
      engine.getRuntimeService().createMessageCorrelation(eventSubscription.getEventName()).processInstanceId(pi.getId()).correlate();
    }
    return false;
  }

  protected void handleJobs(List<Job> jobs) {
    for (Job job : jobs) {
      if (engine.getManagementService().createJobQuery().jobId(job.getId()).count() == 1) {
        engine.getManagementService().executeJob(job.getId());
        return;
      } else {
        // System.out.println("COULD NOT EXECUTE JOB " + job);
      }
    }
  }

  protected NormalDistribution createDistributionForElement(ProcessInstance pi, String id) {
    try {
      BaseElement taskElement = engine.getRepositoryService().getBpmnModelInstance(pi.getProcessDefinitionId()).getModelElementById(id);

      double durationMean = 600; // Default = 10 minutes
      String camundaPropertyMean = DemoModelInstrumentator.readCamundaProperty(taskElement, "durationMean");
      if (camundaPropertyMean != null) {
        durationMean = Double.parseDouble(camundaPropertyMean);
      }

      double durationStandardDeviation = 600; // Default also 10 minutes
      String camundaPropertySd = DemoModelInstrumentator.readCamundaProperty(taskElement, "durationSd");
      if (camundaPropertySd != null) {
        durationStandardDeviation = Double.parseDouble(camundaPropertySd);
      }

      NormalDistribution distribution = new NormalDistribution(durationMean, durationStandardDeviation);
      return distribution;
    } catch (Exception ex) {
      throw new RuntimeException("Could not read distribution for element '" + id + "' of process definition '" + pi.getProcessDefinitionId() + "'", ex);
    }
  }

  public TimeAwareDemoGenerator timeBetweenStartsBusinessDays(double mean, double standardDeviation) {
    timeBetweenStartsBusinessDays = new StatisticalDistribution(mean, standardDeviation);
    return this;
  }

  public TimeAwareDemoGenerator processDefinitionKey(String processDefinitionKey) {
    this.processDefinitionKey = processDefinitionKey;
    return this;
  }

  public TimeAwareDemoGenerator additionalModelKeys(String... additionalModelKeys) {
    this.additionalModelKeys = additionalModelKeys;
    return this;
  }

  public TimeAwareDemoGenerator numberOfDaysInPast(int numberOfDaysInPast) {
    this.numberOfDaysInPast = numberOfDaysInPast;
    return this;
  }

}
