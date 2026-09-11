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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RelaisTls.mintWouldReplaceExistingIdentity] — the fourth axis of the key-material-recovery
 * family (feature-18 codex round 13): **detection of partial state**.
 *
 * The three earlier axes each assumed the node could tell a first install from a damaged one. It
 * could not. The CA and the leaf are two halves of one identity, and either half can go missing on
 * its own — a selective backup restore, a partial `adb` wipe, a user clearing one file. When that
 * happens the surviving half proves an identity already existed, so minting the missing half
 * *replaces* something clients hold.
 *
 * The bug this pins is silence, not incorrect recovery. Re-minting is the right action in every one
 * of these states; the defect was that `caWasReplaced` / `leafKeyWasReplaced` fired only on the
 * unrecoverable-password route, so a deleted file produced the identical user-visible breakage —
 * `--cacert` stops verifying, `--pinnedpubkey` stops matching — with nothing on `GET /` to explain
 * it.
 *
 * Both-absent is the case that has to stay false: it is a genuine first mint, and warning there
 * would put a rotation notice on every brand-new node.
 */
class RelaisTlsPartialStateTest {

  @Test
  fun `a missing component whose sibling survives is a replacement`() {
    // The round-13 pair, both halves. CA deleted with the leaf intact, and leaf deleted with the CA
    // intact: each mints under an identity clients already trust.
    assertTrue(
      "CA gone, leaf survives — every imported CA stops verifying",
      RelaisTls.mintWouldReplaceExistingIdentity(componentExists = false, siblingExists = true),
    )
  }

  @Test
  fun `a first install is not a replacement`() {
    // Must stay false: a fresh node has neither file, and a warning here would appear on every new
    // install and teach users to ignore the one that matters.
    assertFalse(
      "neither file present is a genuine first mint",
      RelaisTls.mintWouldReplaceExistingIdentity(componentExists = false, siblingExists = false),
    )
  }

  @Test
  fun `a component that already exists is never a replacement`() {
    // Nothing is being minted in this state, so the question does not arise. Pinned in both sibling
    // positions because a predicate that ignored `componentExists` would pass the first case above
    // and still be wrong here.
    assertFalse(
      "component present, sibling present",
      RelaisTls.mintWouldReplaceExistingIdentity(componentExists = true, siblingExists = true),
    )
    assertFalse(
      "component present, sibling absent",
      RelaisTls.mintWouldReplaceExistingIdentity(componentExists = true, siblingExists = false),
    )
  }

  @Test
  fun `when exactly one half is missing, exactly that half reports a replacement`() {
    // The real call pattern: one disk state, the predicate asked once per component with the two
    // arguments swapped. This is the integration invariant the round-13 defect broke — asking about
    // the surviving half must stay silent while asking about the missing half must warn.
    val caExists = true
    val leafExists = false

    val caVerdict =
      RelaisTls.mintWouldReplaceExistingIdentity(componentExists = caExists, siblingExists = leafExists)
    val leafVerdict =
      RelaisTls.mintWouldReplaceExistingIdentity(componentExists = leafExists, siblingExists = caExists)

    assertFalse("the surviving CA is not being replaced", caVerdict)
    assertTrue("the missing leaf is being replaced", leafVerdict)
  }
}
