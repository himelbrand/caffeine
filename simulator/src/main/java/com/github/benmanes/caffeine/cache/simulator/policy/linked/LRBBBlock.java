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


import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.google.common.base.MoreObjects;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;

/**
 * A cache that uses multiple linked lists, each holding entries with close range of benefit to
 * utilize access times to create a simple "latency aware" replacement algorithms.
 * This is a building block to be used by other policies, just like LRU is being used as a building block.
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
public final class LRBBBlock {

  final Long2ObjectMap<Node> data;
  final List<Node> lists;
  final int maximumSize;
  private final int resetCount;
  private int reqCount;
  private int maxLists;
  private int currOp;
  private double eps;
  private double k;
  private long lastReset;
  private int currentSize;

  public LRBBBlock(double k, double reset, double eps, int maximumSize) {
    this.maximumSize = maximumSize;
    this.currOp = 1;
    this.data = new Long2ObjectOpenHashMap<>();
    this.lists = new ArrayList<>();
    this.resetCount = (int) (reset * maximumSize);
    this.lists.add(new Node());
    this.maxLists = (int) Math.round(2.0 / eps);
    this.lastReset = System.nanoTime();
    this.reqCount = 0;
    this.currentSize = 0;
    this.k = k;
    this.eps = eps;
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
      return evict(event);
    } else {
      return onAccess(old);
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
  private List<Long> evict(AccessEvent candidate) {
    int listIndex;
    Node victim;
    Node inSentinel;
    Node victimSent;
    List<Long> evictions = new ArrayList<>();
    long currKey;
    if (currentSize > maximumSize) {
      while (currentSize > maximumSize) {
        victim = findVictim();
        listIndex = findList(candidate);
        inSentinel = lists.get(listIndex);
        currentSize -= victim.event.weight();
        data.remove(victim.key);
        currKey = victim.key;
        victimSent = victim.sentinel;
        victim.remove();
        if (victimSent.size <= 0 && victimSent != inSentinel) {
          lists.remove(victimSent);
        }
        addToList(candidate, inSentinel);
        evictions.add(currKey);
      }
      return evictions;
  } else {
    listIndex = findList(candidate);
    inSentinel = lists.get(listIndex);
    addToList(candidate, inSentinel);
    return new ArrayList<>();
  }
}

  private void addToList(AccessEvent candidate, Node inSentinel) {
    Node node = new Node(candidate, candidate.weight(), inSentinel);

    data.put((long) candidate.key(), node);
    if (inSentinel.size > 0) {
      Node listNext = inSentinel.next;
      if (listNext.event.delta() * Math.pow((double) currOp - listNext.lastOp, -k) > candidate
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
    double rank;
    Node victim = null;
    double minRank = Double.MAX_VALUE;
    int maxSize = Integer.MAX_VALUE;

    for (Node currSentinel : lists) {
      if (currSentinel.next == currSentinel) {
        continue;
      }
      if (currSentinel.size < maxSize) {
        maxSize = currSentinel.size;
      }
      Node currVictim = currSentinel.next;
      if (currVictim.lastTouch < lastReset) {
        currVictim.resetOp();
      }
      rank = Math.pow(currVictim.event.delta(), Math.pow((double) currOp - currVictim.lastOp, -k));

      if (rank < minRank || victim == null || (rank == minRank
          && (double) currVictim.lastOp / currOp < (double) victim.lastOp / currOp)) {
        minRank = rank;
        victim = currVictim;
      }
    }
    return victim;
  }

  List<Long> onAccess(Node node) {
    Node head = node.sentinel;
    if (node.event.delta() < 0) {
      data.remove(node.key);
      long key = node.key;
      node.remove();
      if (head.size <= 0) {
        lists.remove(head);
      }
      List<Long> l = new ArrayList<>();
      l.add(key);
      return l;
    } else {
      node.moveToTail(currOp++);
      return new ArrayList<>();
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

  public double avgBenefit() {
    return this.sentinel.totalBenefit / getSize();
  }

  public int getSize() {
    return this.sentinel.size;
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

