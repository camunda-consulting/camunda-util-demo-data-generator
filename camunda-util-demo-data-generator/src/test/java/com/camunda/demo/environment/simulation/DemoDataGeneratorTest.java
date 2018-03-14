package com.camunda.demo.environment.simulation;

import static org.camunda.bpm.engine.test.assertions.ProcessEngineAssertions.init;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineAssertions.processEngine;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.execute;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.historyService;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.repositoryService;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.runtimeService;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstanceQuery;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.camunda.bpm.engine.variable.value.TypedValue;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.builder.EndEventBuilder;
import org.camunda.bpm.model.bpmn.impl.instance.camunda.CamundaPropertiesImpl;
import org.camunda.bpm.model.bpmn.instance.BpmnModelElementInstance;
import org.camunda.bpm.model.bpmn.instance.Definitions;
import org.camunda.bpm.model.bpmn.instance.Extension;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Categories.ExcludeCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.camunda.demo.environment.DemoDataGenerator;

import ch.qos.logback.core.net.SyslogOutputStream;

/**
 * Test case starting an in-memory database-backed Process Engine.
 */
public class DemoDataGeneratorTest {
  Logger LOG = LoggerFactory.getLogger(DemoDataGeneratorTest.class);

  @Rule
  public ProcessEngineRule rule = new ProcessEngineRule();

  // enable more detailed logging
  static {
    // LogUtil.readJavaUtilLoggingConfigFromClasspath(); // process engine
    // LogFactory.useJdkLogging(); // MyBatis
  }

  @Before
  public void setup() {
    init(rule.getProcessEngine());

    List<String> list = rule.getHistoryService().createHistoricProcessInstanceQuery().list().stream().map(HistoricProcessInstance::getId)
        .collect(Collectors.toList());
    if (!list.isEmpty()) {
      rule.getProcessEngineConfiguration().getCommandExecutorTxRequired().execute(new Command<Object>() {

        @Override
        public Object execute(CommandContext commandContext) {
          commandContext.getHistoricProcessInstanceManager().deleteHistoricProcessInstanceByIds(list);
          return null;
        }
      });
    }

    // execute(job);

  }

