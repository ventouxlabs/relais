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

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.grepon.relais.chat.ChatStreamRequest
import cc.grepon.relais.chat.ChatTransportSelector
import cc.grepon.relais.chat.ContentReportDelivery
import cc.grepon.relais.chat.ContentReportDraft
import cc.grepon.relais.chat.ReportOutcome
import cc.grepon.relais.chat.ReportSendResult
import cc.grepon.relais.chat.attemptReportSend
import cc.grepon.relais.chat.deliverReport
import cc.grepon.relais.chat.ERROR_BACKEND
import cc.grepon.relais.chat.ReportDraftResult
import cc.grepon.relais.chat.ReportReason
import cc.grepon.relais.chat.ReportRejection
import cc.grepon.relais.chat.SpeechState
import cc.grepon.relais.chat.buildContentReportDraft
import cc.grepon.relais.chat.persistContentReport
import cc.grepon.relais.chat.historyForRequest
import cc.grepon.relais.data.ChatTurn
import cc.grepon.relais.data.Conversation
import cc.grepon.relais.data.RelaisDatabase
import cc.grepon.relais.data.ReportSendState
import cc.grepon.relais.data.ReportSurface
import cc.grepon.relais.tts.RelaisTtsEngine
import cc.grepon.relais.tts.RelaisTtsEngineProvider
import cc.grepon.relais.tts.TtsAvailability
import cc.grepon.relais.tts.TtsPlayer
import cc.grepon.relais.tts.speakableText
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Persistence-backed streaming chat view model for the "Chat Depth" in-app chat feature. Owns
 * conversation/turn state (via [ChatRepository]), token streaming (via [ChatTransportSelector] /
 * [cc.grepon.relais.chat.ChatTransport]), and model-switch reload observation ([RelaisEngine]).
 * Not wired into any UI yet — that lands in Task 7.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel @JvmOverloads constructor(
  app: Application,
  /**
   * Where speech synthesis/playback runs. Injectable so the #211 seam tests can drive it on a test
   * dispatcher — a hard-coded [Dispatchers.IO] escapes virtual time and makes those tests racy.
   * Production always takes the default.
   */
  private val speechDispatcher: CoroutineDispatcher = Dispatchers.IO,
  /**
   * How an opt-in report is delivered (#258 gate 1). Injectable so a test can assert the opt-in
   * gate — that `alsoSend = false` never invokes this — without a network stack. Production always
   * takes the default.
   */
  private val sendReport: (ContentReportDraft, String) -> ReportSendResult =
    ContentReportDelivery::send,
) : AndroidViewModel(app) {

  private val repo = ChatRepository(app, RelaisDatabase.get(app).chatDao())


  /** One selector (and one owned [HttpClient]) for the ViewModel's lifetime; closed in [onCleared]. */
  private val transportSelector = ChatTransportSelector(app)

  private val cancelled = AtomicBoolean(false)

  /**
   * Guards against overlapping streams: [send]/[regenerate]/[editAndResend] all mutate the same
   * `_streamingText` and append assistant turns, so a second trigger while one is in flight would
   * interleave tokens and corrupt history. Acquired synchronously before launching; released in the
   * launched coroutine's `finally`.
   */
  private val inFlight = AtomicBoolean(false)

  val conversations: StateFlow<List<Conversation>> =
    repo
      .observeConversations()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _activeConversationId = MutableStateFlow<String?>(null)
  val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

  val turns: StateFlow<List<ChatTurn>> =
    _activeConversationId
      .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeTurns(id) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _streamingText = MutableStateFlow("")
  val streamingText: StateFlow<String> = _streamingText.asStateFlow()

  private val _streaming = MutableStateFlow(false)
  val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

  // Set to the just-persisted assistant turn's id for the brief hand-off window (see
  // streamAndPersist), so the UI can suppress the streaming bubble by id rather than by content —
  // content equality misfires when two consecutive assistant turns happen to match.
  private val _pendingPersistedTurnId = MutableStateFlow<String?>(null)
  val pendingPersistedTurnId: StateFlow<String?> = _pendingPersistedTurnId.asStateFlow()

  private val _reloadingModel = MutableStateFlow(false)
  val reloadingModel: StateFlow<Boolean> = _reloadingModel.asStateFlow()

  /**
   * Transient outcome of the last [reportContent], shown as a strip under the chat. Never null-on-
   * failure: a report that could not be saved says so, because silently dropping it would leave the
   * operator believing a flag was recorded when it wasn't.
   */
  private val _reportNotice = MutableStateFlow<String?>(null)
  val reportNotice: StateFlow<String?> = _reportNotice.asStateFlow()

  /**
   * Monotonic token identifying the current report submission. Guards every [_reportNotice] write so
   * a late-arriving outcome from an EARLIER report — its up-to-~35s send window still open — can never
   * overwrite a MORE RECENT report's notice. Without this, reporting a second turn while the first is
   * still sending could misattribute one report's outcome onto the other: the operator could be told
   * the wrong report was (or wasn't) transmitted, which matters because the transmitted content may
   * include a name or other detail typed into the note. Same pattern as [speechGeneration].
   */
  private val reportGeneration = java.util.concurrent.atomic.AtomicLong(0)

  /** True while [generation] is still the newest report submission — the guard on every notice write. */
  private fun reportOwns(generation: Long): Boolean = reportGeneration.get() == generation

  /** The in-flight reload-observation poll, cancelled and replaced on each model switch. */
  private var reloadJob: kotlinx.coroutines.Job? = null

  // ---- In-app speech playback (#211) -------------------------------------------------------
  // Synthesis runs on the SAME process-wide engine the HTTP `/v1/audio/speech` route uses, whose
  // `synthLock` already serializes native generate() calls — so an in-app tap and a concurrent LAN
  // request queue behind each other rather than racing. No second admission gate is needed here.

  private val ttsPlayer = TtsPlayer(app)

  private val _speech = MutableStateFlow<SpeechState>(SpeechState.Idle)
  val speech: StateFlow<SpeechState> = _speech.asStateFlow()

  /** True when a TTS engine is registered and is (or can become) usable — drives showing SPEAK at all. */
  private val _speechOffered = MutableStateFlow(false)
  val speechOffered: StateFlow<Boolean> = _speechOffered.asStateFlow()

  /** The in-flight synthesis/playback job, cancelled when superseded or stopped. */
  private var speechJob: kotlinx.coroutines.Job? = null

  /**
   * Monotonic token identifying the current speech attempt. An outgoing job only writes state while
   * it still owns the latest token.
   *
   * Comparing turn ids instead is NOT sufficient: stopping and re-tapping the *same* turn gives the
   * old and new attempts identical ids, and the outgoing job — which resumes after its blocking
   * playback returns, at a point with no suspension for cancellation to take effect — would reset the
   * incoming attempt's state to Idle mid-synthesis.
   */
  private val speechGeneration = java.util.concurrent.atomic.AtomicLong(0)

  init {
    refreshSpeechOffered()
  }

  /**
   * Re-reads whether speech can be offered at all.
   *
   * Deliberately does NOT call `availability()`: that loads the ~64 MB voice model and reads
   * encrypted prefs, and this runs on the main thread (from `init` and every `ON_RESUME`). Engine
   * registration is the cheap proxy — per `SherpaTtsEngine`, a registered engine is only ever READY
   * or PROVISIONING, and [speak] does the authoritative check and clears this on UNAVAILABLE.
   *
   * Also settles a stale [SpeechState.Fetching]: if the voice finished downloading, drop the notice.
   */
  fun refreshSpeechOffered() {
    _speechOffered.value = RelaisTtsEngineProvider.get() != null

    val fetching = _speech.value as? SpeechState.Fetching ?: return
    viewModelScope.launch {
      val ready =
        withContext(speechDispatcher) {
          RelaisTtsEngineProvider.get()?.availability(getApplication()) == TtsAvailability.READY
        }
      // Only clear if the very same notice is still showing — a newer attempt may have replaced it.
      if (ready && _speech.value === fetching) _speech.value = SpeechState.Idle
    }
  }

  /**
   * Synthesize [turn]'s prose and play it. Supersedes any current playback (tapping SPEAK on a second
   * turn switches to it). Markdown is reduced to speakable prose first — see [speakableText].
   *
   * In-flight native synthesis is **not** abortable — the sherpa-onnx runtime exposes no cancel — so
   * a superseded attempt still runs to completion under `synthLock`; its result is discarded. The
   * ownership check before synthesis keeps rapid tapping from queueing work nobody wants.
   */
  fun speak(turn: ChatTurn) {
    val generation = supersedeAndClaim()
    val engine = RelaisTtsEngineProvider.get()
    if (engine == null) {
      _speechOffered.value = false
      _speech.value = SpeechState.Idle
      return
    }
    speechJob = viewModelScope.launch { runSpeechAttempt(turn, engine, generation) }
  }

  /**
   * Stop whatever is playing and claim a fresh generation token.
   *
   * Called synchronously *before* any early return in [speak], so every exit path leaves prior
   * playback stopped and the outgoing job disowned — an early return above this is exactly the bug
   * the blank-text path once had.
   */
  private fun supersedeAndClaim(): Long {
    speechJob?.cancel()
    ttsPlayer.stop()
    return speechGeneration.incrementAndGet()
  }

  /** True while [generation] is still the newest attempt — the guard on every state write below. */
  private fun owns(generation: Long): Boolean = speechGeneration.get() == generation

  /** One speech attempt: resolve availability + text off the main thread, then route on the result. */
  private suspend fun runSpeechAttempt(turn: ChatTurn, engine: RelaisTtsEngine, generation: Long) {
    val ctx = getApplication<Application>()
    try {
      // One hop to IO for the whole preamble: availability() can load the voice model and
      // speakableText() sweeps the full turn with several regexes. Neither belongs on Main.
      val (availability, text) =
        withContext(speechDispatcher) { engine.availability(ctx) to speakableText(turn.content) }
      if (!owns(generation)) return

      // Emptiness is a property of the TURN, not of the engine — check it before availability.
      // Gating this on READY would make a code-only turn kick a ~64 MB voice download first and
      // only then report there was never anything to read.
      if (text.isBlank()) {
        _speech.value = SpeechState.Failed(turn.id, "nothing to speak")
        return
      }

      when (availability) {
        TtsAvailability.UNAVAILABLE -> {
          _speechOffered.value = false
          _speech.value = SpeechState.Failed(turn.id, "speech unavailable")
        }
        TtsAvailability.PROVISIONING -> {
          // The voice isn't on disk yet. Kick the download and say so; the notice clears when a
          // later availability re-check sees READY (see refreshSpeechOffered).
          withContext(speechDispatcher) { engine.ensureProvisioningStarted(ctx) }
          if (owns(generation)) _speech.value = SpeechState.Fetching(turn.id)
        }
        TtsAvailability.READY -> synthesizeAndPlay(turn, engine, text, generation)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Log.w(TAG, "speech failed for turn ${turn.id}", e)
      if (owns(generation)) {
        _speech.value =
          SpeechState.Failed(turn.id, e.message ?: e::class.simpleName ?: "speech failed")
      }
    }
  }

  /** The READY path: synthesize, play, and report the outcome — each step gated on still owning it. */
  private suspend fun synthesizeAndPlay(
    turn: ChatTurn,
    engine: RelaisTtsEngine,
    text: String,
    generation: Long,
  ) {
    val ctx = getApplication<Application>()
    _speech.value = SpeechState.Preparing(turn.id)
    val audio =
      withContext(speechDispatcher) {
        // Re-check before entering synthLock so a superseded tap doesn't queue native work.
        if (owns(generation)) engine.synthesize(ctx, text) else null
      } ?: return
    if (!owns(generation)) return // superseded while synthesizing — don't start stale audio

    _speech.value = SpeechState.Speaking(turn.id)
    val result = withContext(speechDispatcher) { ttsPlayer.play(audio) }
    if (!owns(generation)) return
    _speech.value =
      if (result == TtsPlayer.PlaybackResult.FAILED) {
        SpeechState.Failed(turn.id, "playback failed")
      } else {
        SpeechState.Idle
      }
  }

  /** Stops playback and clears speech state. Safe to call when nothing is playing. */
  fun stopSpeaking() {
    // Bump the generation too: the outgoing job resumes from blocking playback at a point with no
    // suspension, so `cancel()` alone would not stop it writing one last state after this Idle.
    speechGeneration.incrementAndGet()
    speechJob?.cancel()
    speechJob = null
    ttsPlayer.stop()
    _speech.value = SpeechState.Idle
  }

  /**
   * Clears a transient [SpeechState.Failed] notice once the UI has shown it.
   *
   * [SpeechState.Fetching] is deliberately NOT cleared on a timer — a ~64 MB voice download outlasts
   * any sensible notice delay, so reverting the label to SPEAK mid-download would just invite a
   * pointless re-tap. It clears when [refreshSpeechOffered] observes the voice became READY.
   */
  fun clearSpeechNotice(turnId: String) {
    val state = _speech.value
    if (state is SpeechState.Failed && state.turnId == turnId) _speech.value = SpeechState.Idle
  }

  /**
   * Record an operator report of assistant output (#258), satisfying Play's AI-Generated Content
   * policy requirement for in-app flagging.
   *
   * The report is always written to this device first. [alsoSend] is the operator's separate,
   * explicit opt-in ([ContentReportDialog]'s toggle, default off) to also deliver it to the
   * maintainer via [ContentReportDelivery] — a save never depends on the send succeeding, and a
   * failed send never undoes the save. Validation happens in [buildContentReportDraft] before
   * anything reaches Room. Gating and outcome sequencing are shared with the Gallery/agent chat
   * surface via [deliverReport]; every notice write here is guarded by [reportOwns] so a second
   * report submitted while this one is still sending can't have its outcome overwritten by this one
   * arriving late.
   */
  fun reportContent(turn: ChatTurn, reason: ReportReason, note: String, alsoSend: Boolean) {
    val generation = reportGeneration.incrementAndGet()
    val result =
      buildContentReportDraft(
        reason = reason,
        content = turn.content,
        note = note,
        modelId = turn.answeredByModelId,
        backend = turn.answeredByBackend,
      )
    when (result) {
      is ReportDraftResult.Rejected ->
        if (reportOwns(generation)) {
          _reportNotice.value =
            when (result.error) {
              ReportRejection.EMPTY_CONTENT -> "Nothing to report — that turn is empty."
              ReportRejection.NOTE_TOO_LONG -> "That note is too long."
            }
        }
      is ReportDraftResult.Valid ->
        viewModelScope.launch {
          // The row id, not just a boolean: it is what makes a failed send recoverable (#273) rather
          // than lost the moment this coroutine ends.
          val reportId =
            persistContentReport(
              context = getApplication(),
              draft = result.draft,
              surface = ReportSurface.CHAT,
              nowMs = System.currentTimeMillis(),
              sendState = if (alsoSend) ReportSendState.PENDING else ReportSendState.NONE,
            )
          deliverReport(
            saved = reportId != null,
            alsoSend = alsoSend,
            draft = result.draft,
            surface = ReportSurface.CHAT,
            send = { draft, surface ->
              withContext(Dispatchers.IO) {
                attemptReportSend(
                  context = getApplication(),
                  reportId = reportId,
                  draft = draft,
                  surface = surface,
                  attempt = sendReport,
                )
              }
            },
            onOutcome = { outcome ->
              if (reportOwns(generation)) {
                _reportNotice.value =
                  when (outcome) {
                    ReportOutcome.SAVE_FAILED -> "Could not save that report."
                    ReportOutcome.SAVED_ONLY -> "REPORTED — saved on this device"
                    ReportOutcome.SAVED_AND_SENT -> "REPORTED — saved on this device and sent to the developer"
                    ReportOutcome.SAVED_SEND_FAILED ->
                      "REPORTED — saved on this device. Could not reach the developer."
                  }
              }
            },
          )
        }
    }
  }

  /** Dismisses the [reportNotice] strip. */
  fun clearReportNotice() {
    _reportNotice.value = null
  }

  /** Clears the active conversation; a new one is created lazily on the next [send]. */
  fun newConversation() {
    _activeConversationId.value = null
  }

  fun openConversation(id: String) {
    _activeConversationId.value = id
  }

  /**
   * Persists the user turn, then streams the assistant reply on [Dispatchers.IO], persisting it on
   * completion. Creates a conversation first (title = first ~40 chars of [text]) if none is active.
   */
  fun send(text: String, attachmentType: String?, attachmentBytes: ByteArray?) {
    if (!inFlight.compareAndSet(false, true)) return
    val ctx = getApplication<Application>()
    viewModelScope.launch {
      try {
        val convId =
          _activeConversationId.value
            ?: repo
              .createConversation(
                title = text.take(40).ifBlank { "New chat" },
                modelId = RelaisConfig.modelId(ctx),
              )
              .also { _activeConversationId.value = it }

        appendUserAndStream(convId, text, attachmentType, attachmentBytes)
      } finally {
        inFlight.set(false)
      }
    }
  }

  /** Persists a new user turn, then streams and persists the assistant reply. */
  private suspend fun appendUserAndStream(
    conversationId: String,
    text: String,
    attachmentType: String?,
    attachmentBytes: ByteArray?,
  ) {
    repo.appendUserTurn(conversationId, text, attachmentType, attachmentBytes)
    streamAndPersist(conversationId, text, attachmentType, attachmentBytes)
  }

  private suspend fun streamAndPersist(
    conversationId: String,
    text: String,
    attachmentType: String?,
    attachmentBytes: ByteArray?,
  ) {
    val ctx = getApplication<Application>()
    cancelled.set(false)
    _streamingText.value = ""
    _streaming.value = true
    try {
      val persisted =
        withContext(Dispatchers.IO) {
          runCatching {
              val history = historyForRequest(repo.turnsFor(conversationId))
              val transport = transportSelector.select()
              transport.stream(
                request =
                  ChatStreamRequest(
                    history = history,
                    userText = text,
                    imagePng = if (attachmentType == "image") attachmentBytes else null,
                    audioWav = if (attachmentType == "audio") attachmentBytes else null,
                  ),
                onToken = { token -> _streamingText.value += token },
                onReasoning = {},
                shouldCancel = { cancelled.get() },
              )
            }
            .fold(
              onSuccess = { result ->
                repo.appendAssistantTurn(conversationId, result.text, result.modelId, result.backend.name)
              },
              onFailure = { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                repo.appendAssistantTurn(
                  conversationId,
                  content = "[error] ${error.message ?: error::class.simpleName}",
                  modelId = RelaisConfig.modelId(ctx),
                  backend = ERROR_BACKEND,
                )
              },
            )
        }
      // Keep the streaming bubble up until the just-persisted turn is actually reflected in [turns],
      // so there's never a frame with neither the bubble nor the persisted turn visible. Bounded by a
      // timeout so a missed/delayed Flow emission can't hang the UI in the streaming state forever.
      _pendingPersistedTurnId.value = persisted.id
      withTimeoutOrNull(TURN_PERSIST_AWAIT_TIMEOUT_MS) {
        turns.first { list -> list.any { it.id == persisted.id } }
      }
    } finally {
      // Reset in `finally` so a scope/coroutine cancel (e.g. ViewModel cleared mid-stream) still
      // clears the streaming flags rather than leaving the UI stuck in the "streaming" state.
      _streaming.value = false
      _streamingText.value = ""
      _pendingPersistedTurnId.value = null
    }
  }

  /** Flips the cancellation flag read by the in-flight transport's `shouldCancel`. */
  fun stop() {
    cancelled.set(true)
  }

  /**
   * Truncates the conversation back to just before [fromAssistantTurn]'s preceding user turn, then
   * re-streams a reply for that user turn's text/attachment.
   */
  fun regenerate(fromAssistantTurn: ChatTurn) {
    val convId = _activeConversationId.value ?: return
    val ordered = turns.value.sortedBy { it.createdAt }
    val assistantIndex = ordered.indexOfFirst { it.id == fromAssistantTurn.id }
    if (assistantIndex <= 0) return
    val precedingUserTurn =
      ordered.subList(0, assistantIndex).lastOrNull { it.role == "user" } ?: return

    if (!inFlight.compareAndSet(false, true)) return
    viewModelScope.launch {
      try {
        repo.truncateAfter(convId, precedingUserTurn)
        val bytes = precedingUserTurn.attachmentPath?.let { path -> readAttachment(path) }
        val type = if (bytes != null) precedingUserTurn.attachmentType else null
        streamAndPersist(convId, precedingUserTurn.content, type, bytes)
      } finally {
        inFlight.set(false)
      }
    }
  }

  /** Removes [userTurn] and everything after it, then sends [newText] as a fresh turn. */
  fun editAndResend(userTurn: ChatTurn, newText: String) {
    val convId = _activeConversationId.value ?: return
    val ordered = turns.value.sortedBy { it.createdAt }
    val userIndex = ordered.indexOfFirst { it.id == userTurn.id }
    if (userIndex < 0) return
    val precedingTurn = ordered.getOrNull(userIndex - 1)

    if (!inFlight.compareAndSet(false, true)) return
    viewModelScope.launch {
      try {
        val bytes = userTurn.attachmentPath?.let { path -> readAttachment(path) } // read BEFORE truncation
        // Only carry the attachment type if the bytes are actually still on disk — otherwise the
        // resent turn would persist a type with no data (a "phantom attachment").
        val type = if (bytes != null) userTurn.attachmentType else null
        if (precedingTurn != null) {
          repo.truncateAfter(convId, precedingTurn)
        } else {
          repo.truncateAfter(convId, userTurn.copy(createdAt = userTurn.createdAt - 1))
        }
        appendUserAndStream(convId, newText, type, bytes)
      } finally {
        inFlight.set(false)
      }
    }
  }

  private fun readAttachment(path: String): ByteArray? {
    val file = File(path)
    return if (file.exists()) file.readBytes() else null
  }

  /** Switches to a curated ref (persisting the full ref, not just its id) and reflects the reload. */
  fun switchToRef(ref: cc.grepon.relais.data.RelaisModelRef) {
    ModelSwitch.applyRef(getApplication(), ref)
    observeReload()
  }

  /** Switches to a raw manual id (dropping any curated ref) and reflects the reload. */
  fun switchToManualId(modelId: String) {
    // resolvedPath = null: this surface resolves through the allowlist on reload, so it is NOT
    // bypassing resolveModel and must not pre-empt the path the provisioner will record.
    ModelSwitch.applyManualId(getApplication(), modelId, resolvedPath = null)
    observeReload()
  }

  /** Reflects [RelaisEngine]'s lazy model reload into [reloadingModel] (see [ModelSwitch.awaitReload]). */
  private fun observeReload() {
    // Cancel any in-flight poll first so a rapid re-pick doesn't leave overlapping pollers racing to
    // write _reloadingModel (harmless final value, but avoids flicker and wasted coroutines).
    reloadJob?.cancel()
    reloadJob =
      viewModelScope.launch {
        _reloadingModel.value = true
        _reloadingModel.value = !ModelSwitch.awaitReload()
      }
  }

  fun rename(id: String, title: String) {
    viewModelScope.launch { repo.rename(id, title) }
  }

  fun delete(id: String) {
    viewModelScope.launch {
      repo.delete(id)
      if (_activeConversationId.value == id) {
        _activeConversationId.value = null
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    transportSelector.close()
    // viewModelScope is cancelled by super, but the AudioTrack is native — release it explicitly or
    // the speaker keeps playing the buffered tail after the screen is gone.
    ttsPlayer.release()
  }

  private companion object {
    /** Shared with the tts package so all speech logging greps under one tag. */
    const val TAG = "RelaisTts"

    const val TURN_PERSIST_AWAIT_TIMEOUT_MS = 3_000L
  }
}
