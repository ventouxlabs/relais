/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

package cc.grepon.relais

import java.net.InetAddress

/**
 * Pure debounce policy for the service's LAN rebind observer.
 *
 * Network callbacks say that something changed, not that DHCP has finished. A candidate has to be
 * non-empty and unchanged for [stableForMs] before the service is allowed to replace its leaf and
 * listener group. That temporal observation is what distinguishes a transient drop from a genuine
 * network move; comparing SAN sets alone cannot do it.
 */
internal class RelaisLanRebindStability(private val stableForMs: Long) {
  private var candidate: List<String>? = null
  private var candidateSinceMs = 0L

  /** Returns the delay before this same observation may be applied, or null when it is empty. */
  fun observe(addresses: List<InetAddress>, nowMs: Long): Long? = observeLiterals(addresses.mapNotNull { it.hostAddress }, nowMs)

  internal fun observeLiterals(addresses: List<String>, nowMs: Long): Long? {
    val normalized = addresses.map { it.substringBefore('%') }.filter { it.isNotEmpty() }.distinct().sorted()
    if (normalized.isEmpty()) {
      clear()
      return null
    }
    if (normalized != candidate) {
      candidate = normalized
      candidateSinceMs = nowMs
      return stableForMs
    }
    return (stableForMs - (nowMs - candidateSinceMs)).coerceAtLeast(0L)
  }

  fun clear() {
    candidate = null
    candidateSinceMs = 0L
  }
}
