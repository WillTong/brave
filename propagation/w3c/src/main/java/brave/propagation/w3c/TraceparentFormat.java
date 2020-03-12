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

import brave.internal.HexCodec;
import brave.internal.Nullable;
import brave.internal.Platform;
import brave.propagation.TraceContext;
import java.nio.ByteBuffer;

import static brave.internal.HexCodec.writeHexLong;

/** Implements https://w3c.github.io/trace-context/#traceparent-header */
final class TraceparentFormat {
  static final int FORMAT_LENGTH = 3 + 32 + 1 + 16 + 3; // 00-traceid128-spanid-01

  /**
   * Writes all B3 defined fields in the trace context, except {@link TraceContext#parentIdAsLong()
   * parent ID}, to a hyphen delimited string.
   *
   * <p>This is appropriate for receivers who understand "b3" single header format, and always do
   * work in a child span. For example, message consumers always do work in child spans, so message
   * producers can use this format to save bytes on the wire. On the other hand, RPC clients should
   * use {@link #writeTraceparentFormat(TraceContext)} instead, as RPC servers often share a span ID
   * with the client.
   */
  public static String writeTraceparentFormat(TraceContext context) {
    char[] buffer = getCharBuffer();
    int length = writeTraceparentFormat(context, buffer);
    return new String(buffer, 0, length);
  }

  /**
   * Like {@link #writeTraceparentFormat(TraceContext)}, but for carriers with byte array or byte
   * buffer values. For example, {@link ByteBuffer#wrap(byte[])} can wrap the result.
   */
  public static byte[] writeTraceparentFormatAsBytes(TraceContext context) {
    char[] buffer = getCharBuffer();
    int length = writeTraceparentFormat(context, buffer);
    return asciiToNewByteArray(buffer, length);
  }

  static int writeTraceparentFormat(TraceContext context, char[] result) {
    int pos = 0;
    result[pos++] = '0';
    result[pos++] = '0';
    result[pos++] = '-';
    long traceIdHigh = context.traceIdHigh();
    writeHexLong(result, pos, traceIdHigh);
    pos += 16;
    writeHexLong(result, pos, context.traceId());
    pos += 16;
    result[pos++] = '-';
    writeHexLong(result, pos, context.spanId());
    pos += 16;

    Boolean sampled = context.sampled();
    if (sampled != null) {
      result[pos++] = '-';
      result[pos++] = '0';
      result[pos++] = sampled ? '1' : '0';
    }

    return pos;
  }

  @Nullable
  public static TraceContext parseTraceparentFormat(CharSequence parent) {
    return parseTraceparentFormat(parent, 0, parent.length());
  }

