# ReadBook Polish Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Reset-today button, "Start"/"Snooze 15m" notification actions on the hourly nudge, and a weekly summary notification to the existing ReadBook Android app.

**Architecture:** Every feature slots into the app's existing layered structure — pure functions in `data`, DAO/repository logic in `data`, `AlarmManager`/`BroadcastReceiver` orchestration in `scheduling`, notification building in `notifications`, and Compose screens in `ui`. No new architectural patterns; each task follows a pattern already proven elsewhere in this codebase (cited per task). Where a file is touched by more than one task (`NudgeScheduler.kt`, `TimerNotifications.kt`), each task applies a targeted edit scoped to only its own feature — never a full-file rewrite that bundles another task's untested code — so every task's tests fully cover everything that task adds.

**Tech Stack:** Kotlin, Jetpack Compose, Room, AlarmManager, Robolectric + JUnit (Robolectric for anything touching Android framework classes — Context, AlarmManager, NotificationManager, BroadcastReceiver, Room; plain `kotlin.test` for pure functions).

## Global Constraints

- Never use `fallbackToDestructiveMigration()` — this plan makes no schema changes, so this shouldn't come up, but do not introduce one.
- Alarms are always inexact (`AlarmManager.setWindow`, never `setExactAndAllowWhileIdle`).
- Every Robolectric test touching Room must pin `.setQueryCoroutineContext(StandardTestDispatcher(testScheduler))` to the same scheduler as `runTest`, and any code using `viewModelScope` needs `Dispatchers.setMain(StandardTestDispatcher(testScheduler))` in `@Before`/at the top of the test, `Dispatchers.resetMain()` in `@After` (see `HomeViewModelTest.kt`, `HistoryViewModelTest.kt`).
- Every test class that touches `AlarmManager` needs the `@Before` cleanup: `shadowOf(alarmManager).getScheduledAlarms().forEach { it.operation?.let(alarmManager::cancel) }` — shadow state has been observed leaking across test classes in the full suite run.
- `BroadcastReceiver` tests must dispatch via `context.registerReceiver(...)` + `context.sendBroadcast(...)` + `shadowOf(Looper.getMainLooper()).idle()` — never call `.onReceive()` directly (leaves `goAsync()`'s `PendingResult` null → NPE on `.finish()`).
- All Robolectric test classes use `@RunWith(RobolectricTestRunner::class)` and `@Config(sdk = [35])`.
- TDD throughout: write the failing test, run it, confirm it fails for the right reason, then write minimal code to pass. Every task's production code must be covered by a test written earlier in that same task — never bundle another task's untested code into a "while I'm in this file" rewrite.
- Commit after each task with `git add <specific files>` (never `-A`/`.`) — this repo has a `.claude/` directory that must never be committed.
- Run `cd "D:/Users/zivk/Documents/GitHub/ReadBook" &&` before every Gradle/adb command (Bash tool, not PowerShell, for this repo's established workflow in this session).

---

## Task 1: `ReadingTimerRepository` — completed-guard on `start()`, and `resetToday()`

**Files:**
- Modify: `app/src/main/java/com/example/readbook/data/ReadingTimerRepository.kt`
- Test: `app/src/test/java/com/example/readbook/data/ReadingTimerRepositoryTest.kt`

**Interfaces:**
- Consumes: existing `DailyProgressDao`, `ReadingSessionDao`, `ReadingConfigDao`, `StatsDao`, `Clock` (all already constructor params), `StreakCalculator.calculate(completedDates: Set<LocalDate>, enabledDaysMask: Int, today: LocalDate): Int` (existing, in `com.example.readbook.data`), `DEFAULT_TARGET_SECONDS`, `DEFAULT_ENABLED_DAYS_MASK` (existing constants in `ReadingConfig.kt`).
- Produces: `ReadingTimerRepository.resetToday(date: LocalDate): DailyProgress?` — used by Task 2's `HomeViewModel`. `start()` keeps its existing signature `start(date: LocalDate): DailyProgress` but gains a no-op guard when the day is already completed.

- [ ] **Step 1: Write the failing tests**

Open `app/src/test/java/com/example/readbook/data/ReadingTimerRepositoryTest.kt` and add these five tests at the end of the class, just before the closing `}`:

```kotlin
    @Test
    fun start_whenTodayIsAlreadyCompleted_isANoOp() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis += DEFAULT_TARGET_SECONDS * 1000L
        val completed = repository.stop(today)
        assertEquals(true, completed?.completed)

        clock.millis += 500_000L
        val result = repository.start(today)

        assertEquals(completed, result)
        assertNull(db.dailyProgressDao().getByDate(today.toString())?.activeSessionStartedAt)
    }

    @Test
    fun resetToday_onAPausedInProgressDay_restoresFullDuration() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis += 120_000L
        repository.stop(today) // paused with remainingSeconds = target - 120

        val reset = repository.resetToday(today)

        assertEquals(DEFAULT_TARGET_SECONDS, reset?.remainingSeconds)
        assertNull(reset?.activeSessionStartedAt)
        assertEquals(false, reset?.completed)
    }

    @Test
    fun resetToday_whenNoRowExistsForToday_isANoOp() = runTest {
        val result = repository.resetToday(today)

        assertNull(result)
    }

    @Test
    fun resetToday_onACompletedDay_unCompletesAndRollsBackStats() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis += DEFAULT_TARGET_SECONDS * 1000L
        repository.stop(today) // completes today

        val statsAfterCompletion = db.statsDao().getStats()
        assertEquals(1, statsAfterCompletion?.totalCompletedDays)
        assertEquals(1, statsAfterCompletion?.currentStreak)

        val reset = repository.resetToday(today)

        assertEquals(false, reset?.completed)
        assertNull(reset?.completedAt)
        assertEquals(DEFAULT_TARGET_SECONDS, reset?.remainingSeconds)

        val statsAfterReset = db.statsDao().getStats()
        assertEquals(0, statsAfterReset?.totalCompletedDays)
        assertEquals(0, statsAfterReset?.currentStreak)
    }

    @Test
    fun resetToday_onACompletedDay_doesNotDeletePastReadingSessions() = runTest {
        clock.millis = 1_000_000L
        repository.start(today)
        clock.millis += DEFAULT_TARGET_SECONDS * 1000L
        repository.stop(today)

        repository.resetToday(today)

        assertEquals(1, db.readingSessionDao().getByDate(today.toString()).size)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.data.ReadingTimerRepositoryTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'resetToday'` (the other test, `start_whenTodayIsAlreadyCompleted_isANoOp`, would compile fine but fail at runtime since the guard doesn't exist yet; the compile error from the missing `resetToday` will mask it, which is fine — both are "fails for the right reason").

- [ ] **Step 3: Implement the guard and `resetToday()`**

Replace the `start` function in `app/src/main/java/com/example/readbook/data/ReadingTimerRepository.kt`:

```kotlin
    suspend fun start(date: LocalDate): DailyProgress {
        val key = date.toString()
        val existing = dailyProgressDao.getByDate(key)
        if (existing?.completed == true) return existing // guard: don't restart an already-completed day
        val row = if (existing != null) {
            existing.copy(activeSessionStartedAt = clock.nowMillis())
        } else {
            val config = readingConfigDao.getConfig()
            val targetSeconds = config?.targetSeconds ?: DEFAULT_TARGET_SECONDS
            DailyProgress(
                date = key,
                targetSeconds = targetSeconds,
                remainingSeconds = targetSeconds,
                completed = false,
                completedAt = null,
                activeSessionStartedAt = clock.nowMillis(),
            )
        }
        dailyProgressDao.upsert(row)
        return row
    }
```

Then add `resetToday` and its private helper right after `reconcileCrashedSession`, before `finishSession`:

```kotlin
    /**
     * Restores today's remaining time to the full configured duration. If today was already
     * completed, also un-completes it and rolls back [Stats] — recalculating the streak by
     * re-running [StreakCalculator] with today excluded from the completed-dates set, rather
     * than any bespoke undo logic. A no-op (returns null) if there's no row for today at all.
     */
    suspend fun resetToday(date: LocalDate): DailyProgress? {
        val key = date.toString()
        val existing = dailyProgressDao.getByDate(key) ?: return null

        if (existing.completed) {
            rollBackStatsForUncompletedDay(date)
        }

        val config = readingConfigDao.getConfig()
        val targetSeconds = config?.targetSeconds ?: DEFAULT_TARGET_SECONDS
        val reset = existing.copy(
            targetSeconds = targetSeconds,
            remainingSeconds = targetSeconds,
            completed = false,
            completedAt = null,
            activeSessionStartedAt = null,
        )
        dailyProgressDao.upsert(reset)
        return reset
    }

    private suspend fun rollBackStatsForUncompletedDay(date: LocalDate) {
        val remainingCompletedDates = dailyProgressDao.getCompletedDates()
            .map { LocalDate.parse(it) }
            .filter { it != date }
            .toSet()
        val config = readingConfigDao.getConfig()
        val enabledDaysMask = config?.enabledDaysMask ?: DEFAULT_ENABLED_DAYS_MASK
        val newStreak = StreakCalculator.calculate(remainingCompletedDates, enabledDaysMask, date)

        val priorStats = statsDao.getStats() ?: Stats(totalCompletedDays = 0, currentStreak = 0)
        statsDao.upsert(
            priorStats.copy(
                totalCompletedDays = (priorStats.totalCompletedDays - 1).coerceAtLeast(0),
                currentStreak = newStreak,
            )
        )
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.data.ReadingTimerRepositoryTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all tests (existing 8 + new 5 = 13) pass.

- [ ] **Step 5: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add app/src/main/java/com/example/readbook/data/ReadingTimerRepository.kt app/src/test/java/com/example/readbook/data/ReadingTimerRepositoryTest.kt
git commit -m "$(cat <<'EOF'
Add ReadingTimerRepository.resetToday() and a completed-day start guard

resetToday() restores the full configured duration and, if today was
already completed, rolls back Stats via the existing StreakCalculator
(no new streak math). The start() guard stops a stale entry point (e.g.
a notification action tapped after the day already finished) from
reopening a completed day.
EOF
)"
```

---

## Task 2: Wire the "Reset today" button into Home

**Files:**
- Modify: `app/src/main/java/com/example/readbook/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/example/readbook/ui/home/HomeViewModelFactory.kt`
- Modify: `app/src/main/java/com/example/readbook/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/example/readbook/MainActivity.kt`
- Test: `app/src/test/java/com/example/readbook/ui/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `ReadingTimerRepository.resetToday(date: LocalDate): DailyProgress?` (Task 1). `AppContainer.readingTimerRepository` (existing, in `com.example.readbook.data.AppContainer`).
- Produces: `HomeViewModel.onResetToday(): Unit` (fire-and-forget, launches in `viewModelScope`). `HomeScreen`'s new `onResetToday: () -> Unit` parameter.

- [ ] **Step 1: Write the failing test**

Open `app/src/test/java/com/example/readbook/ui/home/HomeViewModelTest.kt`. First, update the `buildViewModel` helper (replace the whole function) to also build a `ReadingTimerRepository` and pass it to `HomeViewModel`:

```kotlin
    private fun TestScope.buildViewModel(clock: Clock, today: LocalDate): Pair<HomeViewModel, AppDatabase> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        val repository = ReadingTimerRepository(
            dailyProgressDao = db.dailyProgressDao(),
            readingSessionDao = db.readingSessionDao(),
            readingConfigDao = db.readingConfigDao(),
            statsDao = db.statsDao(),
            clock = clock,
        )
        val viewModel = HomeViewModel(
            dailyProgressDao = db.dailyProgressDao(),
            readingConfigDao = db.readingConfigDao(),
            repository = repository,
            clock = clock,
            today = { today },
        )
        testScheduler.runCurrent()
        return viewModel to db
    }
```

Add the import `com.example.readbook.data.ReadingTimerRepository` alongside the other `com.example.readbook.data.*` imports at the top of the file.

Then add this test at the end of the class, before the closing `}`:

```kotlin
    @Test
    fun onResetToday_restoresFullDurationOnAPausedDay() = runTest {
        val today = LocalDate.of(2026, 7, 5)
        val (viewModel, db) = buildViewModel(FakeClock(millis = 1000L), today)
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        db.dailyProgressDao().upsert(
            DailyProgress(
                date = today.toString(), targetSeconds = 900, remainingSeconds = 500,
                completed = false, completedAt = null, activeSessionStartedAt = null,
            )
        )
        testScheduler.runCurrent()

        viewModel.onResetToday()
        testScheduler.runCurrent()

        val row = db.dailyProgressDao().getByDate(today.toString())
        assertEquals(DEFAULT_TARGET_SECONDS, row?.remainingSeconds)

        db.close()
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.ui.home.HomeViewModelTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `No value passed for parameter 'repository'` (constructor mismatch) and `Unresolved reference 'onResetToday'`.

- [ ] **Step 3: Implement `HomeViewModel.onResetToday()`**

Replace the full content of `app/src/main/java/com/example/readbook/ui/home/HomeViewModel.kt`:

```kotlin
package com.example.readbook.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readbook.data.Clock
import com.example.readbook.data.DailyProgressDao
import com.example.readbook.data.ReadingConfigDao
import com.example.readbook.data.ReadingTimerRepository
import com.example.readbook.data.SystemClock
import com.example.readbook.service.ReadingTimerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class HomeViewModel(
    dailyProgressDao: DailyProgressDao,
    readingConfigDao: ReadingConfigDao,
    private val repository: ReadingTimerRepository,
    private val clock: Clock = SystemClock,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val notificationsDenied = MutableStateFlow(false)

    fun setNotificationsDenied(denied: Boolean) {
        notificationsDenied.value = denied
    }

    val uiState: StateFlow<HomeUiState> = combine(
        readingConfigDao.observeConfig(),
        dailyProgressDao.observeByDate(today().toString()),
        notificationsDenied,
    ) { config, progress, denied ->
        deriveHomeUiState(config, progress, today(), clock.nowMillis(), denied)
    }.stateIn(
        viewModelScope,
        // Eagerly, not WhileSubscribed — this is a single-screen app with one subscriber and
        // no cost concern; Eagerly also means tests can read .value without needing to collect.
        SharingStarted.Eagerly,
        HomeUiState.NotConfigured(notificationsDenied = false),
    )

    /** No-op from [HomeUiState.NotConfigured] or [HomeUiState.Done] — nothing to toggle there. */
    fun onToggleTimer(context: Context) {
        val action = when (val state = uiState.value) {
            is HomeUiState.InProgress -> if (state.isRunning) {
                ReadingTimerService.ACTION_STOP
            } else {
                ReadingTimerService.ACTION_START
            }
            is HomeUiState.NonEnabledDay -> ReadingTimerService.ACTION_START
            is HomeUiState.NotConfigured, is HomeUiState.Done -> return
        }
        context.startService(Intent(context, ReadingTimerService::class.java).setAction(action))
    }

    /** Only ever called from the UI when the timer is not running (see HomeScreen) — no service
     * coordination needed, since by construction nothing is running to race with. */
    fun onResetToday() {
        viewModelScope.launch {
            repository.resetToday(today())
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.ui.home.HomeViewModelTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all tests (existing 6 + new 1 = 7) pass. `HomeViewModel` compiles standalone even before its callers are updated, since Kotlin allows adding a constructor param and updating a single call site.

- [ ] **Step 5: Update `HomeViewModelFactory`**

Replace the full content of `app/src/main/java/com/example/readbook/ui/home/HomeViewModelFactory.kt`:

```kotlin
package com.example.readbook.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.readbook.data.DailyProgressDao
import com.example.readbook.data.ReadingConfigDao
import com.example.readbook.data.ReadingTimerRepository

class HomeViewModelFactory(
    private val dailyProgressDao: DailyProgressDao,
    private val readingConfigDao: ReadingConfigDao,
    private val repository: ReadingTimerRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return HomeViewModel(dailyProgressDao, readingConfigDao, repository) as T
    }
}
```

- [ ] **Step 6: Update `HomeScreen` to show the Reset button**

Replace the full content of `app/src/main/java/com/example/readbook/ui/home/HomeScreen.kt`:

```kotlin
package com.example.readbook.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onToggleTimer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onResetToday: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ReadBook") },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "History")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (uiState.notificationsDenied) {
                NotificationsOffBanner()
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (uiState) {
                    is HomeUiState.NotConfigured -> Text("Setting up…")
                    is HomeUiState.NonEnabledDay -> NonEnabledDayContent(onToggleTimer)
                    is HomeUiState.Done -> DoneContent(onResetToday)
                    is HomeUiState.InProgress -> InProgressContent(uiState, onToggleTimer, onResetToday)
                }
            }
        }
    }
}

