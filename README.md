# Artifact for Boosting Cache Performance by Access Time Measurements paper

This repository branch includes all the necessary code and instructions to reproduce the main results presented in the [paper](https://github.com/himelbrand/caffeine/edit/submission/README.md).

## Caffeine Simulator

To run LRU, CAMP, GDWheel, CRA and W-TinyLFU versions policies, we use [Caffeine Simulator](https://github.com/ben-manes/caffeine/wiki/Simulator).

### Initial setup

    # clone the repository:
    git clone https://github.com/himelbrand/caffeine.git

    # move to the right branch:
    git checkout submission

    # allow gradle download dependencies and self check its running:
    ./gradlew build -x test

    # now you should be able to run:
    ./gradlew simulator:run
This will run the `HCA-W-CATinyLFU` policy on the `GCC` trace with cache size of `750` items.

### Configurations

In order to run each policy, use the following configurations above the default configuration of the simulator. That can be done by editing the [`application.conf`](https://github.com/ohadeytan/caffeine/blob/VLDB_submission/simulator/src/main/resources/application.conf) file.

Policy | Changes 
-|-
LRU |<pre>policies = [ linked.Lru ]</pre>
W-TinyLFU |<pre>policies = [ sketch.WindowTinyLfu ]</pre>
HC-W-TinyLFU |<pre>policies = [ sketch.HillClimberWindowTinyLfu ]</pre>
GDWheel |<pre>policies = [ greedy-dual.GDWheel ]</pre>
CAMP |<pre>policies = [ greedy-dual.CAMP ]</pre>
Hyperbolic-CA |<pre>policies = [ sampled.Hyperbolic-CA ]</pre>
CRA |<pre>policies = [ linked.CRA ]</pre>
W-CATinyLFU |<pre>policies = [ sketch.WindowCA ]</pre>
HC-W-CATinyLFU |<pre>policies = [ sketch.ACA ]</pre>

For the climbing strategies of `HC-W-CATinyLFU` use the following configs:
Climber | Policy | Changes
-|-|-
ADAM | `HCA-W-CATinyLFU` |<pre>ca-hill-climber-window {strategy = ["adam"] ...}</pre>
NADAM | `HCN-W-CATinyLFU`|<pre><pre>ca-hill-climber-window {strategy = ["nadam"] ...}</pre>
Simple | `HCS-W-CATinyLFU`|<pre>ca-hill-climber-window {strategy = ["simple"] ...}</pre>


The size of the cache can be set via the `maximum-size` parameter.

The path to and the format of the trace through the `files` section.

## Workloads

All workloads can be downloaded freely and run in the simulator with the appropriate format.

Workload | Link | Notes
-|-|-
DNS | |
AOL ||
WS | |
MULTI1 | |
MULTI2 | |
OLTP2 | |
LINUX | |
MAC | |
WIKI | |
SYSTOR17 | [SNIA IOTTA Repository](http://iotta.snia.org/traces/4964) | Combination of the files in `systor17-01.tar`
