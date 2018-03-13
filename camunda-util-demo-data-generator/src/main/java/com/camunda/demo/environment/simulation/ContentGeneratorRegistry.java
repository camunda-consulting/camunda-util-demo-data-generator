package com.camunda.demo.environment.simulation;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.el.ExpressionManager;
import org.camunda.bpm.engine.impl.javax.el.FunctionMapper;
import org.camunda.bpm.model.bpmn.Bpmn;
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
  private static final String WRAPPER_CLASS_NAME_PREFIX = "cam.camunda.demo.environment.simulation.StaticGeneratorWrapper";
  
  private static long wrapperClassSuffix = 0l;
  private static Class<?> wrapperClassCache = null;
  static Map<BaseElement, ContentGenerator> contentGeneratorRegistry = new HashMap<>();
  static ExpressionManager expressionManagerCache = null;

  
  public static void clear() {
    contentGeneratorRegistry.clear();
    wrapperClassCache = null;
    wrapperClassSuffix++;
    expressionManagerCache = null;
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

  public static void setGenerator(Class<?> wrapperClass, ContentGenerator contentGenerator) {
    try {
      wrapperClass.getField("generator").set(null, contentGenerator);
    } catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
      VariableSetterDelegate.LOG.debug("Could not set content generator on wrapper class", e);
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
  public static Class<?> getWrapperClass(Class<?> generatorClass) {
    if (wrapperClassCache == null) {

      // check if we already built the class
      ClassLoader classLoader = generatorClass.getClassLoader();
      try {
        Class<?> class1 = classLoader.loadClass(WRAPPER_CLASS_NAME_PREFIX + wrapperClassSuffix);
        wrapperClassCache = class1;

      } catch (ClassNotFoundException e1) {
        // we did not, so let's go

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
        CtClass wrapperCtClass = classPool.makeClass(WRAPPER_CLASS_NAME_PREFIX + wrapperClassSuffix);

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
          VariableSetterDelegate.LOG.debug("Could not create wrapper class", e);
          throw new RuntimeException("Could not create wrapper class", e);
        }
      }
    }

    return wrapperClassCache;
  }
  
  public static ExpressionManager getExpressionManager(ProcessEngineConfiguration processEngineConfiguration, Class<?> wrapperClass) {
    if (expressionManagerCache == null) {
      ExpressionManager expressionManager = ((ProcessEngineConfigurationImpl) processEngineConfiguration).getExpressionManager();
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

}
