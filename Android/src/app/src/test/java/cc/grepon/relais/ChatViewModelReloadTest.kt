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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The chat sheet's half of #364: a pick must not latch [ChatViewModel.reloadingModel], because the
 * chat screen's SEND is `canSend = !reloadingModel && …` (RelaisChatActivity). [ModelSwitchTest]
 * pins the shared observer; this pins that the ViewModel maps it straight through, which is where
 * the defect lived (`!awaitReload()`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatViewModelReloadTest {

  private val dispatcher = StandardTestDispatcher()

  /** Owns the ViewModel so [tearDown] can clear it (see ChatViewModelSpeechTest). */
  private val store = ViewModelStore()
  private lateinit var vm: ChatViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    val app = RuntimeEnvironment.getApplication()
    val factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
          ChatViewModel(app, dispatcher) as T
      }
    vm = ViewModelProvider(store, factory)[ChatViewModel::class.java]
  }

  @After
  fun tearDown() {
    store.clear()
    Dispatchers.resetMain()
  }

  /**
   * [runTest] that clears the ViewModel BEFORE returning. The reload poll runs in `viewModelScope` on
   * this test's scheduler; were it ever to stop terminating (a regression [ModelSwitchTest] fails
   * cleanly), runTest would wait on it forever instead of failing — [tearDown] runs too late for that.
   */
  private fun runVmTest(body: suspend TestScope.() -> Unit) =
    runTest(dispatcher) {
      try {
        body()
      } finally {
        store.clear()
      }
    }

  @Test
  fun `a pick with nothing loading leaves SEND's reloading gate open`() = runVmTest {
    // IDLE, OFF, ERROR: not ready, nothing starting. The old mapping read `!isReady` and latched true.
    assertFalse("precondition: a leaked startup from another test", RelaisLivenessState.snapshot.startupInProgress)
    assertFalse("precondition: no engine in a JVM test", RelaisEngine.isReady)
    vm.switchToManualId("litert-community/some-other-model")
    runCurrent()
    assertFalse(vm.reloadingModel.value)
  }

  @Test
  fun `a pick while a startup runs reads reloading until it settles`() = runVmTest {
    // The positive twin: without it, the test above would also pass if the pick never observed at all.
    assertFalse("precondition: a leaked startup from another test", RelaisLivenessState.snapshot.startupInProgress)
    RelaisLivenessState.beginStartup()
    var ended = false
    try {
      vm.switchToManualId("litert-community/some-other-model")
      runCurrent()
      assertTrue(vm.reloadingModel.value)
      RelaisLivenessState.endStartup()
      ended = true
      advanceTimeBy(ModelSwitch.RELOAD_POLL_INTERVAL_MS + 1)
      assertFalse(vm.reloadingModel.value)
    } finally {
      if (!ended) RelaisLivenessState.endStartup()
    }
  }
}
