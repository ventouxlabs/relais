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
 * The three lifecycle facts that must be observed together when deciding whether a node is
 * reachable, still starting, or idle.
 *
 * [idleUnloaded] is true iff the last engine close was an idle-TTL release (#178):
 * `RelaisEngine.releaseIfIdle` publishes true immediately BEFORE it closes the engine (so the
 * writer's state sequence never has an instant with the engine gone and the flag clear; a reader's
 * own two-read tear remains — see the comment in `releaseIfIdle`), every other close —
 * `shutdown()`, i.e. STOP and the swap thread — publishes false after its close, and the start of every real init
 * attempt clears it too (`beginStartup(clearIdleUnloaded = true)`, which flips both facts in ONE
 * snapshot). It lives here rather than as a separately-read volatile for the reason #322/#327
 * exist: lifecycle facts combined across separate reads tear.
 */
data class RelaisLiveness(
  val listenersUp: Boolean = false,
  val startupInProgress: Boolean = false,
  val idleUnloaded: Boolean = false,
)

/**
 * Owns whole-snapshot publication. Each writer replaces the volatile reference while holding the
 * publisher lock, so a concurrent listener/startup update preserves the counterpart component.
 */
internal class RelaisLivenessPublisher(initial: RelaisLiveness = RelaisLiveness()) {
  @Volatile private var current = initial
  private var activeStartupOperations = if (initial.startupInProgress) 1 else 0

  val snapshot: RelaisLiveness get() = current

  @Synchronized
  fun publishListenersUp(value: Boolean) {
    current = current.copy(listenersUp = value)
  }

  /**
   * [clearIdleUnloaded] is passed ONLY by `RelaisEngine.ensureInitialized`'s real-init branch — the
   * one place that is, by construction, a real init attempt. Every other owner (the service's init
   * thread, the model-swap thread, `ensureInitializedInBackground`'s caller-side publish) begins
   * startup before it knows whether an init will happen at all: the swap thread bails on a missing
   * file and ends startup with nothing attempted, and clearing idle there would make a healthy idle
   * node read STARTING, trip the stall detector, and get restarted for a failed operator action.
   */
  @Synchronized
  fun beginStartup(clearIdleUnloaded: Boolean = false) {
    activeStartupOperations += 1
    current = current.copy(
      startupInProgress = true,
      idleUnloaded = if (clearIdleUnloaded) false else current.idleUnloaded,
    )
  }

  @Synchronized
  fun publishIdleUnloaded(value: Boolean) {
    current = current.copy(idleUnloaded = value)
  }

  @Synchronized
  fun endStartup() {
    check(activeStartupOperations > 0) { "endStartup without a matching beginStartup" }
    activeStartupOperations -= 1
    current = current.copy(startupInProgress = activeStartupOperations > 0)
  }
}

/**
 * Process-wide node-liveness publication. A fresh process starts with all three facts false: no
 * listener is bound, no lifecycle operation is underway, and nothing has been idle-released until
 * the service or engine publishes one.
 *
 * Readers must take [snapshot] once before deriving a composite state. Reading separate volatile
 * fields used to permit a poll to combine `listenersUp=false` from before a bind with
 * `startupInProgress=false` from after it, rendering a healthy node as OFFLINE (#322).
 */
object RelaisLivenessState {
  private val publisher = RelaisLivenessPublisher()

  val snapshot: RelaisLiveness get() = publisher.snapshot

  fun publishListenersUp(value: Boolean) = publisher.publishListenersUp(value)

  fun beginStartup(clearIdleUnloaded: Boolean = false) = publisher.beginStartup(clearIdleUnloaded)

  fun publishIdleUnloaded(value: Boolean) = publisher.publishIdleUnloaded(value)

  fun endStartup() = publisher.endStartup()
}
