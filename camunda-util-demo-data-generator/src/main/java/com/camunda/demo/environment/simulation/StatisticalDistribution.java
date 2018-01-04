package com.camunda.demo.environment.simulation;

import org.apache.commons.math3.distribution.ConstantRealDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.RealDistribution;

public class StatisticalDistribution {
  private RealDistribution distribution;

  public StatisticalDistribution(double mean, double standardDeviation) {
    if (standardDeviation != 0) {
      distribution = new NormalDistribution(mean, standardDeviation);
    } else {
      distribution = new ConstantRealDistribution(mean);
    }
  }

  public double nextSample() {
    return distribution.sample();
  }
}
