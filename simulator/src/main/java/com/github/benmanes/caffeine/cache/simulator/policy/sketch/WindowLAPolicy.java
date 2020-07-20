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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch;

import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.toSet;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.LATinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.LRBBBlock;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.LRBBBlock.Node;
import com.google.common.collect.ImmutableSet;
import com.typesafe.config.Config;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Set;

/**
 * An adaption of the TinyLfu policy that adds a temporal admission window. This window allows the
 * policy to have a high hit rate when entries exhibit a high temporal / low frequency pattern.
 * <p>
 * A new entry starts in the window and remains there as long as it has high temporal locality.
 * Eventually an entry will slip from the end of the window onto the front of the main queue. If the
 * main queue is already full, then a historic frequency filter determines whether to evict the
 * newly admitted entry or the victim entry chosen by main queue's policy. This process ensures that
 * the entries in the main queue have both a high recency and frequency. The window space uses LRU
 * and the main uses Segmented LRU.
 * <p>
 * Scan resistance is achieved by means of the window. Transient data will pass through from the
 * window and not be accepted into the main queue. Responsiveness is maintained by the main queue's
 * LRU and the TinyLfu's reset operation so that expired long term entries fade away.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class WindowLAPolicy implements Policy {

  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final Admittor admittor;
  private final int maximumSize;

  private final LRBBBlock headWindow;
  private final LRBBBlock headProbation;
  private final LRBBBlock headProtected;

  private final int maxWindow;
  private final int maxProtected;

  private int sizeWindow;
  private int sizeProtected;

  public WindowLAPolicy(double percentMain, WindowLASettings settings, double k,
      double eps, double reset) {
    String name = String
        .format("sketch.WindowLATinyLfu (%.0f%%,k=%.2f,eps=%.3f)", 100 * (1.0d - percentMain), k,
            eps);
    this.policyStats = new PolicyStats(name);
    this.admittor = new LATinyLfu(settings.config(), policyStats);
    int maxMain = (int) (settings.maximumSize() * percentMain);
    this.maxProtected = (int) (maxMain * settings.percentMainProtected());
    this.maxWindow = settings.maximumSize() - maxMain;
    this.data = new Long2ObjectOpenHashMap<>();
    this.maximumSize = settings.maximumSize();
    this.headProtected = new LRBBBlock(k, reset, eps, this.maxProtected);
    this.headProbation = new LRBBBlock(k, reset, eps, maxMain - this.maxProtected);
    this.headWindow = new LRBBBlock(k, reset, eps, this.maxWindow);
  }

  /**
   * Returns all variations of this policy based on the configuration parameters.
   */
  public static Set<Policy> policies(Config config) {
    WindowLASettings settings = new WindowLASettings(config);
    return settings.percentMain().stream()
        .map(percentMain ->
            settings.k().stream()
                .map(k ->
                    settings.reset().stream()
                        .map(reset ->
                            settings.epsilon().stream()
                                .map(eps -> new WindowLAPolicy(percentMain, settings, k, eps,
                                    reset)
                                ))))
        .flatMap(x -> x)
        .flatMap(x -> x)
        .flatMap(x -> x)
        .collect(toSet());
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  /**
   * Adds the entry to the admission window, evicting if necessary.
   */
  private void onMiss(AccessEvent event) {
    long key = event.key();
    admittor.record(event);
    Node n = headWindow.addEntry(event);
    data.put(key, n);
    sizeWindow++;
    evict();
  }

  /**
   * Moves the entry to the MRU position in the admission window.
   */
  private void onWindowHit(Node node) {
    admittor.record(node.event());
    node.moveToTail();
  }

  /**
   * Promotes the entry to the protected region's MRU position, demoting an entry if necessary.
   */
  private void onProbationHit(Node node) {
    admittor.record(node.event());

    node.remove();
    headProbation.remove(node.key());
    headProtected.addEntry(node);

    sizeProtected++;
    if (sizeProtected > maxProtected) {
      Node demote = headProtected.findVictim();
      demote.remove();
      headProtected.remove(demote.key());
      headProbation.addEntry(demote);
      sizeProtected--;
    }
  }

  /**
   * Moves the entry to the MRU position, if it falls outside of the fast-path threshold.
   */
  private void onProtectedHit(Node node) {
    admittor.record(node.event());
    node.moveToTail();
  }

  /**
   * Evicts from the admission window into the probation space. If the size exceeds the maximum,
   * then the admission candidate and probation's victim are evaluated and one is evicted.
   */
  private void evict() {
    if (sizeWindow <= maxWindow) {
      return;
    }
    Node candidate = headWindow.findVictim();
    sizeWindow--;
    candidate.remove();
    headWindow.remove(candidate.key());
    headProbation.addEntry(candidate);
    if (data.size() > maximumSize) {
      Node victim = headProbation.findVictim();
      Node evict = admittor.admit(candidate.event(), victim.event()) ? victim : candidate;
      data.remove(evict.key());
      evict.remove();
      headProbation.remove(evict.key());
      policyStats.recordEviction();
    }
  }

  @Override
  public Set<Characteristic> characteristics() {
    return ImmutableSet.of();
  }

  @Override
  public void record(AccessEvent event) {
    long key = event.key();
    policyStats.recordOperation();
    Node node = data.get(key);
    if (node == null) {
      onMiss(event);
      policyStats.recordMiss();
    } else if (headWindow.isHit(key)) {
      onWindowHit(node);
      policyStats.recordHit();
    } else if (headProbation.isHit(key)) {
      onProbationHit(node);
      policyStats.recordHit();
    } else if (headProtected.isHit(key)) {
      onProtectedHit(node);
      policyStats.recordHit();
    } else {
      throw new IllegalStateException();
    }
  }

  @Override
  public void finished() {
    long windowSize = data.values().stream().filter(n -> headWindow.isHit(n.key())).count();
    long probationSize = data.values().stream().filter(n -> headProbation.isHit(n.key())).count();
    long protectedSize = data.values().stream().filter(n -> headProtected.isHit(n.key())).count();
  System.out.println(probationSize);
  System.out.println(protectedSize);
    checkState(windowSize == sizeWindow);
    checkState(protectedSize == sizeProtected);
    checkState(probationSize == data.size() - windowSize - protectedSize);

    checkState(data.size() <= maximumSize);
  }

  enum Status {
    WINDOW, PROBATION, PROTECTED
  }

  public static final class WindowLASettings extends BasicSettings {

    public WindowLASettings(Config config) {
      super(config);
    }

    public List<Double> percentMain() {
      return config().getDoubleList("window-tiny-lfu.percent-main");
    }

    public double percentMainProtected() {
      return config().getDouble("window-tiny-lfu.percent-main-protected");
    }

    public List<Double> k() {
      return config().getDoubleList("lrbb.k");
    }

    public List<Double> epsilon() {
      return config().getDoubleList("lrbb.epsilon");
    }

    public List<Double> reset() {
      return config().getDoubleList("lrbb.reset");
    }
  }
}
