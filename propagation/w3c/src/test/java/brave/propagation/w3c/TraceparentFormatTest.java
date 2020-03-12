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

import brave.internal.Platform;
import brave.propagation.TraceContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static brave.propagation.w3c.TraceparentFormat.parseTraceparentFormat;
import static brave.propagation.w3c.TraceparentFormat.writeTraceparentFormat;
import static brave.propagation.w3c.TraceparentFormat.writeTraceparentFormatAsBytes;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.when;

@RunWith(PowerMockRunner.class)
// Added to declutter console: tells power mock not to mess with implicit classes we aren't testing
@PowerMockIgnore({"org.apache.logging.*", "javax.script.*"})
@PrepareForTest({Platform.class, TraceparentFormat.class})
public class TraceparentFormatTest {
  String traceId = "00000000000000090000000000000001";
  String spanId = "0000000000000003";
  TraceContext sampled =
    TraceContext.newBuilder().traceIdHigh(9).traceId(1).spanId(3).sampled(true).build();
  TraceContext debug = sampled.toBuilder().debug(true).build();
  TraceContext unsampled = sampled.toBuilder().sampled(false).build();

  Platform platform = mock(Platform.class);

  @Before public void setup() {
    mockStatic(Platform.class);
    when(Platform.get()).thenReturn(platform);
  }

  /** Either we asserted on the log messages or there weren't any */
  @After public void ensureNothingLogged() {
    verifyNoMoreInteractions(platform);
  }

  @Test public void writeTraceparentFormat_unsampled() {
    assertThat(writeTraceparentFormat(unsampled))
      .isEqualTo("00-" + traceId + "-" + spanId + "-00")
      .isEqualTo(new String(writeTraceparentFormatAsBytes(unsampled), UTF_8));
  }

  @Test public void writeTraceparentFormat_sampled() {
    assertThat(writeTraceparentFormat(sampled))
      .isEqualTo("00-" + traceId + "-" + spanId + "-01")
      .isEqualTo(new String(writeTraceparentFormatAsBytes(sampled), UTF_8));
  }

  /** debug is closest to sampled */
  @Test public void writeTraceparentFormat_debug() {
    assertThat(writeTraceparentFormat(debug))
      .isEqualTo("00-" + traceId + "-" + spanId + "-01")
      .isEqualTo(new String(writeTraceparentFormatAsBytes(debug), UTF_8));
  }

  @Test public void parseTraceparentFormat_middleOfString() {
    String input = "traceparent=00-" + traceId + "-" + spanId + "-00,";
    assertThat(parseTraceparentFormat(input, 12, input.length() - 1))
      .isEqualToComparingFieldByField(unsampled);
  }

  @Test public void parseTraceparentFormat_middleOfString_incorrectOffset() {
    String input = "b2=foo,b3=d,b4=bar";
    assertThat(parseTraceparentFormat(input, 10, 12))
      .isNull(); // instead of raising exception

    verify(platform).log("Invalid input: truncated", null);
  }

  @Test public void parseTraceparentFormat_idsunsampled() {
    assertThat(parseTraceparentFormat("00-" + traceId + "-" + spanId + "-00"))
      .isEqualToComparingFieldByField(unsampled);
  }

  @Test public void parseTraceparentFormat_ascii() {
    assertThat(parseTraceparentFormat("00-" + traceId + "-" + spanId + "-💩"))
      .isNull(); // instead of crashing

    verify(platform)
      .log("Invalid input: neither hyphen, nor lower-hex at offset {0}", 53, null);
  }

  @Test public void parseTraceparentFormat_sampledCorrupt() {
    assertThat(parseTraceparentFormat("00-" + traceId + "-" + spanId + "-f0"))
      .isNull(); // instead of crashing

    verify(platform)
      .log("Invalid input: expected 00 or 01 for flags at offset {0}", 53, null);
  }

  @Test public void parseTraceparentFormat_malformed_traceId() {
    assertThat(parseTraceparentFormat("00-00000000000000000000000000000000-" + spanId + "-00"))
      .isNull(); // instead of raising exception

    verify(platform).log("Invalid input: expected a 32 lower hex trace ID at offset {0}", 3, null);
  }

  @Test public void parseTraceparentFormat_malformed_id() {
    assertThat(
      parseTraceparentFormat("00-" + traceId + "-0000000000000000-00"))
      .isNull(); // instead of raising exception

    verify(platform).log("Invalid input: expected a 16 lower hex parent ID at offset {0}", 36,
      null);
  }

  @Test public void parseTraceparentFormat_malformed() {
    assertThat(parseTraceparentFormat("not-a-tumor"))
      .isNull(); // instead of raising exception

    verify(platform).log("Invalid input: truncated", null);
  }

  @Test public void parseTraceparentFormat_malformed_uuid() {
    assertThat(parseTraceparentFormat("b970dafd-0d95-40aa-95d8-1d8725aebe40"))
      .isNull(); // instead of raising exception

    verify(platform).log("Invalid input: truncated", null);
  }

  @Test public void parseTraceparentFormat_empty() {
    assertThat(parseTraceparentFormat("")).isNull();

    verify(platform).log("Invalid input: empty", null);
  }

  @Test public void parseTraceparentFormat_truncated() {
    String b3 = "00-" + traceId + "-" + spanId + "-";
    assertThat(parseTraceparentFormat(b3))
      .withFailMessage("expected " + b3 + " to not parse").isNull();
    verify(platform).log("Invalid input: truncated", null);
    reset(platform);
  }

  @Test public void parseTraceparentFormat_traceIdTooLong() {
    assertThat(
      parseTraceparentFormat("00-" + traceId + "a" + "-" + spanId.substring(0, 15) + "-00"))
      .isNull(); // instead of raising exception

    verify(platform).log("Invalid input: trace ID is too long", null);
  }

  @Test public void parseTraceparentFormat_parentIdTooLong() {
    assertThat(parseTraceparentFormat("00-" + traceId + "-" + spanId + "a-0"))
      .isNull(); // instead of raising exception

    verify(platform).log("Invalid input: parent ID is too long", null);
  }
}