@Composable
private fun NotificationsOffBanner() {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            "Notifications are off — nudges won't fire",
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun NonEnabledDayContent(onToggleTimer: () -> Unit) {
    Text("Today isn't a scheduled reading day.")
    Text("Want to read anyway?")
    Button(onClick = onToggleTimer, modifier = Modifier.padding(top = 16.dp)) {
        Text("Start")
    }
}

@Composable
private fun DoneContent(onResetToday: () -> Unit) {
    Text("Nice — today's reading is done")
    TextButton(onClick = onResetToday, modifier = Modifier.padding(top = 16.dp)) {
        Text("Reset today")
    }
}

@Composable
private fun InProgressContent(
    state: HomeUiState.InProgress,
    onToggleTimer: () -> Unit,
    onResetToday: () -> Unit,
) {
    // Live 1Hz countdown while running — the ViewModel/DB only update on Start/Stop/Completion,
    // not every second, so the visual tick lives here and resets whenever the underlying state
    // (a new baseline remainingSeconds, or isRunning flipping) actually changes.
    var displaySeconds by remember(state) { mutableIntStateOf(state.remainingSeconds) }

    LaunchedEffect(state) {
        while (state.isRunning && displaySeconds > 0) {
            delay(1000)
            displaySeconds -= 1
        }
    }

    val minutes = displaySeconds / 60
    val seconds = displaySeconds % 60
    Text(String.format("%d:%02d", minutes, seconds), style = MaterialTheme.typography.displayMedium)
    Button(onClick = onToggleTimer, modifier = Modifier.padding(top = 16.dp)) {
        Text(if (state.isRunning) "Stop" else "Start")
    }
    if (!state.isRunning) {
        TextButton(onClick = onResetToday, modifier = Modifier.padding(top = 8.dp)) {
            Text("Reset today")
        }
    }
}
```

- [ ] **Step 7: Wire `MainActivity`**

In `app/src/main/java/com/example/readbook/MainActivity.kt`, change the `HomeViewModelFactory` construction (around line 46-49):

```kotlin
        val container = (application as ReadingApp).container
        homeViewModel = ViewModelProvider(
            this,
            HomeViewModelFactory(container.dailyProgressDao, container.readingConfigDao, container.readingTimerRepository),
        )[HomeViewModel::class.java]
```

And change the `HomeScreen(...)` call inside `Screen.HOME` branch:

```kotlin
                    Screen.HOME -> {
                        val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
                        HomeScreen(
                            uiState = uiState,
                            onToggleTimer = { homeViewModel.onToggleTimer(this) },
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onOpenHistory = { screen = Screen.HISTORY },
                            onResetToday = { homeViewModel.onResetToday() },
                        )
                    }
```

- [ ] **Step 8: Run the full test suite**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest assembleDebug --rerun-tasks 2>&1 | tail -50`

Expected: `BUILD SUCCESSFUL`, all tests pass.

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && total=0; failed=0; for f in app/build/test-results/testDebugUnitTest/*.xml; do t=$(grep -oE 'tests="[0-9]+"' "$f" | grep -oE '[0-9]+'); fl=$(grep -oE 'failures="[0-9]+"' "$f" | grep -oE '[0-9]+'); total=$((total+t)); failed=$((failed+fl)); done; echo "TOTAL=$total FAILED=$failed"`

Expected: `FAILED=0`.

- [ ] **Step 9: Manual device verification**

Requires the physical Android device connected (`$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe devices`). Install the freshly built `app/build/outputs/apk/debug/app-debug.apk`, launch the app, and:
1. Start the timer, then Stop it partway through (creating a paused `InProgress(isRunning=false)` state) — confirm a "Reset today" text button appears below Start, tap it, confirm the countdown jumps back to the full configured duration (e.g. 6:59 → 14:59 for a 15-minute config, or whatever the current configured minutes are).
2. Let a session run to completion (or use a very short configured duration via Settings to make this fast) — confirm the Done screen shows "Reset today", tap it, confirm it returns to a fresh `15:00`-style countdown with a Start button (not Done).
3. Check `adb logcat` for crashes: `adb logcat -d -t 300 | grep -iE "readbook.*(exception|error|crash|FATAL)" | grep -v "ForegroundServiceTypeLogger"` — expect no output.

- [ ] **Step 10: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/ui/home/HomeViewModel.kt \
  app/src/main/java/com/example/readbook/ui/home/HomeViewModelFactory.kt \
  app/src/main/java/com/example/readbook/ui/home/HomeScreen.kt \
  app/src/main/java/com/example/readbook/MainActivity.kt \
  app/src/test/java/com/example/readbook/ui/home/HomeViewModelTest.kt
git commit -m "$(cat <<'EOF'
Add Reset today button to Home screen

Shown only when the timer is not running (paused mid-session, or Done)
to avoid racing the foreground service's in-memory auto-complete job.
Verified on-device: resets a paused session to full duration, and
resets a completed day back to a fresh in-progress state.
EOF
)"
```

---

## Task 3: `NudgeScheduler.scheduleSnooze()` and the `NudgeReceiver` snooze branch

**Files:**
- Modify: `app/src/main/java/com/example/readbook/scheduling/NudgeScheduler.kt` (targeted edit — only adds snooze-related code; the weekly-summary alarm is a separate, later task's edit to this same file)
- Modify: `app/src/main/java/com/example/readbook/scheduling/NudgeReceiver.kt`
- Test: `app/src/test/java/com/example/readbook/scheduling/NudgeSchedulerTest.kt`
- Test: `app/src/test/java/com/example/readbook/scheduling/NudgeReceiverTest.kt`

**Interfaces:**
- Consumes: existing `NudgeScheduler.ACTION_NUDGE`, `NudgeReceiver` class reference, `WINDOW_LENGTH_MS` (existing constant).
- Produces: `NudgeScheduler.scheduleSnooze(): Unit` and `NudgeScheduler.SNOOZE_REQUEST_CODE`/`SNOOZE_DELAY_MS` constants (used by Task 4's notification action). `NudgeReceiver.EXTRA_SNOOZE: String` constant and a new `nudgeSchedulerOverride` test seam field (used by Task 4's notification-action `Intent`, and this task's own test).

- [ ] **Step 1: Write the failing test for `scheduleSnooze()`**

Open `app/src/test/java/com/example/readbook/scheduling/NudgeSchedulerTest.kt` and add this test at the end of the class, before the closing `}`:

```kotlin
    @Test
    fun scheduleSnooze_schedulesExactlyOneAlarmFifteenMinutesOut() {
        clock.millis = epochMillisAt(enabledDay, hour = 10)

        scheduler.scheduleSnooze()

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        assertEquals(1, alarms.size)
        assertEquals(clock.millis + 15 * 60 * 1000L, alarms[0].triggerAtTime)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.NudgeSchedulerTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'scheduleSnooze'`.

- [ ] **Step 3: Implement `scheduleSnooze()`**

In `app/src/main/java/com/example/readbook/scheduling/NudgeScheduler.kt`, find this exact block:

```kotlin
    /** Schedules the daily rollover job for 00:01 the day after [from]. */
    fun scheduleRollover(from: LocalDate) {
        val nextMidnight = epochMillisAt(from.plusDays(1), hour = 0, minute = 1)
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, nextMidnight, WINDOW_LENGTH_MS, rolloverPendingIntent())
    }

    private fun epochMillisAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
