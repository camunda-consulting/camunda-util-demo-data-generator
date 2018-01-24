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
    if (processDefinition == null) {
      throw new RuntimeException("Could not find process definition with key '" + processDefinitionKey + "'");
    }
    autoGenerateFor(engine, processDefinition, processApplicationReference, additionalModelKeys);
  }

  public static void autoGenerateFor(ProcessEngine engine, ProcessDefinition processDefinition, ProcessApplicationReference processApplicationReference,
      String... additionalModelKeys) {
    log.info("check auto generation for " + processDefinition);

    BpmnModelInstance modelInstance = engine.getRepositoryService().getBpmnModelInstance(processDefinition.getId());

    String numberOfDaysInPast = findProperty(modelInstance, "simulateNumberOfDaysInPast").orElse("30");
    String timeBetweenStartsBusinessDaysMean = findProperty(modelInstance, "simulateTimeBetweenStartsBusinessDaysMean").orElse("3600");
    String timeBetweenStartsBusinessDaysSd = findProperty(modelInstance, "simulateTimeBetweenStartsBusinessDaysSd").orElse("0");
    String startBusinessDayAt = findProperty(modelInstance, "simulateStartBusinessDayAt").orElse("8:00");
    String endBusinessDayAt = findProperty(modelInstance, "simulateEndBusinessDayAt").orElse("18:00");
    String includeWeekend = findProperty(modelInstance, "simulateIncludeWeekend").orElse("false");
    boolean runAlways = findProperty(modelInstance, "simulateRunAlways").orElse("false").toLowerCase().equals("true");

    log.info("simulation properties set - auto generation applied (" + numberOfDaysInPast + " days in past, time between mean: "
        + timeBetweenStartsBusinessDaysMean + " and Standard Deviation: " + timeBetweenStartsBusinessDaysSd);

    new TimeAwareDemoGenerator(engine, processApplicationReference) //
        .processDefinitionKey(processDefinition.getKey()) //
        .additionalModelKeys(additionalModelKeys) //
        .numberOfDaysInPast(Integer.valueOf(numberOfDaysInPast)) //
        .timeBetweenStartsBusinessDays(timeBetweenStartsBusinessDaysMean, timeBetweenStartsBusinessDaysSd) //
        .startTimeBusinessDay(startBusinessDayAt) //
        .endTimeBusinessDay(endBusinessDayAt) //
        .includeWeekend(includeWeekend.toLowerCase().equals("true")) //
        .runAlways(runAlways) //
        .run();

  }

  public static Optional<String> findProperty(BpmnModelInstance modelInstance, String propertyName) {
    Collection<CamundaProperties> propertiesList = modelInstance.getModelElementsByType(CamundaProperties.class);
    for (CamundaProperties properties : propertiesList) {
      Collection<CamundaProperty> propertyCollection = properties.getCamundaProperties();
      for (CamundaProperty camundaProperty : propertyCollection) {
        if (propertyName.equals(camundaProperty.getCamundaName())) {
          return Optional.ofNullable(camundaProperty.getCamundaValue());
        }
      }
    }
    return Optional.empty();
  }
}
