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
package com.github.benmanes.caffeine.cache.simulator.policy.linked;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A cache that uses multiple linked lists, each holding entries with close range of benefit to
 * utilize access times to create a simple "latency aware" replacement algorithms.
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
@Policy.PolicySpec(name = "linked.LRBB")
public final class LRBB implements Policy {

  final Long2ObjectMap<Node> data;
  final List<Node> lists;
  final PolicyStats policyStats;
  final Admittor admittor;
  final int maximumSize;
  private final int resetCount;
  private final double eps;
  private final double k;
  private final int maxLists;
  private int reqCount;
  private int currOp;
  private long lastReset;
  private int currentSize;
//  private long reqCountAll;

  public LRBB(Admission admission, Config config, double k, double reset, double eps) {
    BasicSettings settings = new BasicSettings(config);
    this.k = k;
    this.eps = eps;
    this.policyStats = new PolicyStats(admission.format(getPolicyName()));
    this.admittor = admission.from(config, policyStats);
    this.maximumSize = settings.maximumSize();
    this.currOp = 1;
    this.data = new Long2ObjectOpenHashMap<>();
    this.lists = new ArrayList<>();
    this.resetCount = (int) (reset * maximumSize);
    this.maxLists = (int) Math.round(2.0 / eps);
    this.lastReset = System.nanoTime();
    this.reqCount = 0;
    this.currentSize = 0;
//    this.reqCountAll = 0;
  }

  private String getPolicyName() {
    return String.format("LRBB(k=%.2f,eps=%.3f)", k, eps);
  }

  /**
   * Returns all variations of this policy based on the configuration parameters.
   */
  public static Set<Policy> policies(Config config) {
    BasicSettings settings = new BasicSettings(config);
    Set<Policy> policies = new HashSet<>();
    for (Admission admission : settings.admission()) {
      for (double k : settings.lrbb().kValues()) {
        for (double reset : settings.lrbb().reset()) {
          for (double eps : settings.lrbb().epsilon()) {
            policies.add(new LRBB(admission, config, k, reset, eps));
          }
        }
      }
    }
    return policies;
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }



  @Override
  public void record(AccessEvent event) {
    final int weight = event.weight();
    final long key = event.key();
    Node old = data.get(key);

    admittor.record(event);
    reqCount++;
//    reqCountAll++;
    if (reqCount > resetCount) {
      reqCount = 0;
      lastReset = System.nanoTime();
      currOp >>= 1;
    }

    if (old == null) {
      policyStats.recordWeightedMiss(weight);
      if (weight > maximumSize) {
        policyStats.recordOperation();
        return;
      }
      currentSize += weight;
      evict(event);
    } else {
      old.event.updateHitPenalty(event.hitPenalty());
      policyStats.recordApproxAccuracy(event.missPenalty(), old.event().missPenalty());
      policyStats.recordWeightedHit(weight);
      onAccess(old);
    }
  }

  private int findList(AccessEvent candidate) {
    double d = Double.MAX_VALUE;
    double tmpD;
    double minRange;
    double maxRange;
    int index = -1;
    int insertAt = 0;
    Node sentinel;

    for (int i = 0; i < lists.size(); i++) {
      sentinel = lists.get(i);
      if (sentinel.next == sentinel || sentinel.size <= 0) {
        continue;
      }
      minRange = Math.pow(sentinel.avgBenefit(), 1 - eps);
      maxRange = Math.pow(sentinel.avgBenefit(), 1 + eps);
      if (candidate.delta() >= minRange && candidate.delta() <= maxRange) {
        index = i;
      }
      tmpD = Math.abs(candidate.delta() - sentinel.avgBenefit());
      if (tmpD < d) {
        d = tmpD;
        insertAt = i;
      }
    }
    if (index < 0 && lists.size() < maxLists) {
      lists.add(insertAt, new Node());
    }
    return insertAt;
  }

  /**
   * Evicts while the map exceeds the maximum capacity.
   */
  private void evict(AccessEvent candidate) {
    if (currentSize > maximumSize) {
      while (currentSize > maximumSize) {
        Node victim = findVictim();
        policyStats.recordEviction();
        boolean admit = admittor.admit(candidate, victim.event);
        if (admit) {
          int listIndex = findList(candidate);
          Node inSentinel = lists.get(listIndex);
          currentSize -= victim.event.weight();
          data.remove(victim.key);
          Node victimSent = victim.sentinel;
          victim.remove();
          if (victimSent.size <= 0 && victimSent != inSentinel) {
            lists.remove(victimSent);
          }
          addToList(candidate, inSentinel);
        } else {
          currentSize -= candidate.weight();
        }
      }
    } else {
      int listIndex = findList(candidate);
      Node inSentinel = lists.get(listIndex);
      policyStats.recordOperation();
      addToList(candidate, inSentinel);
    }
  }

