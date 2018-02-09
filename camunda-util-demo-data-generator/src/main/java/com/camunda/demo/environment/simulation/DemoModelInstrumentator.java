package com.camunda.demo.environment.simulation;

import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.camunda.bpm.application.ProcessApplicationReference;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.ProcessEngineImpl;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.impl.BpmnModelConstants;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent;
import org.camunda.bpm.model.bpmn.instance.BusinessRuleTask;
import org.camunda.bpm.model.bpmn.instance.CallActivity;
import org.camunda.bpm.model.bpmn.instance.ConditionExpression;
import org.camunda.bpm.model.bpmn.instance.EndEvent;
import org.camunda.bpm.model.bpmn.instance.EventBasedGateway;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.InclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.camunda.bpm.model.bpmn.instance.IntermediateThrowEvent;
import org.camunda.bpm.model.bpmn.instance.ManualTask;
import org.camunda.bpm.model.bpmn.instance.ParallelGateway;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.ReceiveTask;
import org.camunda.bpm.model.bpmn.instance.ScriptTask;
import org.camunda.bpm.model.bpmn.instance.SendTask;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.SubProcess;
import org.camunda.bpm.model.bpmn.instance.Task;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaExecutionListener;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaScript;
import org.camunda.bpm.model.xml.ModelInstance;
import org.camunda.bpm.model.xml.impl.util.IoUtil;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DemoModelInstrumentator {

  private static final Logger LOG = LoggerFactory.getLogger(DemoModelInstrumentator.class);
  private ProcessEngineImpl engine;

  private ProcessApplicationReference originalProcessApplication;
  private ProcessApplicationReference simulatingProcessApplication;

  private Map<String, String> originalModels = new HashMap<String, String>();
  private Map<String, String> tweakedModels = new HashMap<String, String>();
  private Set<String> tweakedProcessKeys = new HashSet<>();

  public DemoModelInstrumentator(ProcessEngine engine, ProcessApplicationReference originalProcessApplication,
      ProcessApplicationReference simulatingProcessApplication) {
    this.engine = (ProcessEngineImpl) engine;
    this.originalProcessApplication = originalProcessApplication;
    this.simulatingProcessApplication = simulatingProcessApplication;
  }

  public void addAdditionalModels(String... additionalModelKeys) {
    for (String key : additionalModelKeys) {
      ProcessDefinition pd = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult();
      if (pd != null) {
        addBpmn(key);
      } else {
        LOG.warn("Model key '{}' is not a BPMN model - ignoring", key);
      }
    }

  }

  public void addBpmn(String processDefinitionKey) {
    tweakProcessDefinition(processDefinitionKey);
  }

  public String deployTweakedModels() {
    LOG.info("Starting deployment of tweaked models for demo data generation");
    try {
      DeploymentBuilder deploymentBuilder = engine.getRepositoryService().createDeployment();
      for (Entry<String, String> model : tweakedModels.entrySet()) {
        deploymentBuilder.addInputStream(model.getKey(), new ByteArrayInputStream(model.getValue().getBytes("UTF-8")));
      }
      Deployment deployment = deploymentBuilder.deploy();
      if (simulatingProcessApplication != null) {
        engine.getManagementService().registerProcessApplication(deployment.getId(), simulatingProcessApplication);
      }
      LOG.info("Deployed tweaked modes for demo data generation with deployment " + deployment.getId());
      return deployment.getId();
    } catch (Exception ex) {
      throw new RuntimeException("Could not deploy tweaked process definition", ex);
    }
  }

  public void restoreOriginalModels() {
    LOG.info("Starting to restore models after demo data generation");
    try {
      DeploymentBuilder deploymentBuilder = engine.getRepositoryService().createDeployment();
      for (Entry<String, String> model : originalModels.entrySet()) {
        deploymentBuilder.addInputStream(model.getKey(), new ByteArrayInputStream(model.getValue().getBytes("UTF-8")));
      }
      Deployment deployment = deploymentBuilder.deploy();
      if (originalProcessApplication != null) {
        engine.getManagementService().registerProcessApplication(deployment.getId(), originalProcessApplication);
      }
      LOG.info("Restored original modes after demo data generation with deployment " + deployment.getId());
    } catch (Exception ex) {
      throw new RuntimeException("Could not restore original models", ex);
    }
  }

  protected void tweakProcessDefinition(String processDefinitionKey) {
    if (tweakedProcessKeys.contains(processDefinitionKey)) {
      return;
    }

    LOG.info("tweak process definition " + processDefinitionKey);

    ProcessDefinition processDefinition = engine.getRepositoryService().createProcessDefinitionQuery() //
        .processDefinitionKey(processDefinitionKey) //
        .latestVersion() //
        .singleResult();
    if (processDefinition == null) {
      throw new RuntimeException("Process with key '" + processDefinitionKey + "' not found.");
    }
    // store original process application reference
    if (originalProcessApplication == null) {
      originalProcessApplication = engine.getProcessEngineConfiguration().getProcessApplicationManager()
          .getProcessApplicationForDeployment(processDefinition.getDeploymentId());
      if (simulatingProcessApplication == null) {
        simulatingProcessApplication = originalProcessApplication;
      }
    }

    BpmnModelInstance bpmn = engine.getRepositoryService().getBpmnModelInstance(processDefinition.getId());

    String originalBpmn = IoUtil.convertXmlDocumentToString(bpmn.getDocument());
    // do not do a validation here as it caused quite strange trouble
    LOG.debug("-----\n" + originalBpmn + "\n------");

    originalModels.put(processDefinitionKey + ".bpmn", originalBpmn);

    Collection<Process> processes = bpmn.getModelElementsByType(Process.class);
    processes.stream().forEach(process -> process.setAttributeValueNs("http://camunda.org/schema/1.0/bpmn", "versionTag", "demo-data-generator"));

    Collection<ModelElementInstance> serviceTasks = bpmn.getModelElementsByType(bpmn.getModel().getType(ServiceTask.class));
    Collection<ModelElementInstance> sendTasks = bpmn.getModelElementsByType(bpmn.getModel().getType(SendTask.class));
    // Collection<ModelElementInstance> receiveTasks =
    // bpmn.getModelElementsByType(bpmn.getModel().getType(ReceiveTask.class));
    Collection<ModelElementInstance> businessRuleTasks = bpmn.getModelElementsByType(bpmn.getModel().getType(BusinessRuleTask.class));
    // Collection<ModelElementInstance> scriptTasks =
    // bpmn.getModelElementsByType(bpmn.getModel().getType(ScriptTask.class));
    Collection<ModelElementInstance> userTasks = bpmn.getModelElementsByType(bpmn.getModel().getType(UserTask.class));
    Collection<ModelElementInstance> executionListeners = bpmn.getModelElementsByType(bpmn.getModel().getType(CamundaExecutionListener.class));
    // Collection<ModelElementInstance> taskListeners =
    // bpmn.getModelElementsByType(bpmn.getModel().getType(CamundaTaskListener.class));
    Collection<ModelElementInstance> xorGateways = bpmn.getModelElementsByType(bpmn.getModel().getType(ExclusiveGateway.class));
    // Collection<ModelElementInstance> orGateways =
    // bpmn.getModelElementsByType(bpmn.getModel().getType(InclusiveGateway.class));
    Collection<ModelElementInstance> scripts = bpmn.getModelElementsByType(bpmn.getModel().getType(CamundaScript.class));
    Collection<BaseElement> elementsWithSetVariable = bpmn.getModelElementsByType(CamundaProperties.class).stream() //
        .filter(properties -> properties.getCamundaProperties().stream().anyMatch(property -> "simulateSetVariable".equals(property.getCamundaName()))) //
        .map(ModelElementInstance::getParentElement) //
        // should be bpmn2:extensionElements now
        .map(ModelElementInstance::getParentElement) //
        // only interested in base elements (not form fields or whatever)
        .filter(BaseElement.class::isInstance) //
        .map(BaseElement.class::cast) //
        .collect(Collectors.toList());

    for (ModelElementInstance modelElementInstance : serviceTasks) {
      ServiceTask serviceTask = ((ServiceTask) modelElementInstance);
      if (checkKeepLogic(serviceTask)) {
        continue;
      }
      serviceTask.setCamundaClass(null);
      // TODO: Wait for https://app.camunda.com/jira/browse/CAM-4178 and set
      // to null!
      // serviceTask.setCamundaDelegateExpression(null);
      // Workaround:
      serviceTask.removeAttributeNs("http://activiti.org/bpmn", "delegateExpression");
      serviceTask.removeAttributeNs("http://camunda.org/schema/1.0/bpmn", "delegateExpression");

      serviceTask.setCamundaExpression("#{true}"); // Noop
    }
    for (ModelElementInstance modelElementInstance : sendTasks) {
      SendTask serviceTask = ((SendTask) modelElementInstance);
      if (checkKeepLogic(serviceTask)) {
        continue;
      }
      serviceTask.setCamundaClass(null);
      serviceTask.removeAttributeNs("http://activiti.org/bpmn", "delegateExpression");
      serviceTask.removeAttributeNs("http://camunda.org/schema/1.0/bpmn", "delegateExpression");
      serviceTask.setCamundaExpression("#{true}"); // Noop
    }
    for (ModelElementInstance modelElementInstance : businessRuleTasks) {
      BusinessRuleTask businessRuleTask = (BusinessRuleTask) modelElementInstance;
      if (checkKeepLogic(businessRuleTask)) {
        continue;
      }
      businessRuleTask.removeAttributeNs("http://activiti.org/bpmn", "decisionRef"); // DMN
                                                                                     // ref
                                                                                     // from
                                                                                     // 7.4
                                                                                     // on
      businessRuleTask.removeAttributeNs("http://camunda.org/schema/1.0/bpmn", "decisionRef"); // DMN
                                                                                               // ref
                                                                                               // from
                                                                                               // 7.4
                                                                                               // on
      businessRuleTask.setCamundaClass(null);
      businessRuleTask.removeAttributeNs("http://activiti.org/bpmn", "delegateExpression");
      businessRuleTask.removeAttributeNs("http://camunda.org/schema/1.0/bpmn", "delegateExpression");

      businessRuleTask.setCamundaExpression("#{true}"); // Noop
    }
    for (ModelElementInstance modelElementInstance : executionListeners) {
      CamundaExecutionListener executionListener = (CamundaExecutionListener) modelElementInstance;
      // according to
      // https://docs.camunda.org/manual/7.8/reference/bpmn20/custom-extensions/extension-elements/#executionlistener
      // all possible parents are baseElements, but the direct parent is
      // 'extensionElements'
      if (checkKeepLogic((BaseElement) executionListener.getParentElement().getParentElement())) {
        continue;
      }
      // executionListener.setCamundaClass(null);
      executionListener.removeAttributeNs("http://activiti.org/bpmn", "class");
      executionListener.removeAttributeNs("http://camunda.org/schema/1.0/bpmn", "class");

      executionListener.removeAttributeNs("http://activiti.org/bpmn", "delegateExpression");
      executionListener.removeAttributeNs("http://camunda.org/schema/1.0/bpmn", "delegateExpression");
      executionListener.setCamundaExpression("#{true}"); // Noop
    }
    for (ModelElementInstance modelElementInstance : scripts) {
      // find parent base element
      ModelElementInstance parent = modelElementInstance;
      do {
        parent = parent.getParentElement();
      } while (parent != null && !(parent instanceof BaseElement));

      // keep logic only if we found some BaseElement as parent
      if (parent != null && checkKeepLogic((BaseElement) parent)) {
        continue;
      }

      CamundaScript script = (CamundaScript) modelElementInstance;
      // executionListener.setCamundaClass(null);
      script.setTextContent(""); // java.lang.System.out.println('x');
      script.setCamundaScriptFormat("javascript");
      script.removeAttributeNs("http://activiti.org/bpmn", "resource");
      script.removeAttributeNs("http://camunda.org/schema/1.0/bpmn", "resource");
    }

    for (ModelElementInstance modelElementInstance : userTasks) {
      UserTask userTask = ((UserTask) modelElementInstance);
      // This does not work, it sets the values to empty strings, but we want
      // them to be removed
      // userTask.setCamundaAssignee(null);
      // userTask.setCamundaCandidateGroups(null);
      userTask.removeAttributeNs(BpmnModelConstants.CAMUNDA_NS, BpmnModelConstants.CAMUNDA_ATTRIBUTE_ASSIGNEE);
      userTask.removeAttributeNs(BpmnModelConstants.CAMUNDA_NS, BpmnModelConstants.CAMUNDA_ATTRIBUTE_CANDIDATE_GROUPS);
    }

    for (ModelElementInstance modelElementInstance : xorGateways) {
      ExclusiveGateway xorGateway = ((ExclusiveGateway) modelElementInstance);
      if (checkKeepLogic(xorGateway)) {
        continue;
      }
      tweakGateway(xorGateway);
    }

    for (BaseElement element : elementsWithSetVariable) {
      if (Stream
          .of(Process.class, Task.class, ServiceTask.class, SendTask.class, UserTask.class, BusinessRuleTask.class, ScriptTask.class, ReceiveTask.class,
              ManualTask.class, ExclusiveGateway.class, SequenceFlow.class, ParallelGateway.class, InclusiveGateway.class, EventBasedGateway.class,
              StartEvent.class, IntermediateCatchEvent.class, IntermediateThrowEvent.class, EndEvent.class, BoundaryEvent.class, SubProcess.class,
              CallActivity.class) //
          .anyMatch(clazz -> clazz.isInstance(element))) {
        enableSetVariable(element);
      } else {
        LOG.warn("Element '{}' has 'simulateSetVariable' set but allows no execution listeners, Ignoring.", element.getId());
      }
    }

    // Bpmn.validateModel(bpmn);
    String xmlString = Bpmn.convertToString(bpmn);
    tweakedModels.put(processDefinitionKey + ".bpmn", xmlString);
    LOG.debug("-----TWEAKED-----\n-----TWEAKED-----\n-----\n" + xmlString + "\n------");

    // store all contained process keys for not tweaking them twice
    bpmn.getModelElementsByType(bpmn.getModel().getType(Process.class)).forEach(instance -> tweakedProcessKeys.add((((Process) instance).getId())));
  }

  private void enableSetVariable(BaseElement element) {
    CamundaExecutionListener executionListener = element.getModelInstance().newInstance(CamundaExecutionListener.class);
    executionListener.setCamundaEvent("end");
    executionListener.setCamundaClass(VariableSetterDelegate.class.getName());
    // extension elements should exist because we have camunda:properties
    element.getExtensionElements().addChildElement(executionListener);
  }

  protected boolean checkKeepLogic(BaseElement bpmnBaseElement) {
    return readCamundaProperty(bpmnBaseElement, "simulateKeepImplementation").orElse("false").toLowerCase().equals("true");
  }

  protected void tweakGateway(ExclusiveGateway xorGateway) {
    ModelInstance bpmn = xorGateway.getModelInstance();

    double probabilitySum = 0;
    // Process Variable used to store sample from distribution to decide for
    // outgoing transition
    String var = "SIM_SAMPLE_VALUE_" + xorGateway.getId().replaceAll("-", "_");

    Collection<SequenceFlow> flows = xorGateway.getOutgoing();
    if (flows.size() > 1) { // if outgoing flows = 1 it is a joining gateway
      for (SequenceFlow sequenceFlow : flows) {
        double probability = readCamundaProperty(sequenceFlow, "probability").map(Double::valueOf).orElse(1.0);

        ConditionExpression conditionExpression = bpmn.newInstance(ConditionExpression.class);
        conditionExpression.setTextContent("#{" + var + " >= " + probabilitySum + " && " + var + " < " + (probabilitySum + probability) + "}");
        sequenceFlow.setConditionExpression(conditionExpression);

        probabilitySum += probability;
      }

      // add execution listener to do decision based on random which corresponds
      // to configured probabilities
      // (because of expressions on outgoing sequence flows)
      CamundaExecutionListener executionListener = bpmn.newInstance(CamundaExecutionListener.class);
      executionListener.setCamundaEvent("start");
      CamundaScript script = bpmn.newInstance(CamundaScript.class);
      script.setTextContent(//
          "sample = com.camunda.demo.environment.simulation.StatisticsHelper.nextSample(" + probabilitySum + ");\n" + "execution.setVariable('" + var
              + "', sample);");
      script.setCamundaScriptFormat("Javascript");
      executionListener.setCamundaScript(script);

      if (xorGateway.getExtensionElements() == null) {
        ExtensionElements extensionElements = bpmn.newInstance(ExtensionElements.class);
        xorGateway.addChildElement(extensionElements);
      }
      xorGateway.getExtensionElements().addChildElement(executionListener);
    }
  }

  public static Optional<String> readCamundaProperty(BaseElement modelElementInstance, String propertyName) {
    if (modelElementInstance.getExtensionElements() == null) {
      return Optional.empty();
    }
    return queryCamundaPropertyValues(modelElementInstance, propertyName).findFirst();
  }

  public static Collection<String> readCamundaPropertyMulti(BaseElement modelElementInstance, String propertyName) {
    if (modelElementInstance.getExtensionElements() == null) {
      return Collections.emptyList();
    }
    return queryCamundaPropertyValues(modelElementInstance, propertyName).collect(Collectors.toList());
  }

  protected static Stream<String> queryCamundaPropertyValues(BaseElement modelElementInstance, String propertyName) {
    return modelElementInstance.getExtensionElements().getElementsQuery().filterByType(CamundaProperties.class).list().stream() //
        .map(CamundaProperties::getCamundaProperties) //
        .flatMap(Collection::stream) //
        .filter(property -> property.getCamundaName().equals(propertyName)) //
        .map(CamundaProperty::getCamundaValue) //
        .filter(Objects::nonNull) //
    ;
  }

}
