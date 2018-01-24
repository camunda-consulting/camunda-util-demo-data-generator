package com.camunda.demo.environment.simulation;

import java.util.Calendar;
import java.util.Date;

public class Person {

  private String name;
  private String email;
  private Date birthday;
  private String sex;
  private int age;
  
  public Person() {
  }

  public Person(String name, String email, Date birthday, String sex) {
    super();
    setName(name);
    setEmail(email);
    setBirthday(birthday);
    setSex(sex);
  }

  public Date getBirthday() {
    return birthday;
  }

  public void setBirthday(Date birthday) {
    this.birthday = birthday;
    Calendar dob = Calendar.getInstance();
    dob.setTime(birthday);
    Calendar today = Calendar.getInstance();
    today.setTime(new Date());

    int age = today.get(Calendar.YEAR) - dob.get(Calendar.YEAR);
    if (today.get(Calendar.MONTH) < dob.get(Calendar.MONTH)) {
      age--;
    } else if (today.get(Calendar.MONTH) == dob.get(Calendar.MONTH) && today.get(Calendar.DAY_OF_MONTH) < dob.get(Calendar.DAY_OF_MONTH)) {
      age--;
    }
    setAge(age);
  }

  public String getSex() {
    return sex;
  }

  public void setSex(String sex) {
    this.sex = sex;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public int getAge() {
    return age;
  }

  public void setAge(int age) {
    this.age = age;
  }
}
