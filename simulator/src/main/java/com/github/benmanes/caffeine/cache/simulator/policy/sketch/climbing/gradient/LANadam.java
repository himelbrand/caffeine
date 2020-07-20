/*
 * Copyright 2020 Omri Himelbrand. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.gradient;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.AbstractLAClimber;
import com.typesafe.config.Config;
import java.util.List;

/**
 * Nesterov-accelerated Adaptive Moment Estimation (Nadam) optimizer. Nadam modifies the Adam
 * optimizer to replace normal momentum with Nesterov's accelerated gradient. The authors describe
 * it in <a href="https://openreview.net/pdf?id=OM0jvwB8jIp57ZJjtNEZ">Incorporating Nesterov
 * Momentum into Adam<a>. This optimizer try to optimize based on access times instead of hit
 * ratio.
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
public final class LANadam extends AbstractLAClimber {

  private final int stepSize;
  private final double beta1;
  private final double beta2;
  private final double epsilon;

  private int t;
  private double moment;
  private double velocity;

  public LANadam(Config config) {
    NadamSettings settings = new NadamSettings(config);
    sampleSize = (int) (settings.percentSample() * settings.maximumSize());
    stepSize = (int) (settings.percentPivot() * settings.maximumSize());
    epsilon = settings.epsilon();
    beta1 = settings.beta1();
    beta2 = settings.beta2();
    t = 1;
  }

  @Override
  protected void resetSample(double avgPenalty) {
    super.resetSample(avgPenalty);
    t++;
  }

  @Override
  protected double adjust(double avgPenalty) {
    double gradient = avgPenalty - previousAvgPenalty;
    moment = (beta1 * moment) + ((1 - beta1) * gradient);
    velocity = (beta2 * velocity) + ((1 - beta2) * (gradient * gradient));

    // https://towardsdatascience.com/10-gradient-descent-optimisation-algorithms-86989510b5e9#6d4c
    double momentBias = moment / (1 - Math.pow(beta1, t));
    double velocityBias = velocity / (1 - Math.pow(beta2, t));
    return (stepSize / (Math.sqrt(velocityBias) + epsilon))
        * ((beta1 * momentBias) + (((1 - beta1) / (1 - Math.pow(beta1, t))) * gradient));
  }

  static final class NadamSettings extends BasicSettings {

    static final String BASE_PATH = "la-hill-climber-window.nadam.";

    public NadamSettings(Config config) {
      super(config);
    }

    public List<Double> percentMain() {
      return config().getDoubleList("la-hill-climber-window.percent-main");
    }

    public double percentPivot() {
      return config().getDouble(BASE_PATH + "percent-pivot");
    }

    public double percentSample() {
      return config().getDouble(BASE_PATH + "percent-sample");
    }

    public double beta1() {
      return config().getDouble(BASE_PATH + "beta1");
    }

    public double beta2() {
      return config().getDouble(BASE_PATH + "beta2");
    }

    public double epsilon() {
      return config().getDouble(BASE_PATH + "epsilon");
    }
  }
}
