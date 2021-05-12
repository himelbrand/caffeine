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


import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.apache.commons.lang3.ArrayUtils;

import java.util.*;

import static com.google.common.base.Preconditions.checkState;

/**
 * A cache that uses multiple linked lists, each holding entries with close range of benefit to
 * utilize access times to create a simple "latency aware" replacement algorithms. This is a
 * building block to be used by other policies, just like LRU is being used as a building block.
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
public final class LrbbBlock {

  final Long2ObjectMap<Node> data;
  final Node[] lists;
  final Set<Integer> activeLists;
  final int maximumSize;
  private final int resetCount;
  private double normalizationBias;
  private double normalizationFactor;
  private final double k;
  private final int maxLists;
  private int reqCount;
  private int currOp;
  private long lastReset;
  private int currentSize;
//  final NavigableSet<Entry> priorityQueue;
  private double maxDelta;

//  private int prevSize;
//  private double meanActive;
//  private int maxActive;
//  private long countActives;

  public LrbbBlock(double k, int maxLists, int maximumSize) {
    this.maximumSize = maximumSize;
    this.activeLists = new HashSet<>();
    this.currOp = 1;
    this.data = new Long2ObjectOpenHashMap<>();
    this.lists = new Node[maxLists+1];
    for (int i = 0; i <= maxLists; i++) {
      this.lists[i] = new Node(i);
    }
    this.resetCount = maximumSize;
    this.maxLists = maxLists;
    this.lastReset = System.nanoTime();
    this.reqCount = 0;
    this.currentSize = 0;
//    this.priorityQueue = new TreeSet<>();
    this.maxDelta = 1;
    this.k = k;
  }

  public List<Long> record(AccessEvent event) {
    final int weight = event.weight();
    final long key = event.key();
    Node old = data.get(key);
    reqCount++;
    if (reqCount > resetCount) {
      reqCount = 0;
      lastReset = System.nanoTime();
      currOp >>= 1;
    }
    if (old == null) {
      if (weight > maximumSize) {
        return new ArrayList<>();
      }
      currentSize += weight;
//      normalizationBias = normalizationBias > 0 ? Math.min(normalizationBias,event.missPenalty()) : event.missPenalty();
//      normalizationFactor = Math.max(normalizationFactor,event.missPenalty());
      return evict(event);
    } else {
      return onAccess(old);
    }
  }

  public double getNormalizationFactor() {
    return maxDelta;//priorityQueue.isEmpty() ? normalizationFactor : priorityQueue.first().penalty;
  }

  public void setNormalization(double normalizationBias, double normalizationFactor) {
    this.normalizationBias = normalizationBias;
    this.normalizationFactor = normalizationFactor;
    this.maxDelta = 1;
  }

  private int findList(AccessEvent candidate) {
    return candidate.delta() < 0 ? 0 : Math.max(1,Math.min((int) (((candidate.delta() - normalizationBias) / normalizationFactor) * (maxLists+1)),maxLists));

//    double d = Double.MAX_VALUE;
//    double tmpD;
//    double minRange;
//    double maxRange;
//    int index = -1;
//    int insertAt = 0;
//    Node sentinel;
//
//    for (int i = 0; i < lists.size(); i++) {
//      sentinel = lists.get(i);
//      if (sentinel.next == sentinel || sentinel.size <= 0) {
//        continue;
//      }
//      minRange = Math.pow(sentinel.avgBenefit(), 1 - eps);
//      maxRange = Math.pow(sentinel.avgBenefit(), 1 + eps);
//      if (candidate.delta() >= minRange && candidate.delta() <= maxRange) {
//        index = i;
//      }
//      tmpD = Math.abs(candidate.delta() - sentinel.avgBenefit());
//      if (tmpD < d) {
//        d = tmpD;
//        insertAt = i;
//      }
//    }
//    if (index < 0 && lists.size() < maxLists) {
//      lists.add(insertAt, new Node());
//    }
//    return insertAt;
  }

  /**
   * Evicts while the map exceeds the maximum capacity.
   */
  public List<Long> evict(AccessEvent candidate) {
    int listIndex;
    Node victim;
    Node inSentinel;
    Node victimSent;
    List<Long> evictions = new ArrayList<>();
    long currKey;
    listIndex = findList(candidate);
    inSentinel = lists[listIndex];
    if (currentSize > maximumSize) {
      while (currentSize > maximumSize) {
        victim = findVictim();
        int victimListIndex = victim.sentinel.index;
        currentSize -= victim.event.weight();
        data.remove(victim.key);
        currKey = victim.key;
        victimSent = victim.sentinel;
        victim.remove();
        if (victimSent.size <= 0 && victimSent != inSentinel) {
          activeLists.remove(victimListIndex);
//          priorityQueue.remove(new Entry(victimListIndex));
        }
        evictions.add(currKey);
      }
      addToList(candidate, inSentinel);
      activeLists.add(listIndex);
//      normalizationFactor = Math.min(normalizationFactor, Collections.max(Arrays.asList(ArrayUtils.toObject(activeLists.stream().mapToDouble(x-> lists[x].next.event().missPenalty()).toArray()))));
      return evictions;
    } else {
      activeLists.add(listIndex);
      addToList(candidate, inSentinel);
      return new ArrayList<>();
    }
  }

  private Node addToList(AccessEvent candidate, Node inSentinel) {
    Node node = new Node(candidate, candidate.weight(), inSentinel);
    data.put(candidate.key(), node);
    node.appendToTail();
    node.updateOp(currOp++);
    maxDelta = Math.max(inSentinel.next.event.delta(),maxDelta);
//    priorityQueue.add(new Entry(inSentinel.index,Math.max(0,inSentinel.next.event.delta())));
    return node;
  }

  private Node addToList(Node node, Node inSentinel) {
    node.sentinel = inSentinel;
    data.put(node.key, node);
    node.appendToTail();
    node.updateOp(currOp++);
    maxDelta = Math.max(inSentinel.next.event.delta(),maxDelta);
//    priorityQueue.add(new Entry(inSentinel.index,Math.max(0,inSentinel.next.event.delta())));
    return node;
  }

  public Node findVictim() {
    double rank;
    Node currSentinel;
    Node victim = null;
    double currMaxDelta = -1;
    double minRank = Double.MAX_VALUE;
    if (activeLists.contains(0)){
      currSentinel = lists[0];
      if (currSentinel.next != currSentinel)
        return currSentinel.next;
    }
    for (int i : activeLists) {
      currSentinel = lists[i];
      if (currSentinel.size == 0) {
        continue;
      }
      Node currVictim = currSentinel.next;
      currMaxDelta = Math.max(currVictim.event.delta(),currMaxDelta);
      if (currVictim.lastTouch < lastReset) {
        currVictim.resetOp();
      }

      rank = Math.signum(currVictim.event.delta()) * Math.pow(Math.abs(currVictim.event.delta()), Math.pow((double) currOp - currVictim.lastOp, -k));
      if (rank < minRank || victim == null || (rank == minRank
              && (double) currVictim.lastOp / currOp < (double) victim.lastOp / currOp)) {
        minRank = rank;
        victim = currVictim;
      }
    }
    maxDelta = Math.min(currMaxDelta < 0 ? maxDelta : currMaxDelta,maxDelta);
    checkState(victim != null, "\n\nmaxlists: %s\n\n victim is null! activeLists = %s\nlists=%s", maxLists, java.util.Arrays.toString(activeLists.toArray()),java.util.Arrays.toString(lists));
    return victim;
  }
  public void remove(long key){
    data.remove(key);
  }

  public Node addEntry(AccessEvent event){
    int listIndex = findList(event);
    activeLists.add(listIndex);
    maxDelta = Math.max(event.delta(),maxDelta);
    return addToList(event,lists[listIndex]);
  }

  public Node addEntry(Node node){
    int listIndex = findList(node.event());
    activeLists.add(listIndex);
    maxDelta = Math.max(node.event().delta(),maxDelta);
    return addToList(node,lists[listIndex]);
  }

  public boolean isHit(long key){
    return data.containsKey(key);
  }

  List<Long> onAccess(Node node) {
    Node head = node.sentinel;
    if (node.event.delta() < 0) {
      data.remove(node.key);
      long key = node.key;
      node.remove();
      if (head.size <= 0) {
        activeLists.remove(head.index);
//        priorityQueue.remove(new Entry(head.index));
      }
      List<Long> l = new ArrayList<>();
      l.add(key);
      return l;
    } else {
      int index = findList(node.event);
      if (index != head.index){
//                AccessEvent event = node.event;
        node.remove();
        if (head.size == 0) {
          activeLists.remove(head.index);
//                priorityQueue.remove(new Entry(victimSentinel.index));
        }
        node.sentinel = lists[index];
        lists[index].size += 1;
        node.moveToTail(currOp++);
      }else{
        node.moveToTail(currOp++);
      }
//      node.moveToTail(currOp++);
//      priorityQueue.remove(new Entry(head.index));
//      priorityQueue.add(new Entry(head.index,head.next.event.missPenalty()));
      maxDelta = Math.max(node.sentinel.next.event.delta(),maxDelta);
      return new ArrayList<>();
    }
  }
  public String type() {
    return (maxLists == 1) ? "LRU" : "LRBB";
  }

  static final private class Entry implements Comparable<Entry> {
    final int index;
    double penalty;

    public Entry(int index, double penalty) {
      this.index = index;
      this.penalty = penalty;
    }

    public Entry(int index) {
      this.index = index;
    }

    @Override
    public int compareTo(Entry o) {
      return o.index == index ? 0 : (int) Math.signum(o.penalty - penalty);
    }
  }

  /**
   * A node on the double-linked list.
   */
  public static final class Node {

    Node sentinel;
    int size;
    Node prev;
    Node next;
    long key;
    int weight;
    AccessEvent event;
    long lastOp;
    long lastTouch;
    double totalBenefit;
    int index;

    /**
     * Creates a new sentinel node.
     */
    public Node(int index) {
      this.key = Long.MIN_VALUE;
      this.sentinel = this;
      this.prev = this;
      this.next = this;
      this.event = null;
      this.lastOp = 1;
      this.size = 0;
      this.totalBenefit = 0;
      this.index = index;
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
      sentinel.size += 1;
//      sentinel.totalBenefit += this.event.delta();
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
     * Removes the node from the list.
     */
    public void remove() {
      sentinel.size -= 1;
      prev.next = next;
      next.prev = prev;
      prev = next = null;
//      sentinel.totalBenefit -= this.event.delta();
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

    public AccessEvent event() {
      return this.event;
    }

    public long key() {
      return key;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
              .add("key", key)
              .add("weight", weight)
              .add("size",size)
              .toString();
    }
  }

}

