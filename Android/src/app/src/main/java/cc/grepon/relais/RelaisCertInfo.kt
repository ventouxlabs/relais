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
 * An immutable, already-formatted snapshot of the node's certificate state (feature-18 T3), read by
 * the CONFIGURE screen, the served `GET /` panel, and `GET /ca.crt`.
 *
 * Everything here is **public** certificate material. No private key, no keystore password, and
 * nothing derived from either, ever enters this type — it crosses into the UI layer and into an
 * HTTP response body, so the type itself is the boundary that makes "the CA private key is never
 * emitted by any route, log line, or share" checkable by reading one file.
 *
 * The values arrive pre-formatted rather than as `X509Certificate`s so that a consumer cannot
 * accidentally reach through to key material, and so no consumer has to agree with any other about
 * how a fingerprint is spelled.
 *
 * @property caFingerprint the CA public key as `sha256/<base64>` — the out-of-band value a user
 *   checks after fetching the CA. **Not** what `--pinnedpubkey` wants; see [nodeKeyPin].
 * @property nodeKeyPin the *leaf* public key as `sha256/<base64>` — what
 *   `curl --pinnedpubkey sha256//<value>` pins. Survives a re-mint unchanged, which is the whole
 *   point of reusing the leaf key.
 * @property sanList the leaf's subject-alternative names as display strings, in certificate order.
 * @property leafNotAfter the leaf's expiry as epoch millis, for the "in N days" row.
 * @property caPem the CA certificate in PEM, served verbatim by `GET /ca.crt` and written by the
 *   share sheet. The CA only — never the leaf, and never a key.
 */
// Public, unlike most Relais types, because the public `DashboardStatus` carries one. That is safe
// precisely because of the invariant above: every field is public certificate material.
data class RelaisCertInfo(
  val caFingerprint: String,
  val nodeKeyPin: String,
  val sanList: List<String>,
  val leafNotAfter: Long,
  val caPem: String,
)
