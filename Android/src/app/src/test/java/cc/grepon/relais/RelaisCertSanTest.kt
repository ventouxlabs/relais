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
 * **Consequence, stated rather than hidden:** the `isUp` filter lives in
 * [RelaisLanIp.allLanAddresses], which enumerates real interfaces, so it is not covered in the JVM
 * lane. IPv6 link-local rejection is repeated in [RelaisCertMint.buildSanList], where it is tested
 * below, because a scoped address cannot be represented safely in a certificate SAN.
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

  @Test
  fun `an IPv6 address is certified as an IP address`() {
    val sans = RelaisCertMint.buildSanList(listOf(InetAddress.getByName("2001:db8::1")))
    val entry = sans.single { render(it) == literal("2001:db8::1") }

    assertEquals(GeneralName.iPAddress, entry.tagNo)
  }

  @Test
  fun `IPv6 loopback is certified because HTTPS explicitly binds IPv6`() {
    val sans = RelaisCertMint.buildSanList(emptyList())

    assertTrue("::1 must be certified", sans.any { render(it) == literal("::1") })
  }

  @Test
  fun `IPv6 link local is not certified because a SAN cannot carry its interface scope`() {
    val sans = RelaisCertMint.buildSanList(listOf(InetAddress.getByName("fe80::1")))

    assertEquals(fixedEntries, sans.map { render(it) })
  }

  /**
   * No `.local` name is certified, because the app cannot make one resolve to this node.
   *
   * `relais-node.local` was in the fixed set until a device run found it does not resolve. It is
   * the third instance of one mistake — `::1`, the IPv6 LAN addresses, and this — where the SAN set
   * was derived from what we *meant* to cover rather than from what the node actually answers to.
   *
   * The name looks like it should work because [RelaisDiscovery] registers `serviceName =
   * "relais-node"`, but that is a DNS-SD **service instance** (`relais-node._relais._tcp.local`,
   * a PTR/SRV/TXT triple), which is a different namespace from the **host** A record
   * `relais-node.local`. The host name is the responder's to choose, and `NsdServiceInfo` exposes
   * `getHostname()` with **no** `setHostname()` in either android-36 (the compileSdk) or
   * android-37 — the app can read what the platform picked and cannot set it. On the test device
   * the platform picked one derived from `device_name`, which was `portage-e2e-comet`.
   *
   * Re-adding this needs an API that lets the app own the host record, not just a nicer name.
   */
  @Test
  fun `no dot-local name is certified, because the app cannot make one resolve`() {
    val sans = RelaisCertMint.buildSanList(listOf(InetAddress.getByName("192.168.1.40")))

    assertTrue(".local must not be certified", sans.none { render(it).endsWith(".local") })
  }

  /**
   * The three entries [RelaisCertMint.buildSanList] always prepends, in [render]'s spelling.
   * `::1` is paired with the explicit IPv6 HTTPS listener; `relais-node.local` remains excluded
   * because DNS-SD does not let this app own a matching host record.
   */
  private val fixedEntries = listOf("127.0.0.1", literal("::1"), "localhost")

  private fun literal(value: String): String = InetAddress.getByName(value).hostAddress ?: ""

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
