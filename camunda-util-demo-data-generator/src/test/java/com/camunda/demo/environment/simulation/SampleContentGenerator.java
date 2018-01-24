package com.camunda.demo.environment.simulation;

import java.util.Date;

public class SampleContentGenerator extends DefaultContentGenerator {
  public SampleDTO createDTO(String uuid, Person person, double price, long amount) {
    return new SampleDTO(uuid, person, price, amount);
  }
  
  public Person createPerson(String name, String email, Date birthday, String sex) {
    return new Person(name, email, birthday, sex);
  }
}
