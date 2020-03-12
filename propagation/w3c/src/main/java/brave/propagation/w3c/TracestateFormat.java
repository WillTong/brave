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

import brave.internal.Nullable;

import static brave.propagation.w3c.TraceparentFormat.FORMAT_LENGTH;

// TODO: this format changed since writing. Check for drift then not the spec version
final class TracestateFormat {
  final String stateName;
  final int stateNameLength;
  final int stateEntryLength;

  TracestateFormat(String stateName) {
    this.stateName = stateName;
    this.stateNameLength = stateName.length();
    this.stateEntryLength = stateNameLength + 1 /* = */ + FORMAT_LENGTH;
  }

  enum Op {
    THIS_STATE,
    OTHER_STATE
  }

  interface Handler {
    boolean onThisState(CharSequence tracestateString, int pos);
  }

  String write(String thisState, CharSequence otherState) {
    int extraLength = otherState == null ? 0 : otherState.length();

    char[] result;
    if (extraLength == 0) {
      result = new char[stateEntryLength];
    } else {
      result = new char[stateEntryLength + 1 /* , */ + extraLength];
    }

    int pos = 0;
    for (int i = 0; i < stateNameLength; i++) {
      result[pos++] = stateName.charAt(i);
    }
    result[pos++] = '=';

    for (int i = 0, len = thisState.length(); i < len; i++) {
      result[pos++] = thisState.charAt(i);
    }

    if (extraLength > 0) { // Append others after ours
      result[pos++] = ',';
      for (int i = 0; i < extraLength; i++) {
        result[pos++] = otherState.charAt(i);
      }
    }
    return new String(result);
  }

  // TODO: characters were added to the valid list, so it is possible this impl no longer works
  @Nullable CharSequence parseAndReturnOtherState(String tracestateString, Handler handler) {
    StringBuilder currentString = new StringBuilder(), otherState = null;
    Op op;
    OUTER:
    for (int i = 0, length = tracestateString.length(); i < length; i++) {
      char c = tracestateString.charAt(i);
      if (c == ' ') continue; // trim whitespace
      if (c == '=') { // we reached a field name
        if (++i == length) break; // skip '=' character
        if (currentString.indexOf(stateName) == 0) {
          op = Op.THIS_STATE;
        } else {
          op = Op.OTHER_STATE;
          if (otherState == null) otherState = new StringBuilder();
          otherState.append(',').append(currentString);
        }
        currentString.setLength(0);
      } else {
        currentString.append(c);
        continue;
      }
      // no longer whitespace
      switch (op) {
        case OTHER_STATE:
          otherState.append(c);
          while (i < length && (c = tracestateString.charAt(i)) != ',') {
            otherState.append(c);
            i++;
          }
          break;
        case THIS_STATE:
          if (!handler.onThisState(tracestateString, i)) {
            break OUTER;
          }
          break;
      }
    }
    return otherState;
  }
}
