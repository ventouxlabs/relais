/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

/**
 * Process-wide, Context-free holder for "what is the relais-init thread doing right now" (AUDIT.md
 * Q6). [RelaisNodeService]'s init thread writes it as boot progresses through
 * [RelaisModelProvisioner]; [RelaisControlActivity]'s 1s poll loop reads it to render the STARTING
 * phase line (`resolving model…` / `downloading model · 43% · 1.2/2.8 GB` / `loading engine…`) via
 * [computeControlPanelState]. Never a bare "starting…" (P6).
 *
 * `@Volatile` fields (mirrors [RelaisLivenessState.snapshot]'s publication pattern) — cross-thread visibility
 * without a lock is enough for a 1s-polled progress readout; no invariant spans more than one field.
 */
object RelaisNodeProgress {
  @Volatile var phase: ProvisionPhase = ProvisionPhase.IDLE
  @Volatile var downloadReceivedBytes: Long = 0L
  @Volatile var downloadTotalBytes: Long = 0L

  /** Reset to the pre-boot state — called at the start and end of each `relais-init` attempt. */
  fun reset() {
    phase = ProvisionPhase.IDLE
    downloadReceivedBytes = 0L
    downloadTotalBytes = 0L
  }

  internal fun onDownloadProgress(receivedBytes: Long, totalBytes: Long) {
    phase = ProvisionPhase.DOWNLOADING
    downloadReceivedBytes = receivedBytes
    downloadTotalBytes = totalBytes
  }
}
