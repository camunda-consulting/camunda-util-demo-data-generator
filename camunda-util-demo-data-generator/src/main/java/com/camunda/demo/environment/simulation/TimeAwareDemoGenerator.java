package com.camunda.demo.environment.simulation;

import java.lang.reflect.Field;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.camunda.bpm.application.ProcessApplicationReference;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.externaltask.ExternalTask;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricCaseInstance;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.persistence.entity.TimerEntity;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.runtime.CaseExecution;
import org.camunda.bpm.engine.runtime.CaseInstance;
import org.camunda.bpm.engine.runtime.EventSubscription;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.camunda.demo.environment.DemoDataGenerator;

/**
 * Classloading: Currently everything is done as JavaScript - so no classes are
 * necessary
 */
public class TimeAwareDemoGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(TimeAwareDemoGenerator.class);

  private String processDefinitionKey;
  private int numberOfDaysInPast;
  private StatisticalDistribution timeBetweenStartsBusinessDays;
  private String startTimeBusinessDay = "08:30";
  private String endTimeBusinessDay = "18:00";

  private StatisticalDistribution timeBetweenStartsWeekend;

  private DemoModelInstrumentator instrumentator;

  private ProcessEngine engine;

  private Map<String, NormalDistribution> distributions = new HashMap<String, NormalDistribution>();

  private Map<Object, Date> dueCache = new HashMap<>();

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
    instrumentator.tweakProcessDefinition(processDefinitionKey); // root process
                                                                 // definition
    if (additionalModelKeys != null) {
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
      LOG.info("start multiple process instances for " + numberOfDaysInPast + " workingdays in the past");

      long counter = 0;

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

              LOG.debug("Currently " + ++counter + " process instances are finished.");

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
    ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey(processDefinitionKey,
        Variables.putValue(DemoDataGenerator.VAR_NAME_GENERATED, true));
    try {
      driveProcessInstance(pi);
    } catch (ReachedCurrentTimeException e) {
      // no problem
    }
  }

  protected void driveProcessInstance(ProcessInstance pi) throws ReachedCurrentTimeException {
    Set<String> processInstancesAlreadyReachedCurrentTime = new HashSet<>();
    while (true) {
      // TODO: Signal

      /* get all work and add it to */
      List<Work<?>> todo = new LinkedList<>();
      engine.getTaskService().createTaskQuery().processInstanceId(pi.getId()).active().list().stream() //
          .map(task -> new TaskWork(task, pi)) //
          .forEach(todo::add);
      // lock everything we can get for this process instance by fetching all
      // open tasks, accumulate their topics and lock on them
      // TODO lock time should be derived from durationMean+durationSd+epsilon
      engine.getExternalTaskService().createExternalTaskQuery().processInstanceId(pi.getId()).notLocked().active().list().stream() //
          .map(ExternalTask::getTopicName) //
          .distinct() //
          .forEach(topic -> engine.getExternalTaskService().fetchAndLock(Integer.MAX_VALUE, "demo-data-generator").topic(topic, 3600L * 1000L).execute());
      engine.getExternalTaskService().createExternalTaskQuery().processInstanceId(pi.getId()).active().locked().list().stream() //
          .map(externalTask -> new ExternalTaskWork(externalTask, pi)) //
          .forEach(todo::add);
      engine.getRuntimeService().createEventSubscriptionQuery().processInstanceId(pi.getId()).eventType("message").list().stream() //
          .map(event -> new EventWork(event, pi)) //
          .forEach(todo::add);
      engine.getManagementService().createJobQuery().processInstanceId(pi.getId()).active().list().stream() //
          .map(job -> new JobWork(job, pi)) //
          .forEach(todo::add);
      engine.getHistoryService().createHistoricActivityInstanceQuery().unfinished().processInstanceId(pi.getId()).activityType("callActivity").list().stream() //
          // early filter out already stopped ones
          .filter(historicCallActivity -> !processInstancesAlreadyReachedCurrentTime.contains(historicCallActivity.getCalledProcessInstanceId()))
          .map(callActivity -> new CallActivityWork(callActivity, pi)) //
          .forEach(todo::add);

      // try to find something executable: first come first serve, but nothing
      // in "real" future
      Date theRealNow = new Date();
      Optional<Work<?>> candidate = todo.stream() //
          .filter(work -> !work.getDue().after(theRealNow)) //
          .min((workA, workB) -> workA.getDue().compareTo(workB.getDue()));

      // if there is no work to do, we are done -- if everything would be so
      // easy
      if (!candidate.isPresent()) {
        if (todo.isEmpty()) {
          LOG.debug("Instance " + pi.getId() + " finished (no work anymore).");
        } else {
          LOG.debug("Instance " + pi.getId() + " reached current time -- stopping.");
          throw new ReachedCurrentTimeException(pi.getId());
        }
        break;
      }

      // we have work, we do work - advancing time if necessary
      if (candidate.get().getDue().after(ClockUtil.getCurrentTime())) {
        ClockUtil.setCurrentTime(candidate.get().getDue());
      }
      try {
        candidate.get().execute(engine);
      } catch (ReachedCurrentTimeException e) {
        processInstancesAlreadyReachedCurrentTime.add(e.getProcessInstanceId());
      }
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

  abstract class Work<T> {
    protected T workItem;
    protected ProcessInstance pi;

    public Work(T workItem, ProcessInstance pi) {
      this.workItem = workItem;
      this.pi = pi;
    }

    abstract protected Date calculateNewRandomDue();

    final public void execute(ProcessEngine engine) throws ReachedCurrentTimeException {
      executeImpl(engine);
      // If we are a recurring job, we have to make sure that due date is newly
      // calculated after each execution. To do so, we simply remove it from
      // cache in every case.
      dueCache.remove(workItem);
    };

    abstract protected void executeImpl(ProcessEngine engine) throws ReachedCurrentTimeException;

    public Date getDue() {
      if (!dueCache.containsKey(workItem)) {
        dueCache.put(workItem, calculateNewRandomDue());
      }
      return dueCache.get(workItem);
    }

    protected Date calculateNewRandomDue(ProcessInstance pi, String id, Date creationTime) {
      if (!distributions.containsKey(id)) {
        NormalDistribution distribution = createDistributionForElement(pi, id);
        distributions.put(id, distribution);
      }

      Calendar cal = Calendar.getInstance();
      cal.setTime(creationTime);
      double timeToWait = distributions.get(id).sample();
      if (timeToWait <= 0) {
        timeToWait = 1;
      }
      cal.add(Calendar.SECOND, (int) Math.round(timeToWait));

      return cal.getTime();
    }
  }

  class TaskWork extends Work<Task> {

    public TaskWork(Task workItem, ProcessInstance pi) {
      super(workItem, pi);
    }

    @Override
    protected Date calculateNewRandomDue() {
      return calculateNewRandomDue(pi, workItem.getTaskDefinitionKey(), workItem.getCreateTime());
    }

    @Override
    public void executeImpl(ProcessEngine engine) {
      engine.getTaskService().complete(workItem.getId());
    }

  }

  class ExternalTaskWork extends Work<ExternalTask> {

    public ExternalTaskWork(ExternalTask workItem, ProcessInstance pi) {
      super(workItem, pi);
    }

    @Override
    protected Date calculateNewRandomDue() {
      // external tasks have no creation date, so we say this happens when we
      // see them first time
      return calculateNewRandomDue(pi, workItem.getActivityId(), ClockUtil.getCurrentTime());
    }

    @Override
    public void executeImpl(ProcessEngine engine) {
      engine.getExternalTaskService().complete(workItem.getId(), workItem.getWorkerId());
    }

  }

  class EventWork extends Work<EventSubscription> {

    public EventWork(EventSubscription workItem, ProcessInstance pi) {
      super(workItem, pi);
    }

    @Override
    protected Date calculateNewRandomDue() {
      return calculateNewRandomDue(pi, workItem.getActivityId(), workItem.getCreated());
    }

    @Override
    public void executeImpl(ProcessEngine engine) {
      engine.getRuntimeService().createMessageCorrelation(workItem.getEventName()).processInstanceId(pi.getId()).correlateAllWithResult();
    }

  }

  class JobWork extends Work<Job> {

    public JobWork(Job workItem, ProcessInstance pi) {
      super(workItem, pi);
    }

    @Override
    protected Date calculateNewRandomDue() {
      // taking what the engine thinks is "now" makes the job eligible for
      // immediate execution
      return Optional.ofNullable(workItem.getDuedate()).orElse(ClockUtil.getCurrentTime());
    }

    @Override
    public void executeImpl(ProcessEngine engine) {
      if (workItem.isSuspended())
        return;

      if (workItem instanceof TimerEntity) {
        /*
         * Caused by DurationHelper.getDateAfterRepeat: return next.before(date)
         * ? null : next;
         * 
         * This leads to endless loop if we call a timer job at exactly the time
         * it will schedule next. Cannot be handled by engine, because there is
         * no "counter" in the database for executions - it has to trust the
         * clock on the wall.
         */
        Calendar cal = Calendar.getInstance();
        cal.setTime(ClockUtil.getCurrentTime());
        cal.add(Calendar.MILLISECOND, 1);
        ClockUtil.setCurrentTime(cal.getTime());
      }
      engine.getManagementService().executeJob(workItem.getId());
    }

  }

  class CallActivityWork extends Work<HistoricActivityInstance> {

    public CallActivityWork(HistoricActivityInstance workItem, ProcessInstance pi) {
      super(workItem, pi);
    }

    @Override
    protected Date calculateNewRandomDue() {
      // immediate
      return ClockUtil.getCurrentTime();
    }

    @Override
    public void executeImpl(ProcessEngine engine) throws ReachedCurrentTimeException {
      if (workItem.getCalledProcessInstanceId() != null) {
        driveProcessInstance(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(workItem.getCalledProcessInstanceId()).singleResult());
      } else if (workItem.getCalledCaseInstanceId() != null) {
        driveCaseInstance(engine.getCaseService().createCaseInstanceQuery().caseInstanceId(workItem.getCalledCaseInstanceId()).singleResult());
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
