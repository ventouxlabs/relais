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

/**
 * The two lifecycle facts that must be observed together when deciding whether a node is reachable
 * or still starting.
 */
data class RelaisLiveness(
  val listenersUp: Boolean = false,
  val startupInProgress: Boolean = false,
)

/**
 * Owns whole-snapshot publication. Each writer replaces the volatile reference while holding the
 * publisher lock, so a concurrent listener/startup update preserves the counterpart component.
 */
internal class RelaisLivenessPublisher(initial: RelaisLiveness = RelaisLiveness()) {
  @Volatile private var current = initial

  val snapshot: RelaisLiveness get() = current

  @Synchronized
  fun publishListenersUp(value: Boolean) {
    current = current.copy(listenersUp = value)
  }

  @Synchronized
  fun publishStartupInProgress(value: Boolean) {
    current = current.copy(startupInProgress = value)
  }
}

/**
 * Process-wide node-liveness publication. A fresh process starts with both facts false: no listener
 * is bound and no lifecycle operation is underway until the service or engine publishes one.
 *
 * Readers must take [snapshot] once before deriving a composite state. Reading separate volatile
 * fields used to permit a poll to combine `listenersUp=false` from before a bind with
 * `startupInProgress=false` from after it, rendering a healthy node as OFFLINE (#322).
 */
object RelaisLivenessState {
  private val publisher = RelaisLivenessPublisher()

  val snapshot: RelaisLiveness get() = publisher.snapshot

  fun publishListenersUp(value: Boolean) = publisher.publishListenersUp(value)

  fun publishStartupInProgress(value: Boolean) = publisher.publishStartupInProgress(value)
}
