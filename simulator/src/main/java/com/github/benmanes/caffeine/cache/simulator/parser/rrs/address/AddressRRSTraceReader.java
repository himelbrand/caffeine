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
package com.github.benmanes.caffeine.cache.simulator.parser.rrs.address;

import com.github.benmanes.caffeine.cache.simulator.Characteristics;
import com.github.benmanes.caffeine.cache.simulator.parser.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A reader for the trace files of application address instructions, provided by
 * <a href="http://cseweb.ucsd.edu/classes/fa07/cse240a/project1.html">UC SD</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddressRRSTraceReader extends TextTraceReader {

  public AddressRRSTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Stream<AccessEvent> events() throws IOException {
    return lines()
        .map(line -> line.split(" ", 6))
        .map(split -> split[1] + " " + split[3] + " " + split[4] + " " + split[5])
        .map(address_rrs -> address_rrs.substring(2).split(" ", 4))
        .map(address_rrs -> new AccessEvent.AccessEventBuilder(Long.parseLong(address_rrs[0], 16))
                                .setSize(Long.parseLong(address_rrs[1]))
                                .setCacheReadRate(Long.parseLong(address_rrs[2]))
                                .setMissReadRate(Long.parseLong(address_rrs[3])).build());
  }

  @Override
  public Set<Characteristics> getCharacteristicsSet() {
    return ImmutableSet.of(Characteristics.KEY,Characteristics.CACHE_READ_RATE,Characteristics.MISS_READ_RATE,Characteristics.SIZE);
  }
}
