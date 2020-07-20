/*
 * Copyright 2019 Omri Himelbrand. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.dns;

    import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
    import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
    import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
    import com.google.common.collect.ImmutableSet;
    import com.google.common.hash.Hashing;

    import java.io.IOException;
    import java.util.Set;
    import java.util.stream.Stream;

/**
 * A reader for the trace files of DNS lookup times. Traces & format can be found at: git repo
 * address
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
public final class DNSTraceReader extends TextTraceReader {

  public DNSTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Set<Characteristic> characteristics() {
    return ImmutableSet.of();
  }

  @Override
  public Stream<AccessEvent> events() throws IOException {
    return lines()
        .map(line -> line.split(" ", 3))
        .map(
            split ->
                AccessEvent.forKeyAndPenalties(
                    Hashing.murmur3_128().hashUnencodedChars(split[0]).asLong(),
                    Double.parseDouble(split[1]),
                    Double.parseDouble(split[2])));
  }
}
