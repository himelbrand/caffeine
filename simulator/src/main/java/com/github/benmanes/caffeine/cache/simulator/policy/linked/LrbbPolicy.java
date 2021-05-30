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

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.greedy_dual.CampPolicy;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.apache.commons.lang3.ArrayUtils;


import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;

/**
 * A cache that uses multiple linked lists, each holding entries with close range of benefit to
 * utilize access times to create a simple "latency aware" replacement algorithms.
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
@Policy.PolicySpec(name = "linked.LRBBv2")
public final class LrbbPolicy implements Policy {

    final Long2ObjectMap<Node> data;
    final Node[] lists;
    final Set<Integer> activeLists;
    final PolicyStats policyStats;
//    final NavigableSet<Entry> priorityQueue;
    final Admittor admittor;
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
    private double maxDelta;
    private int maxDeltaCounts;
//    private int prevSize;
    private double meanActive;
    private int maxActive;
    private long countActives;
    private int samplesCount;


    public LrbbPolicy(Admission admission, Config config, double k, int maxLists) {
        BasicSettings settings = new BasicSettings(config);
        this.k = k;
        this.activeLists = new HashSet<>();
        this.policyStats = new PolicyStats(admission.format(String.format("LRBB(k=%.0f,maxLists=%d)", k, maxLists)));
        this.admittor = admission.from(config, policyStats);
        this.maximumSize = settings.maximumSize();
        this.currOp = 1;
        this.data = new Long2ObjectOpenHashMap<>();
        this.maxLists = maxLists;
        this.lists = new Node[maxLists+1];
        for (int i = 0; i <= maxLists; i++) {
            this.lists[i] = new Node(i);
        }
        this.resetCount = maximumSize;
        this.normalizationBias = 0;//settings.lrbb().minMiss();
        this.normalizationFactor = 0;//settings.lrbb().maxMiss() - normalizationBias;
        this.lastReset = System.nanoTime();
        this.reqCount = 0;
        this.currentSize = 0;
//        this.priorityQueue = new TreeSet<>();
//        this.prevSize = 0;
        this.countActives = 0;
        this.maxActive = -1;
        this.maxDelta = 0;
        this.maxDeltaCounts = 0;
        this.samplesCount = 0;

    }


    /**
     * Returns all variations of this policy based on the configuration parameters.
     */
    public static Set<Policy> policies(Config config) {
        BasicSettings settings = new BasicSettings(config);
        Set<Policy> policies = new HashSet<>();
        for (Admission admission : settings.admission()) {
            for (double k : settings.lrbb().kValues()) {
                for (int maxLists : settings.lrbb().maxLists()) {
                    policies.add(new LrbbPolicy(admission, config, k, maxLists));
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
        if (reqCount % 100 == 0){
            meanActive = (meanActive*countActives + activeLists.size())/++countActives;
            if(maxActive < activeLists.size()){
                maxActive = activeLists.size();
            }
            policyStats.recordActiveList(meanActive,maxActive);
        }
        reqCount++;

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
            if (event.delta() > normalizationFactor){
                samplesCount++;
                maxDelta = (maxDelta*maxDeltaCounts + event.delta())/++maxDeltaCounts;
            }
            normalizationBias = normalizationBias > 0 ? Math.min(normalizationBias,Math.max(0,event.delta())) : Math.max(0,event.delta());
            if (samplesCount%1000 == 0 || normalizationFactor == 0){
                normalizationFactor = maxDelta;
                maxDeltaCounts = 1;
                samplesCount = 0;
            }
//            normalizationFactor = normalizationFactor*1.5 < Math.max(0,event.delta()) ? Math.max(0,event.delta())*1.5 : normalizationFactor;//Math.max(normalizationFactor,event.missPenalty());

            evict(event);
//            normalizationFactor = Math.min(normalizationFactor, 1.5*maxDelta);
        } else {
            old.event.updateHitPenalty(event.hitPenalty());
//            maxDelta = Math.max(old.event.delta(),maxDelta);
            policyStats.recordWeightedHit(weight);
            onAccess(old);
        }
//        normalizationFactor = Math.min(normalizationFactor, 1.5*maxDelta);
    }

    private int findList(AccessEvent candidate) {
//        while (index >= maxLists) {
//            index--;
//        }
//        assert index >= 0;
        return candidate.delta() < 0 ? 0 : Math.max(1,Math.min((int) (((candidate.delta() - normalizationBias) / normalizationFactor) * (maxLists+1)),maxLists));
//        return Math.max(0,Math.min((int) ((candidate.delta() / normalizationFactor) * maxLists),maxLists-1));
    }

    /**
     * Evicts while the map exceeds the maximum capacity.
     */
    private void evict(AccessEvent candidate) {
        if (currentSize > maximumSize) {
            while (currentSize > maximumSize) {
                Node victim = findVictim();
                try {
                    findList(victim.event);
                }catch (Exception e){
                    System.out.println("Error");
                    System.out.println(victim==null);

                    System.out.println(victim);
                }
                int victimListIndex = victim.sentinel.index;
                policyStats.recordEviction();
                boolean admit = admittor.admit(candidate, victim.event);
                if (admit) {
                    int listIndex = findList(candidate);
                    Node inSentinel = lists[listIndex];
                    activeLists.add(listIndex);

                    currentSize -= victim.event.weight();
                    data.remove(victim.key);
                    Node victimSentinel = victim.sentinel;
                    try {
                        victim.remove();
                    }catch (Exception e){
                        System.out.println(victim);
                        System.out.println(victim.event);
                        System.out.println(victimListIndex);
                        throw e;
                    }

                    if (victimSentinel.size == 0 && victimSentinel != inSentinel) {
                        activeLists.remove(victimListIndex);
//                        priorityQueue.remove(new Entry(victimListIndex));
                    }
                    addToList(candidate, inSentinel);

                } else {
                    currentSize -= candidate.weight();
                }
            }
        } else {
            int listIndex = findList(candidate);
            Node inSentinel = lists[listIndex];
            activeLists.add(listIndex);
            policyStats.recordOperation();
            addToList(candidate, inSentinel);
        }
    }

    private void addToList(AccessEvent candidate, Node inSentinel) {
        Node node = new Node(candidate, candidate.weight(), inSentinel);
        data.put(candidate.key(), node);
        node.appendToTail();
        node.updateOp(currOp++);
        maxDelta = Math.max(inSentinel.next.event.delta(),maxDelta);

//        priorityQueue.add(new Entry(inSentinel.index,Math.max(0,inSentinel.next.event.delta())));
    }

    private Node findVictim() {
        double rank;
        Node victim = null;
        Node currSentinel;
        Node currVictim;
        double currMaxDelta = -1;
        double minRank = Double.MAX_VALUE;
        if (activeLists.contains(0)){
            currSentinel = lists[0];
            return currSentinel.next;
        }
        for (int i : activeLists) {
            currSentinel = lists[i];
            if (currSentinel.size == 0) {
                continue;
            }
            currVictim = currSentinel.next;
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

    void onAccess(Node node) {
        policyStats.recordOperation();
        Node victimSentinel = node.sentinel;
        if (node.event.delta() < 0) {
            data.remove(node.key);

            node.remove();
            if (victimSentinel.size == 0) {
                activeLists.remove(victimSentinel.index);
//                priorityQueue.remove(new Entry(victimSentinel.index));
            }
        } else {
            int index = findList(node.event);
            if (index != victimSentinel.index){
//                AccessEvent event = node.event;
                node.remove();
                if (victimSentinel.size == 0) {
                    activeLists.remove(victimSentinel.index);
//                priorityQueue.remove(new Entry(victimSentinel.index));
                }
                node.sentinel = lists[index];
                lists[index].size += 1;
                node.moveToTail(currOp++);
            }else{
                node.moveToTail(currOp++);
            }

//            priorityQueue.remove(new Entry(victimSentinel.index));
//            priorityQueue.add(new Entry(victimSentinel.index,Math.max(0,victimSentinel.next.event.delta())));
            maxDelta = Math.max(node.sentinel.next.event.delta(),maxDelta);
        }

    }

    @Override
    public void finished() {
        System.out.printf("Max lists: %d\nMax active lists:%d\nMean number of active lists: %f\n",maxLists,maxActive,meanActive);
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
    static final class Node {

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
//            key = Long.MIN_VALUE;
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
            if(prev != null) {
                // unlink
                prev.next = next;
                next.prev = prev;
            }
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

