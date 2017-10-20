package com.camunda.demo.environment.simulation;

import static org.camunda.bpm.engine.test.assertions.ProcessEngineAssertions.init;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineAssertions.processEngine;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.repositoryService;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.runtimeService;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.List;

import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BpmnModelElementInstance;
import org.camunda.bpm.model.bpmn.instance.Definitions;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.camunda.demo.environment.DemoDataGenerator;

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
  }

  @Test
  @Deployment(resources = "simulate.bpmn")
  public void testSimulationDrive() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("simulate") //
        .numberOfDaysInPast(2) //
        .timeBetweenStartsBusinessDays(6000.0, 100.0); // every 6000 seconds
    generator.generateData();

    // ProcessInstance pi =
    // runtimeService().startProcessInstanceByKey("simulate");
    // assertThat(pi).task();
    // complete(task());
    // assertThat(pi).isEnded();
  }

  @Test
  @Deployment(resources = "externalTask.bpmn")
  public void testExternalTask() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("externalTask") //
        .numberOfDaysInPast(2) //
        .timeBetweenStartsBusinessDays(600.0, 100.0);
    generator.generateData();

    long runned = processEngine().getHistoryService().createHistoricProcessInstanceQuery().finished().processDefinitionKey("externalTask").count();
    // we have at least every 740 seconds a finish and run at least 24h
    assert (runned > 115);
  }

  @Test
  @Deployment(resources = "boundaryMessage.bpmn")
  public void testMessageBoundary() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("boundaryMessage") //
        .numberOfDaysInPast(2) //
        .timeBetweenStartsBusinessDays(600.0, 100.0);
    generator.generateData();

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
        .numberOfDaysInPast(2) //
        .timeBetweenStartsBusinessDays(6000.0, 100.0);
    generator.generateData();

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
        .numberOfDaysInPast(2) //
        .timeBetweenStartsBusinessDays(600.0, 100.0);
    generator.generateData();

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
  @Deployment(resources = { "runOnce.bpmn", "runAlways.bpmn" })
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

  // @Test
  @Deployment(resources = { "InsuranceApplication.bpmn", "ApplicationCheck.cmmn" })
  public void testInsuranceApplicationWithCmmn() {
    TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(processEngine()) //
        .processDefinitionKey("insurance-application") //
        .numberOfDaysInPast(2) //
        .timeBetweenStartsBusinessDays(6000.0, 100.0); // every 6000 seconds
    generator.generateData();

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
