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
package com.github.benmanes.caffeine.cache.simulator.parser.arc;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

import java.io.IOException;
import java.util.Set;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.common.collect.Sets;

/**
 * A reader for the trace files provided by the authors of the ARC algorithm. See
 * <a href="http://researcher.watson.ibm.com/researcher/view_person_subpage.php?id=4700">traces</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ArcTraceReader extends TextTraceReader {

  public ArcTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Sets.immutableEnumSet(WEIGHTED);
  }  

  @Override
  public Stream<AccessEvent> events() throws IOException {
    return lines().flatMap(line -> {
      String[] array = line.split(" ", 3);
      long startBlock = Long.parseLong(array[0]);
      int sequence = Integer.parseInt(array[1]);
      return LongStream.range(startBlock, startBlock + sequence)
          .mapToObj(key -> AccessEvent.forKeyAndWeight(key, 512));
    });
  }
}
