/*
 * Copyright 2013-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.propagation.w3c;

import brave.propagation.Propagation.Getter;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.propagation.w3c.TraceContextPropagation.Extra;
import java.util.Collections;
import java.util.List;

import static brave.propagation.w3c.TraceparentFormat.parseTraceparentFormat;

final class TraceContextExtractor<C, K> implements Extractor<C> {
  final Getter<C, K> getter;
  final K tracestateKey;
  final TracestateFormat tracestateFormat;

  TraceContextExtractor(TraceContextPropagation<K> propagation, Getter<C, K> getter) {
    this.getter = getter;
    this.tracestateKey = propagation.tracestateKey;
    this.tracestateFormat = new TracestateFormat(propagation.stateName);
  }

  @Override public TraceContextOrSamplingFlags extract(C carrier) {
    if (carrier == null) throw new NullPointerException("carrier == null");
    String tracestateString = getter.get(carrier, tracestateKey);
    if (tracestateString == null) return EMPTY;

    TraceparentFormatHandler handler = new TraceparentFormatHandler();
    CharSequence otherState = tracestateFormat.parseAndReturnOtherState(tracestateString, handler);

    List<Object> extra;
    if (otherState == null) {
      extra = DEFAULT_EXTRA;
    } else {
      Extra e = new Extra();
      e.otherState = otherState;
      extra = Collections.singletonList(e);
    }

    if (handler.context == null) {
      if (extra == DEFAULT_EXTRA) return EMPTY;
      return TraceContextOrSamplingFlags.newBuilder()
        .extra(extra)
        .samplingFlags(SamplingFlags.EMPTY)
        .build();
    }
    return TraceContextOrSamplingFlags.newBuilder().context(handler.context).extra(extra).build();
  }

  static final class TraceparentFormatHandler implements TracestateFormat.Handler {
    TraceContext context;

    @Override
    public boolean onThisState(CharSequence tracestateString, int beginIndex, int endIndex) {
      context = parseTraceparentFormat(tracestateString, beginIndex, endIndex);
      return context != null;
    }
  }

  /** When present, this context was created with TracestatePropagation */
  static final Extra MARKER = new Extra();

  static final List<Object> DEFAULT_EXTRA = Collections.singletonList(MARKER);
  static final TraceContextOrSamplingFlags EMPTY =
    TraceContextOrSamplingFlags.EMPTY.toBuilder().extra(DEFAULT_EXTRA).build();
}
