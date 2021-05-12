/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.opt;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.AtomicDouble;
import com.typesafe.config.Config;

import java.util.Comparator;
import java.util.PriorityQueue;

import it.unimi.dsi.fastutil.doubles.*;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntPriorityQueue;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Bélády's optimal page replacement policy. The upper bound of the hit rate is estimated
 * by evicting from the cache the item that will next be used farthest into the future.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "opt.ClairvoyantLA")
public final class ClairvoyantLAPolicy implements Policy {
  private final Long2ObjectMap<PriorityQueue<AtomicDouble>> accessTimes;
  private final PolicyStats policyStats;
  private final DoubleSortedSet data;
  private final int maximumSize;

  private Recorder recorder;

//  private int infiniteTimestamp;
  private int tick;

  public ClairvoyantLAPolicy(Config config) {
    BasicSettings settings = new BasicSettings(config);
    maximumSize = Ints.checkedCast(settings.maximumSize());
    accessTimes = new Long2ObjectOpenHashMap<>();
    policyStats = new PolicyStats(name());
//    infiniteTimestamp = Integer.MAX_VALUE;
    data = new DoubleRBTreeSet();
  }

  @Override
  public void record(AccessEvent event) {
    if (recorder == null) {
      recorder = event.isPenaltyAware() ? new EventRecorder() : new KeyOnlyRecorder();
    }
    tick++;
    recorder.add(event);
    PriorityQueue<AtomicDouble> times = accessTimes.get(event.key());
    if (times == null) {
      times = new PriorityQueue<AtomicDouble>((a, b) -> {
        if(a.doubleValue() < b.doubleValue()){
          return 1;
        }else if(b.doubleValue() < a.doubleValue()){
          return -1;
        }
        return 0;
      });
      accessTimes.put(event.key(), times);
    }
    AtomicDouble time = new AtomicDouble(event.delta());
    for (AtomicDouble oldTime : times
         ) {
      oldTime.addAndGet(event.delta()/tick);
    }
    times.add(time);
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    policyStats.stopwatch().start();
    if (recorder != null) {
      recorder.process();
    }
    policyStats.stopwatch().stop();
  }

  /** Performs the cache operations for the given key. */
  private void process(long key, double hitPenalty, double missPenalty) {
    PriorityQueue<AtomicDouble> times = accessTimes.get(key);

    AtomicDouble lastAccess = times.poll();
    assert lastAccess != null;
    boolean found = data.remove(lastAccess.doubleValue());

    if (times.isEmpty()) {
//      data.add(infiniteTimestamp--);
      accessTimes.remove(key);
    } else {
      data.add(times.peek().doubleValue());
    }
    if (found) {
      policyStats.recordHit();
      policyStats.recordHitPenalty(hitPenalty);
    } else {
      policyStats.recordMiss();
      policyStats.recordMissPenalty(missPenalty);
      if (data.size() > maximumSize) {
        evict();
      }
    }
  }

  /** Removes the entry whose next access is farthest away into the future. */
  private void evict() {
    data.remove(data.firstDouble());
    policyStats.recordEviction();
  }

  /** An optimized strategy for storing the event history. */
  private interface Recorder {
    void add(AccessEvent event);
    void process();
  }

  private final class KeyOnlyRecorder implements Recorder {
    private final LongArrayFIFOQueue future;

    KeyOnlyRecorder() {
      future = new LongArrayFIFOQueue(maximumSize);
    }
    @Override public void add(AccessEvent event) {
      future.enqueue(event.key());
    }
    @Override public void process() {
      while (!future.isEmpty()) {
        ClairvoyantLAPolicy.this.process(future.dequeueLong(), 0.0, 0.0);
      }
    }
  }

  private final class EventRecorder implements Recorder {
    private final Queue<AccessEvent> future;

    EventRecorder() {
      future = new ArrayDeque<>(maximumSize);
    }
    @Override public void add(AccessEvent event) {
      future.add(event);
    }
    @Override public void process() {
      while (!future.isEmpty()) {
        AccessEvent event = future.poll();
        ClairvoyantLAPolicy.this.process(event.key(), event.hitPenalty(), event.missPenalty());
      }
    }
  }
}
