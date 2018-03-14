package com.camunda.demo.environment.simulation;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.delegate.VariableScope;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.delegate.ExpressionGetInvocation;
import org.camunda.bpm.engine.impl.el.ExpressionManager;
import org.camunda.bpm.engine.impl.javax.el.ELContext;
import org.camunda.bpm.engine.impl.javax.el.FunctionMapper;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import javassist.CannotCompileException;
import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;

public final class ContentGeneratorRegistry {
  private static final String WRAPPER_CLASS_NAME_SUFFIX = "_StaticWrapper";

  /* from generator class to static wrapper class */
  private static Map<Class<?>, Class<?>> wrapperClassCache = new HashMap<>();
  static Map<BaseElement, ContentGenerator> contentGeneratorRegistry = new HashMap<>();

  static ExpressionManager cachedExpressionManager = null;

  static Class<?> currentEvaluationWrapperClass = null;

  public static Object evaluateJuelWithGenerator(String expression, ContentGenerator contentGenerator) {
    return evaluateJuelWithGenerator(expression, contentGenerator, null);
  }

  synchronized public static Object evaluateJuelWithGenerator(String expression, ContentGenerator contentGenerator, VariableScope scope) {
    currentEvaluationWrapperClass = getWrapperClass(contentGenerator);

    try {
      if (cachedExpressionManager == null) {
        cachedExpressionManager = getExpressionManager(Context.getProcessEngineConfiguration());
      }
      ELContext elContext = cachedExpressionManager.getElContext(scope == null ? new ExecutionEntity() : scope);
      ExpressionGetInvocation invocation = new ExpressionGetInvocation(cachedExpressionManager.createValueExpression(expression), elContext, null);
      invocation.proceed();
      return invocation.getInvocationResult();
    } catch (Exception e) {
      throw new RuntimeException("Could not evaluate JUEL expression '" + expression + "'", e);
    } finally {
      currentEvaluationWrapperClass = null;
    }
  }

  /**
   * Can be used multiple times to reset cache.
   * 
   * @param engine
   *          used for expression manager
   */
  public static void init(ProcessEngine engine) {
    contentGeneratorRegistry.clear();
    if (cachedExpressionManager == null) {
      cachedExpressionManager = getExpressionManager((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration());
    }
  }

  private static ExpressionManager getExpressionManager(ProcessEngineConfigurationImpl processEngineConfigurationImpl) {
    ExpressionManager em = processEngineConfigurationImpl.getExpressionManager();
    em.addFunctionMapper(new FunctionMapper() {

      @Override
      public Method resolveFunction(String prefix, String localName) {
        if (currentEvaluationWrapperClass == null || !"g".equals(prefix))
          return null;

        return Arrays.stream(currentEvaluationWrapperClass.getMethods()).filter(m -> localName.equals(m.getName())).findFirst().orElse(null);
      }
    });
    return em;
  }

  /**
   * Each activity gets its own cached singleton generator instance.
   * 
   * @param element
   *          each bpmn-element gets its own generator instance
   * @return a newly created or the cached content generator
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
          VariableSetterDelegate.LOG.error("Unable to instantiate object of class {}", simulateContentGenerator);
          throw new RuntimeException(e);
        }
      } else {
        contentGenerator = new DefaultContentGenerator();
      }
      contentGeneratorRegistry.put(element, contentGenerator);
    }
    return contentGenerator;
  }

  /**
   * Returns a class that contains a static method for every method of generator
   * class. These static methods call the appropriate generator method on the
   * generator object set as static field "generator" of the returned class.
   * 
   * @param generator
   *          the generator to build the wrapper for
   * @return a static wrapper class that refers to the given generator
   */
  public static Class<?> getWrapperClass(ContentGenerator generator) {
    Class<?> generatorClass = generator.getClass();
    Class<?> cached = wrapperClassCache.get(generatorClass);
    if (cached == null) {

      // check if we already built the class
      ClassLoader classLoader = generatorClass.getClassLoader();
      String wrapperClassName = generatorClass.getName() + WRAPPER_CLASS_NAME_SUFFIX;
      try {
        cached = classLoader.loadClass(wrapperClassName);
      } catch (ClassNotFoundException e1) {
        // we did not, so let's go
        ClassPool classPool = ClassPool.getDefault();
        classPool.insertClassPath(new ClassClassPath(generatorClass));
        CtClass wrapperCtClass = classPool.makeClass(wrapperClassName);

        Method[] methods = Arrays.stream(generatorClass.getMethods()) //
            .filter(m -> !"equals".equals(m.getName())) //
            .filter(m -> !"getClass".equals(m.getName())) //
            .filter(m -> !"hashCode".equals(m.getName())) //
            .filter(m -> !"notify".equals(m.getName())) //
            .filter(m -> !"notifyAll".equals(m.getName())) //
            .filter(m -> !"toString".equals(m.getName())) //
            .filter(m -> !"wait".equals(m.getName())) //
            .toArray(Method[]::new);

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

            // actual wrapper method, simply invokes the method stored in method
            // field on current generator
            CtClass[] parameters = classPool.get(Arrays.stream(m.getParameterTypes()).map(Class::getName).toArray(String[]::new));
            CtMethod method = new CtMethod(classPool.get(m.getReturnType().getName()), m.getName(), parameters, wrapperCtClass);
            method.setModifiers(Modifier.PUBLIC | Modifier.STATIC);
            method.setBody("{ return (" + m.getReturnType().getName() + ") " + fieldName + ".invoke(generator, $args); }");
            method.addCatch("throw new RuntimeException(\"Error calling content generator method: \" + $e.getMessage(), $e);",
                classPool.get(Exception.class.getName()));
            wrapperCtClass.addMethod(method);
          }

          cached = wrapperCtClass.toClass();

          // fill static fields that hold the method objects
          for (Method m : methods) {
            cached.getField(m.getName() + "_method_" + m.getParameterCount()).set(null, m);
          }
        } catch (NotFoundException | CannotCompileException | IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
          VariableSetterDelegate.LOG.debug("Could not create wrapper class", e);
          throw new RuntimeException("Could not create wrapper class", e);
        }
      }

      wrapperClassCache.put(generatorClass, cached);
    }

    // set current generator
    try {
      cached.getField("generator").set(null, generator);
    } catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
      VariableSetterDelegate.LOG.debug("Could not set content generator on wrapper class", e);
      throw new RuntimeException("Could not set content generator on wrapper class", e);
    }

    return cached;
  }
}
