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

import org.json.JSONArray
import org.json.JSONObject

/**
 * The inventory of models actually **present on this device** (#180).
 *
 * This is the registry the first cut said didn't exist. `RelaisConfig` persists exactly ONE
 * `model_ref` — the operator's current selection — and *drops* it when the id diverges, so nothing
 * remembered what else had been downloaded. Without that history a request naming any other model
 * was unresolvable, which is why the first cut could only ever swap to `RelaisConfig.modelId`.
 *
 * **This registry is the safety boundary of the full feature.** It only ever gains an entry when a
 * provision *succeeds locally* ([RelaisModelProvisioner.remember]), so a swap can complete a model
 * the operator already downloaded but can never originate a download. An arbitrary `model` string
 * from a LAN client therefore still cannot make the node fetch multi-GB files unattended — the
 * attack surface the first cut's KDoc called out. Broadening the swap without this property would
 * reintroduce exactly that hole.
 *
 * Entries are pure data and the list operations below are pure functions, so the whole
 * add/prune/lookup contract is unit-tested without a Context.
 */
data class ProvisionedModel(val modelId: String, val path: String, val displayName: String) {
  fun toJson(): JSONObject =
    JSONObject().put("model_id", modelId).put("path", path).put("display_name", displayName)

  companion object {
    fun fromJson(o: JSONObject): ProvisionedModel? {
      val id = o.optString("model_id").takeIf { it.isNotBlank() } ?: return null
      val path = o.optString("path").takeIf { it.isNotBlank() } ?: return null
      return ProvisionedModel(id, path, o.optString("display_name").ifBlank { id })
    }
  }
}

/**
 * Upsert by [ProvisionedModel.modelId]. Re-provisioning the same id must REPLACE rather than
 * duplicate — the path changes when a model is re-downloaded at a new commit hash, and a stale
 * duplicate would let a swap resolve to a file that no longer exists.
 *
 * Consequence: the registry holds ONE build per id. Two builds sharing an id (the G5 TPU and GPU
 * builds of E2B) replace each other, the last provisioned wins, and a swap to that id loads it.
 * Id-level resolution is the right grain for the swap lane — a LAN client can only name an id — but
 * it is not a record of every file on the device.
 */
fun upsertProvisioned(
  existing: List<ProvisionedModel>,
  entry: ProvisionedModel,
): List<ProvisionedModel> = existing.filterNot { it.modelId == entry.modelId } + entry

/**
 * Drop entries whose file is gone (uninstalled model, cleared storage, manual delete). [exists] is
 * injected so this stays pure; production passes `File(it).exists()`.
 *
 * Pruning matters for correctness, not tidiness: a swap to a registry entry whose file vanished
 * would fail deep inside engine init, long after the request was accepted.
 */
fun pruneMissingProvisioned(
  entries: List<ProvisionedModel>,
  exists: (String) -> Boolean,
): List<ProvisionedModel> = entries.filter { exists(it.path) }

/** The set a request's `model` field is checked against. */
fun provisionedIds(entries: List<ProvisionedModel>): Set<String> = entries.map { it.modelId }.toSet()

/** Serialize for [RelaisConfig]. Stable shape; unknown/corrupt members are dropped on read. */
fun encodeProvisioned(entries: List<ProvisionedModel>): String =
  JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString()

/**
 * Parse the persisted list. A corrupt or truncated blob yields an EMPTY registry rather than
 * throwing — the node must still boot and serve its resident model if this preference is damaged
 * (same defensive posture as `RelaisModelRef.fromJson`).
 */
fun decodeProvisioned(json: String?): List<ProvisionedModel> {
  if (json.isNullOrBlank()) return emptyList()
  return runCatching {
      val arr = JSONArray(json)
      (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { ProvisionedModel.fromJson(it) }
      }
    }
    .getOrDefault(emptyList())
}

/**
 * The registry entry a [ModelRequestOutcome.SwapThenRetry] should load.
 *
 * Exists to make one invariant explicit and testable: the swap target is the **requested** model,
 * never the operator's configured one. They coincided under #180's first cut (its guard only allowed
 * `requested == configured`), so `ensureModelSwapInBackground` simply loaded the configured model.
 * Once ANY provisioned model became eligible that stopped being true, and loading the configured
 * model would 503 "swapping", reload the same model, and 503 the retry forever.
 *
 * Returns null when the target isn't in the registry — the operator's just-selected model that
 * hasn't been recorded yet, where the engine's configured-model fallback is correct.
 */
fun swapTargetFor(targetModelId: String, provisioned: List<ProvisionedModel>): ProvisionedModel? =
  provisioned.firstOrNull { it.modelId == targetModelId }
