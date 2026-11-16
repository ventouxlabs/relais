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

package cc.grepon.relais.customtasks.agentchat

import java.net.URLDecoder

/**
 * Guards the model-controlled `scriptName` passed to the runJs tool so it cannot escape the skill's
 * `scripts/` directory (path traversal, absolute/scheme URLs, encoded variants).
 */
object SkillScriptName {
  fun isSafe(name: String): Boolean {
    if (name.isEmpty() || name.any { it.isISOControl() }) return false
    // Decode repeatedly so double-encoded sequences cannot hide a traversal.
    var current = name
    repeat(3) {
      val decoded =
        try {
          URLDecoder.decode(current.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
          return false
        }
      if (decoded == current) return@repeat
      current = decoded
    }
    if (current.any { it.isISOControl() || it == '?' || it == '#' || it == ':' }) return false
    if (current.startsWith("/") || current.startsWith("\\")) return false
    return current.split('/', '\\').none { it == ".." || it == "." }
  }
}
