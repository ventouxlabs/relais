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
 * Whether the node's listeners are actually up — the signal every user-visible surface needs and
 * none of them had (feature-18 codex round 18).
 *
 * **Deliberately not a field on [RelaisEngine].** Engine readiness and node reachability are
 * different facts, and conflating them is precisely the defect this exists to fix: when a `:8443`
 * bind failed, the service kept the engine resident (correctly — it initialised fine, and reloading
 * it costs seconds of model load) and tore both listeners down, but the control panel, the QS tile
 * and the widget all keyed on `RelaisEngine.isReady` and so reported **LIVE** for a node nothing
 * could reach. Storing this next to the engine's flags would invite the same conflation back.
 *
 * It is the same proxy that was fixed one level down, when `httpsServer != null` became
 * `isListening`: a field being assigned is not a socket being open, and an engine having
 * initialised is not a node being reachable. Ask the artefact.
 *
 * The false LIVE was worse than a wrong label. `shouldDispatchStartup` and the watchdog both read
 * the node as healthy, so the automatic retry never fired — the state that needed recovery was the
 * state preventing it, and the user had to stop and start by hand even after the port cleared.
 *
 * Process-lifetime and `@Volatile`, mirroring [RelaisNodeProgress]: written by
 * [RelaisNodeService] on every transition that can change reachability, read from other threads
 * (QS tile, widget, watchdog receiver) without locking. A fresh process starts false, which is
 * correct — nothing is listening until a service starts one.
 */
object RelaisListenerState {

  /**
   * True only while every listener the node needs is bound and open.
   *
   * Written exclusively by `RelaisNodeService.refreshListenerState()`, which derives it from the
   * live sockets via `isListening` so there is one expression rather than two that can drift.
   */
  @Volatile var listenersUp: Boolean = false
}
