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

import cc.grepon.relais.customtasks.agentchat.SkillScriptName
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pure JVM tests for [SkillScriptName] — confines model-supplied script names to the skill dir. */
class SkillScriptNameTest {

  @Test fun `plain and nested names are allowed`() {
    assertTrue(SkillScriptName.isSafe("index.html"))
    assertTrue(SkillScriptName.isSafe("sub/run.js"))
  }

  @Test fun `parent traversal is rejected`() {
    assertFalse(SkillScriptName.isSafe("../other/scripts/index.html"))
    assertFalse(SkillScriptName.isSafe("a/../../b"))
    assertFalse(SkillScriptName.isSafe("..\\b"))
    assertFalse(SkillScriptName.isSafe(".."))
  }

  @Test fun `encoded traversal is rejected`() {
    assertFalse(SkillScriptName.isSafe("%2e%2e/b"))
    assertFalse(SkillScriptName.isSafe("..%2fb"))
    assertFalse(SkillScriptName.isSafe("%252e%252e/b"))
  }

  @Test fun `absolute urls, query, fragment, empty are rejected`() {
    assertFalse(SkillScriptName.isSafe("/etc/passwd"))
    assertFalse(SkillScriptName.isSafe("https://evil.com/x.js"))
    assertFalse(SkillScriptName.isSafe("x.html?../a"))
    assertFalse(SkillScriptName.isSafe("x.html#a"))
    assertFalse(SkillScriptName.isSafe(""))
    assertFalse(SkillScriptName.isSafe("a\u0000b"))
  }

  @get:Rule val tmp = TemporaryFolder()

  @Test fun `resolveIn returns the file inside the scripts dir for a safe name`() {
    val dir = tmp.newFolder("scripts")
    assertEquals(File(dir, "index.html"), SkillScriptName.resolveIn(dir, "index.html"))
    assertEquals(File(dir, "sub/run.js"), SkillScriptName.resolveIn(dir, "sub/run.js"))
  }

  @Test fun `resolveIn rejects names that escape the scripts dir`() {
    val dir = tmp.newFolder("scripts")
    assertNull(SkillScriptName.resolveIn(dir, "../SKILL.md"))
    assertNull(SkillScriptName.resolveIn(dir, "a/../../b"))
    assertNull(SkillScriptName.resolveIn(dir, "/etc/passwd"))
    assertNull(SkillScriptName.resolveIn(dir, "%2e%2e/SKILL.md"))
    assertNull(SkillScriptName.resolveIn(dir, ""))
  }

  @Test fun `asset url allows a relative path with query and fragment`() {
    assertTrue(SkillScriptName.isSafeAssetUrl("webview.html"))
    assertTrue(SkillScriptName.isSafeAssetUrl("webview.html?seed=1&name=a%20b"))
    assertTrue(SkillScriptName.isSafeAssetUrl("ui/index.html#top"))
  }

  @Test fun `asset url rejects traversal in its path`() {
    assertFalse(SkillScriptName.isSafeAssetUrl("../../other/assets/x.html"))
    assertFalse(SkillScriptName.isSafeAssetUrl("%2e%2e/x.html?a=1"))
    assertFalse(SkillScriptName.isSafeAssetUrl("/etc/passwd"))
    assertFalse(SkillScriptName.isSafeAssetUrl("file:///etc/passwd"))
    assertFalse(SkillScriptName.isSafeAssetUrl("?only=query"))
  }
}
