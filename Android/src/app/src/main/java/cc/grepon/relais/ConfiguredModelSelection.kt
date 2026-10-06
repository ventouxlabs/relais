/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 */
package cc.grepon.relais

import cc.grepon.relais.data.RelaisModelRef

/** One coherent provisioning input. The path is mutable output, not selection identity. */
internal data class ConfiguredModelSelection(
  val modelId: String,
  val ref: RelaisModelRef?,
  val persistedPath: String?,
) {
  fun sameIdentity(other: ConfiguredModelSelection): Boolean = modelId == other.modelId && ref == other.ref
}
