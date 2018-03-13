package com.camunda.demo.environment.simulation;

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.el.ExpressionManager;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VariableSetterDelegate implements ExecutionListener {
  static Logger LOG = LoggerFactory.getLogger(VariableSetterDelegate.class);

  /*
   * Caches
   */
  private static Map<BaseElement, Work[]> setVariablePropertyCache = new HashMap<>();

  /**
   * This thing is completely synchronized, because we rely on some static
   * fields set for each execution. If used during executing
   * TimeAwareDemoGenerator, this does not harm since it simulates strictly
   * single-threaded.
   * 
   * WARNING: Parts of the implementation are not robust against engine restart
   * or multiple engines on same DB.
   */
  @Override
  public void notify(DelegateExecution execution) throws Exception {
    synchronized (this) {
      BaseElement element = execution.getBpmnModelElementInstance();

      // prepare data for this call in content generator
      ContentGenerator contentGenerator = ContentGeneratorRegistry.getContentGenerator(element);
      contentGenerator.incActivityVisitCount();
      contentGenerator.setCurrentSimulationTime(ClockUtil.getCurrentTime());
      contentGenerator.setBusinessKey(execution.getProcessBusinessKey());

      Optional<TimeAwareDemoGenerator> generatorO = Optional.ofNullable(TimeAwareDemoGenerator.getRunningInstance());
      contentGenerator.setFirstStartTime(generatorO.map(TimeAwareDemoGenerator::getFirstStartTime).orElse(null));
      contentGenerator.setNextStartTime(generatorO.map(TimeAwareDemoGenerator::getNextStartTime).orElse(null));
      contentGenerator.setPreviousStartTime(generatorO.map(TimeAwareDemoGenerator::getPreviousStartTime).orElse(null));
      contentGenerator.setStopTime(generatorO.map(TimeAwareDemoGenerator::getStopTime).orElse(null));
      Date firstStartTime = contentGenerator.firstStartTime();
      Date previousStartTime = contentGenerator.previousStartTime();
      Date stopTime = contentGenerator.stopTime();
      if (firstStartTime != null && previousStartTime != null && stopTime != null) {
        contentGenerator.setPercentDone((double) previousStartTime.getTime() / (stopTime.getTime() - firstStartTime.getTime()));
      } else {
        contentGenerator.setPercentDone(null);
      }

      Class<?> wrapperClass = ContentGeneratorRegistry.getWrapperClass(contentGenerator.getClass());
      ContentGeneratorRegistry.setGenerator(wrapperClass, contentGenerator);
      ExpressionManager expressionManager = ContentGeneratorRegistry.getExpressionManager(Context.getProcessEngineConfiguration(), wrapperClass);

      // set variables
      Work[] workSet = getSetVariableValuesOrdered(element);
      for (Work work : workSet) {

        LOG.debug("Setting variable with name evaluated from '{}' to value evaluated from '{}'", work.variableExpression, work.valueExpression);

        String variableName = ((String) expressionManager.createExpression(work.variableExpression).getValue(execution)).trim();
        Object value = expressionManager.createExpression(work.valueExpression).getValue(execution);

        LOG.debug("Setting variable '{}' to '{}'", variableName, value);

        execution.setVariable(variableName, value);
      }
    }
  }

  /**
   * Cached read of "simulateSetVariable"-extensions for the given element.
   * 
   * @param element
   * @return
   */
  public static Work[] getSetVariableValuesOrdered(BaseElement element) {
    Work[] values = setVariablePropertyCache.get(element);
    if (values == null) {
      String[] expressions = DemoModelInstrumentator.readCamundaPropertyMulti(element, "simulateSetVariable").toArray(new String[] {});
      values = new Work[expressions.length];

      DirectedAcyclicGraph<Work, Object> graph = new DirectedAcyclicGraph<>(Object.class);
      for (int i = 0; i < expressions.length; i++) {
        values[i] = new Work(expressions[i]);
        graph.addVertex(values[i]);
      }
      for (Work currentWork : values) {
        for (Work otherWork : values) {
          if (currentWork.valueExpression.matches(".*\\W" + Pattern.quote(otherWork.variableExpression) + "\\W.*")) {
            try {
              graph.addEdge(otherWork, currentWork);
            } catch (IllegalArgumentException e) {
              LOG.warn("Possible cycle in simulateSetVariable-dependencies detected when checking '{}'", currentWork.valueExpression);
            }
          }
        }
      }

      int i = 0;
      for (Iterator<Work> iterator = graph.iterator(); iterator.hasNext();) {
        Work next = iterator.next();
        values[i++] = next;
      }
      setVariablePropertyCache.put(element, values);
    }
    return values;
  }

  static class Work {
    String variableExpression;
    String valueExpression;

    public Work(String setVariableValue) {
      String[] split = setVariableValue.split("=", 2);

      if (split.length != 2) {
        throw new RuntimeException("Expression does not evaluate to proper simulateSetVariable command: " + setVariableValue);
      }

      variableExpression = split[0];
      valueExpression = split[1];
    }
  }
}
