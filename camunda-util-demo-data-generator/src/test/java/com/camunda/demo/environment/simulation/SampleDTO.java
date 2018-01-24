package com.camunda.demo.environment.simulation;

import java.io.Serializable;

public class SampleDTO implements Serializable {
  private static final long serialVersionUID = -4981625622527252745L;

  private String uuid;
  private Person person;

  private double price;
  private long amount;
  
  public SampleDTO() {
  }

  public SampleDTO(String uuid, Person person, double price, long amount) {
    super();
    this.uuid = uuid;
    this.person = person;
    this.price = price;
    this.amount = amount;
  }

  protected String getUuid() {
    return uuid;
  }

  protected void setUuid(String uuid) {
    this.uuid = uuid;
  }

  protected Person getPerson() {
    return person;
  }

  protected void setPerson(Person person) {
    this.person = person;
  }

  protected double getPrice() {
    return price;
  }

  protected void setPrice(double price) {
    this.price = price;
  }

  protected long getAmount() {
    return amount;
  }

  protected void setAmount(long amount) {
    this.amount = amount;
  }

}
