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

import java.net.InetAddress
import org.bouncycastle.asn1.x509.GeneralName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for [RelaisCertMint.buildSanList] (feature-18 T3).
 *
 * `buildSanList` takes a plain `List<InetAddress>` rather than reading the interfaces itself
 * precisely so it can be tested this way — `NetworkInterface` is final and has no public
 * constructor, so anything that enumerates interfaces is untestable off a device.
 *
 * **Consequence, stated rather than hidden:** the `isUp` and IPv6-link-local filters live in
 * [RelaisLanIp.allLanAddresses], which enumerates real interfaces, so **neither is covered here or
 * anywhere else in the JVM lane.** They are covered by `CertReissueProbe` on hardware. The
 * plan's `downInterfaceExcluded` and `excludesIpv6LinkLocal` cases are not writable as unit tests;
 * writing something that exercised a hand-rolled filter instead would assert against a copy of the
 * logic rather than the shipped one.
 */
class RelaisCertSanTest {

  @Test
  fun `loopback and localhost are present even with no LAN addresses`() {
    val sans = RelaisCertMint.buildSanList(emptyList())

    assertEquals(fixedEntries, sans.map { render(it) })
  }

  @Test
  fun `an IP literal is tagged iPAddress, never dNSName`() {
    val sans = RelaisCertMint.buildSanList(listOf(InetAddress.getByName("192.168.1.40")))
    val entry = sans.single { render(it) == "192.168.1.40" }

    // A dNSName holding an IP literal is silently ignored by every verifier — the failure this
    // assertion exists to catch produces a cert that looks correct and verifies nowhere.
    assertEquals(GeneralName.iPAddress, entry.tagNo)
  }

  @Test
  fun `a duplicated address appears once`() {
    val addr = InetAddress.getByName("192.168.1.40")
    val sans = RelaisCertMint.buildSanList(listOf(addr, addr, addr))

    assertEquals(1, sans.count { render(it) == "192.168.1.40" })
  }

  @Test
  fun `an address already in the fixed set is not repeated`() {
    val sans = RelaisCertMint.buildSanList(listOf(InetAddress.getByName("127.0.0.1")))

    assertEquals(1, sans.count { render(it) == "127.0.0.1" })
  }

  @Test
  fun `the order is total, so a reshuffled input mints an identical SAN set`() {
    val addrs =
      listOf("192.168.1.40", "10.0.0.7", "172.16.3.9", "100.101.102.103", "192.168.1.41")
        .map { InetAddress.getByName(it) }

    val first = RelaisCertMint.buildSanList(addrs).map { render(it) }
    val second = RelaisCertMint.buildSanList(addrs.reversed()).map { render(it) }
    val third = RelaisCertMint.buildSanList(addrs.shuffled()).map { render(it) }

    // Unstable ordering would make needsReissue see a "changed" set on every start and re-mint the
    // certificate forever.
    assertEquals(first, second)
    assertEquals(first, third)
  }

  @Test
  fun `an overlay CGNAT address is kept, because that is the Tailscale story`() {
    val sans = RelaisCertMint.buildSanList(listOf(InetAddress.getByName("100.101.102.103")))

    assertTrue(sans.any { render(it) == "100.101.102.103" })
  }

  @Test
  fun `forty addresses cap at thirty-two and the loopback entries survive`() {
    val addrs = (1..40).map { InetAddress.getByName("10.0.0.$it") }

    val sans = RelaisCertMint.buildSanList(addrs)

    assertEquals(32, sans.size)
    // The fixed entries are prepended before the cap, so surplus real addresses are the only thing
    // ever dropped. Count deliberately unstated here — it changed once already (`::1` removed), and
    // a number in prose beside a non-hardcoded assertion is what talks the next reader into
    // "correcting" the assertion to match the comment.
    // take(fixedEntries.size), not a literal: the fixed set shrank from four to three when `::1`
    // was removed, and a hardcoded count silently starts asserting about a dynamic address.
    assertEquals(fixedEntries, sans.take(fixedEntries.size).map { render(it) })
  }

  /**
   * IPv6 is **excluded**, because the node's listeners are IPv4-only (`0.0.0.0:8443`,
   * `127.0.0.1:8080`). Certifying an address nothing serves is what this prevents — a status page
   * would report it as covered, truthfully about the certificate and wrongly about the node.
   *
   * Restoring these is a tracked follow-up that must land together with a dual-stack bind; either
   * half alone reproduces the mismatch from the other side. Do not re-add IPv6 here on its own.
   */
  @Test
  fun `an IPv6 address is not certified, because nothing serves it`() {
    val sans = RelaisCertMint.buildSanList(listOf(InetAddress.getByName("2001:db8::1")))

    assertEquals(fixedEntries, sans.map { render(it) })
    assertTrue("no IPv6 literal may appear", sans.none { render(it).contains(":") })
  }

  @Test
  fun `IPv6 loopback is not certified either, for the same reason`() {
    // ::1 was in the fixed set until it was noticed it has the identical defect: the loopback
    // listener binds 127.0.0.1, so ::1 is certified and unreachable just like a LAN IPv6 address.
    val sans = RelaisCertMint.buildSanList(emptyList())

    assertTrue("::1 must not be certified", sans.none { render(it).contains(":") })
  }

  /**
   * The three entries [RelaisCertMint.buildSanList] always prepends, in [render]'s spelling.
   *
   * Was four until `::1` was removed — the loopback listener binds `127.0.0.1`, so the IPv6
   * loopback was certified and unreachable exactly like a LAN IPv6 address.
   */
  private val fixedEntries = listOf("127.0.0.1", "localhost", "relais-node.local")

  /** Renders a [GeneralName] back to its literal, decoding `iPAddress` octets via [InetAddress]. */
  private fun render(gn: GeneralName): String =
    when (gn.tagNo) {
      GeneralName.iPAddress -> {
        val octets = org.bouncycastle.asn1.ASN1OctetString.getInstance(gn.name).octets
        InetAddress.getByAddress(octets).hostAddress ?: ""
      }
      else -> gn.name.toString()
    }
}