  private void addToList(AccessEvent candidate, Node inSentinel) {
    Node node = new Node(candidate, candidate.weight(), inSentinel);

    data.put(candidate.key(), node);
    if (inSentinel.size > 0) {
      Node listNext = inSentinel.next;
      if (listNext.event.delta() * Math.pow((double) currOp - listNext.lastOp + 1, -k) > candidate
          .delta()) {
        node.appendToHead();
      } else {
        node.appendToTail();
      }
    } else {
      node.appendToTail();
    }
    node.updateOp(currOp++);
  }

  private Node findVictim() {
    policyStats.recordOperation();
    double rank;
    Node victim = null;
    double minRank = Double.MAX_VALUE;
    for (Node currSentinel : lists) {
      if (currSentinel.next == currSentinel) {
        continue;
      }
      Node currVictim = currSentinel.next;
      if (currVictim.lastTouch < lastReset) {
        currVictim.resetOp();
      }

      rank = Math.pow(Math.abs(currVictim.event.delta()), Math.pow((double) currOp - currVictim.lastOp, -k)) * (currVictim.event.delta() > 0 ? 1 : -1);
      if (rank < minRank || victim == null || (rank == minRank
          && (double) currVictim.lastOp / currOp < (double) victim.lastOp / currOp)) {
        minRank = rank;
        victim = currVictim;
      }
    }
    return victim;
  }

  void onAccess(Node node) {
    policyStats.recordOperation();
    Node head = node.sentinel;
    if (node.event.delta() < 0) {
      data.remove(node.key);
      node.remove();
      if (head.size <= 0) {
        lists.remove(head);
      }
    } else {
      node.moveToTail(currOp++);
    }
  }

  /**
   * A node on the double-linked list.
   */
  static final class Node {

    final Node sentinel;
    int size;
    Node prev;
    Node next;
    long key;
    int weight;
    AccessEvent event;
    long lastOp;
    long lastTouch;
    double totalBenefit;

    /**
     * Creates a new sentinel node.
     */
    public Node() {
      this.key = Long.MIN_VALUE;
      this.sentinel = this;
      this.prev = this;
      this.next = this;
      this.event = null;
      this.lastOp = 1;
      this.size = 0;
      this.totalBenefit = 0;
    }

    /**
     * Creates a new, unlinked node.
     */
    public Node(AccessEvent event, int weight, Node sentinel) {
      this.sentinel = sentinel;
      this.key = event.key();
      this.weight = weight;
      this.event = event;
      this.lastOp = 1;
    }

    /**
     * Appends the node to the tail of the list.
     */
    public void appendToTail() {
      Node tail = sentinel.prev;
      sentinel.prev = this;
      tail.next = this;
      next = sentinel;
      prev = tail;
      this.sentinel.size += 1;
      sentinel.totalBenefit += this.event.delta();
    }

    public void appendToHead() {
      Node head = sentinel.next;
      sentinel.next = this;
      head.prev = this;
      next = head;
      prev = sentinel;
      sentinel.size += 1;
      sentinel.totalBenefit += this.event.delta();
    }


    /**
     * Appends the node to the tail of the list.
     */
    public void appendToTail(long op) {
      Node tail = sentinel.prev;
      sentinel.prev = this;
      tail.next = this;
      next = sentinel;
      prev = tail;
      lastOp = op;
      lastTouch = System.nanoTime();
    }

    /**
     * Removes the node from the list.
     */
    public void remove() {
      sentinel.size -= 1;
      if (this == sentinel) {
        System.out.println(toString());
      }
      prev.next = next;
      next.prev = prev;
      prev = next = null;
      key = Long.MIN_VALUE;
      sentinel.totalBenefit -= this.event.delta();
    }

    /**
     * Moves the node to the tail.
     */
    public void moveToTail() {
      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = sentinel;
      prev = sentinel.prev;
      sentinel.prev = this;
      prev.next = this;

    }

    /**
     * Moves the node to the tail.
     */
    public void moveToTail(long op) {
      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = sentinel;
      prev = sentinel.prev;
      sentinel.prev = this;
      prev.next = this;
      lastOp = op;
      sentinel.updateOp(op);
      lastTouch = System.nanoTime();
    }

    /**
     * Updates the node's lastop without moving it
     */
    public void updateOp(long op) {
      lastOp = op;
      lastTouch = System.nanoTime();
    }

    /**
     * Updates the node's lastop without moving it
     */
    public void resetOp() {
      lastOp = Math.max(1, lastOp >> 1);
      lastTouch = System.nanoTime();
    }

    /**
     * Updates the node's event without moving it
     */
    public void updateEvent(AccessEvent e) {
      event = e;
    }
    public double avgBenefit() {
      return this.sentinel.totalBenefit / getSize();
    }

    public int getSize() {
      return this.sentinel.size;
    }

    public AccessEvent event(){
      return this.event;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("weight", weight)
          .toString();
    }
  }

}