```

Replace it with:

```kotlin
    /** Schedules the daily rollover job for 00:01 the day after [from]. */
    fun scheduleRollover(from: LocalDate) {
        val nextMidnight = epochMillisAt(from.plusDays(1), hour = 0, minute = 1)
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, nextMidnight, WINDOW_LENGTH_MS, rolloverPendingIntent())
    }

    /** Schedules exactly one extra nudge-check 15 minutes out — triggered by tapping "Snooze"
     * on a nudge notification. Reuses the normal ACTION_NUDGE path; when it fires it's a
     * completely ordinary NudgeReceiver invocation (same completion check, same notification). */
    fun scheduleSnooze() {
        val triggerAt = clock.nowMillis() + SNOOZE_DELAY_MS
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, WINDOW_LENGTH_MS, snoozePendingIntent())
    }

    private fun epochMillisAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
```

Then find this exact block:

```kotlin
    private fun rolloverPendingIntent(): PendingIntent {
        val intent = Intent(context, RolloverReceiver::class.java).setAction(ACTION_ROLLOVER)
        return PendingIntent.getBroadcast(
            context, ROLLOVER_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        val NUDGE_HOURS = listOf(9, 10, 11, 12, 13)
        const val WINDOW_LENGTH_MS = 15 * 60 * 1000L
        const val ROLLOVER_REQUEST_CODE = 100
        const val ACTION_NUDGE = "com.example.readbook.action.NUDGE"
        const val ACTION_ROLLOVER = "com.example.readbook.action.ROLLOVER"
    }
}
```

Replace it with:

```kotlin
    private fun rolloverPendingIntent(): PendingIntent {
        val intent = Intent(context, RolloverReceiver::class.java).setAction(ACTION_ROLLOVER)
        return PendingIntent.getBroadcast(
            context, ROLLOVER_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun snoozePendingIntent(): PendingIntent {
        val intent = Intent(context, NudgeReceiver::class.java).setAction(ACTION_NUDGE)
        return PendingIntent.getBroadcast(
            context, SNOOZE_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        val NUDGE_HOURS = listOf(9, 10, 11, 12, 13)
        const val WINDOW_LENGTH_MS = 15 * 60 * 1000L
        const val ROLLOVER_REQUEST_CODE = 100
        const val SNOOZE_REQUEST_CODE = 200
        const val SNOOZE_DELAY_MS = 15 * 60 * 1000L
        const val ACTION_NUDGE = "com.example.readbook.action.NUDGE"
        const val ACTION_ROLLOVER = "com.example.readbook.action.ROLLOVER"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.NudgeSchedulerTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all tests (existing 5 + new 1 = 6) pass.

- [ ] **Step 5: Write the failing test for the `NudgeReceiver` snooze branch**

Open `app/src/test/java/com/example/readbook/scheduling/NudgeReceiverTest.kt`. Add this import at the top, alongside the existing ones:

```kotlin
import android.app.AlarmManager
```

Add this test at the end of the class, before the closing `}`:

```kotlin
    @Test
    fun onReceive_withSnoozeExtra_cancelsNotification_andSchedulesASnoozeAlarm() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        shadowOf(alarmManager).getScheduledAlarms().forEach { it.operation?.let(alarmManager::cancel) }

        val manager = context.getSystemService(NotificationManager::class.java)
        TimerNotifications.createChannels(context)
        manager.notify(NudgeReceiver.NOTIFICATION_ID_NUDGE, TimerNotifications.buildNudgeNotification(context))

        val receiver = NudgeReceiver()
        receiver.today = { LocalDate.of(2026, 7, 5) }
        receiver.dailyProgressDaoOverride = db.dailyProgressDao()
        receiver.nudgeSchedulerOverride = NudgeScheduler(context, FakeClock())
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        context.registerReceiver(receiver, IntentFilter(NudgeScheduler.ACTION_NUDGE), Context.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(NudgeScheduler.ACTION_NUDGE).putExtra(NudgeReceiver.EXTRA_SNOOZE, true))
        shadowOf(Looper.getMainLooper()).idle()
        testScheduler.advanceUntilIdle()

        val stillActive = manager.activeNotifications.firstOrNull { it.id == NudgeReceiver.NOTIFICATION_ID_NUDGE }
        assertNull(stillActive)
        assertEquals(1, shadowOf(alarmManager).getScheduledAlarms().size)

        db.close()
    }
```

Note: `buildNudgeNotification(context)` here builds the notification exactly as it exists at this point in the plan (no action buttons yet — those are added in Task 4). That's fine; this test only checks that the notification gets cancelled and an alarm gets scheduled, not what actions the notification carries.

- [ ] **Step 6: Run test to verify it fails**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.NudgeReceiverTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'nudgeSchedulerOverride'` and `Unresolved reference 'EXTRA_SNOOZE'`.

- [ ] **Step 7: Implement the snooze branch in `NudgeReceiver`**

Replace the full content of `app/src/main/java/com/example/readbook/scheduling/NudgeReceiver.kt`:

```kotlin
package com.example.readbook.scheduling

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.readbook.ReadingApp
import com.example.readbook.data.DailyProgressDao
import com.example.readbook.notifications.TimerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Fired by AlarmManager at each hourly nudge time. A no-op if today is already completed.
 * Also handles the "Snooze 15m" notification action via [EXTRA_SNOOZE]. */
class NudgeReceiver : BroadcastReceiver() {

    // Overridable seams for tests — null means "use the real app's container / a real scope".
    internal var today: () -> LocalDate = { LocalDate.now() }
    internal var dailyProgressDaoOverride: DailyProgressDao? = null
    internal var nudgeSchedulerOverride: NudgeScheduler? = null
    internal var scopeOverride: CoroutineScope? = null

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val dao = dailyProgressDaoOverride
            ?: (context.applicationContext as ReadingApp).container.dailyProgressDao
        val scheduler = nudgeSchedulerOverride
            ?: (context.applicationContext as ReadingApp).container.nudgeScheduler
        val scope = scopeOverride ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                if (intent.getBooleanExtra(EXTRA_SNOOZE, false)) {
                    val manager = context.getSystemService(NotificationManager::class.java)
                    manager.cancel(NOTIFICATION_ID_NUDGE)
                    scheduler.scheduleSnooze()
                    return@launch
                }
                val row = dao.getByDate(today().toString())
                if (row?.completed != true) {
                    TimerNotifications.createChannels(context)
                    val manager = context.getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID_NUDGE, TimerNotifications.buildNudgeNotification(context))
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val NOTIFICATION_ID_NUDGE = 2
        const val EXTRA_SNOOZE = "com.example.readbook.extra.SNOOZE"
    }
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.NudgeReceiverTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all tests (existing 2 + new 1 = 3) pass.

- [ ] **Step 9: Run the full test suite**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --rerun-tasks 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/scheduling/NudgeScheduler.kt \
  app/src/main/java/com/example/readbook/scheduling/NudgeReceiver.kt \
  app/src/test/java/com/example/readbook/scheduling/NudgeSchedulerTest.kt \
  app/src/test/java/com/example/readbook/scheduling/NudgeReceiverTest.kt
git commit -m "$(cat <<'EOF'
Add NudgeScheduler.scheduleSnooze() and a snooze branch in NudgeReceiver

Snooze reuses the ordinary ACTION_NUDGE path rather than duplicating the
completion-check/notify logic in a new receiver: NudgeReceiver just
detects the EXTRA_SNOOZE flag, cancels the current notification, and
schedules one extra nudge-check 15 minutes out via its own request code.
EOF
)"
```

---

## Task 4: Add "Start" and "Snooze 15m" actions to the nudge notification

**Files:**
- Modify: `app/src/main/java/com/example/readbook/notifications/TimerNotifications.kt` (targeted edit — only adds the two notification actions; the weekly-summary channel/builder is a separate, later task's edit to this same file)
- Test: `app/src/test/java/com/example/readbook/notifications/TimerNotificationsTest.kt`

**Interfaces:**
- Consumes: `ReadingTimerService.ACTION_START` (existing, `com.example.readbook.service.ReadingTimerService`), `NudgeReceiver::class`, `NudgeReceiver.EXTRA_SNOOZE` (Task 3), `NudgeScheduler.ACTION_NUDGE` (existing).
- Produces: `buildNudgeNotification(context)` keeps its existing signature but now returns a `Notification` with two actions.

- [ ] **Step 1: Write the failing test**

Open `app/src/test/java/com/example/readbook/notifications/TimerNotificationsTest.kt` and add this test at the end of the class, before the closing `}`:

```kotlin
    @Test
    fun buildNudgeNotification_hasStartAndSnoozeActions() {
        TimerNotifications.createChannels(context)

        val notification = TimerNotifications.buildNudgeNotification(context)

        assertEquals(2, notification.actions.size)
        assertEquals("Start", notification.actions[0].title)
        assertEquals("Snooze 15m", notification.actions[1].title)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.notifications.TimerNotificationsTest" --rerun-tasks 2>&1 | tail -40`

Expected: `NullPointerException` — `notification.actions` is `null` (no actions added yet), so `.size` throws. This is "fails for the right reason" — the assertion target doesn't exist.

- [ ] **Step 3: Implement the actions**

In `app/src/main/java/com/example/readbook/notifications/TimerNotifications.kt`, find this exact block (the imports):

```kotlin
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.readbook.R
```

Replace it with:

```kotlin
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.readbook.R
import com.example.readbook.scheduling.NudgeReceiver
import com.example.readbook.scheduling.NudgeScheduler
import com.example.readbook.service.ReadingTimerService
```

Then find this exact block:

```kotlin
object TimerNotifications {
    const val CHANNEL_NUDGE = "nudge"
    const val CHANNEL_TIMER = "timer"
    const val CHANNEL_COMPLETION = "completion"

    fun createChannels(context: Context) {
```

Replace it with:

```kotlin
object TimerNotifications {
    const val CHANNEL_NUDGE = "nudge"
    const val CHANNEL_TIMER = "timer"
    const val CHANNEL_COMPLETION = "completion"

    private const val START_ACTION_REQUEST_CODE = 300
    private const val SNOOZE_ACTION_REQUEST_CODE = 301

    fun createChannels(context: Context) {
```

Then find this exact block:

```kotlin
    fun buildNudgeNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_NUDGE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText("15 minutes today?")
            .setAutoCancel(true)
            .build()
```

Replace it with:

```kotlin
    fun buildNudgeNotification(context: Context): Notification {
        val startIntent = Intent(context, ReadingTimerService::class.java).setAction(ReadingTimerService.ACTION_START)
        val startPendingIntent = PendingIntent.getForegroundService(
            context, START_ACTION_REQUEST_CODE, startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snoozeIntent = Intent(context, NudgeReceiver::class.java)
            .setAction(NudgeScheduler.ACTION_NUDGE)
            .putExtra(NudgeReceiver.EXTRA_SNOOZE, true)
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context, SNOOZE_ACTION_REQUEST_CODE, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_NUDGE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText("15 minutes today?")
            .setAutoCancel(true)
            .addAction(R.drawable.ic_launcher_foreground, "Start", startPendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Snooze 15m", snoozePendingIntent)
            .build()
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.notifications.TimerNotificationsTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all tests (existing 6 + new 1 = 7) pass.

- [ ] **Step 5: Run the full test suite**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest assembleDebug --rerun-tasks 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Manual device verification**

Requires the physical device connected. Install `app-debug.apk`. Trigger a nudge notification directly (faster than waiting for a real hourly slot):

```bash
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe shell am broadcast -n com.example.readbook/.scheduling.NudgeReceiver -a com.example.readbook.action.NUDGE
```

Pull down the notification shade, confirm the nudge notification shows two action buttons: "Start" and "Snooze 15m". Tap "Start" — confirm the app's foreground timer notification appears and the Home screen (if opened) shows the timer running, without having tapped the notification body or opened the app first. Re-trigger the same broadcast command, then tap "Snooze 15m" — confirm the notification disappears immediately. Check `adb logcat -d -t 300 | grep -iE "readbook.*(exception|error|crash|FATAL)" | grep -v "ForegroundServiceTypeLogger"` for crashes.

- [ ] **Step 7: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/notifications/TimerNotifications.kt \
  app/src/test/java/com/example/readbook/notifications/TimerNotificationsTest.kt
git commit -m "$(cat <<'EOF'
Add Start/Snooze action buttons to the nudge notification

Start targets ReadingTimerService directly via getForegroundService —
Android permits starting a foreground service this way because a
notification-action tap is a direct user interaction. Snooze broadcasts
to NudgeReceiver with EXTRA_SNOOZE, added in the prior task.
EOF
)"
```

---

## Task 5: `computeWeeklySummary` pure function

**Files:**
- Create: `app/src/main/java/com/example/readbook/data/WeeklySummary.kt`
- Test: `app/src/test/java/com/example/readbook/data/WeeklySummaryTest.kt`

**Interfaces:**
- Consumes: `isEnabledDay(date: LocalDate, enabledDaysMask: Int): Boolean` (existing, `com.example.readbook.data.EnabledDays.kt`), `DEFAULT_ENABLED_DAYS_MASK` (existing).
- Produces: `data class WeeklySummary(val completedCount: Int, val enabledCount: Int)` and `computeWeeklySummary(enabledDaysMask: Int, weekStart: LocalDate, completedDates: Set<LocalDate>): WeeklySummary` — used by Task 6's notification builder and Task 7's `WeeklySummaryReceiver`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/example/readbook/data/WeeklySummaryTest.kt`:

```kotlin
package com.example.readbook.data

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class WeeklySummaryTest {

    private val sunday = LocalDate.of(2026, 7, 5) // week start

    @Test
    fun `counts only enabled days in the denominator`() {
        val summary = computeWeeklySummary(
            enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, // Sun-Thu
            weekStart = sunday,
            completedDates = emptySet(),
        )

        assertEquals(5, summary.enabledCount)
        assertEquals(0, summary.completedCount)
    }

    @Test
    fun `counts completed enabled days in the numerator`() {
        val completed = setOf(sunday, sunday.plusDays(1), sunday.plusDays(4)) // Sun, Mon, Thu

        val summary = computeWeeklySummary(
            enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK,
            weekStart = sunday,
            completedDates = completed,
        )

        assertEquals(3, summary.completedCount)
        assertEquals(5, summary.enabledCount)
    }

    @Test
    fun `a completed date outside the enabled mask does not count`() {
        val friday = sunday.plusDays(5) // disabled by default
        val summary = computeWeeklySummary(
            enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK,
            weekStart = sunday,
            completedDates = setOf(friday),
        )

        assertEquals(0, summary.completedCount)
        assertEquals(5, summary.enabledCount)
    }

    @Test
    fun `a completed date outside the 7-day window does not count`() {
        val nextWeekSunday = sunday.plusDays(7)
        val summary = computeWeeklySummary(
            enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK,
            weekStart = sunday,
            completedDates = setOf(nextWeekSunday),
        )

        assertEquals(0, summary.completedCount)
    }

    @Test
    fun `zero enabled days yields zero enabledCount`() {
        val summary = computeWeeklySummary(enabledDaysMask = 0, weekStart = sunday, completedDates = setOf(sunday))

        assertEquals(0, summary.enabledCount)
        assertEquals(0, summary.completedCount)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.data.WeeklySummaryTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'computeWeeklySummary'`.

- [ ] **Step 3: Implement `computeWeeklySummary`**

Create `app/src/main/java/com/example/readbook/data/WeeklySummary.kt`:

```kotlin
package com.example.readbook.data

import java.time.LocalDate

data class WeeklySummary(val completedCount: Int, val enabledCount: Int)

/**
 * Walks the 7 days starting at [weekStart] (inclusive), counting how many are enabled per
 * [enabledDaysMask] and how many of those enabled days are in [completedDates].
 */
fun computeWeeklySummary(
    enabledDaysMask: Int,
    weekStart: LocalDate,
    completedDates: Set<LocalDate>,
): WeeklySummary {
    var enabledCount = 0
    var completedCount = 0
    for (offset in 0 until 7) {
        val date = weekStart.plusDays(offset.toLong())
        if (isEnabledDay(date, enabledDaysMask)) {
            enabledCount++
            if (date in completedDates) completedCount++
        }
    }
    return WeeklySummary(completedCount = completedCount, enabledCount = enabledCount)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.data.WeeklySummaryTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all 5 tests pass.

- [ ] **Step 5: Run the full test suite**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --rerun-tasks 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add app/src/main/java/com/example/readbook/data/WeeklySummary.kt app/src/test/java/com/example/readbook/data/WeeklySummaryTest.kt
git commit -m "$(cat <<'EOF'
Implement computeWeeklySummary pure function

Walks the 7-day window and counts enabled-vs-completed using the same
isEnabledDay used everywhere else.
EOF
)"
```

---

## Task 6: Weekly summary notification channel and builder

**Files:**
- Modify: `app/src/main/java/com/example/readbook/notifications/TimerNotifications.kt` (targeted edit — adds only the weekly-summary channel/builder on top of Task 4's Start/Snooze actions)
- Test: `app/src/test/java/com/example/readbook/notifications/TimerNotificationsTest.kt`

**Interfaces:**
- Consumes: `WeeklySummary` data class (Task 5).
- Produces: `TimerNotifications.CHANNEL_WEEKLY_SUMMARY: String` and `TimerNotifications.buildWeeklySummaryNotification(context, summary): Notification` — used by Task 7's `WeeklySummaryReceiver`.

- [ ] **Step 1: Write the failing tests**

Open `app/src/test/java/com/example/readbook/notifications/TimerNotificationsTest.kt`. Add this import:

```kotlin
import com.example.readbook.data.WeeklySummary
```

Add these two tests at the end of the class, before the closing `}`:

```kotlin
    @Test
    fun createChannels_registersWeeklySummaryChannel_withDefaultImportance() {
        TimerNotifications.createChannels(context)

        val channel = manager.getNotificationChannel(TimerNotifications.CHANNEL_WEEKLY_SUMMARY)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun buildWeeklySummaryNotification_usesWeeklySummaryChannel_andReportsTheCounts() {
        TimerNotifications.createChannels(context)

        val notification = TimerNotifications.buildWeeklySummaryNotification(
            context, WeeklySummary(completedCount = 4, enabledCount = 5),
        )

        assertEquals(TimerNotifications.CHANNEL_WEEKLY_SUMMARY, notification.channelId)
        assertEquals("You read 4/5 days last week", shadowContentText(notification))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.notifications.TimerNotificationsTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'CHANNEL_WEEKLY_SUMMARY'` and `'buildWeeklySummaryNotification'`.

- [ ] **Step 3: Implement the channel and builder**

In `app/src/main/java/com/example/readbook/notifications/TimerNotifications.kt`, find this exact block (the imports, as left by Task 4):

```kotlin
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.readbook.R
import com.example.readbook.scheduling.NudgeReceiver
import com.example.readbook.scheduling.NudgeScheduler
import com.example.readbook.service.ReadingTimerService
```

Replace it with:

```kotlin
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.readbook.R
import com.example.readbook.data.WeeklySummary
import com.example.readbook.scheduling.NudgeReceiver
import com.example.readbook.scheduling.NudgeScheduler
import com.example.readbook.service.ReadingTimerService
```

Then find this exact block:

```kotlin
    const val CHANNEL_NUDGE = "nudge"
    const val CHANNEL_TIMER = "timer"
    const val CHANNEL_COMPLETION = "completion"

    private const val START_ACTION_REQUEST_CODE = 300
    private const val SNOOZE_ACTION_REQUEST_CODE = 301

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NUDGE, "Reading reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TIMER, "Reading timer", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPLETION, "Reading completed", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
```

Replace it with:

```kotlin
    const val CHANNEL_NUDGE = "nudge"
    const val CHANNEL_TIMER = "timer"
    const val CHANNEL_COMPLETION = "completion"
    const val CHANNEL_WEEKLY_SUMMARY = "weekly_summary"

    private const val START_ACTION_REQUEST_CODE = 300
    private const val SNOOZE_ACTION_REQUEST_CODE = 301

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NUDGE, "Reading reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TIMER, "Reading timer", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPLETION, "Reading completed", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WEEKLY_SUMMARY, "Weekly summary", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
```

Finally, find this exact block (the end of the file):

```kotlin
    fun buildCompletionNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_COMPLETION)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText("Nice — today's reading is done")
            .setAutoCancel(true)
            .build()
}
```

Replace it with:

```kotlin
    fun buildCompletionNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_COMPLETION)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText("Nice — today's reading is done")
            .setAutoCancel(true)
            .build()

    fun buildWeeklySummaryNotification(context: Context, summary: WeeklySummary): Notification =
        NotificationCompat.Builder(context, CHANNEL_WEEKLY_SUMMARY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText("You read ${summary.completedCount}/${summary.enabledCount} days last week")
            .setAutoCancel(true)
            .build()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.notifications.TimerNotificationsTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all tests (existing 7 + new 2 = 9) pass.

- [ ] **Step 5: Run the full test suite**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --rerun-tasks 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add app/src/main/java/com/example/readbook/notifications/TimerNotifications.kt app/src/test/java/com/example/readbook/notifications/TimerNotificationsTest.kt
git commit -m "$(cat <<'EOF'
Add weekly summary notification channel and builder

New CHANNEL_WEEKLY_SUMMARY so it can be muted independently of daily
nudges. buildWeeklySummaryNotification renders "You read X/Y days last
week" from a WeeklySummary.
EOF
)"
```

---

## Task 7: `NudgeScheduler.scheduleWeeklySummary()` and `WeeklySummaryReceiver`

**Files:**
- Modify: `app/src/main/java/com/example/readbook/scheduling/NudgeScheduler.kt` (targeted edit — adds the weekly-summary alarm on top of Task 3's snooze alarm)
- Create: `app/src/main/java/com/example/readbook/scheduling/WeeklySummaryReceiver.kt`
- Test: `app/src/test/java/com/example/readbook/scheduling/NudgeSchedulerTest.kt`
- Test: `app/src/test/java/com/example/readbook/scheduling/WeeklySummaryReceiverTest.kt`

**Interfaces:**
- Consumes: `computeWeeklySummary` (Task 5), `TimerNotifications.buildWeeklySummaryNotification`/`createChannels` (Task 6), `DailyProgressDao.getCompletedDates(): List<String>` (existing), `ReadingConfigDao.getConfig(): ReadingConfig?` (existing), `AppContainer.dailyProgressDao`/`readingConfigDao`/`nudgeScheduler` (existing).
- Produces: `NudgeScheduler.scheduleWeeklySummary(from: LocalDate): Unit`, `NudgeScheduler.ACTION_WEEKLY_SUMMARY`/`WEEKLY_SUMMARY_REQUEST_CODE` constants, `WeeklySummaryReceiver.NOTIFICATION_ID_WEEKLY_SUMMARY` constant and test seams (`today`, `dailyProgressDaoOverride`, `readingConfigDaoOverride`, `schedulerOverride`, `scopeOverride`) — used by Task 8's manifest/self-heal wiring (no code dependency, just needs the class to exist and be registered).

Note on sequencing within this task: `NudgeScheduler.scheduleWeeklySummary` references `WeeklySummaryReceiver::class.java` as a `PendingIntent` target, so it cannot compile until that class exists. Step 3 below creates `WeeklySummaryReceiver` as an empty shell (no behavior — nothing to test yet) purely so the scheduler code compiles; the receiver's actual logic is then driven out by its own failing tests in Steps 5-7, same as every other piece of production code in this plan.

- [ ] **Step 1: Write the failing tests for `scheduleWeeklySummary`**

Open `app/src/test/java/com/example/readbook/scheduling/NudgeSchedulerTest.kt` and add these three tests at the end of the class, before the closing `}`:

```kotlin
    @Test
    fun scheduleWeeklySummary_fromASundayBeforeNine_schedulesTodayAtNine() {
        val sunday = LocalDate.of(2026, 7, 5)
        clock.millis = epochMillisAt(sunday, hour = 7)

        scheduler.scheduleWeeklySummary(from = sunday)

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        assertEquals(1, alarms.size)
        assertEquals(epochMillisAt(sunday, hour = 9), alarms[0].triggerAtTime)
    }

    @Test
    fun scheduleWeeklySummary_fromASundayAfterNine_schedulesNextSunday() {
        val sunday = LocalDate.of(2026, 7, 5)
        clock.millis = epochMillisAt(sunday, hour = 10)

        scheduler.scheduleWeeklySummary(from = sunday)

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        assertEquals(epochMillisAt(sunday.plusDays(7), hour = 9), alarms[0].triggerAtTime)
    }

    @Test
    fun scheduleWeeklySummary_fromAMidWeekDay_schedulesTheUpcomingSunday() {
        val wednesday = LocalDate.of(2026, 7, 8)
        clock.millis = epochMillisAt(wednesday, hour = 6)

        scheduler.scheduleWeeklySummary(from = wednesday)

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        assertEquals(epochMillisAt(LocalDate.of(2026, 7, 12), hour = 9), alarms[0].triggerAtTime)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.NudgeSchedulerTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'scheduleWeeklySummary'`.

- [ ] **Step 3: Create the `WeeklySummaryReceiver` shell, then implement `scheduleWeeklySummary`**

Create `app/src/main/java/com/example/readbook/scheduling/WeeklySummaryReceiver.kt` as an empty shell (its real behavior is added in Step 6):

```kotlin
package com.example.readbook.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WeeklySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}
```

In `app/src/main/java/com/example/readbook/scheduling/NudgeScheduler.kt`, find this exact block (the imports, as left by Task 3):

```kotlin
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.readbook.data.Clock
import com.example.readbook.data.ReadingConfig
import com.example.readbook.data.SystemClock
import com.example.readbook.data.isEnabledDay
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
```

Replace it with:

```kotlin
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.readbook.data.Clock
import com.example.readbook.data.ReadingConfig
import com.example.readbook.data.SystemClock
import com.example.readbook.data.isEnabledDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
```

Then find this exact block (as left by Task 3):

```kotlin
    fun scheduleSnooze() {
        val triggerAt = clock.nowMillis() + SNOOZE_DELAY_MS
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, WINDOW_LENGTH_MS, snoozePendingIntent())
    }

    private fun epochMillisAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
```

Replace it with:

```kotlin
    fun scheduleSnooze() {
        val triggerAt = clock.nowMillis() + SNOOZE_DELAY_MS
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, WINDOW_LENGTH_MS, snoozePendingIntent())
    }

    /** Schedules the weekly summary for the next Sunday 9:00 — [from] today if it's a Sunday
     * and still before 9:00, otherwise the following Sunday. */
    fun scheduleWeeklySummary(from: LocalDate) {
        var candidate = from
        while (candidate.dayOfWeek != DayOfWeek.SUNDAY) {
            candidate = candidate.plusDays(1)
        }
        var triggerAt = epochMillisAt(candidate, hour = 9)
        if (triggerAt <= clock.nowMillis()) {
            candidate = candidate.plusDays(7)
            triggerAt = epochMillisAt(candidate, hour = 9)
        }
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, WINDOW_LENGTH_MS, weeklySummaryPendingIntent())
    }

    private fun epochMillisAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
