package com.camunda.demo.environment.simulation;

import java.awt.PageAttributes.MediaType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.text.DateFormat;
import java.text.ParseException;
import java.time.Instant;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import javax.mail.internet.ContentType;

import org.assertj.core.util.Arrays;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.value.FileValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultContentGenerator extends ContentGenerator {
  private static Logger LOG = LoggerFactory.getLogger(DefaultContentGenerator.class);

  public DefaultContentGenerator() {
    super();
  }

  /**
   * Treats null as false. Treats numbers to be true iff > 0. Treats strings to
   * be true if their lower-case version equals "1", "true" or "yes". Anything
   * unknown is treated as false.
   * 
   * @param o
   * @return
   */
  public Boolean toBoolean(Object o) {
    if (o == null)
      return false;
    if (o instanceof Boolean)
      return (Boolean) o;
    if (o instanceof Number)
      return ((Number) o).doubleValue() > 0;
    if (o instanceof String)
      return ((String) o).toLowerCase().equals("true") || o.equals("1") || ((String) o).toLowerCase().equals("yes");
    return false;
  }

  /**
   * Handles null, Date, Calendar and String (by DateFormat.parse). Anything
   * else will result in null.
   * 
   * @param o
   * @return
   */
  public Date toDate(Object o) {
    if (o == null)
      return null;
    if (o instanceof Date)
      return (Date) o;
    if (o instanceof Calendar)
      return ((Calendar) o).getTime();
    if (o instanceof String)
      try {
        return DateFormat.getDateTimeInstance().parse((String) o);
      } catch (ParseException e) {
        throw new RuntimeException(e);
      }
    return null;
  }

  public Double toDouble(Object o) {
    if (o == null)
      return null;
    if (o instanceof Number)
      return ((Number) o).doubleValue();
    return Double.parseDouble(o.toString());
  }

  public Integer toInt(Object o) {
    if (o == null)
      return null;
    if (o instanceof Number)
      return ((Number) o).intValue();
    return Integer.parseInt(o.toString());
  }

  public Long toLong(Object o) {
    if (o == null)
      return null;
    if (o instanceof Number)
      return ((Number) o).longValue();
    return Long.parseLong(o.toString());
  }

  public Short toShort(Object o) {
    if (o == null)
      return null;
    if (o instanceof Number)
      return ((Number) o).shortValue();
    return Short.parseShort(o.toString());
  }

  public String toString(Object o) {
    if (o == null)
      return null;
    return o.toString();
  }

  String[] firstnamesFemale = null;
  String[] firstnamesMale = null;

  public String firstnameFemale() {
    if (firstnamesFemale == null) {
      try (BufferedReader buffer = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/firstnames-female.txt")))) {
        firstnamesFemale = buffer.lines().toArray(String[]::new);
      } catch (IOException e) {
        LOG.error("Could not load female first names");
        firstnamesFemale = new String[] { "Jane" };
      }
    }
    return firstnamesFemale[(int) (Math.random() * firstnamesFemale.length)];
  }

  public String firstnameMale() {
    if (firstnamesMale == null) {
      try (BufferedReader buffer = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/firstnames-male.txt")))) {
        firstnamesMale = buffer.lines().toArray(String[]::new);
      } catch (IOException e) {
        LOG.error("Could not load male first names");
        firstnamesMale = new String[] { "John" };
      }
    }
    return firstnamesMale[(int) (Math.random() * firstnamesMale.length)];
  }

  /**
   * Female/male 50:50
   * 
   * @return
   */
  public String firstname() {
    return Math.random() < 0.5 ? firstnameFemale() : firstnameMale();
  }

  String[] surnamesGerman = null;
  String[] surnamesEnglish = null;

  public String surnameGerman() {
    if (surnamesGerman == null) {
      try (BufferedReader buffer = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/surnames-de.txt")))) {
        surnamesGerman = buffer.lines().toArray(String[]::new);
      } catch (IOException e) {
        LOG.error("Could not load german surnames");
        surnamesGerman = new String[] { "Mustermann" };
      }
    }
    return surnamesGerman[(int) (Math.random() * surnamesGerman.length)];
  }

  public String surnameEnglish() {
    if (surnamesEnglish == null) {
      try (BufferedReader buffer = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/surnames-en.txt")))) {
        surnamesEnglish = buffer.lines().toArray(String[]::new);
      } catch (IOException e) {
        LOG.error("Could not load english surnames");
        surnamesEnglish = new String[] { "Doe" };
      }
    }
    return surnamesEnglish[(int) (Math.random() * surnamesEnglish.length)];
  }

  /**
   * Evenly distributed birth date, minAge and maxAge in respect to current
   * simulation time.
   * 
   * @param minAge
   * @param maxAge
   * @return
   */
  public Date uniformBirthdate(int minAge, int maxAge) {
    Calendar calMin = Calendar.getInstance();
    calMin.setTime(getCurrentSimulationTime() != null ? getCurrentSimulationTime() : new Date());
    calMin.set(Calendar.HOUR_OF_DAY, 0);
    calMin.set(Calendar.MINUTE, 0);
    calMin.set(Calendar.SECOND, 0);
    calMin.set(Calendar.MILLISECOND, 0);
    Calendar calMax = (Calendar) calMin.clone();
    calMin.add(Calendar.YEAR, -maxAge);
    calMax.add(Calendar.YEAR, -minAge);
    long minMillis = calMin.getTimeInMillis();
    long maxMillis = calMax.getTimeInMillis();
    long chosenMillis = minMillis + (long) (Math.random() * (maxMillis - minMillis));
    return Date.from(Instant.ofEpochMilli(chosenMillis));
  }

  public Object uniformFromArgs2(Object o1, Object o2) {
    return uniformFromArray(Arrays.array(o1, o2));
  }

  public Object uniformFromArgs3(Object o1, Object o2, Object o3) {
    return uniformFromArray(Arrays.array(o1, o2, o3));
  }

  public Object uniformFromArgs4(Object o1, Object o2, Object o3, Object o4) {
    return uniformFromArray(Arrays.array(o1, o2, o3, o4));
  }

  public Object uniformFromArgs5(Object o1, Object o2, Object o3, Object o4, Object o5) {
    return uniformFromArray(Arrays.array(o1, o2, o3, o4, o5));
  }

  public Object uniformFromArgs6(Object o1, Object o2, Object o3, Object o4, Object o5, Object o6) {
    return uniformFromArray(Arrays.array(o1, o2, o3, o4, o5, o6));
  }

  public Object uniformFromArgs7(Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7) {
    return uniformFromArray(Arrays.array(o1, o2, o3, o4, o5, o6, o7));
  }

  public Object uniformFromArgs8(Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8) {
    return uniformFromArray(Arrays.array(o1, o2, o3, o4, o5, o6, o7, o8));
  }

  public Object uniformFromArgs9(Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9) {
    return uniformFromArray(Arrays.array(o1, o2, o3, o4, o5, o6, o7, o8, o9));
  }

  public Object uniformFromArray(Object[] objects) {
    if (objects == null || objects.length == 0)
      return null;
    return objects[(int) (Math.random() * objects.length)];
  }

  public Object uniformFromList(List<?> objects) {
    if (objects == null || objects.size() == 0)
      return null;
    return objects.get((int) (Math.random() * objects.size()));
  }

  /**
   * As always min <= returnValue < max.
   * 
   * @param min
   * @param max
   * @return
   */
  public Short uniformShort(short min, short max) {
    return (short) (min + (short) Math.floor(((Math.random() * (max - min)))));
  }

  /**
   * As always min <= returnValue < max.
   * 
   * @param min
   * @param max
   * @return
   */
  public Integer uniformInt(int min, int max) {
    return min + (int) Math.floor(((Math.random() * (max - min))));
  }

  /**
   * As always min <= returnValue < max.
   * 
   * @param min
   * @param max
   * @return
   */
  public Long uniformLong(long min, long max) {
    return min + (long) Math.floor(((Math.random() * (max - min))));
  }

  /**
   * As always min <= returnValue < max.
   * 
   * @param min
   * @param max
   * @return
   */
  public Double uniformDouble(double min, double max) {
    return min + Math.random() * (max - min);
  }

  public Boolean uniformBoolean() {
    return Math.random() < 0.5;
  }

  public FileValue smallPdf(String name) {
    try (InputStream data = getClass().getResourceAsStream("/mockument.pdf");) {
      return Variables.fileValue(name.toLowerCase().endsWith(".pdf") ? name : name + ".pdf").mimeType("application/pdf").file(data).create();
    } catch (IOException e) {
      LOG.error("Could not load mockument");
      return Variables.fileValue("Error loading content").create();
    }
  }

  // public Object construct(String fullQualifiedClassname, Object...
  // contructorArgs) {
  // try {
  // Class<?> clazz = Class.forName(fullQualifiedClassname);
  // Constructor<?> constructor = getConstructorForArgs(clazz, contructorArgs);
  // if (constructor == null) {
  // LOG.error("No suitable constructor found while instantiating class {}",
  // fullQualifiedClassname);
  // return null;
  // }
  // return constructor.newInstance(contructorArgs);
  // } catch (ClassNotFoundException e) {
  // LOG.error("Class {} not found.", fullQualifiedClassname);
  // return null;
  // } catch (InstantiationException | IllegalAccessException |
  // IllegalArgumentException | InvocationTargetException e) {
  // LOG.error("Error while calling constructor of class {}.",
  // fullQualifiedClassname);
  // return null;
  // }
  // }

  public Object ifthenelse(Object condition, Object whenTrue, Object whenFalse) {
    return toBoolean(condition) ? whenTrue : whenFalse;
  }

  private Long uniqueNumber = 1l;

  public Long uniqueNumber() {
    return uniqueNumber++;
  }

  public String email(String name, String company) {
    return name.trim().toLowerCase().replaceAll("\\W", ".") + "@" + company.trim().toLowerCase().replaceAll("\\W", "-") + ".com";
  }

  public String format1(String pattern, Object o1) {
    return String.format(pattern, o1);
  }

  public String format2(String pattern, Object o1, Object o2) {
    return String.format(pattern, o1, o2);
  }

  public String format3(String pattern, Object o1, Object o2, Object o3) {
    return String.format(pattern, o1, o2, o3);
  }

  public String format4(String pattern, Object o1, Object o2, Object o3, Object o4) {
    return String.format(pattern, o1, o2, o3, o4);
  }

  public String format5(String pattern, Object o1, Object o2, Object o3, Object o4, Object o5) {
    return String.format(pattern, o1, o2, o3, o4, o5);
  }

  public String format6(String pattern, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6) {
    return String.format(pattern, o1, o2, o3, o4, o5, o6);
  }

  public String format7(String pattern, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7) {
    return String.format(pattern, o1, o2, o3, o4, o5, o6, o7);
  }

  public String format8(String pattern, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8) {
    return String.format(pattern, o1, o2, o3, o4, o5, o6, o7, o8);
  }

  public String format9(String pattern, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9) {
    return String.format(pattern, o1, o2, o3, o4, o5, o6, o7, o8, o9);
  }

  // protected Constructor<?> getConstructorForArgs(Class<?> klass, Object[]
  // args) {
  // // Get all the constructors from given class
  // Constructor<?>[] constructors = klass.getConstructors();
  //
  // for (Constructor<?> constructor : constructors) {
  // // Walk through all the constructors, matching parameter amount and
  // // parameter types with given types (args)
  // Class<?>[] types = constructor.getParameterTypes();
  // if (types.length == args.length) {
  // boolean argumentsMatch = true;
  // for (int i = 0; i < args.length; i++) {
  // if (args[i] == null)
  // continue;
  // if (!types[i].isAssignableFrom(args[i].getClass())) {
  // argumentsMatch = false;
  // break;
  // }
  // }
  //
  // if (argumentsMatch) {
  // // We found a matching constructor, return it
  // return constructor;
  // }
  // }
  // }
  //
  // // No matching constructor
  // return null;
  // }

}
