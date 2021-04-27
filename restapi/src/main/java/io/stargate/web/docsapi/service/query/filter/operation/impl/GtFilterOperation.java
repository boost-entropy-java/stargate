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

package io.stargate.web.docsapi.service.query.filter.operation.impl;

import io.stargate.db.query.Predicate;
import java.util.Optional;
import org.immutables.value.Value;

/** Greater than or equal filter operation. */
@Value.Style(visibility = Value.Style.ImplementationVisibility.PACKAGE)
@Value.Immutable(singleton = true)
public abstract class GtFilterOperation extends NotNullValueFilterOperation {

  public static final String RAW_VALUE = "$gt";

  /** @return Singleton instance */
  public static GtFilterOperation of() {
    return ImmutableGtFilterOperation.of();
  }

  /** {@inheritDoc} */
  @Override
  public String getRawValue() {
    return RAW_VALUE;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<Predicate> getDatabasePredicate() {
    return Optional.of(Predicate.GT);
  }

  /** {@inheritDoc} */
  @Override
  public boolean isSatisfied(int compareValue) {
    return compareValue > 0;
  }
}
