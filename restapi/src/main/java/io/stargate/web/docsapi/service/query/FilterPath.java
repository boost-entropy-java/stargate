/*
 * Copyright The Stargate Authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.stargate.web.docsapi.service.query;

import java.util.Collections;
import java.util.List;
import org.immutables.value.Value;

/** Contains path information for a filter operation. */
@Value.Immutable
public interface FilterPath {

  /** @return The complete path to the field, including the field name as the last element. */
  @Value.Parameter
  List<String> getFullPath();

  /** @return The name of the field. Effectively, the last element of {@link #getPath()}. */
  default String getField() {
    List<String> path = getFullPath();
    if (null == path || path.isEmpty()) {
      return null;
    } else {
      int length = path.size();
      return path.get(length - 1);
    }
  }

  /** @return The path without the field. Effectively, the last element of {@link #getPath()}. */
  default List<String> getPath() {
    List<String> path = getFullPath();
    if (null != path && path.size() > 1) {
      int length = path.size();
      return path.subList(0, length - 1);
    } else {
      return Collections.emptyList();
    }
  }

  /** @return Joins {@link #getPath()} with dot. Returns empty string if there are no paths. */
  default String getPathString() {
    List<String> path = getPath();
    if (null != path) {
      return String.join(".", path);
    } else {
      return "";
    }
  }

  /** @return Returns complete JSON path as String to the field, including the field. */
  default String getFullFieldPathString() {
    List<String> path = getFullPath();
    if (null != path) {
      return String.join(".", path);
    } else {
      return "";
    }
  }
}
