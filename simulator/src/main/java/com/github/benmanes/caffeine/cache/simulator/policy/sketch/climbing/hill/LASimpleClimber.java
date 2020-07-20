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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.hill;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.AbstractLAClimber;
import com.typesafe.config.Config;

/**
 * A naive, simple hill climber. This simple hill climber makes adjustments based on access times
 * instead of hit ratio.
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
public final class LASimpleClimber extends AbstractLAClimber {

  private final double restartThreshold;
  private final double initialStepSize;
  private final double sampleDecayRate;
  private final int initialSampleSize;
  private final double stepDecayRate;
  private final double tolerance;

  private boolean increaseWindow;
  private double stepSize;

  public LASimpleClimber(Config config) {
    SimpleClimberSettings settings = new SimpleClimberSettings(config);
    this.initialSampleSize = (int) (settings.percentSample() * settings.maximumSize());
    this.initialStepSize = settings.percentPivot() * settings.maximumSize();
    this.restartThreshold = settings.restartThreshold();
    this.sampleDecayRate = settings.sampleDecayRate();
    this.stepDecayRate = settings.stepDecayRate();
    this.tolerance = 100d * settings.tolerance();
    this.sampleSize = initialSampleSize;
    this.stepSize = initialStepSize;
  }

  @Override
  protected double adjust(double avgPenalty) {
    if (avgPenalty / previousAvgPenalty > 1.0 + tolerance) {
      increaseWindow = !increaseWindow;
    }
    if (1 - Math
        .abs(Math.min(avgPenalty, previousAvgPenalty) / Math.max(avgPenalty, previousAvgPenalty))
        >= restartThreshold) {
      sampleSize = initialSampleSize;
      stepSize = initialStepSize;
    }
    return increaseWindow ? stepSize : -stepSize;
  }

  @Override
  protected void resetSample(double avgPenalty) {
    super.resetSample(avgPenalty);
    stepSize *= stepDecayRate;
    sampleSize = (int) (sampleSize * sampleDecayRate);
    if ((stepSize <= 0.01) || (sampleSize <= 1)) {
      sampleSize = Integer.MAX_VALUE;
    }
  }

  static final class SimpleClimberSettings extends BasicSettings {

    static final String BASE_PATH = "la-hill-climber-window.simple.";

    public SimpleClimberSettings(Config config) {
      super(config);
    }

    public double percentPivot() {
      return config().getDouble(BASE_PATH + "percent-pivot");
    }

    public double percentSample() {
      return config().getDouble(BASE_PATH + "percent-sample");
    }

    public double tolerance() {
      return config().getDouble(BASE_PATH + "tolerance");
    }

    public double stepDecayRate() {
      return config().getDouble(BASE_PATH + "step-decay-rate");
    }

    public double sampleDecayRate() {
      return config().getDouble(BASE_PATH + "sample-decay-rate");
    }

    public double restartThreshold() {
      return config().getDouble(BASE_PATH + "restart-threshold");
    }
  }
}