```

Then find this exact block (as left by Task 3):

```kotlin
    private fun snoozePendingIntent(): PendingIntent {
        val intent = Intent(context, NudgeReceiver::class.java).setAction(ACTION_NUDGE)
        return PendingIntent.getBroadcast(
            context, SNOOZE_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        val NUDGE_HOURS = listOf(9, 10, 11, 12, 13)
        const val WINDOW_LENGTH_MS = 15 * 60 * 1000L
        const val ROLLOVER_REQUEST_CODE = 100
        const val SNOOZE_REQUEST_CODE = 200
        const val SNOOZE_DELAY_MS = 15 * 60 * 1000L
        const val ACTION_NUDGE = "com.example.readbook.action.NUDGE"
        const val ACTION_ROLLOVER = "com.example.readbook.action.ROLLOVER"
    }
}
```

Replace it with:

```kotlin
    private fun snoozePendingIntent(): PendingIntent {
        val intent = Intent(context, NudgeReceiver::class.java).setAction(ACTION_NUDGE)
        return PendingIntent.getBroadcast(
            context, SNOOZE_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun weeklySummaryPendingIntent(): PendingIntent {
        val intent = Intent(context, WeeklySummaryReceiver::class.java).setAction(ACTION_WEEKLY_SUMMARY)
        return PendingIntent.getBroadcast(
            context, WEEKLY_SUMMARY_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        val NUDGE_HOURS = listOf(9, 10, 11, 12, 13)
        const val WINDOW_LENGTH_MS = 15 * 60 * 1000L
        const val ROLLOVER_REQUEST_CODE = 100
        const val SNOOZE_REQUEST_CODE = 200
        const val SNOOZE_DELAY_MS = 15 * 60 * 1000L
        const val WEEKLY_SUMMARY_REQUEST_CODE = 400
        const val ACTION_NUDGE = "com.example.readbook.action.NUDGE"
        const val ACTION_ROLLOVER = "com.example.readbook.action.ROLLOVER"
        const val ACTION_WEEKLY_SUMMARY = "com.example.readbook.action.WEEKLY_SUMMARY"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.NudgeSchedulerTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all tests (existing 6 + new 3 = 9) pass.

- [ ] **Step 5: Write the failing tests for `WeeklySummaryReceiver`**

Create `app/src/test/java/com/example/readbook/scheduling/WeeklySummaryReceiverTest.kt`:

```kotlin
package com.example.readbook.scheduling

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.data.AppDatabase
import com.example.readbook.data.DEFAULT_ENABLED_DAYS_MASK
import com.example.readbook.data.DEFAULT_TARGET_SECONDS
import com.example.readbook.data.DailyProgress
import com.example.readbook.data.ReadingConfig
import com.example.readbook.notifications.TimerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun epochMillisAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
    date.atTime(LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeeklySummaryReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    // AlarmManager's shadow state has been observed leaking across test classes when the full
    // suite runs (order-dependent, not reproducible in isolation) — clear the slate defensively.
    @Before
    fun clearAnyPreExistingAlarms() {
        shadowOf(alarmManager).getScheduledAlarms().forEach { it.operation?.let(alarmManager::cancel) }
    }

    private fun dispatch(receiver: WeeklySummaryReceiver, action: String) {
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun onReceive_postsASummaryOfLastWeek_andReschedulesItself() = runTest {
        val sunday = LocalDate.of(2026, 7, 5)
        val clock = FakeClock(millis = epochMillisAt(sunday, hour = 9))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        val lastSunday = sunday.minusDays(7)
        db.dailyProgressDao().upsert(
            DailyProgress(
                date = lastSunday.toString(), targetSeconds = 900, remainingSeconds = 0,
                completed = true, completedAt = 1L, activeSessionStartedAt = null,
            )
        )

        val receiver = WeeklySummaryReceiver()
        receiver.today = { sunday }
        receiver.dailyProgressDaoOverride = db.dailyProgressDao()
        receiver.readingConfigDaoOverride = db.readingConfigDao()
        receiver.schedulerOverride = NudgeScheduler(context, clock)
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, NudgeScheduler.ACTION_WEEKLY_SUMMARY)
        testScheduler.advanceUntilIdle()

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.firstOrNull { it.id == WeeklySummaryReceiver.NOTIFICATION_ID_WEEKLY_SUMMARY }
        assertNotNull(notification)
        assertEquals(TimerNotifications.CHANNEL_WEEKLY_SUMMARY, notification?.notification?.channelId)

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        assertEquals(1, alarms.size) // rescheduled for next Sunday

        db.close()
    }

    @Test
    fun onReceive_whenNoEnabledDays_postsNoNotification_butStillReschedules() = runTest {
        val sunday = LocalDate.of(2026, 7, 5)
        val clock = FakeClock(millis = epochMillisAt(sunday, hour = 9))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = 0, targetSeconds = DEFAULT_TARGET_SECONDS))

        val receiver = WeeklySummaryReceiver()
        receiver.today = { sunday }
        receiver.dailyProgressDaoOverride = db.dailyProgressDao()
        receiver.readingConfigDaoOverride = db.readingConfigDao()
        receiver.schedulerOverride = NudgeScheduler(context, clock)
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, NudgeScheduler.ACTION_WEEKLY_SUMMARY)
        testScheduler.advanceUntilIdle()

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.firstOrNull { it.id == WeeklySummaryReceiver.NOTIFICATION_ID_WEEKLY_SUMMARY }
        assertNull(notification)

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        assertEquals(1, alarms.size)

        db.close()
    }
}
```

- [ ] **Step 6: Run tests to verify they fail, then implement `WeeklySummaryReceiver`**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.WeeklySummaryReceiverTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'today'`, `'dailyProgressDaoOverride'`, `'readingConfigDaoOverride'`, `'schedulerOverride'`, `'scopeOverride'`, `'NOTIFICATION_ID_WEEKLY_SUMMARY'` (the Step 3 shell has none of these).

Replace the full content of `app/src/main/java/com/example/readbook/scheduling/WeeklySummaryReceiver.kt`:

```kotlin
package com.example.readbook.scheduling

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.readbook.ReadingApp
import com.example.readbook.data.DEFAULT_ENABLED_DAYS_MASK
import com.example.readbook.data.DailyProgressDao
import com.example.readbook.data.ReadingConfigDao
import com.example.readbook.data.computeWeeklySummary
import com.example.readbook.notifications.TimerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Fires Sunday 9:00: posts a summary of the week that just ended, then reschedules itself
 * for next Sunday — same self-chaining pattern as [RolloverReceiver]. */
class WeeklySummaryReceiver : BroadcastReceiver() {

    internal var today: () -> LocalDate = { LocalDate.now() }
    internal var dailyProgressDaoOverride: DailyProgressDao? = null
    internal var readingConfigDaoOverride: ReadingConfigDao? = null
    internal var schedulerOverride: NudgeScheduler? = null
    internal var scopeOverride: CoroutineScope? = null

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val container = (context.applicationContext as ReadingApp).container
        val dailyProgressDao = dailyProgressDaoOverride ?: container.dailyProgressDao
        val readingConfigDao = readingConfigDaoOverride ?: container.readingConfigDao
        val scheduler = schedulerOverride ?: container.nudgeScheduler
        val scope = scopeOverride ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val firedOn = today()
                val weekStart = firedOn.minusDays(7)
                val config = readingConfigDao.getConfig()
                val enabledDaysMask = config?.enabledDaysMask ?: DEFAULT_ENABLED_DAYS_MASK
                val completedDates = dailyProgressDao.getCompletedDates().map { LocalDate.parse(it) }.toSet()
                val summary = computeWeeklySummary(enabledDaysMask, weekStart, completedDates)

                if (summary.enabledCount > 0) {
                    TimerNotifications.createChannels(context)
                    val manager = context.getSystemService(NotificationManager::class.java)
                    manager.notify(
                        NOTIFICATION_ID_WEEKLY_SUMMARY,
                        TimerNotifications.buildWeeklySummaryNotification(context, summary),
                    )
                }

                scheduler.scheduleWeeklySummary(from = firedOn)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val NOTIFICATION_ID_WEEKLY_SUMMARY = 3
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.WeeklySummaryReceiverTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, both tests pass.

- [ ] **Step 8: Run the full test suite**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --rerun-tasks 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/scheduling/NudgeScheduler.kt \
  app/src/main/java/com/example/readbook/scheduling/WeeklySummaryReceiver.kt \
  app/src/test/java/com/example/readbook/scheduling/NudgeSchedulerTest.kt \
  app/src/test/java/com/example/readbook/scheduling/WeeklySummaryReceiverTest.kt
git commit -m "$(cat <<'EOF'
Implement scheduleWeeklySummary and WeeklySummaryReceiver

Fires Sunday 9:00, computes last week's completed-vs-enabled count via
computeWeeklySummary, posts the notification (skipping if zero days are
enabled), and reschedules itself for the following Sunday — the same
self-chaining shape as RolloverReceiver.
EOF
)"
```

---

## Task 8: Wire the weekly summary alarm into app-open/boot self-heal and the manifest

**Files:**
- Modify: `app/src/main/java/com/example/readbook/ReadingApp.kt`
- Modify: `app/src/main/java/com/example/readbook/scheduling/BootReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/test/java/com/example/readbook/scheduling/BootReceiverTest.kt`

**Interfaces:**
- Consumes: `NudgeScheduler.scheduleWeeklySummary(from: LocalDate)` (Task 7).
- Produces: nothing new for later tasks — this is the last wiring step. `WeeklySummaryReceiver` becomes reachable via `AlarmManager`-delivered broadcasts once registered in the manifest.

- [ ] **Step 1: Write the failing test — update `BootReceiverTest`'s alarm count**

Open `app/src/test/java/com/example/readbook/scheduling/BootReceiverTest.kt`. In `onBootCompleted_reSchedulesTodaysNudges_andTheRolloverChain`, change the assertion:

```kotlin
        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        // 5 nudge alarms (all still future at 6am) + 1 rollover alarm for tonight + 1 weekly
        // summary alarm (today is a Sunday in this test, and 6am is before the 9am slot).
        assertEquals(7, alarms.size)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.BootReceiverTest" --rerun-tasks 2>&1 | tail -40`

Expected: `onBootCompleted_reSchedulesTodaysNudges_andTheRolloverChain` FAILS — `expected:<7> but was:<6>` (BootReceiver doesn't call `scheduleWeeklySummary` yet).

- [ ] **Step 3: Wire `BootReceiver`**

In `app/src/main/java/com/example/readbook/scheduling/BootReceiver.kt`, update the `try` block inside `onReceive`:

```kotlin
            try {
                val date = today()
                coordinator.ensureScheduled(date)
                scheduler.scheduleRollover(from = date)
                scheduler.scheduleWeeklySummary(from = date)
            } catch (e: Exception) {
                // Never let a boot-time failure crash the receiver — next app-open self-heals.
            } finally {
                pendingResult.finish()
            }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.BootReceiverTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, both tests pass.

- [ ] **Step 5: Wire `ReadingApp.onCreate()`**

Replace the full content of `app/src/main/java/com/example/readbook/ReadingApp.kt`:

```kotlin
package com.example.readbook

import android.app.Application
import com.example.readbook.data.AppContainer
import com.example.readbook.data.ensureConfigSeeded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

class ReadingApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Self-heal on every app open: seeds default config on first launch (otherwise nothing
        // saves one until a Settings screen exists), reconciles a session left dangling by a
        // process kill (the foreground service dies with the process — activeSessionStartedAt
        // would otherwise sit stuck forever with no service left to ever finish it), then ensures
        // today's nudges, the rollover chain, and the weekly summary alarm are all scheduled even
        // if the midnight/boot jobs never got to run (OEM battery killers, a missed boot
        // receiver, etc.) — not solely reliant on any single scheduling path.
        appScope.launch {
            ensureConfigSeeded(container.readingConfigDao)
            container.readingTimerRepository.reconcileCrashedSession()
            val today = LocalDate.now()
            container.nudgeSchedulingCoordinator.ensureScheduled(today)
            container.nudgeScheduler.scheduleRollover(from = today)
            container.nudgeScheduler.scheduleWeeklySummary(from = today)
        }
    }
}
```

- [ ] **Step 6: Register `WeeklySummaryReceiver` in the manifest**

In `app/src/main/AndroidManifest.xml`, add this `<receiver>` entry right after the existing `.scheduling.RolloverReceiver` entry (before the `.scheduling.BootReceiver` entry):

```xml
        <receiver
            android:name=".scheduling.WeeklySummaryReceiver"
            android:exported="false" />
```

- [ ] **Step 7: Run the full test suite**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest assembleDebug --rerun-tasks 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`.

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && total=0; failed=0; for f in app/build/test-results/testDebugUnitTest/*.xml; do t=$(grep -oE 'tests="[0-9]+"' "$f" | grep -oE '[0-9]+'); fl=$(grep -oE 'failures="[0-9]+"' "$f" | grep -oE '[0-9]+'); total=$((total+t)); failed=$((failed+fl)); done; echo "TOTAL=$total FAILED=$failed"`

Expected: `FAILED=0`.

- [ ] **Step 8: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/ReadingApp.kt \
  app/src/main/java/com/example/readbook/scheduling/BootReceiver.kt \
  app/src/main/AndroidManifest.xml \
  app/src/test/java/com/example/readbook/scheduling/BootReceiverTest.kt
git commit -m "$(cat <<'EOF'
Wire the weekly summary alarm into app-open/boot self-heal

Registers WeeklySummaryReceiver in the manifest and schedules it from
the same three places every other alarm in this app is scheduled from:
ReadingApp.onCreate(), BootReceiver, and (from the previous task) its
own self-rescheduling after firing.
EOF
)"
```

---

## Task 9: Full-suite verification and on-device confirmation of all four features

**Files:** none (verification only).

**Interfaces:**
- Consumes: everything from Tasks 1-8.
- Produces: nothing — this is the final acceptance pass before considering the spec complete.

- [ ] **Step 1: Run the full unit test suite one more time**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest assembleDebug --rerun-tasks 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Confirm the device is connected**

Run: `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe devices -l`

Expected: one device listed as `device` (not `unauthorized`/`offline`). If not connected, stop here and wait for the user to reconnect it — do not silently skip device verification.

- [ ] **Step 3: Install and launch**

```bash
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe install -r "D:/Users/zivk/Documents/GitHub/ReadBook/app/build/outputs/apk/debug/app-debug.apk"
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe shell am start -n com.example.readbook/.MainActivity
```

- [ ] **Step 4: Verify Reset today (paused mid-session)**

On the Home screen, tap Start, wait a few seconds, tap Stop. Confirm a "Reset today" button appears below Start. Tap it. Confirm the countdown resets to the full configured duration.

- [ ] **Step 5: Verify Reset today (after completion)**

Via Settings, temporarily set the duration to 1 minute, Save, then Start and let it run to completion (or Stop after the full minute elapses so it auto-completes). Confirm the Done screen shows "Reset today". Tap it. Confirm it returns to an in-progress countdown (not Done), and open History to confirm `totalCompletedDays`/`currentStreak` both dropped back down (compare against what they showed just before the reset).

- [ ] **Step 6: Verify notification Start action**

```bash
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe shell am broadcast -n com.example.readbook/.scheduling.NudgeReceiver -a com.example.readbook.action.NUDGE
```

Pull down the notification shade. Confirm the nudge notification has "Start" and "Snooze 15m" buttons. Tap "Start". Confirm the app begins a running session (check via `adb shell dumpsys activity services | grep -i readbook` for the foreground service, or just open the app and confirm the Home screen shows a running countdown) without having opened the app first.

- [ ] **Step 7: Verify notification Snooze action**

Re-trigger the same broadcast from Step 6 (after stopping whatever session Step 6 started, so today isn't already completed). Tap "Snooze 15m" on the notification. Confirm the notification disappears immediately. Confirm a new alarm is scheduled 15 minutes out:

```bash
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe shell dumpsys alarm | grep -A 3 "com.example.readbook"
```

- [ ] **Step 8: Verify weekly summary notification**

```bash
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe shell am broadcast -n com.example.readbook/.scheduling.WeeklySummaryReceiver -a com.example.readbook.action.WEEKLY_SUMMARY
```

Pull down the notification shade. Confirm a "You read X/Y days last week" notification appears (X/Y reflecting whatever real completed-day history exists from prior testing sessions — 0/5 is a valid and correct result if last week had no completions).

- [ ] **Step 9: Check logcat for crashes across all of the above**

```bash
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe logcat -d -t 500 | grep -iE "readbook.*(exception|error|crash|FATAL)" | grep -v "ForegroundServiceTypeLogger"
```

Expected: no output.

- [ ] **Step 10: Report results to the user**

Summarize which of the 4 features were confirmed working on-device, and flag anything that didn't behave as expected for follow-up before considering this plan complete.
