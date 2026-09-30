/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.grepon.relais

import android.app.Application
import cc.grepon.relais.data.DataStoreRepository
import cc.grepon.relais.notifications.NotificationScheduleManager
import cc.grepon.relais.ui.theme.ThemeSettings
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import cc.grepon.relais.widget.refreshWidgetsOnceAsync

@HiltAndroidApp
class RelaisApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository
  @Inject lateinit var notificationScheduleManager: NotificationScheduleManager

  override fun onCreate() {
    super.onCreate()
    // Initialize the notification schedule manager to load the scheduled notifications from the
    // disk.
    notificationScheduleManager.initialize()

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    // #358: re-render the home-screen widget once per process start. After a force-stop or an update
    // the service (and its widget refresher) is gone, so otherwise nothing renders and the widget
    // keeps its last frame. Background thread; no-op when no widget is placed.
    refreshWidgetsOnceAsync(this)
  }
}
