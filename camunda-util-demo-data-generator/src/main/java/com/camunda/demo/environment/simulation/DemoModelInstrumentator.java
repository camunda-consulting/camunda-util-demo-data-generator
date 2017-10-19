package com.camunda.demo.environment.simulation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.camunda.bpm.application.ProcessApplicationReference;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.ProcessEngineImpl;
import org.camunda.bpm.engine.repository.CaseDefinition;
import org.camunda.bpm.engine.repository.DecisionDefinition;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.BusinessRuleTask;
import org.camunda.bpm.model.bpmn.instance.ConditionExpression;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.InclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.ReceiveTask;
import org.camunda.bpm.model.bpmn.instance.ScriptTask;
import org.camunda.bpm.model.bpmn.instance.SendTask;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaExecutionListener;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaScript;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaTaskListener;
import org.camunda.bpm.model.cmmn.Cmmn;
import org.camunda.bpm.model.cmmn.CmmnModelInstance;
import org.camunda.bpm.model.cmmn.instance.CasePlanModel;
import org.camunda.bpm.model.cmmn.instance.DecisionTask;
import org.camunda.bpm.model.cmmn.instance.HumanTask;
import org.camunda.bpm.model.cmmn.instance.Sentry;
import org.camunda.bpm.model.cmmn.instance.camunda.CamundaCaseExecutionListener;
import org.camunda.bpm.model.xml.ModelInstance;
import org.camunda.bpm.model.xml.impl.util.IoUtil;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DemoModelInstrumentator {

  private static final Logger LOG = LoggerFactory.getLogger(DemoModelInstrumentator.class);
  private ProcessEngineImpl engine;

  private ProcessApplicationReference processApplicationReference;

  private Map<String, String> originalModels = new HashMap<String, String>();
  private Map<String, String> tweakedModels = new HashMap<String, String>();

  public DemoModelInstrumentator(ProcessEngine engine, ProcessApplicationReference processApplicationReference) {
    this.engine = (ProcessEngineImpl) engine;
    this.processApplicationReference = processApplicationReference;
  }

  public void addAdditionalModels(String... additionalModelKeys) {
    for (String key : additionalModelKeys) {
      ProcessDefinition pd = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult();
      if (pd != null) {
        addBpmn(key);
      } else {
        CaseDefinition cd = engine.getRepositoryService().createCaseDefinitionQuery().caseDefinitionKey(key).latestVersion().singleResult();
        if (cd != null) {
          addCmmn(key);
        } else {
          // DecisionDefinition dd =
          // engine.getRepositoryService().createDecisionDefinitionQuery().decisionDefinitionKey(key).latestVersion().singleResult();
          // if (dd != null) {
          // addDmn(key);
          // } else {
          // ignore for now
          // }
        }
      }
    }

  }

  public void addBpmn(String processDefinitionKey) {
    tweakProcessDefinition(processDefinitionKey);
  }

  public void addCmmn(String caseDefinitionKey) {
    tweakCaseDefinition(caseDefinitionKey);
  }

  // public void addDmn(String decisionDefinitionKey) {
  // tweakDecisionDefinition(decisionDefinitionKey);
  // }

  public void deployTweakedModels() {
    LOG.info("Starting deployment of tweaked models for demo data generation");
    try {
      DeploymentBuilder deploymentBuilder = engine.getRepositoryService().createDeployment();
      for (Entry<String, String> model : tweakedModels.entrySet()) {
        deploymentBuilder.addInputStream(model.getKey(), new ByteArrayInputStream(model.getValue().getBytes("UTF-8")));
      }
      Deployment deployment = deploymentBuilder.deploy();
      if (processApplicationReference != null) {
        engine.getManagementService().registerProcessApplication(deployment.getId(), processApplicationReference);
      }
      LOG.info("Deployed tweaked modes for demo data generation with deployment " + deployment.getId());
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
      if (processApplicationReference != null) {
        engine.getManagementService().registerProcessApplication(deployment.getId(), processApplicationReference);
      }
      LOG.info("Restored original modes after demo data generation with deployment " + deployment.getId());
    } catch (Exception ex) {
      throw new RuntimeException("Could not restore original models", ex);
    }
  }

  protected String tweakProcessDefinition(String processDefinitionKey) {
    LOG.info("tweak process definition " + processDefinitionKey);

    ProcessDefinition processDefinition = engine.getRepositoryService().createProcessDefinitionQuery() //
        .processDefinitionKey(processDefinitionKey) //
        .latestVersion() //
        .singleResult();
    if (processDefinition == null) {
      throw new RuntimeException("Process with key '" + processDefinitionKey + "' not found.");
    }
    // store original process application reference
    if (processApplicationReference == null) {
      processApplicationReference = engine.getProcessEngineConfiguration().getProcessApplicationManager()
          .getProcessApplicationForDeployment(processDefinition.getDeploymentId());
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
    //Collection<ModelElementInstance> receiveTasks = bpmn.getModelElementsByType(bpmn.getModel().getType(ReceiveTask.class));
    Collection<ModelElementInstance> businessRuleTasks = bpmn.getModelElementsByType(bpmn.getModel().getType(BusinessRuleTask.class));
    //Collection<ModelElementInstance> scriptTasks = bpmn.getModelElementsByType(bpmn.getModel().getType(ScriptTask.class));
    Collection<ModelElementInstance> userTasks = bpmn.getModelElementsByType(bpmn.getModel().getType(UserTask.class));
    Collection<ModelElementInstance> executionListeners = bpmn.getModelElementsByType(bpmn.getModel().getType(CamundaExecutionListener.class));
    //Collection<ModelElementInstance> taskListeners = bpmn.getModelElementsByType(bpmn.getModel().getType(CamundaTaskListener.class));
    Collection<ModelElementInstance> xorGateways = bpmn.getModelElementsByType(bpmn.getModel().getType(ExclusiveGateway.class));
    //Collection<ModelElementInstance> orGateways = bpmn.getModelElementsByType(bpmn.getModel().getType(InclusiveGateway.class));

    Collection<ModelElementInstance> scripts = bpmn.getModelElementsByType(bpmn.getModel().getType(CamundaScript.class));

    for (ModelElementInstance modelElementInstance : serviceTasks) {
      ServiceTask serviceTask = ((ServiceTask) modelElementInstance);
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
      serviceTask.setCamundaClass(null);
      serviceTask.removeAttributeNs("http://activiti.org/bpmn", "delegateExpression");
      serviceTask.removeAttributeNs("http://camunda.org/schema/1.0/bpmn", "delegateExpression");
      serviceTask.setCamundaExpression("#{true}"); // Noop
    }
    for (ModelElementInstance modelElementInstance : businessRuleTasks) {
      BusinessRuleTask businessRuleTask = (BusinessRuleTask) modelElementInstance;
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
      // executionListener.setCamundaClass(null);
      executionListener.removeAttributeNs("http://activiti.org/bpmn", "class");
      executionListener.removeAttributeNs("http://camunda.org/schema/1.0/bpmn", "class");

      executionListener.removeAttributeNs("http://activiti.org/bpmn", "delegateExpression");
      executionListener.removeAttributeNs("http://camunda.org/schema/1.0/bpmn", "delegateExpression");
      executionListener.setCamundaExpression("#{true}"); // Noop
    }
    for (ModelElementInstance modelElementInstance : scripts) {
      CamundaScript script = (CamundaScript) modelElementInstance;
      // executionListener.setCamundaClass(null);
      script.setTextContent(""); // java.lang.System.out.println('x');
      script.setCamundaScriptFormat("javascript");
      script.removeAttributeNs("http://activiti.org/bpmn", "resource");
      script.removeAttributeNs("http://camunda.org/schema/1.0/bpmn", "resource");
    }

    for (ModelElementInstance modelElementInstance : userTasks) {
      UserTask userTask = ((UserTask) modelElementInstance);
      userTask.setCamundaAssignee(null);
      userTask.setCamundaCandidateGroups(null);
    }

    for (ModelElementInstance modelElementInstance : xorGateways) {
      ExclusiveGateway xorGateway = ((ExclusiveGateway) modelElementInstance);
      tweakGateway(xorGateway);
    }

    // Bpmn.validateModel(bpmn);
    String xmlString = Bpmn.convertToString(bpmn);
    tweakedModels.put(processDefinitionKey + ".bpmn", xmlString);
    LOG.debug("-----TWEAKED-----\n-----TWEAKED-----\n-----\n" + xmlString + "\n------");
    return xmlString;
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
        String camundaProperty = readCamundaProperty(sequenceFlow, "probability");
        double probability = 1; // default
        if (camundaProperty != null) {
          probability = Double.valueOf(camundaProperty);
        }

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

  public String tweakCaseDefinition(String caseDefinitionKey) {
    LOG.info("tweak case definition " + caseDefinitionKey);

    CaseDefinition caseDefinition = engine.getRepositoryService().createCaseDefinitionQuery() //
        .caseDefinitionKey(caseDefinitionKey) //
        .latestVersion() //
        .singleResult();
    if (caseDefinition == null) {
      throw new RuntimeException("Case with key '" + caseDefinitionKey + "' not found.");
    }
    // store original process application reference
    if (processApplicationReference == null) {
      processApplicationReference = engine.getProcessEngineConfiguration().getProcessApplicationManager()
          .getProcessApplicationForDeployment(caseDefinition.getDeploymentId());
    }

    CmmnModelInstance cmmn = engine.getRepositoryService().getCmmnModelInstance(caseDefinition.getId());

    String originalCmmn = IoUtil.convertXmlDocumentToString(cmmn.getDocument());
    // do not do a validation here as it caused quite strange trouble
    LOG.debug("-----\n" + originalCmmn + "\n------");
    originalModels.put(caseDefinitionKey + ".cmmn", originalCmmn);

    CasePlanModel casePlanModel = (CasePlanModel) cmmn.getModelElementsByType(cmmn.getModel().getType(CasePlanModel.class)).iterator().next();

    Collection<ModelElementInstance> sentries = cmmn.getModelElementsByType(cmmn.getModel().getType(Sentry.class));
    Collection<ModelElementInstance> businessRuleTasks = cmmn.getModelElementsByType(cmmn.getModel().getType(DecisionTask.class));
    Collection<ModelElementInstance> userTasks = cmmn.getModelElementsByType(cmmn.getModel().getType(HumanTask.class));

    for (ModelElementInstance modelElementInstance : sentries) {
      Sentry sentry = ((Sentry) modelElementInstance);
      if (sentry.getIfPart() != null && sentry.getIfPart().getCondition() != null) {
        tweakSentry(casePlanModel, sentry);
      }
    }
    // for (ModelElementInstance modelElementInstance : businessRuleTasks) {
    // DecisionTask businessRuleTask = (DecisionTask) modelElementInstance;
    // businessRuleTask.removeAttributeNs("http://activiti.org/bpmn",
    // "decisionRef");
    // businessRuleTask.removeAttributeNs("http://camunda.org/schema/1.0/bpmn",
    // "decisionRef");
    // businessRuleTask.removeAttributeNs("http://activiti.org/bpmn",
    // "delegateExpression");
    // businessRuleTask.removeAttributeNs("http://camunda.org/schema/1.0/bpmn",
    // "delegateExpression");
    // businessRuleTask.setDecisionExpression("#{true}"); // Noop
    // }
    // for (ModelElementInstance modelElementInstance : executionListeners) {
    // CamundaExecutionListener executionListener = (CamundaExecutionListener)
    // modelElementInstance;
    // // executionListener.setCamundaClass(null);
    // executionListener.removeAttributeNs("http://activiti.org/bpmn", "class");
    // executionListener.removeAttributeNs("http://camunda.org/schema/1.0/bpmn",
    // "class");
    //
    // executionListener.removeAttributeNs("http://activiti.org/bpmn",
    // "delegateExpression");
    // executionListener.removeAttributeNs("http://camunda.org/schema/1.0/bpmn",
    // "delegateExpression");
    // executionListener.setCamundaExpression("#{true}"); // Noop
    // }

    for (ModelElementInstance modelElementInstance : userTasks) {
      HumanTask userTask = ((HumanTask) modelElementInstance);
      userTask.setCamundaAssignee(null);
      userTask.setCamundaCandidateGroups(null);
    }

    // Bpmn.validateModel(bpmn);
    String xmlString = Cmmn.convertToString(cmmn);
    tweakedModels.put(caseDefinitionKey + ".cmmn", xmlString);
    return xmlString;
  }

  protected void tweakSentry(CasePlanModel casePlanModel, Sentry sentry) {
    CmmnModelInstance cmmn = (CmmnModelInstance) sentry.getModelInstance();

    String var = "SIM_SAMPLE_VALUE_" + sentry.getId().replaceAll("-", "_");

    // let's toggle with a 50/50 for the beginning

    org.camunda.bpm.model.cmmn.instance.ConditionExpression conditionExpression = cmmn
        .newInstance(org.camunda.bpm.model.cmmn.instance.ConditionExpression.class);
    conditionExpression.setTextContent("#{" + var + " >= 1 }");
    sentry.getIfPart().setCondition(conditionExpression);

    // add execution listener to set variable based on random
    CamundaCaseExecutionListener executionListener = cmmn.newInstance(CamundaCaseExecutionListener.class);
    executionListener.setCamundaEvent("create");
    org.camunda.bpm.model.cmmn.instance.camunda.CamundaScript script = cmmn.newInstance(org.camunda.bpm.model.cmmn.instance.camunda.CamundaScript.class);
    script.setTextContent(//
        "sample = com.camunda.demo.environment.simulation.StatisticsHelper.nextSample(" + 2 + ");\n" + "caseExecution.setVariable('" + var + "', sample);");
    script.setCamundaScriptFormat("Javascript");
    executionListener.setCamundaScript(script);

    // CmmnElementImpl parentElement = (CmmnElementImpl)
    // sentry.getParentElement();

    if (casePlanModel.getExtensionElements() == null) {
      ExtensionElements extensionElements = cmmn.newInstance(ExtensionElements.class);
      casePlanModel.addChildElement(extensionElements);
    }
    casePlanModel.getExtensionElements().addChildElement(executionListener);

  }

  public String tweakDecisionDefinition(String decisionDefinitionKey) {
    LOG.info("tweak decision definition " + decisionDefinitionKey);

    DecisionDefinition decisionDefinition = engine.getRepositoryService().createDecisionDefinitionQuery() //
        .decisionDefinitionKey(decisionDefinitionKey) //
        .latestVersion() //
        .singleResult();
    if (decisionDefinition == null) {
      throw new RuntimeException("Decision with key '" + decisionDefinitionKey + "' not found.");
    }

    InputStream decisionModel = engine.getRepositoryService().getDecisionModel(decisionDefinition.getId());

    try {
      String originalDmn = IoUtil.getStringFromInputStream(decisionModel);
      // do not do a validation here as it caused quite strange trouble
      LOG.debug("-----\n" + originalDmn + "\n------");
      originalModels.put(decisionDefinitionKey + ".dmn", originalDmn);

      return originalDmn;
    } catch (IOException e) {
      throw new RuntimeException("Cannot read decision definition: " + e.getMessage(), e);
    }
  }

  public static String readCamundaProperty(BaseElement modelElementInstance, String propertyName) {
    if (modelElementInstance.getExtensionElements() == null) {
      return null;
    }
    Collection<CamundaProperty> properties = modelElementInstance.getExtensionElements().getElementsQuery() //
        .filterByType(CamundaProperties.class) //
        .singleResult() //
        .getCamundaProperties();
    for (CamundaProperty property : properties) {
      // in 7.1 one has to use: property.getAttributeValue("name")
      if (propertyName.equals(property.getCamundaName())) {
        return property.getCamundaValue();
      }
    }
    return null;
  }

}
