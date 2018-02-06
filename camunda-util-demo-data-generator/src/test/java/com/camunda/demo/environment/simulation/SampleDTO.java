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

  public String getUuid() {
    return uuid;
  }

  public void setUuid(String uuid) {
    this.uuid = uuid;
  }

  public Person getPerson() {
    return person;
  }

  public void setPerson(Person person) {
    this.person = person;
  }

  public double getPrice() {
    return price;
  }

  public void setPrice(double price) {
    this.price = price;
  }

  public long getAmount() {
    return amount;
  }

  public void setAmount(long amount) {
    this.amount = amount;
  }

  @Override
  public String toString() {
    return String.format("SampleDTO [getUuid()=%s, getPerson()=%s, getPrice()=%f, getAmount()=%d]", getUuid(), getPerson(), getPrice(), getAmount());
  }

  
}
