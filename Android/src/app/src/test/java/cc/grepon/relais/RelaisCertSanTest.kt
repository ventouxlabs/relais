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
    // The four fixed entries are prepended before the cap, so surplus real addresses are the only
    // thing ever dropped.
    assertEquals(fixedEntries, sans.take(4).map { render(it) })
  }

  @Test
  fun `an IPv6 address is carried as iPAddress`() {
    val sans = RelaisCertMint.buildSanList(listOf(InetAddress.getByName("2001:db8::1")))
    val entry = sans.single { render(it) == ip("2001:db8::1") }

    assertEquals(GeneralName.iPAddress, entry.tagNo)
  }

  /**
   * The four entries [RelaisCertMint.buildSanList] always prepends, in [render]'s spelling.
   *
   * `::1` is written through [ip] rather than as a literal because [render] decodes the certificate
   * octets back through [InetAddress], which canonicalises IPv6 to its fully expanded form
   * (`0:0:0:0:0:0:0:1`). Comparing against the shorthand would fail for a cert that is perfectly
   * correct — the same normalisation trap that makes [RelaisCertMint.needsReissue] compare DER
   * rather than strings.
   */
  private val fixedEntries = listOf("127.0.0.1", ip("::1"), "localhost", "relais-node.local")

  /** An IP literal in the canonical spelling [render] produces. */
  private fun ip(literal: String): String = InetAddress.getByName(literal).hostAddress ?: ""

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
