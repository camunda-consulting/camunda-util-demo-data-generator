package com.camunda.demo.environment.simulation;

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
import java.util.TreeSet;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.camunda.bpm.application.ProcessApplicationReference;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.externaltask.ExternalTask;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.metrics.reporter.DbMetricsReporter;
import org.camunda.bpm.engine.impl.persistence.entity.TimerEntity;
import org.camunda.bpm.engine.impl.util.ClockUtil;
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

  public static final int METRIC_INTERVAL_MINUTES = 15;

  private static final Logger LOG = LoggerFactory.getLogger(TimeAwareDemoGenerator.class);

  private String processDefinitionKey;
  private int numberOfDaysInPast;
  private int numberOfDaysToSkip;
  private StatisticalDistribution timeBetweenStartsBusinessDays;
  private String startTimeBusinessDay;
  private String endTimeBusinessDay;
  private boolean runAlways;

  private boolean includeWeekend = false;

  private DemoModelInstrumentator instrumentator;

  private ProcessEngine engine;

  private Map<String, NormalDistribution> distributions = new HashMap<String, NormalDistribution>();

  private Map<Object, Date> dueCache = new HashMap<>();

  private ProcessApplicationReference originalProcessApplication;
  private ProcessApplicationReference simulatingProcessApplication;

  private String[] additionalModelKeys;

  private String deploymentId;

  public TimeAwareDemoGenerator(ProcessEngine engine, ProcessApplicationReference processApplicationReference) {
    this.engine = engine;
    this.originalProcessApplication = processApplicationReference;
    this.simulatingProcessApplication = processApplicationReference;
  }

  public TimeAwareDemoGenerator(ProcessEngine engine, ProcessApplicationReference originalProcessApplicationReference,
      ProcessApplicationReference simulatingProcessApplicationReference) {
    this.engine = engine;
    this.originalProcessApplication = originalProcessApplicationReference;
    this.simulatingProcessApplication = simulatingProcessApplicationReference;
  }

  public TimeAwareDemoGenerator(ProcessEngine processEngine) {
    this.engine = processEngine;
  }

  public void generateData() {
    long count = engine.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(processDefinitionKey)
        .variableValueEquals(DemoDataGenerator.VAR_NAME_GENERATED, true).count();

    if (count > 0 && (!runAlways)) {
      LOG.info("Skipped data generation because already generated data found. Set simulateRunAlways=true in bpmn to generate data despite that.");
      return;
    }

    instrumentator = new DemoModelInstrumentator(engine, originalProcessApplication, simulatingProcessApplication);
    instrumentator.tweakProcessDefinition(processDefinitionKey); // root process
                                                                 // definition
    if (additionalModelKeys != null) {
      instrumentator.addAdditionalModels(additionalModelKeys);
    }

    deploymentId = instrumentator.deployTweakedModels();

    synchronized (engine) {
      ProcessEngineConfigurationImpl processEngineConfigurationImpl = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
      processEngineConfigurationImpl.getJobExecutor().shutdown();
      boolean metrics = processEngineConfigurationImpl.isMetricsEnabled() && processEngineConfigurationImpl.isDbMetricsReporterActivate();
      if (metrics) {
        processEngineConfigurationImpl.getDbMetricsReporter().setReporterId("DEMO-DATA-GENERATOR");
      }

      try {
        simulate();
      } finally {
        ClockUtil.reset();
        instrumentator.restoreOriginalModels();
        if (metrics) {
          processEngineConfigurationImpl.getDbMetricsReporter().reportNow();
          processEngineConfigurationImpl.getDbMetricsReporter().setReporterId(processEngineConfigurationImpl.getMetricsReporterIdProvider().provideId(engine));
        }
        processEngineConfigurationImpl.getJobExecutor().start();
      }
    }
  }

  protected void copyTimeField(Calendar calFrom, Calendar calTo, int... calendarFieldConstant) {
    for (int i = 0; i < calendarFieldConstant.length; i++) {
      calTo.set(calendarFieldConstant[i], calFrom.get(calendarFieldConstant[i]));
    }
  }

  protected void simulate() {
    // fix the real time for whole simulation
    Date theRealNow = new Date();

    // calculate last time to start
    Calendar lastTimeToStart = Calendar.getInstance();
    lastTimeToStart.setTime(theRealNow);
    lastTimeToStart.add(Calendar.DAY_OF_YEAR, -1 * numberOfDaysToSkip);
    lastTimeToStart.set(Calendar.HOUR_OF_DAY, 0);
    lastTimeToStart.set(Calendar.MINUTE, 0);

    Set<String> runningProcessInstanceIds = new TreeSet<>();
    Set<String> processInstancIdsAlreadyReachedCurrentTime = new HashSet<>();
    Date nextStartTime = calculateNextStartTime(null, lastTimeToStart.getTime());
    while (true) {
      Optional<Work<?>> candidate = calculateNextSimulationStep(theRealNow, runningProcessInstanceIds, processInstancIdsAlreadyReachedCurrentTime);

      // check if we are finally done
      if (!candidate.isPresent() && nextStartTime == null) {
        break;
      }

      // check if we have to start a new instance before next simulation step
      if (nextStartTime != null && (!candidate.isPresent() || candidate.get().getDue().after(nextStartTime))) {
        // start new instance
        ClockUtil.setCurrentTime(nextStartTime);
        ProcessInstance newInstance = engine.getRuntimeService().startProcessInstanceByKey(processDefinitionKey,
            Variables.putValue(DemoDataGenerator.VAR_NAME_GENERATED, true));
        runningProcessInstanceIds.add(newInstance.getId());
        nextStartTime = calculateNextStartTime(nextStartTime, lastTimeToStart.getTime());
        continue;
      }

      // we have work, we do work - advancing time if necessary
      if (candidate.get().getDue().after(ClockUtil.getCurrentTime())) {
        ClockUtil.setCurrentTime(candidate.get().getDue());
      }
      candidate.get().execute(engine);
    }
  }

  private Date calculateNextStartTime(Date previousStartTime, Date latestStartTime) {
    Calendar nextStartTime = Calendar.getInstance();
    if (previousStartTime == null) {
      nextStartTime = Calendar.getInstance();
      nextStartTime.add(Calendar.DAY_OF_YEAR, -1 * numberOfDaysInPast);
      nextStartTime.set(Calendar.HOUR_OF_DAY, 0);
      nextStartTime.set(Calendar.MINUTE, 0);
    } else {
      nextStartTime.setTime(previousStartTime);
    }

    while (!nextStartTime.getTime().after(latestStartTime)) {
      // business day (OK - simplified - do not take holidays into
      // account)
      double time = timeBetweenStartsBusinessDays.nextSample();
      nextStartTime.add(Calendar.SECOND, (int) Math.round(time));
      if ((!includeWeekend) && (nextStartTime.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || nextStartTime.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)) {
        continue;
      }
      if (!isInTimeFrame(nextStartTime, startTimeBusinessDay, endTimeBusinessDay)) {
        continue;
      }
      return nextStartTime.getTime();
    }

    // we ran behind latestStartTime
    return null;
  }

  private Date cachedDayStartTime = null;
  private Date cachedDayEndTime = null;

  private boolean isInTimeFrame(Calendar cal, String startTime, String endTime) {
    try {
      if (cachedDayStartTime == null || cachedDayEndTime == null) {
        cachedDayStartTime = new SimpleDateFormat("HH:mm").parse(startTime);
        cachedDayEndTime = new SimpleDateFormat("HH:mm").parse(endTime);
      }
      Calendar startCal = Calendar.getInstance();
      startCal.setTime(cachedDayStartTime);
      copyTimeField(cal, startCal, Calendar.YEAR, Calendar.DAY_OF_YEAR);

      Calendar endCal = Calendar.getInstance();
      endCal.setTime(cachedDayEndTime);
      copyTimeField(cal, endCal, Calendar.YEAR, Calendar.DAY_OF_YEAR);

      return (startCal.before(cal) && cal.before(endCal));
    } catch (ParseException ex) {
      throw new RuntimeException("Could not parse time format: '" + startTime + "' or '" + endTime + "'", ex);
    }
  }

  protected Optional<Work<?>> calculateNextSimulationStep(Date theRealNow, Set<String> runningProcessInstanceIds,
      Set<String> processInstancIdsAlreadyReachedCurrentTime) {
    if (runningProcessInstanceIds.isEmpty()) {
      return Optional.empty();
    }

    /* collect all started process instances by events or whatever */
    engine.getRuntimeService().createProcessInstanceQuery().deploymentId(deploymentId).list().stream() //
        .map(ProcessInstance::getId) //
        .filter(id -> !processInstancIdsAlreadyReachedCurrentTime.contains(id)) //
        .forEach(runningProcessInstanceIds::add);
    /*
     * collect all previously started call activities
     * 
     * have to do this "recursively", since a process instance can start a call
     * activity that starts a call activity that starts a call activity...
     * 
     * In fact we do this here by calculating the transitive hull the hard way.
     */
    List<HistoricActivityInstance> allRunningCallActivities = engine.getHistoryService().createHistoricActivityInstanceQuery().unfinished()
        .activityType("callActivity").list();
    long added;
    do {
      added = 0;
      for (HistoricActivityInstance callActivity : allRunningCallActivities) {
        String calledProcessInstanceId = callActivity.getCalledProcessInstanceId();
        if (calledProcessInstanceId == null) {
          // this is a case call activity - no support yet
          continue;
        }
        if (!processInstancIdsAlreadyReachedCurrentTime.contains(calledProcessInstanceId)
            && runningProcessInstanceIds.contains(callActivity.getProcessInstanceId())) {
          if (runningProcessInstanceIds.add(calledProcessInstanceId)) {
            added++;
          }
        }
      }
    } while (added > 0);

    /* get all doable work of all running (process|call activity) instances */
    List<Work<?>> candidates = new LinkedList<>();
    for (ProcessInstance pi : engine.getRuntimeService().createProcessInstanceQuery().processInstanceIds(runningProcessInstanceIds).list()) {
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

      // try to find something executable: first come first serve, but nothing
      // in "real" future
      Optional<Work<?>> candidateOfProcessInstance = todo.stream() //
          .filter(work -> !work.getDue().after(theRealNow)) //
          .min((workA, workB) -> workA.getDue().compareTo(workB.getDue()));

      // if there is no work to do, we are done -- if everything would be so
      // easy
      if (!candidateOfProcessInstance.isPresent()) {
        runningProcessInstanceIds.remove(pi.getId());
        if (todo.isEmpty()) {
          LOG.debug("Instance " + pi.getId() + " finished (no work anymore).");
        } else {
          LOG.debug("Instance " + pi.getId() + " reached current time -- stopping.");
          processInstancIdsAlreadyReachedCurrentTime.add(pi.getId());
        }
      } else {
        candidates.add(candidateOfProcessInstance.get());
      }
    }

    // add the metric job always
    MetricWork metricWork = new MetricWork(null, null);
    if (!metricWork.getDue().after(theRealNow)) {
      candidates.add(metricWork);
    }

    Optional<Work<?>> candidate = candidates.stream() //
        .min((workA, workB) -> workA.getDue().compareTo(workB.getDue()));

    return candidate;
  }

  abstract class Work<T> {
    protected T workItem;
    protected ProcessInstance pi;

    public Work(T workItem, ProcessInstance pi) {
      this.workItem = workItem;
      this.pi = pi;
    }

    abstract protected Date calculateNewRandomDue();

    public void execute(ProcessEngine engine) {
      executeImpl(engine);
      // If we are a recurring job, we have to make sure that due date is newly
      // calculated after each execution. To do so, we simply remove it from
      // cache in every case.
      dueCache.remove(workItem);
    };

    abstract protected void executeImpl(ProcessEngine engine);

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

  private Date nextMetricTime = null;

  class MetricWork extends Work<Object> {

    public MetricWork(Object workItem, ProcessInstance pi) {
      super(null, null);
      if (nextMetricTime == null) {
        nextMetricTime = ClockUtil.getCurrentTime();
      }
    }

    @Override
    public Date getDue() {
      return nextMetricTime;
    }

    @Override
    protected Date calculateNewRandomDue() {
      // stub
      throw new RuntimeException("Only getDue() should be called");
    }

    @Override
    public void execute(ProcessEngine engine) {
      Calendar helper = Calendar.getInstance();
      helper.setTime(nextMetricTime);
      helper.add(Calendar.MINUTE, METRIC_INTERVAL_MINUTES);
      nextMetricTime = helper.getTime();

      Optional.ofNullable(((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getDbMetricsReporter())
          .ifPresent(DbMetricsReporter::reportNow);
    }

    @Override
    protected void executeImpl(ProcessEngine engine) {
      // stub
      throw new RuntimeException("Only execute() should be called");
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

  protected NormalDistribution createDistributionForElement(ProcessInstance pi, String id) {
    try {
      BaseElement taskElement = engine.getRepositoryService().getBpmnModelInstance(pi.getProcessDefinitionId()).getModelElementById(id);

      // Default = 10 minutes each
      double durationMean = DemoModelInstrumentator.readCamundaProperty(taskElement, "durationMean").map(Double::valueOf).orElse(600.0);
      double durationStandardDeviation = DemoModelInstrumentator.readCamundaProperty(taskElement, "durationSd").map(Double::valueOf).orElse(600.0);

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

  public TimeAwareDemoGenerator skipLastDays(int numberOfDaysToSkip) {
    this.numberOfDaysToSkip = numberOfDaysToSkip;
    return this;
  }

  public TimeAwareDemoGenerator startTimeBusinessDay(String startTimeBusinessDay) {
    this.startTimeBusinessDay = startTimeBusinessDay;
    return this;
  }

  public TimeAwareDemoGenerator endTimeBusinessDay(String endTimeBusinessDay) {
    this.endTimeBusinessDay = endTimeBusinessDay;
    return this;
  }

  public TimeAwareDemoGenerator includeWeekend(boolean includeWeekend) {
    this.includeWeekend = includeWeekend;
    return this;
  }

  public TimeAwareDemoGenerator runAlways(boolean runAlways) {
    this.runAlways = runAlways;
    return this;
  }

}
