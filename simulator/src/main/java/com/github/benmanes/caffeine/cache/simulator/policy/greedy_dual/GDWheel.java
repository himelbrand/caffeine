/*
 * Copyright 2021 Omri Himelbrand. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.greedy_dual;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;

import com.typesafe.config.Config;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.*;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

/**
 * Greedy Dual Wheel (GD-Wheel) algorithm.
 * <p>
 * The algorithm is explained by the authors in
 * <a href="https://conglongli.github.io/paper/gdwheel-eurosys2015.pdf">
 * GD-Wheel: A Cost-Aware ReplacementPolicy for Key-Value Stores</a>.
 * This a cost-aware cache policy, which takes into account the access times (i.e. the cost of recomputing or fetching the data).
 * This is also a weighted policy, meaning it supports non-uniform entry sizes.
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
@Policy.PolicySpec(name = "greedy-dual.GDWheel", characteristics = WEIGHTED)
public class GDWheel implements Policy {
    private final int NW;
    private final int NQ;
    private final int[] CH;
    private final CostWheel[] wheels;
    final Admittor admittor;
    final Long2ObjectMap<Node> data;
    private final PolicyStats policyStats;
    private final long maximumSize;
    private int currentSize;

    public GDWheel(Config config, Admission admission) {
        GDWheelSettings settings = new GDWheelSettings(config);
        this.data = new Long2ObjectOpenHashMap<>();
        this.NW = settings.numberOfWheels();
        this.NQ = settings.numberOfQueues();
        this.maximumSize = settings.maximumSize();
        this.policyStats = new PolicyStats(admission.format("greedy-dual.GDWheel"));
        this.CH = new int[NW];
        this.wheels = new CostWheel[NW];
        for (int i = 0; i < NW; i++) {
            wheels[i] = new CostWheel(NQ);
        }
        this.currentSize = 0;
        this.admittor = admission.from(config, policyStats);
    }

    public static Set<Policy> policies(Config config) {
        BasicSettings settings = new BasicSettings(config);
        Set<Policy> policies = new HashSet<>();
        for (Admission admission : settings.admission()) {
            policies.add(new GDWheel(config, admission));
        }
        return policies;
    }

    @Override
    public void record(AccessEvent event) {
        long key = event.key();
        policyStats.recordOperation();
        Node node = data.get(key);
        int w = 0;
        if (node == null) {
            while (event.weight() + currentSize > maximumSize) {
                int ch0 = wheels[0].getNextIndex();
                boolean fullRound = CH[0] > ch0 || !wheels[0].queueNotEmpty(ch0);
                CH[0] = ch0;
                AccessEvent q = wheels[0].evict(CH[0]);
                if (fullRound) {
                    migration(1);
                }
                boolean admit = admittor.admit(event, q);
                if (!admit) {
                    wheels[0].add(CH[0], q);
                    break;
                } else {
                    data.remove(q.key());
                    currentSize -= q.weight();
                    policyStats.recordEviction();
                }
            }
            policyStats.recordWeightedMiss(event.weight());
            for (int i = 0; i < NW; i++) {
                if (Math.round(event.missPenalty() / Math.pow(NQ, i)) > 0) {
                    w = i;
                }
            }
            int q = (int) (Math.round(event.missPenalty() / Math.pow(NQ, w)) + CH[w]) % NQ;
            wheels[w].add(q, event);
            currentSize += event.weight();
            data.put(key, new Node(event, w, q));
        } else {
            policyStats.recordWeightedHit(event.weight());
            wheels[node.wheel].remove(node.q, node.event);
            for (int i = 0; i < NW; i++) {
                if (Math.round(node.event.missPenalty() / Math.pow(NQ, i)) > 0) {
                    w = i;
                }
            }
            int q = (int) (Math.round(event.missPenalty() / Math.pow(NQ, w)) + CH[w]) % NQ;
            node.updateLoc(w, q);
            wheels[w].add(q, node.event);
        }

    }

    private void migration(int idx) {
        CH[idx] = (CH[idx] + 1) % NQ;
        if (CH[idx] == 0 && idx + 1 < NW) {
            migration(idx + 1);
        }
        while (wheels[idx].queueNotEmpty(CH[idx])) {
            AccessEvent p = wheels[idx].evict(CH[idx]);
            int cr = ((int) p.missPenalty()) % ((int) Math.pow(NQ, idx));
            int q = (int) (Math.round(cr / Math.pow(NQ, idx - 1)) + CH[idx - 1]) % NQ;
            wheels[idx - 1].add(q, p);
            Node node = data.get(p.key());
            node.updateLoc(idx - 1, q);
        }
    }

    @Override
    public PolicyStats stats() {
        return policyStats;
    }

    static class GDWheelSettings extends BasicSettings {
        public GDWheelSettings(Config config) {
            super(config);
        }

        public int numberOfWheels() {
            return config().getInt("gd-wheel.nw");
        }

        public int numberOfQueues() {
            return config().getInt("gd-wheel.nq");
        }
    }

    static class CostWheel {
        ArrayList<PriorityQueue<AccessEvent>> wheel;
        int current_index;
        int NQ;

        public CostWheel(int NQ) {
            this.wheel = new ArrayList<>();
            Comparator<AccessEvent> comparator = new EventComparator();
            for (int i = 0; i < NQ; i++) {
                this.wheel.add(new PriorityQueue<>(comparator));
            }
            this.current_index = 0;
            this.NQ = NQ;
        }

        public int getNextIndex() {
            for (int i = current_index; i < current_index + NQ; i++) {
                int j = i % NQ;
                if (!wheel.get(j).isEmpty()) {
                    current_index = j;
                    break;
                }
            }
            return current_index;
        }

        public AccessEvent evict(int i) {
            PriorityQueue<AccessEvent> queue = wheel.get(i);
            return Objects.requireNonNull(queue.poll());
        }

        public void remove(int i, AccessEvent e) {
            PriorityQueue<AccessEvent> queue = wheel.get(i);
            queue.remove(e);
        }

        public void add(int i, AccessEvent e) {
            PriorityQueue<AccessEvent> queue = wheel.get(i);
            queue.add(e);
        }

        public boolean queueNotEmpty(int i) {
            PriorityQueue<AccessEvent> queue = wheel.get(i);
            return !queue.isEmpty();
        }
    }

    static class EventComparator implements Comparator<AccessEvent> {
        @Override
        public int compare(AccessEvent e1, AccessEvent e2) {
            double res = e1.missPenalty() - e2.missPenalty();
            if (res < 0) {
                return -1;
            } else if (res > 0) {
                return 1;
            } else {
                return 0;
            }
        }
    }

    static class Node {
        AccessEvent event;
        long key;
        int wheel;
        int q;

        public Node(AccessEvent event, int wheel, int q) {
            this.event = event;
            this.key = event.key();
            this.wheel = wheel;
            this.q = q;
        }

        public void updateLoc(int w, int q) {
            this.wheel = w;
            this.q = q;
        }
    }
}
