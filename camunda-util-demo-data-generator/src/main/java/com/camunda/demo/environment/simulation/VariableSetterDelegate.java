package com.camunda.demo.environment.simulation;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.el.CommandContextFunctionMapper;
import org.camunda.bpm.engine.impl.el.DateTimeFunctionMapper;
import org.camunda.bpm.engine.impl.el.ExpressionManager;
import org.camunda.bpm.engine.impl.javax.el.FunctionMapper;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javassist.CannotCompileException;
import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;

public class VariableSetterDelegate implements ExecutionListener {
  private static Logger LOG = LoggerFactory.getLogger(VariableSetterDelegate.class);

  /*
   * Caches
   */
  private static Map<BaseElement, Work[]> setVariablePropertyCache = new HashMap<>();
  private static Map<BaseElement, ContentGenerator> contentGeneratorRegistry = new HashMap<>();
  private static Class<?> wrapperClassCache = null;
  private static ExpressionManager expressionManagerCache = null;

  /**
   * This thing is completely synchronized, because we rely on some static
   * fields set for each execution. If used during executing
   * TimeAwareDemoGenerator, this does not harm since it simulates strictly
   * single-threaded.
   */
  @Override
  public void notify(DelegateExecution execution) throws Exception {
    synchronized (this) {
      BaseElement element = execution.getBpmnModelElementInstance();

      // prepare data for this call in content generator
      ContentGenerator contentGenerator = getContentGenerator(element);
      contentGenerator.incActivityVisitCount();
      contentGenerator.setCurrentSimulationTime(ClockUtil.getCurrentTime());

      Optional<TimeAwareDemoGenerator> generatorO = Optional.ofNullable(TimeAwareDemoGenerator.getRunningInstance());
      contentGenerator.setFirstStartTime(generatorO.map(TimeAwareDemoGenerator::getFirstStartTime).orElse(null));
      contentGenerator.setNextStartTime(generatorO.map(TimeAwareDemoGenerator::getNextStartTime).orElse(null));
      contentGenerator.setPreviousStartTime(generatorO.map(TimeAwareDemoGenerator::getPreviousStartTime).orElse(null));
      contentGenerator.setStopTime(generatorO.map(TimeAwareDemoGenerator::getStopTime).orElse(null));
      Date firstStartTime = generatorO.get().getFirstStartTime();
      Date previousStartTime = generatorO.get().getPreviousStartTime();
      Date stopTime = generatorO.get().getStopTime();
      if (generatorO.isPresent() && firstStartTime != null && previousStartTime != null && stopTime != null) {
        contentGenerator.setPercentDone((double) previousStartTime.getTime() / (stopTime.getTime() - firstStartTime.getTime()));
      } else {
        contentGenerator.setPercentDone(null);
      }

      Class<?> wrapperClass = getWrapperClass(contentGenerator.getClass());
      setGenerator(wrapperClass, contentGenerator);
      ExpressionManager expressionManager = getExpressionManager(wrapperClass);

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

  protected ExpressionManager getExpressionManager(Class<?> wrapperClass) {
    if (expressionManagerCache == null) {
      ExpressionManager expressionManager = Context.getProcessEngineConfiguration().getExpressionManager();
      expressionManager.addFunctionMapper(new FunctionMapper() {

        @Override
        public Method resolveFunction(String prefix, String localName) {
          if (!"g".equals(prefix))
            return null;

          return Arrays.stream(wrapperClass.getMethods()).filter(m -> localName.equals(m.getName())).findFirst().orElse(null);
        }
      });
      expressionManagerCache = expressionManager;
    }
    return expressionManagerCache;
  }

  protected void setGenerator(Class<?> wrapperClass, ContentGenerator contentGenerator) {
    try {
      wrapperClass.getField("generator").set(null, contentGenerator);
    } catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
      LOG.debug("Could not set content generator on wrapper class", e);
      throw new RuntimeException("Could not set content generator on wrapper class", e);
    }
  }

  /**
   * Returns a class that contains a static method for every method of generator
   * class. These static methods call the appropriate generator method on the
   * generator object set as static field "generator" of the returned class.
   * 
   * @return
   */
  protected static Class<?> getWrapperClass(Class<?> generatorClass) {
    if (wrapperClassCache == null) {

      Method[] methods = Arrays.stream(generatorClass.getMethods()) //
          .filter(m -> !"equals".equals(m.getName())) //
          .filter(m -> !"getClass".equals(m.getName())) //
          .filter(m -> !"hashCode".equals(m.getName())) //
          .filter(m -> !"notify".equals(m.getName())) //
          .filter(m -> !"notifyAll".equals(m.getName())) //
          .filter(m -> !"toString".equals(m.getName())) //
          .filter(m -> !"wait".equals(m.getName())) //
          .toArray(Method[]::new);

      ClassPool classPool = ClassPool.getDefault();
      classPool.insertClassPath(new ClassClassPath(generatorClass));
      CtClass wrapperCtClass = classPool.makeClass("cam.camunda.demo.environment.simulation.StaticGeneratorWrapper");

      try {
        CtField generatorField = new CtField(classPool.get(DefaultContentGenerator.class.getName()), "generator", wrapperCtClass);
        generatorField.setModifiers(Modifier.PUBLIC | Modifier.STATIC);
        wrapperCtClass.addField(generatorField);

        for (Method m : methods) {
          // field for holding method object
          String fieldName = m.getName() + "_method_" + m.getParameterCount();
          CtField methodField = new CtField(classPool.get(Method.class.getName()), fieldName, wrapperCtClass);
          methodField.setModifiers(Modifier.PUBLIC | Modifier.STATIC);
          wrapperCtClass.addField(methodField);

          CtClass[] parameters = classPool.get(Arrays.stream(m.getParameterTypes()).map(Class::getName).toArray(String[]::new));

          CtMethod method = new CtMethod(classPool.get(m.getReturnType().getName()), m.getName(), parameters, wrapperCtClass);
          method.setModifiers(Modifier.PUBLIC | Modifier.STATIC);
          method.setBody("{ return (" + m.getReturnType().getName() + ") " + fieldName + ".invoke(generator, $args); }");
          method.addCatch("throw new RuntimeException(\"Error calling content generator method\", $e);", classPool.get(Exception.class.getName()));
          wrapperCtClass.addMethod(method);

        }

        Class<?> wrapperClass = wrapperCtClass.toClass();

        for (Method m : methods) {
          wrapperClass.getField(m.getName() + "_method_" + m.getParameterCount()).set(null, m);
        }

        wrapperClassCache = wrapperClass;
      } catch (NotFoundException | CannotCompileException | IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
        LOG.debug("Could not create wrapper class", e);
        throw new RuntimeException("Could not create wrapper class", e);
      }
    }

    return wrapperClassCache;
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

  /**
   * Each activity gets its own cached singleton generator instance.
   * 
   * @param element
   * @return
   */
  public static ContentGenerator getContentGenerator(BaseElement element) {
    ContentGenerator contentGenerator = contentGeneratorRegistry.get(element);
    if (contentGenerator == null) {
      // look for simulateContentGenerator upstairs in the model hierarchy
      String simulateContentGenerator = null;
      ModelElementInstance parent = element;
      while (simulateContentGenerator == null && parent != null) {
        if (parent instanceof BaseElement) {
          Optional<String> property = DemoModelInstrumentator.readCamundaProperty((BaseElement) parent, "simulateContentGenerator");
          if (property.isPresent()) {
            simulateContentGenerator = property.get();
          }
        }
        parent = parent.getParentElement();
      }

      if (simulateContentGenerator != null) {
        try {
          contentGenerator = (ContentGenerator) Class.forName(simulateContentGenerator).newInstance();
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
          LOG.error("Unable to instantiate object of class {}", simulateContentGenerator);
          throw new RuntimeException(e);
        }
      } else {
        contentGenerator = new DefaultContentGenerator();
      }
      contentGeneratorRegistry.put(element, contentGenerator);
    }
    return contentGenerator;
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