  @Test
  @Deployment(resources = "simulate.bpmn")
  public void testSimulationDrive() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("simulate") //
        .numberOfDaysInPast(1) //
        .startTimeBusinessDay("00:00") //
        .endTimeBusinessDay("23:59") //
        .includeWeekend(true) //
        .timeBetweenStartsBusinessDays(6000.0, 100.0); // every 6000 seconds
    generator.run();
  }

  @Test
  @Deployment(resources = "businessKey.bpmn")
  public void testSetBusinessNoInitial() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("businessKeyNoInitial") //
        .additionalModelKeys("businessKeySub") //
        .numberOfDaysInPast(1) //
        .startTimeBusinessDay("00:00") //
        .endTimeBusinessDay("23:00") //
        .includeWeekend(true) //
        .timeBetweenStartsBusinessDays(864.0, 0.0);
    try {
      generator.run();
      assert (false); // expect exception, never get here
    } catch (Exception e) {
      assert (e.getMessage().contains("has no default start event"));
    }
  }

  @Test
  @Deployment(resources = "businessKey.bpmn")
  public void testSetBusinessKeyTimer() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("businessKeyTimer") //
        .additionalModelKeys("businessKeySub") //
        .numberOfDaysInPast(1) //
        .startTimeBusinessDay("00:00") //
        .endTimeBusinessDay("23:00") //
        .includeWeekend(true) //
        .timeBetweenStartsBusinessDays(864.0, 0.0);
    generator.run();

    List<HistoricProcessInstance> parents = processEngine().getHistoryService().createHistoricProcessInstanceQuery().finished()
        .processDefinitionKey("businessKeyTimer").list();
    List<HistoricProcessInstance> subs = processEngine().getHistoryService().createHistoricProcessInstanceQuery().finished()
        .processDefinitionKey("businessKeySub").list();

    assert (parents.size() == subs.size());

    Set<String> uniqueCheckParent = new HashSet<>();
    Set<String> uniqueCheckSub = new HashSet<>();

    for (HistoricProcessInstance pi : parents) {
      assert (pi.getBusinessKey() != null);
      assert (pi.getBusinessKey().startsWith("BK_"));
      assert (pi.getBusinessKey().length() > 3);
      assert (!uniqueCheckParent.contains(pi.getBusinessKey()));
      uniqueCheckParent.add(pi.getBusinessKey());
    }
    for (HistoricProcessInstance pi : subs) {
      assert (pi.getBusinessKey() != null);
      assert (pi.getBusinessKey().startsWith("BK_"));
      assert (pi.getBusinessKey().length() > 3);
      assert (!uniqueCheckSub.contains(pi.getBusinessKey()));
      uniqueCheckSub.add(pi.getBusinessKey());
    }
  }

  @Test
  @Deployment(resources = "businessKey.bpmn")
  public void testSetBusinessKey() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("businessKeyParent") //
        .additionalModelKeys("businessKeySub") //
        .numberOfDaysInPast(1) //
        .startTimeBusinessDay("00:00") //
        .endTimeBusinessDay("23:00") //
        .includeWeekend(true) //
        .timeBetweenStartsBusinessDays(864.0, 0.0);
    generator.run();

    List<HistoricProcessInstance> parents = processEngine().getHistoryService().createHistoricProcessInstanceQuery().finished()
        .processDefinitionKey("businessKeyParent").list();
    List<HistoricProcessInstance> subs = processEngine().getHistoryService().createHistoricProcessInstanceQuery().finished()
        .processDefinitionKey("businessKeySub").list();

    assert (parents.size() == subs.size());

    Set<String> uniqueCheckParent = new HashSet<>();
    Set<String> uniqueCheckSub = new HashSet<>();

    for (HistoricProcessInstance pi : parents) {
      assert (pi.getBusinessKey() != null);
      assert (pi.getBusinessKey().startsWith("BK_"));
      assert (pi.getBusinessKey().length() > 3);
      assert (!uniqueCheckParent.contains(pi.getBusinessKey()));
      uniqueCheckParent.add(pi.getBusinessKey());
    }
    for (HistoricProcessInstance pi : subs) {
      assert (pi.getBusinessKey() != null);
      assert (pi.getBusinessKey().startsWith("BK_"));
      assert (pi.getBusinessKey().length() > 3);
      assertTrue("Sub bk doubled: " + pi.getBusinessKey(), !uniqueCheckSub.contains(pi.getBusinessKey()));
      uniqueCheckSub.add(pi.getBusinessKey());
    }
  }

  @Test
  @Deployment(resources = "setVariable.bpmn")
  public void testSetVariable() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("setVariable") //
        .numberOfDaysInPast(1) //
        .startTimeBusinessDay("00:00") //
        .endTimeBusinessDay("23:00") //
        .includeWeekend(true) //
        .timeBetweenStartsBusinessDays(864.0, 0.0);
    generator.run();

    List<HistoricProcessInstance> finished = processEngine().getHistoryService().createHistoricProcessInstanceQuery().finished()
        .processDefinitionKey("setVariable").list();

    Set<String> uniqueCheck = new HashSet<>();
    long countMale = 0;
    long countFemale = 0;

    for (HistoricProcessInstance pi : finished) {
      List<HistoricVariableInstance> vars = historyService().createHistoricVariableInstanceQuery().processInstanceId(pi.getId()).list();
      int found = 0;
      for (HistoricVariableInstance vi : vars) {
        if ("third".equals(vi.getName())) {
          assertEquals("Hello lovely world", vi.getValue());
          found++;
        }
        if ("dto".equals(vi.getName())) {
          SampleDTO dto = (SampleDTO) vi.getValue();

          assert (!uniqueCheck.contains(dto.getUuid()));
          uniqueCheck.add(dto.getUuid());

          assert (dto.getPerson().getSex().equals("male") || dto.getPerson().getSex().equals("female"));
          if (dto.getPerson().getSex().equals("male"))
            countMale++;
          if (dto.getPerson().getSex().equals("female"))
            countFemale++;

          found++;

          System.out.println(dto);
        }
      }
      assertEquals(2, found);
    }

    // even distribution from uniformFromArgs2 ?
    assert ((double) countMale / (countMale + countFemale) > 0.4);
    assert ((double) countMale / (countMale + countFemale) < 0.6);
  }

  @Test
  @Deployment(resources = "keepImplementation.bpmn")
  public void testKeepImplementation() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("keepImplementation") //
        .numberOfDaysInPast(1) //
        .startTimeBusinessDay("00:00") //
        .endTimeBusinessDay("23:59") //
        .includeWeekend(true) //
        .timeBetweenStartsBusinessDays(864.0, 0.0);
    generator.run();

    long runned = processEngine().getHistoryService().createHistoricProcessInstanceQuery().finished().processDefinitionKey("keepImplementation").count();
    long never = processEngine().getHistoryService().createHistoricActivityInstanceQuery()
        .processDefinitionId(getProcessDefinitionIdByKey("keepImplementation")).activityId("EndEvent_Never").count();
    double fifty1 = processEngine().getHistoryService().createHistoricActivityInstanceQuery()
        .processDefinitionId(getProcessDefinitionIdByKey("keepImplementation")).activityId("EndEvent_Fifty1").count();
    double fifty2 = processEngine().getHistoryService().createHistoricActivityInstanceQuery()
        .processDefinitionId(getProcessDefinitionIdByKey("keepImplementation")).activityId("EndEvent_Fifty2").count();

    assertEquals(101, runned);
    assertEquals(0, never);
    assert (fifty1 / fifty2 > 0.4);
    assert (fifty2 / fifty1 > 0.4);
    assertEquals(0, new TestDelegate1().count());
    assertEquals(0, TestBalloon.getCounter("no"));
    assertEquals(3 * runned, new TestDelegate2().count());
    assertEquals(3 * runned, TestBalloon.getCounter("yes"));
  }

  @Test
  @Deployment(resources = "isoTimeAndConstantDistribution.bpmn")
  public void testIsoTimeAndConstantDistribution() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("isoTimeAndConstantDistribution") //
        .numberOfDaysInPast(1) //
        .startTimeBusinessDay("00:00") //
        .endTimeBusinessDay("23:59") //
        .includeWeekend(true) //
        .timeBetweenStartsBusinessDays(3600.0, 0.0);
    generator.run();

    long epsilon = 1;

    List<HistoricProcessInstance> finished = processEngine().getHistoryService().createHistoricProcessInstanceQuery().finished()
        .processDefinitionKey("isoTimeAndConstantDistribution").list();

    assertEquals(25, finished.size());
    assert (finished.stream().map(HistoricProcessInstance::getDurationInMillis)
        .allMatch(duration -> duration - epsilon < 5400000 && duration + epsilon > 5400000));
  }

  @Test
  @Deployment(resources = "externalTask.bpmn")
  public void testExternalTask() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("externalTask") //
        .numberOfDaysInPast(1) //
        .startTimeBusinessDay("00:00") //
        .endTimeBusinessDay("23:59") //
        .includeWeekend(true) //
        .timeBetweenStartsBusinessDays(600.0, 100.0);
    generator.run();

    long runned = processEngine().getHistoryService().createHistoricProcessInstanceQuery().finished().processDefinitionKey("externalTask").count();
    // we have at least every 740 seconds a finish and run at least 24h
    assert (runned > 115);
  }

  @Test
  @Deployment(resources = "boundaryMessage.bpmn")
  public void testMessageBoundary() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("boundaryMessage") //
        .numberOfDaysInPast(1) //
        .startTimeBusinessDay("00:00") //
        .endTimeBusinessDay("23:59") //
        .includeWeekend(true) //
        .timeBetweenStartsBusinessDays(600.0, 100.0);
    generator.run();

    long runned = processEngine().getHistoryService().createHistoricProcessInstanceQuery().finished().processDefinitionKey("boundaryMessage").count();

    long taken1 = processEngine().getHistoryService().createHistoricActivityInstanceQuery().processDefinitionId(getProcessDefinitionIdByKey("boundaryMessage"))
        .activityId("EndEvent_Taken1").count();
    long notTaken1 = processEngine().getHistoryService().createHistoricActivityInstanceQuery()
        .processDefinitionId(getProcessDefinitionIdByKey("boundaryMessage")).activityId("EndEvent_NotTaken1").count();
    long taken2 = processEngine().getHistoryService().createHistoricActivityInstanceQuery().processDefinitionId(getProcessDefinitionIdByKey("boundaryMessage"))
        .activityId("EndEvent_Taken2").count();
    long notTaken2 = processEngine().getHistoryService().createHistoricActivityInstanceQuery()
        .processDefinitionId(getProcessDefinitionIdByKey("boundaryMessage")).activityId("EndEvent_NotTaken2").count();

    assert (taken1 + 10 > runned);
    assert (taken2 == 0);
    assert (notTaken2 + 10 > runned);
    assert (notTaken1 == 0);
  }

  @Test
  @Deployment(resources = "cycleBoundaryTimer.bpmn")
  public void testCycleBoundaryTimer() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("cycleBoundaryTimer") //
        .numberOfDaysInPast(1) //
        .startTimeBusinessDay("00:00") //
        .endTimeBusinessDay("23:59") //
        .includeWeekend(true) //
        .timeBetweenStartsBusinessDays(6000.0, 100.0);
    generator.run();

    long taken = processEngine().getHistoryService().createHistoricActivityInstanceQuery()
        .processDefinitionId(getProcessDefinitionIdByKey("cycleBoundaryTimer")).activityId("EndEvent_Taken").count();
    long notTaken = processEngine().getHistoryService().createHistoricActivityInstanceQuery()
        .processDefinitionId(getProcessDefinitionIdByKey("cycleBoundaryTimer")).activityId("EndEvent_NotTaken").count();
    double all = taken + notTaken; // force floating point later

    LOG.info(taken / all * 100 + "% taken, " + notTaken / all * 100 + "% not taken");

    // take timer roughly ten times before finish user task
    assert (Math.abs(taken / all) > 0.8);
    assert (Math.abs(taken / all) < 1);
  }

  String getProcessDefinitionIdByKey(String processDefinitionKey) {
    return repositoryService().createProcessDefinitionQuery().processDefinitionKey(processDefinitionKey).versionTag("demo-data-generator").singleResult()
        .getId();
  }

  @Test
  @Deployment(resources = { "repeatCallActivityMain.bpmn", "repeatCallActivitySub.bpmn" })
  public void testRepeatCallActivity() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("repeatCallActivityMain") //
        .additionalModelKeys("repeatCallActivitySub") //
        .numberOfDaysInPast(1) //
        .startTimeBusinessDay("00:00") //
        .endTimeBusinessDay("23:59") //
        .includeWeekend(true) //
        .timeBetweenStartsBusinessDays(6000.0, 100.0);
    generator.run();

    long taken = processEngine().getHistoryService().createHistoricActivityInstanceQuery()
        .processDefinitionId(getProcessDefinitionIdByKey("repeatCallActivitySub")).activityId("EndEvent_SubTaken").count();
    long notTaken = processEngine().getHistoryService().createHistoricActivityInstanceQuery()
        .processDefinitionId(getProcessDefinitionIdByKey("repeatCallActivityMain")).activityId("EndEvent_NotTaken").count();
    double all = taken + notTaken; // force floating point later

    LOG.info(taken / all * 100 + "% taken, " + notTaken / all * 100 + "% not taken");

    // we expect call activitiy to be run 2 times per main run
    assert (taken > notTaken * 1.9);
    assert (taken < notTaken * 2.1);
  }

  @Test
  @Deployment(resources = { "signal.bpmn" })
  public void testSignal() {
    DemoDataGenerator.autoGenerateFor(processEngine(), "signalMaster", null, "signalSlave");

    double finished = processEngine().getHistoryService().createHistoricActivityInstanceQuery().processDefinitionId(getProcessDefinitionIdByKey("signalSlave"))
        .activityId("EndEvent_Finished").count();
    double interrupted = processEngine().getHistoryService().createHistoricActivityInstanceQuery()
        .processDefinitionId(getProcessDefinitionIdByKey("signalSlave")).activityId("EndEvent_Interrupted").count();
    double total = finished + interrupted;

    assert (interrupted / total > 0.3);
    assert (interrupted / total < 0.7);
  }

  @Test
  @Deployment(resources = { "runAlways.bpmn", "runOnce.bpmn" })
  public void testRunAlways() {
    DemoDataGenerator.autoGenerateFor(processEngine(), "runOnce", null);
    long firstRun = processEngine().getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey("runOnce").count();

    assert (firstRun > 0);

    DemoDataGenerator.autoGenerateFor(processEngine(), "runOnce", null);
    long secondRun = processEngine().getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey("runOnce").count();

    assertEquals(firstRun, secondRun);

    DemoDataGenerator.autoGenerateFor(processEngine(), "runAlways", null);
    firstRun = processEngine().getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey("runAlways").count();
    assert (firstRun > 0);
    DemoDataGenerator.autoGenerateFor(processEngine(), "runAlways", null);
    secondRun = processEngine().getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey("runAlways").count();
    assert (firstRun < secondRun);
  }

  @Test
  @Deployment(resources = "participant.bpmn")
  public void testParticipant() {
    DemoDataGenerator.autoGenerateFor(processEngine(), "participant", null);
    long runned = processEngine().getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey("participant").count();

    assertEquals(25, runned);
  }

  // @Test
  @Deployment(resources = { "InsuranceApplication.bpmn", "ApplicationCheck.cmmn" })
  public void testInsuranceApplicationWithCmmn() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("insurance-application") //
        .numberOfDaysInPast(2) //
        .timeBetweenStartsBusinessDays(6000.0, 100.0); // every 6000 seconds
    generator.run();

    // everything should be finished as case instance gets completed
    assertEquals(0, processEngine().getRuntimeService().createProcessInstanceQuery().processDefinitionKey("insurance-application").count());
  }

  @Test
  public void testModelApiUtf8Bug() throws UnsupportedEncodingException {
    BpmnModelInstance modelInstance = Bpmn.readModelFromStream(this.getClass().getResourceAsStream("/umlauts.bpmn"));

    Collection<ModelElementInstance> serviceTasks = modelInstance.getModelElementsByType(modelInstance.getModel().getType(ServiceTask.class));
    assertEquals(1, serviceTasks.size());
    ServiceTask serviceTask = (ServiceTask) serviceTasks.iterator().next();
    serviceTask.setCamundaExpression("#{true}");

    String xmlString = Bpmn.convertToString(modelInstance);
    org.camunda.bpm.engine.repository.Deployment deployment = processEngine().getRepositoryService().createDeployment() //
        .addInputStream("umlauts.bpmn", new ByteArrayInputStream(xmlString.getBytes("UTF-8"))).deploy();

    processEngine().getRepositoryService().deleteDeployment(deployment.getId(), true);
  }

  // @Test
  // public void testModelApiUtf8BugUsingTweak() {
  // org.camunda.bpm.engine.repository.Deployment deployment =
  // processEngine().getRepositoryService().createDeployment() //
  // .addClasspathResource("rechnungseingang.bpmn") //
  // .deploy();
  //
  // TimeAwareDemoGenerator generator = new
  // TimeAwareDemoGenerator(processEngine());
  // generator.processDefinitionKey("rechnungseingang").numberOfDaysInPast(5);
  // generator.tweakProcessDefinition();
  // processEngine().getRepositoryService().deleteDeployment(deployment.getId(),
  // true);
  // }

  @Test
  public void testModelApiBug() {
    BpmnModelInstance modelInstance = Bpmn.createEmptyModel();
    Definitions definitions = modelInstance.newInstance(Definitions.class);
    definitions.setTargetNamespace("http://camunda.org/examples");
    modelInstance.setDefinitions(definitions);
    org.camunda.bpm.model.bpmn.instance.Process process = modelInstance.newInstance(org.camunda.bpm.model.bpmn.instance.Process.class);
    process.setId("test");
    process.setExecutable(true);
    definitions.addChildElement(process);

    StartEvent startEvent = modelInstance.newInstance(StartEvent.class);
    startEvent.setAttributeValue("id", "startEvent1", true);
    process.addChildElement(startEvent);

    ServiceTask serviceTask = modelInstance.newInstance(ServiceTask.class);
    serviceTask.setAttributeValue("id", "serviceTask1", true);
    serviceTask.setCamundaClass(null);
    // TODO: Wait for https://app.camunda.com/jira/browse/CAM-4178 and set to
    // null!
    // serviceTask.setCamundaDelegateExpression(null);
    serviceTask.setCamundaExpression("#{true}"); // Noop
    process.addChildElement(serviceTask);

    createSequenceFlow(process, startEvent, serviceTask);

    repositoryService().createDeployment().addModelInstance("test.bpmn", modelInstance).deploy();
    runtimeService().startProcessInstanceByKey("test");
  }

  public SequenceFlow createSequenceFlow(org.camunda.bpm.model.bpmn.instance.Process process, FlowNode from, FlowNode to) {
    String identifier = from.getId() + "-" + to.getId();
    SequenceFlow sequenceFlow = createElement(process, identifier, SequenceFlow.class);
    process.addChildElement(sequenceFlow);
    sequenceFlow.setSource(from);
    from.getOutgoing().add(sequenceFlow);
    sequenceFlow.setTarget(to);
    to.getIncoming().add(sequenceFlow);
    return sequenceFlow;
  }

  protected <T extends BpmnModelElementInstance> T createElement(BpmnModelElementInstance parentElement, String id, Class<T> elementClass) {
    T element = parentElement.getModelInstance().newInstance(elementClass);
    element.setAttributeValue("id", id, true);
    parentElement.addChildElement(element);
    return element;
  }
}
