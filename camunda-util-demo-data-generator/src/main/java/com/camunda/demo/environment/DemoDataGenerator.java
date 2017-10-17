package com.camunda.demo.environment;

import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import org.camunda.bpm.BpmPlatform;
import org.camunda.bpm.application.ProcessApplicationReference;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.repository.CaseDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;

import com.camunda.demo.environment.simulation.TimeAwareDemoGenerator;

public class DemoDataGenerator {

  public static final String VAR_NAME_GENERATED = "demo-data-generated";
  private static final Logger log = Logger.getLogger(DemoDataGenerator.class.getName());

  public static void autoGenerateAll(ProcessEngine engine, ProcessApplicationReference processApplicationReference, String... additionalModelKeys) {
    List<ProcessDefinition> processDefinitions = engine.getRepositoryService().createProcessDefinitionQuery().latestVersion().list();
    for (ProcessDefinition processDefinition : processDefinitions) {
      autoGenerateFor(engine, processDefinition, processApplicationReference, additionalModelKeys);
    }
  }

  public static void autoGenerateFor(ProcessEngine engine, String processDefinitionKey, ProcessApplicationReference processApplicationReference,
      String... additionalModelKeys) {
    ProcessDefinition processDefinition = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(processDefinitionKey)
        .latestVersion().singleResult();
    autoGenerateFor(engine, processDefinition, processApplicationReference, additionalModelKeys);
  }

  public static void autoGenerateFor(ProcessEngine engine, ProcessDefinition processDefinition, ProcessApplicationReference processApplicationReference,
      String... additionalModelKeys) {
    log.info("check auto generation for " + processDefinition);
    BpmnModelInstance modelInstance = engine.getRepositoryService().getBpmnModelInstance(processDefinition.getId());

    String numberOfDaysInPast = findProperty(modelInstance, "simulateNumberOfDaysInPast");
    if (numberOfDaysInPast != null) {
      autoGenerateFor(engine, processDefinition, Integer.parseInt(numberOfDaysInPast), processApplicationReference, additionalModelKeys);
    }
  }

  public static void autoGenerateFor(ProcessEngine engine, String processDefinitionKey, int numberOfDaysInThePast,
      ProcessApplicationReference processApplicationReference, String... additionalModelKeys) {
    ProcessDefinition processDefinition = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(processDefinitionKey)
        .latestVersion().singleResult();
    if (processDefinition == null) {
      throw new RuntimeException("Could not find process definition with key '" + processDefinitionKey + "'");
    }
    autoGenerateFor(engine, processDefinition, numberOfDaysInThePast, processApplicationReference, additionalModelKeys);
  }

  public static void autoGenerateFor(ProcessEngine engine, ProcessDefinition processDefinition, int numberOfDaysInPast,
      ProcessApplicationReference processApplicationReference, String... additionalModelKeys) {
    BpmnModelInstance modelInstance = engine.getRepositoryService().getBpmnModelInstance(processDefinition.getId());

    boolean runAlways = Optional.ofNullable(findProperty(modelInstance, "runAlways")).map(string -> string.toLowerCase().equals("true")).orElse(false);
    long count = engine.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(processDefinition.getKey())
        .variableValueEquals(VAR_NAME_GENERATED, true).count();
    if (runAlways || count == 0) {
      String timeBetweenStartsBusinessDaysMean = findProperty(modelInstance, "simulateTimeBetweenStartsBusinessDaysMean");
      if (timeBetweenStartsBusinessDaysMean == null) {
        timeBetweenStartsBusinessDaysMean = "3600"; // 1 hour
      }
      String timeBetweenStartsBusinessDaysSd = findProperty(modelInstance, "simulateTimeBetweenStartsBusinessDaysSd");
      if (timeBetweenStartsBusinessDaysSd == null) {
        timeBetweenStartsBusinessDaysSd = "7200"; // 2 hours to get a more
                                                  // flattered curve
      }

      log.info("simulation properties set - auto generation applied (" + numberOfDaysInPast + " days in past, time between mean: "
          + timeBetweenStartsBusinessDaysMean + " and Standard Deviation: " + timeBetweenStartsBusinessDaysSd);

      TimeAwareDemoGenerator generator = new TimeAwareDemoGenerator(engine, processApplicationReference) //
          .processDefinitionKey(processDefinition.getKey()) //
          .additionalModelKeys(additionalModelKeys) //
          .numberOfDaysInPast(Integer.valueOf(numberOfDaysInPast)) //
          .timeBetweenStartsBusinessDays(Integer.valueOf(timeBetweenStartsBusinessDaysMean), Integer.valueOf(timeBetweenStartsBusinessDaysSd));

      generator.generateData();
    } else {
      log.info("generation ignored as more than 10 process instances exist");
    }

  }

  public static String findProperty(BpmnModelInstance modelInstance, String propertyName) {
    Collection<CamundaProperties> propertiesList = modelInstance.getModelElementsByType(CamundaProperties.class);
    for (CamundaProperties properties : propertiesList) {
      Collection<CamundaProperty> propertyCollection = properties.getCamundaProperties();
      for (CamundaProperty camundaProperty : propertyCollection) {
        if (propertyName.equals(camundaProperty.getCamundaName())) {
          return camundaProperty.getCamundaValue();
        }
      }
    }
    return null;
  }
}