  /**
   * @param beginIndex the start index, inclusive
   * @param endIndex the end index, exclusive
   */
  @Nullable
  public static TraceContext parseTraceparentFormat(CharSequence parent, int beginIndex,
    int endIndex) {
    int length = endIndex - beginIndex;

    if (length == 0) {
      Platform.get().log("Invalid input: empty", null);
      return null;
    }

    if (length < FORMAT_LENGTH) {
      Platform.get().log("Invalid input: truncated", null);
      return null;
    } else if (length > FORMAT_LENGTH) {
      Platform.get().log("Invalid input: too long", null);
      return null;
    }

    // Cheaply check for only ASCII characters. This allows for more precise messages later, but
    // kicks out early on data such as unicode.
    for (int i = beginIndex; i < endIndex; i++) {
      char c = parent.charAt(i);
      if (c != '-' && ((c < '0' || c > '9') && (c < 'a' || c > 'f'))) {
        Platform.get().log("Invalid input: neither hyphen, nor lower-hex at offset {0}", i, null);
        return null;
      }
    }

    int pos = beginIndex;
    int version = parseUnsigned16BitLowerHex(parent, pos);
    if (version == -1) {
      Platform.get().log("Invalid input: expected version at offset {0}", beginIndex, null);
      return null;
    }

    if (version != 0) {
      // Parsing higher versions is a SHOULD not MUST
      // https://w3c.github.io/trace-context/#versioning-of-traceparent
      Platform.get().log("Invalid input: expected version 00 at offset {0}", beginIndex, null);
      return null;
    }
    pos += 3;

    long traceIdHigh = tryParse16HexCharacters(parent, pos, endIndex);
    pos += 16;
    long traceId = tryParse16HexCharacters(parent, pos, endIndex);
    pos += 16;

    if (traceIdHigh == 0L && traceId == 0L) {
      Platform.get()
        .log("Invalid input: expected a 32 lower hex trace ID at offset {0}", pos - 32, null);
      return null;
    }

    if (isLowerHex(parent.charAt(pos))) {
      Platform.get().log("Invalid input: trace ID is too long", null);
      return null;
    }

    if (!checkHyphen(parent, pos++)) return null;

    // Confusingly, the spec calls the span ID field parentId
    // https://w3c.github.io/trace-context/#parent-id
    long parentId = tryParse16HexCharacters(parent, pos, endIndex);
    if (parentId == 0L) {
      Platform.get()
        .log("Invalid input: expected a 16 lower hex parent ID at offset {0}", pos, null);
      return null;
    }
    pos += 16; // parentId

    if (isLowerHex(parent.charAt(pos))) {
      Platform.get().log("Invalid input: parent ID is too long", null);
      return null;
    }

    // If the sampling field is present, we'll have a delimiter 2 characters from now. Ex "-1"
    if (endIndex == pos + 1) {
      Platform.get().log("Invalid input: truncated", null);
      return null;
    }
    if (!checkHyphen(parent, pos++)) return null;

    boolean sampled;
    // Only one flag is defined: sampled
    // https://w3c.github.io/trace-context/#sampled-flag
    Boolean maybeSampled = parseSampledFromFlags(parent, pos);
    if (maybeSampled == null) return null;
    sampled = maybeSampled;

    return TraceContext.newBuilder()
      .traceIdHigh(traceIdHigh)
      .traceId(traceId)
      .spanId(parentId)
      .sampled(sampled)
      .build();
  }

  static boolean checkHyphen(CharSequence b3, int pos) {
    if (b3.charAt(pos) == '-') return true;
    Platform.get().log("Invalid input: expected a hyphen(-) delimiter at offset {0}", pos, null);
    return false;
  }

  static long tryParse16HexCharacters(CharSequence lowerHex, int index, int end) {
    int endIndex = index + 16;
    if (endIndex > end) return 0L;
    return HexCodec.lenientLowerHexToUnsignedLong(lowerHex, index, endIndex);
  }

  @Nullable static Boolean parseSampledFromFlags(CharSequence parent, int pos) {
    char sampledChar = parent.charAt(pos) == '0' ? parent.charAt(pos + 1) : '?';
    if (sampledChar == '1') {
      return true;
    } else if (sampledChar == '0') {
      return false;
    } else {
      logInvalidFlags(pos);
    }
    return null;
  }

  static void logInvalidFlags(int pos) {
    Platform.get().log("Invalid input: expected 00 or 01 for flags at offset {0}", pos, null);
  }

  /** Returns -1 if it wasn't hex */
  static int parseUnsigned16BitLowerHex(CharSequence lowerHex, int pos) {
    int result = 0;
    for (int i = 0; i < 2; i++) {
      char c = lowerHex.charAt(pos + i);
      result <<= 4;
      if (c >= '0' && c <= '9') {
        result |= c - '0';
      } else if (c >= 'a' && c <= 'f') {
        result |= c - 'a' + 10;
      } else {
        return -1;
      }
    }
    return result;
  }

  static byte[] asciiToNewByteArray(char[] buffer, int length) {
    byte[] result = new byte[length];
    for (int i = 0; i < length; i++) {
      result[i] = (byte) buffer[i];
    }
    return result;
  }

  static boolean isLowerHex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
  }

  static final ThreadLocal<char[]> CHAR_BUFFER = new ThreadLocal<>();

  static char[] getCharBuffer() {
    char[] charBuffer = CHAR_BUFFER.get();
    if (charBuffer == null) {
      charBuffer = new char[FORMAT_LENGTH];
      CHAR_BUFFER.set(charBuffer);
    }
    return charBuffer;
  }

  TraceparentFormat() {
  }
}
