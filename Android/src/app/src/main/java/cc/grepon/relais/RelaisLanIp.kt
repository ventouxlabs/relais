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

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * LAN IPv4 discovery for the `/v1/clientconfig` base URL (extracted from `RelaisHttpServer` — #173).
 * No UI/Context dependency, so it's a pure network-interface helper.
 */
internal object RelaisLanIp {

  /**
   * The LAN IPv4 to advertise. Prefers the accepting socket's local address (the exact interface this
   * connection arrived on — the most accurate value for the URL the client should call back). Falls
   * back to [lanIpv4] only if that address is missing, loopback, a wildcard, or non-IPv4 (e.g. the
   * HTTPS listener bound to 0.0.0.0).
   */
  fun localLanIp(sock: java.net.Socket): String {
    val local = sock.localAddress
    if (local is Inet4Address && !local.isLoopbackAddress && !local.isAnyLocalAddress) {
      local.hostAddress?.let { return it }
    }
    return lanIpv4()
  }

  /**
   * Best-effort LAN IPv4 (prefers wlan), used only as a fallback when the accepting socket's local
   * address is unavailable/loopback/wildcard. Kept small + UI-free so the server has no UI dependency.
   * Returns "0.0.0.0" if nothing resolves.
   */
  fun lanIpv4(): String =
    runCatching {
      val nis = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
      val ordered = nis.sortedByDescending { it.name.startsWith("wlan") }
      for (ni in ordered) {
        for (addr in ni.inetAddresses) {
          if (addr is Inet4Address && !addr.isLoopbackAddress) return@runCatching addr.hostAddress ?: continue
        }
      }
      "0.0.0.0"
    }.getOrDefault("0.0.0.0")

  /**
   * Every non-loopback address the node is reachable at, for the leaf certificate's SAN set
   * (feature-18 T1). Distinct from [lanIpv4], which picks **one** IPv4 for a display URL — a
   * certificate has to cover them all or hostname verification fails on whichever interface the
   * client actually used.
   *
   * Overlay interfaces (`tun*`/`wg*`/`ts*`) are deliberately **not** filtered out: including a
   * Tailscale/WireGuard address is what lets an overlay client verify the node with no extra
   * machinery, which is the whole of alternative (b) in the plan. The cost is that the SAN list
   * discloses those addresses pre-auth to anyone who can complete a `ClientHello` (threat-model
   * delta 10) — accepted deliberately, since an attacker already on the LAN learns the node's
   * address by scanning it anyway.
   *
   * **IPv6 is excluded entirely, and that is a deliberate scope limit rather than an oversight.**
   * Both listeners bind IPv4 only (`0.0.0.0:8443` and `127.0.0.1:8080`), so an IPv6 address here
   * would end up certified and unreachable — the certificate would describe an endpoint no client
   * can connect to, and the status page would report it as covered while nothing served it.
   *
   * The consequence, which the docs state rather than hide: **an IPv6-only client cannot reach the
   * node.** That is true of this release regardless of the certificate; excluding these addresses
   * makes the certificate honest about it rather than removing a capability.
   *
   * Binding dual-stack and restoring IPv6 SANs is a tracked follow-up, and the two must land
   * together — either alone reproduces the same mismatch from the other side.
   *
   * The `isUp` filter is load-bearing and not cosmetic. A down interface contributes a stale
   * address, so the SAN set differs on the next start, [RelaisCertMint.needsReissue] fires, and the
   * node churns its certificate on every restart for no reason.
   *
   * Sorted by `hostAddress` so the result is **totally** ordered: the re-issue check compares SAN
   * sets, and an unstable order would thrash the same way.
   *
   * An empty return is meaningful, not a failure: on a `BOOT_COMPLETED` start the service comes up
   * before DHCP completes, so the leaf is minted loopback-only and the node needs a restart once the
   * LAN is up. (Recovering from that automatically was built and cut — see the tracked follow-up.)
   * Do not paper over it with a `0.0.0.0` fallback — a wildcard is not an address anything can be
   * reached at.
   */
  fun allLanAddresses(): List<InetAddress> =
    runCatching {
      NetworkInterface.getNetworkInterfaces()
        .toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        // IPv4 only: see the KDoc. An Inet6Address here would be certified and unreachable.
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress }
        .distinctBy { it.hostAddress }
        .sortedBy { it.hostAddress }
    }.getOrDefault(emptyList())
}
