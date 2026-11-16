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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
