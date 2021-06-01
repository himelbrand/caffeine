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

Workload | Link | Notes | Parser format
-|-|-|-
DNS | [direct download](https://drive.google.com/file/d/1vt8NZFia2c8CswzHQqI_ABB3-3J3XZwj/view?usp=sharing) | Was ceated using [dns-timing](https://github.com/himelbrand/dns-timing) | `dns`
AOL |[direct download](https://drive.google.com/file/d/1uKqa6aytR9CITIFYgWqDWWuPisO22AAx/view?usp=sharing) | Used the [AOL 2006 dataset](https://academictorrents.com/details/cd339bddeae7126bb3b15f3a72c903cb0c401bd1) combined with timing done using the [DuckDuckGo](https://duckduckgo.com/api) API | `latency`
WS | [direct download](https://drive.google.com/file/d/1Vr0jioIcKsjzQtpYhL4oqJc9bdeMxFQu/view?usp=sharing) | Used the [2017-2019 Search Engine Keywords](https://www.kaggle.com/hofesiy/2019-search-engine-keywords) combined with timing done using the [DuckDuckGo](https://duckduckgo.com/api) API | `latency`
MULTI1 | [direct download](https://drive.google.com/file/d/1lT7QxHURJaF18dXtfsMVbbLRQamUAqMQ/view?usp=sharing) | Used `multi1` from [Caffeine Simulator](https://github.com/ben-manes/caffeine/tree/master/simulator/src/main/resources/com/github/benmanes/caffeine/cache/simulator/parser) available traces and given as input to [access-times-trace-generator](https://github.com/himelbrand/access-times-trace-generator) to create this workload | `latency`
MULTI2 | [direct download](https://drive.google.com/file/d/1sAwRUuF-jT0D3yGiluoSndKv9tgKl6kj/view?usp=sharing) | Used `multi2` from [Caffeine Simulator](https://github.com/ben-manes/caffeine/tree/master/simulator/src/main/resources/com/github/benmanes/caffeine/cache/simulator/parser) available traces and given as input to [access-times-trace-generator](https://github.com/himelbrand/access-times-trace-generator) to create this workload | `latency`
Gradle | [direct download](https://drive.google.com/file/d/1ML7WqusQqnfKqS0IJOhVS-Y3QOF-6Niz/view?usp=sharing) | Used `build-cache.xz` from [Caffeine Simulator](https://github.com/ben-manes/caffeine/tree/master/simulator/src/main/resources/com/github/benmanes/caffeine/cache/simulator/parser) available traces and given as input to [access-times-trace-generator](https://github.com/himelbrand/access-times-trace-generator) to create this workload | `latency`
OLTP2 | [direct download](https://drive.google.com/file/d/1sZYwHSXLgXBINnQDmjtwjseSjpWzVXx0/view?usp=sharing) | Used `oltp2` from [UMassTraceRepository](http://traces.cs.umass.edu) and given as input to [access-times-trace-generator](https://github.com/himelbrand/access-times-trace-generator) to create this workload | `latency`
LINUX | [direct download](https://drive.google.com/file/d/17KYsv7-YHF6X9I5B-dir3HYr2DCybHV6/view?usp=sharing) | Used `linux2008-09-13` from [UMassTraceRepository](http://traces.cs.umass.edu) and given as input to [access-times-trace-generator](https://github.com/himelbrand/access-times-trace-generator) to create this workload | `latency`
MAC | [direct download](https://drive.google.com/file/d/1Qs6k-e4rD5pUL4ylXRSh5RMTji3C7YaY/view?usp=sharing) | Used one of the macbook traces from [UMassTraceRepository](http://traces.cs.umass.edu) and given as input to [access-times-trace-generator](https://github.com/himelbrand/access-times-trace-generator) to create this workload | `latency`
GCC | [direct download](https://drive.google.com/file/d/1gGF4-_yOP3DXw07JSdralRD-1I5PTpfD/view?usp=sharing) | Used `085.gcc.10m` from [NMSU Tracebase](http://tracebase.nmsu.edu/tracebase/traces) and given as input to [access-times-trace-generator](https://github.com/himelbrand/access-times-trace-generator) to create this workload | `latency`
WIKI | [direct download](https://drive.google.com/file/d/1jxxFYGx_gw-fxs3Synjxdg9ashMgbq1A/view?usp=sharing) |  Used `wiki.1190207720.gz` from [Wikibench access traces](http://www.wikibench.eu/?page_id=60) and given as input to [access-times-trace-generator](https://github.com/himelbrand/access-times-trace-generator) to create this workload | `latency`
SYSTOR17 | [SNIA IOTTA Repository](http://iotta.snia.org/traces/4964) | Combination of the files in `systor17-01.tar` | `snia-systor`
