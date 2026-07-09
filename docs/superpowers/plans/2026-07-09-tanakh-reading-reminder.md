# Daily Tanakh Reading Reminder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a daily Tanakh (Jewish Bible) reading tracker to ReadBook: a persisted "next chapter to read" cursor into the fixed 724-chapter schedule, a Home screen card with a mark-as-read button, and an hourly reminder notification — independent of the existing 15-minute timer's completion tracking.

**Architecture:** Follows the same layered structure as every other feature in this app: pure functions in `data` (schedule parsing, status derivation), a thin repository wrapping a Room DAO in `data`, `AlarmManager`/`BroadcastReceiver` orchestration in `scheduling` (reusing the existing `NudgeScheduler`/`isEnabledDay` machinery), notification building in `notifications`, and Compose state/UI in `ui/home`. Reuses the day-of-week config (`ReadingConfig.enabledDaysMask`) for when the reminder can fire, but keeps a fully separate completion/progress model from the 15-minute timer.

**Tech Stack:** Kotlin, Jetpack Compose, Room (with the project's first schema migration), AlarmManager, Robolectric + JUnit (Robolectric for anything touching Android framework classes; plain `kotlin.test` for pure functions).

## Global Constraints

- Never use `fallbackToDestructiveMigration()` — this plan adds a new table via a real `Migration`, per this codebase's standing rule (see `AppContainer.kt`'s existing comment).
- Alarms are always inexact (`AlarmManager.setWindow`, never `setExactAndAllowWhileIdle`).
- Every Robolectric test touching Room must pin `.setQueryCoroutineContext(StandardTestDispatcher(testScheduler))` to the same scheduler as `runTest`, and any code using `viewModelScope` needs `Dispatchers.setMain(StandardTestDispatcher(testScheduler))` in `@Before`/at the top of the test, `Dispatchers.resetMain()` in `@After` (see `HomeViewModelTest.kt`).
- Every test class that touches `AlarmManager` needs the `@Before` cleanup: `shadowOf(alarmManager).getScheduledAlarms().forEach { it.operation?.let(alarmManager::cancel) }`.
- `BroadcastReceiver` tests must dispatch via `context.registerReceiver(...)` + `context.sendBroadcast(...)` + `shadowOf(Looper.getMainLooper()).idle()` — never call `.onReceive()` directly.
- All Robolectric test classes use `@RunWith(RobolectricTestRunner::class)` and `@Config(sdk = [35])`.
- TDD throughout: write the failing test, run it, confirm it fails for the right reason, then write minimal code to pass.
- Commit after each task with `git add <specific files>` (never `-A`/`.`) — this repo has a `.claude/` directory that must never be committed.
- Run `cd "D:/Users/zivk/Documents/GitHub/ReadBook" &&` before every Gradle/adb command (Bash tool, not PowerShell, for this repo's established workflow).
- Hebrew UI/notification strings are exact, not placeholders: button label `"קראתי"`, behind-message `"אתה בפיגור של {n} פרקים"`, waiting-message `"נחזור לקרוא ב {date}"` (date formatted `d.M.yyyy`), finished message `"סיימת את כל התנ״ך!"`.

---

## Task 1: Bundle the schedule as an app asset + pure CSV parser

**Files:**
- Create: `app/src/main/assets/tanakh_schedule.csv` (copy of `docs/tanakh-reading-schedule/tanakh_schedule.csv`)
- Create: `app/src/main/java/com/example/readbook/data/TanakhSchedule.kt`
- Test: `app/src/test/java/com/example/readbook/data/TanakhScheduleTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class ScheduleEntry(val book: String, val chapterHeb: String, val date: LocalDate)` and `fun parseTanakhSchedule(csvText: String): List<ScheduleEntry>` — used by Task 4 (`BibleReadingRepository`) and Task 2 (`AppContainer`).

- [ ] **Step 1: Copy the schedule CSV into the app as an asset**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
mkdir -p app/src/main/assets
cp docs/tanakh-reading-schedule/tanakh_schedule.csv app/src/main/assets/tanakh_schedule.csv
```

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/com/example/readbook/data/TanakhScheduleTest.kt`:

```kotlin
package com.example.readbook.data

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class TanakhScheduleTest {

    @Test
    fun parseTanakhSchedule_parsesHeaderAndQuotedFields() {
        val csv = """
            "Book","ChapterNum","ChapterHeb","Date"
            "יהושע","19","י״ט","14.6.2026"
            "יהושע","20","כ׳","15.6.2026"
        """.trimIndent()

        val entries = parseTanakhSchedule(csv)

        assertEquals(2, entries.size)
        assertEquals(ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 6, 14)), entries[0])
        assertEquals(ScheduleEntry("יהושע", "כ׳", LocalDate.of(2026, 6, 15)), entries[1])
    }

    @Test
    fun parseTanakhSchedule_parsesSingleDigitDayAndMonth() {
        val csv = "\"Book\",\"ChapterNum\",\"ChapterHeb\",\"Date\"\n\"שופטים\",\"8\",\"ח׳\",\"1.7.2026\""

        val entries = parseTanakhSchedule(csv)

        assertEquals(LocalDate.of(2026, 7, 1), entries[0].date)
    }

    @Test
    fun parseTanakhSchedule_onTheBundledAsset_hasSevenHundredTwentyFourEntries_withCorrectFirstAndLast() {
        val csvText = File("src/main/assets/tanakh_schedule.csv").readText()

        val entries = parseTanakhSchedule(csvText)

        assertEquals(724, entries.size)
        assertEquals(ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 6, 14)), entries.first())
        assertEquals(ScheduleEntry("דברי הימים ב׳", "ל״ו", LocalDate.of(2029, 3, 21)), entries.last())
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.data.TanakhScheduleTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'ScheduleEntry'` / `'parseTanakhSchedule'`.

- [ ] **Step 4: Implement `TanakhSchedule.kt`**

Create `app/src/main/java/com/example/readbook/data/TanakhSchedule.kt`:

```kotlin
package com.example.readbook.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class ScheduleEntry(val book: String, val chapterHeb: String, val date: LocalDate)

private val SCHEDULE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")

/**
 * Parses the exported schedule CSV: header row `Book,ChapterNum,ChapterHeb,Date`, every field
 * double-quoted, no embedded commas/quotes in any value. Pure function, no Android dependency,
 * so it's testable without Robolectric.
 */
fun parseTanakhSchedule(csvText: String): List<ScheduleEntry> =
    csvText.lineSequence()
        .drop(1) // header
        .filter { it.isNotBlank() }
        .map { line ->
            val fields = line.split(",").map { it.trim().removeSurrounding("\"") }
            ScheduleEntry(
                book = fields[0],
                chapterHeb = fields[2],
                date = LocalDate.parse(fields[3], SCHEDULE_DATE_FORMATTER),
            )
        }
        .toList()
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.data.TanakhScheduleTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all 3 tests pass.

- [ ] **Step 6: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/assets/tanakh_schedule.csv \
  app/src/main/java/com/example/readbook/data/TanakhSchedule.kt \
  app/src/test/java/com/example/readbook/data/TanakhScheduleTest.kt
git commit -m "$(cat <<'EOF'
Bundle the Tanakh reading schedule as an app asset

Copies the already-committed docs/tanakh-reading-schedule/ CSV into
app/src/main/assets so both stay in sync from one canonical file, and
adds a pure parseTanakhSchedule() function with no Android dependency.
EOF
)"
```

---

## Task 2: `BibleReadingProgress` entity, DAO, and Room migration

**Files:**
- Create: `app/src/main/java/com/example/readbook/data/BibleReadingProgress.kt`
- Create: `app/src/main/java/com/example/readbook/data/BibleReadingProgressDao.kt`
- Modify: `app/src/main/java/com/example/readbook/data/AppDatabase.kt`
- Modify: `app/src/main/java/com/example/readbook/data/AppContainer.kt`
- Test: `app/src/test/java/com/example/readbook/data/BibleReadingProgressDaoTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `BibleReadingProgressDao` (`suspend fun getProgress(): BibleReadingProgress?`, `fun observeProgress(): Flow<BibleReadingProgress?>`, `suspend fun upsert(progress: BibleReadingProgress)`) — used by Task 4 (`BibleReadingRepository`). `AppContainer.bibleReadingProgressDao` — used by Tasks 4 and 6.

**Note on migration testing:** this task's automated tests use `Room.inMemoryDatabaseBuilder`, which builds fresh from the current entity definitions and does not exercise the migration path (in-memory DBs have no prior version to migrate from). The migration only actually runs when the real app upgrades over an already-installed version with existing data — verified in Task 11's on-device pass, not by an automated test here. This is a deliberate, documented tradeoff: Room's `MigrationTestHelper` is instrumented-test tooling that's fragile to run under this project's Robolectric-only test setup, and this is a single-user personal app where the real device is the environment that matters.

- [ ] **Step 1: Write the failing DAO test**

Create `app/src/test/java/com/example/readbook/data/BibleReadingProgressDaoTest.kt`:

```kotlin
package com.example.readbook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BibleReadingProgressDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BibleReadingProgressDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.bibleReadingProgressDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getProgress_returnsNull_whenNothingSaved() = runTest {
        assertNull(dao.getProgress())
    }

    @Test
    fun upsert_thenGetProgress_returnsSavedCursor() = runTest {
        dao.upsert(BibleReadingProgress(cursorIndex = 5))

        val result = dao.getProgress()

        assertEquals(5, result?.cursorIndex)
    }

    @Test
    fun upsert_overwritesExistingProgress_ratherThanInserting() = runTest {
        dao.upsert(BibleReadingProgress(cursorIndex = 5))
        dao.upsert(BibleReadingProgress(cursorIndex = 6))

        val result = dao.getProgress()

        assertEquals(6, result?.cursorIndex)
    }

    @Test
    fun observeProgress_emitsLatestValue_afterUpsert() = runTest {
        dao.upsert(BibleReadingProgress(cursorIndex = 3))

        val emitted = dao.observeProgress().first()

        assertEquals(3, emitted?.cursorIndex)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.data.BibleReadingProgressDaoTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'BibleReadingProgressDao'` (and `BibleReadingProgress`).

- [ ] **Step 3: Create the entity**

Create `app/src/main/java/com/example/readbook/data/BibleReadingProgress.kt`:

```kotlin
package com.example.readbook.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single-row table, same pattern as [ReadingConfig] — [cursorIndex] is the index of the next
 * unread chapter in the schedule parsed by [parseTanakhSchedule]. */
@Entity(tableName = "bible_reading_progress")
data class BibleReadingProgress(
    @PrimaryKey val id: Int = 0,
    val cursorIndex: Int = 0,
)
```

- [ ] **Step 4: Create the DAO**

Create `app/src/main/java/com/example/readbook/data/BibleReadingProgressDao.kt`:

```kotlin
package com.example.readbook.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BibleReadingProgressDao {
    @Query("SELECT * FROM bible_reading_progress WHERE id = 0")
    suspend fun getProgress(): BibleReadingProgress?

    @Query("SELECT * FROM bible_reading_progress WHERE id = 0")
    fun observeProgress(): Flow<BibleReadingProgress?>

    @Upsert
    suspend fun upsert(progress: BibleReadingProgress)
}
```

- [ ] **Step 5: Add the entity + DAO + migration to `AppDatabase`**

Replace the full content of `app/src/main/java/com/example/readbook/data/AppDatabase.kt`:

```kotlin
package com.example.readbook.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ReadingConfig::class, DailyProgress::class, ReadingSession::class, Stats::class,
        BibleReadingProgress::class,
    ],
    version = 2,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readingConfigDao(): ReadingConfigDao
    abstract fun dailyProgressDao(): DailyProgressDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun statsDao(): StatsDao
    abstract fun bibleReadingProgressDao(): BibleReadingProgressDao

    companion object {
        /** Adds the bible_reading_progress table — the app's first schema change. Never
         * fallbackToDestructiveMigration(); this project's reading history is the entire point. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bible_reading_progress` " +
                        "(`id` INTEGER NOT NULL, `cursorIndex` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }
    }
}
```

- [ ] **Step 6: Wire the migration and the new DAO into `AppContainer`**

Replace the full content of `app/src/main/java/com/example/readbook/data/AppContainer.kt`:

```kotlin
package com.example.readbook.data

import android.content.Context
import androidx.room.Room
import com.example.readbook.scheduling.NudgeScheduler
import com.example.readbook.scheduling.NudgeSchedulingCoordinator

/** Manual DI — no framework needed at this app's size. One instance, owned by [com.example.readbook.ReadingApp]. */
class AppContainer(context: Context) {

    private val db: AppDatabase by lazy {
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "readbook.db")
            // Never fallbackToDestructiveMigration() — the entire point of this schema is
            // long-term reading history; add a real Migration when the schema ever changes.
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }

    val readingConfigDao get() = db.readingConfigDao()
    val dailyProgressDao get() = db.dailyProgressDao()
    val readingSessionDao get() = db.readingSessionDao()
    val statsDao get() = db.statsDao()
    val bibleReadingProgressDao get() = db.bibleReadingProgressDao()

    val readingTimerRepository: ReadingTimerRepository by lazy {
        ReadingTimerRepository(
            dailyProgressDao = dailyProgressDao,
            readingSessionDao = readingSessionDao,
            readingConfigDao = readingConfigDao,
            statsDao = statsDao,
            clock = SystemClock,
        )
    }

    val nudgeScheduler: NudgeScheduler by lazy { NudgeScheduler(context.applicationContext) }

    val nudgeSchedulingCoordinator: NudgeSchedulingCoordinator by lazy {
        NudgeSchedulingCoordinator(readingConfigDao = readingConfigDao, scheduler = nudgeScheduler)
    }
}
```

(`tanakhSchedule` and `bibleReadingRepository` are added to this same file in Task 4, once `BibleReadingRepository` exists.)

- [ ] **Step 7: Run test to verify it passes**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.data.BibleReadingProgressDaoTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all 4 tests pass.

- [ ] **Step 8: Run the full suite to confirm nothing else broke**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --rerun-tasks 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/data/BibleReadingProgress.kt \
  app/src/main/java/com/example/readbook/data/BibleReadingProgressDao.kt \
  app/src/main/java/com/example/readbook/data/AppDatabase.kt \
  app/src/main/java/com/example/readbook/data/AppContainer.kt \
  app/src/test/java/com/example/readbook/data/BibleReadingProgressDaoTest.kt
git commit -m "$(cat <<'EOF'
Add BibleReadingProgress entity, DAO, and the app's first Room migration

Single-row cursorIndex table tracking the next unread Tanakh chapter,
same singleton pattern as ReadingConfig. version 1->2 with a real
Migration, never fallbackToDestructiveMigration().
EOF
)"
```

---

## Task 3: `BibleReadingStatus` and the due/behind/waiting derivation logic

**Files:**
- Create: `app/src/main/java/com/example/readbook/data/BibleReadingStatus.kt`
- Test: `app/src/test/java/com/example/readbook/data/BibleReadingStatusTest.kt`

**Interfaces:**
- Consumes: `ScheduleEntry` (Task 1).
- Produces: `sealed interface BibleReadingStatus` with `OnSchedule(entry)`, `Behind(entry, dueCount)`, `Waiting(entry)`, `Finished`, and `fun deriveBibleReadingStatus(schedule: List<ScheduleEntry>, cursorIndex: Int, today: LocalDate): BibleReadingStatus` — used by Task 4 (`BibleReadingRepository`) and Task 5 (`BibleReadingUiState`).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/example/readbook/data/BibleReadingStatusTest.kt`:

```kotlin
package com.example.readbook.data

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BibleReadingStatusTest {

    // One entry per Sun-Thu, mirroring the real schedule's cadence.
    private val schedule = listOf(
        ScheduleEntry("א", "א׳", LocalDate.of(2026, 7, 5)),  // Sunday
        ScheduleEntry("א", "ב׳", LocalDate.of(2026, 7, 6)),  // Monday
        ScheduleEntry("א", "ג׳", LocalDate.of(2026, 7, 7)),  // Tuesday
        ScheduleEntry("א", "ד׳", LocalDate.of(2026, 7, 8)),  // Wednesday
        ScheduleEntry("א", "ה׳", LocalDate.of(2026, 7, 9)),  // Thursday
        ScheduleEntry("א", "ו׳", LocalDate.of(2026, 7, 12)), // next Sunday
    )

    @Test
    fun onTheExactScheduledDate_yieldsOnSchedule_withNoBehindCount() {
        val status = deriveBibleReadingStatus(schedule, cursorIndex = 2, today = LocalDate.of(2026, 7, 7))

        assertEquals(BibleReadingStatus.OnSchedule(schedule[2]), status)
    }

    @Test
    fun beforeTheScheduledDate_yieldsWaiting() {
        val status = deriveBibleReadingStatus(schedule, cursorIndex = 2, today = LocalDate.of(2026, 7, 6))

        assertEquals(BibleReadingStatus.Waiting(schedule[2]), status)
    }

    @Test
    fun oneDayBehind_yieldsBehindWithDueCountOne() {
        // Cursor still on Tuesday's chapter, but it's now Wednesday and no new chapter is due yet.
        val status = deriveBibleReadingStatus(schedule, cursorIndex = 2, today = LocalDate.of(2026, 7, 8))

        assertEquals(BibleReadingStatus.Behind(schedule[2], dueCount = 1), status)
    }

    @Test
    fun severalDaysBehind_dueCountCountsAllUnreadDueChapters() {
        // Cursor still on Sunday's chapter, today is Thursday: Sun/Mon/Tue/Wed/Thu are all due = 5.
        val status = deriveBibleReadingStatus(schedule, cursorIndex = 0, today = LocalDate.of(2026, 7, 9))

        assertEquals(BibleReadingStatus.Behind(schedule[0], dueCount = 5), status)
    }

    @Test
    fun missedThursday_todayIsFridayNonReadingDay_stillReportsBehindWithDueCountOne() {
        // Regression case: Thursday's chapter (index 4) unread, today is Friday. No new chapter
        // has come due since Thursday, but it's still a prior day, not "today" - must be Behind,
        // not OnSchedule, even though dueCount only comes out to 1.
        val friday = LocalDate.of(2026, 7, 10)

        val status = deriveBibleReadingStatus(schedule, cursorIndex = 4, today = friday)

        assertEquals(BibleReadingStatus.Behind(schedule[4], dueCount = 1), status)
    }

    @Test
    fun caughtUpMidWeek_nextChapterInTheFuture_yieldsWaitingWithThatChaptersDate() {
        // Just read Wednesday's chapter; Thursday's isn't due until Thursday.
        val status = deriveBibleReadingStatus(schedule, cursorIndex = 4, today = LocalDate.of(2026, 7, 8))

        assertEquals(BibleReadingStatus.Waiting(schedule[4]), status)
    }

    @Test
    fun cursorPastTheLastEntry_yieldsFinished() {
        val status = deriveBibleReadingStatus(schedule, cursorIndex = schedule.size, today = LocalDate.of(2026, 8, 1))

        assertIs<BibleReadingStatus.Finished>(status)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.data.BibleReadingStatusTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'BibleReadingStatus'` / `'deriveBibleReadingStatus'`.

- [ ] **Step 3: Implement `BibleReadingStatus.kt`**

Create `app/src/main/java/com/example/readbook/data/BibleReadingStatus.kt`:

```kotlin
package com.example.readbook.data

import java.time.LocalDate

sealed interface BibleReadingStatus {
    data class OnSchedule(val entry: ScheduleEntry) : BibleReadingStatus
    data class Behind(val entry: ScheduleEntry, val dueCount: Int) : BibleReadingStatus
    data class Waiting(val entry: ScheduleEntry) : BibleReadingStatus
    data object Finished : BibleReadingStatus
}

/**
 * [cursorIndex] is the index of the next unread chapter. The schedule's dates never move (no
 * reflow on a missed day) - falling behind means catching up one chapter at a time, never
 * skipping ahead to "today's" chapter.
 */
fun deriveBibleReadingStatus(
    schedule: List<ScheduleEntry>,
    cursorIndex: Int,
    today: LocalDate,
): BibleReadingStatus {
    val entry = schedule.getOrNull(cursorIndex) ?: return BibleReadingStatus.Finished
    return when {
        entry.date.isEqual(today) -> BibleReadingStatus.OnSchedule(entry)
        entry.date.isBefore(today) -> {
            val lastDueIndex = schedule.indexOfLast { !it.date.isAfter(today) }
            val dueCount = lastDueIndex - cursorIndex + 1
            BibleReadingStatus.Behind(entry, dueCount)
        }
        else -> BibleReadingStatus.Waiting(entry)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.data.BibleReadingStatusTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all 7 tests pass.

- [ ] **Step 5: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/data/BibleReadingStatus.kt \
  app/src/test/java/com/example/readbook/data/BibleReadingStatusTest.kt
git commit -m "$(cat <<'EOF'
Add BibleReadingStatus and the due/behind/waiting derivation logic

Pure function deciding OnSchedule/Behind/Waiting/Finished from a
cursor index, today's date, and the fixed schedule - including the
Thursday-missed/Friday-today edge case where dueCount is 1 but the
chapter is still a prior day, not today.
EOF
)"
```

---

## Task 4: `BibleReadingRepository`

**Files:**
- Create: `app/src/main/java/com/example/readbook/data/BibleReadingRepository.kt`
- Modify: `app/src/main/java/com/example/readbook/data/AppContainer.kt`
- Test: `app/src/test/java/com/example/readbook/data/BibleReadingRepositoryTest.kt`

**Interfaces:**
- Consumes: `BibleReadingProgressDao` (Task 2), `ScheduleEntry`/`parseTanakhSchedule` (Task 1), `BibleReadingStatus`/`deriveBibleReadingStatus` (Task 3).
- Produces: `class BibleReadingRepository(dao: BibleReadingProgressDao, schedule: List<ScheduleEntry>)` with `fun observeStatus(today: () -> LocalDate): Flow<BibleReadingStatus>`, `suspend fun currentStatus(today: LocalDate): BibleReadingStatus`, `suspend fun markRead()` — used by Task 6 (`HomeViewModel`) and Task 9 (`BibleReadingReminderReceiver`). `AppContainer.tanakhSchedule: List<ScheduleEntry>` and `AppContainer.bibleReadingRepository: BibleReadingRepository`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/example/readbook/data/BibleReadingRepositoryTest.kt`:

```kotlin
package com.example.readbook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BibleReadingRepositoryTest {

    private val schedule = listOf(
        ScheduleEntry("א", "א׳", LocalDate.of(2026, 7, 5)),
        ScheduleEntry("א", "ב׳", LocalDate.of(2026, 7, 6)),
        ScheduleEntry("א", "ג׳", LocalDate.of(2026, 7, 7)),
    )

    private lateinit var db: AppDatabase
    private lateinit var repository: BibleReadingRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = BibleReadingRepository(db.bibleReadingProgressDao(), schedule)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun currentStatus_withNoProgressSavedYet_treatsCursorAsZero() = runTest {
        val status = repository.currentStatus(today = LocalDate.of(2026, 7, 5))

        assertEquals(BibleReadingStatus.OnSchedule(schedule[0]), status)
    }

    @Test
    fun markRead_advancesTheCursorByExactlyOne() = runTest {
        repository.markRead()

        val status = repository.currentStatus(today = LocalDate.of(2026, 7, 6))

        assertEquals(BibleReadingStatus.OnSchedule(schedule[1]), status)
    }

    @Test
    fun markRead_calledRepeatedly_advancesOneChapterAtATime() = runTest {
        repository.markRead()
        repository.markRead()

        val status = repository.currentStatus(today = LocalDate.of(2026, 7, 20))

        assertIs<BibleReadingStatus.Behind>(status)
        assertEquals(schedule[2], (status as BibleReadingStatus.Behind).entry)
    }

    @Test
    fun observeStatus_emitsTheCurrentStatus_andUpdatesAfterMarkRead() = runTest {
        val today = { LocalDate.of(2026, 7, 5) }

        val before = repository.observeStatus(today).first()
        repository.markRead()
        val after = repository.observeStatus(today).first()

        assertEquals(BibleReadingStatus.OnSchedule(schedule[0]), before)
        assertIs<BibleReadingStatus.Waiting>(after)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.data.BibleReadingRepositoryTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'BibleReadingRepository'`.

- [ ] **Step 3: Implement `BibleReadingRepository.kt`**

Create `app/src/main/java/com/example/readbook/data/BibleReadingRepository.kt`:

```kotlin
package com.example.readbook.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class BibleReadingRepository(
    private val dao: BibleReadingProgressDao,
    private val schedule: List<ScheduleEntry>,
) {
    fun observeStatus(today: () -> LocalDate): Flow<BibleReadingStatus> =
        dao.observeProgress().map { progress ->
            deriveBibleReadingStatus(schedule, progress?.cursorIndex ?: 0, today())
        }

    suspend fun currentStatus(today: LocalDate): BibleReadingStatus {
        val cursorIndex = dao.getProgress()?.cursorIndex ?: 0
        return deriveBibleReadingStatus(schedule, cursorIndex, today)
    }

    suspend fun markRead() {
        val cursorIndex = dao.getProgress()?.cursorIndex ?: 0
        dao.upsert(BibleReadingProgress(cursorIndex = cursorIndex + 1))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.data.BibleReadingRepositoryTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all 4 tests pass.

- [ ] **Step 5: Wire `tanakhSchedule` and `bibleReadingRepository` into `AppContainer`**

In `app/src/main/java/com/example/readbook/data/AppContainer.kt`, add the import and two new properties. The import block becomes:

```kotlin
import android.content.Context
import androidx.room.Room
import com.example.readbook.scheduling.NudgeScheduler
import com.example.readbook.scheduling.NudgeSchedulingCoordinator
```

(no new import needed — `parseTanakhSchedule`, `ScheduleEntry`, and `BibleReadingRepository` are all in the same `com.example.readbook.data` package as `AppContainer`)

Add these two properties at the end of the class body, just before the closing `}`:

```kotlin

    val tanakhSchedule: List<ScheduleEntry> by lazy {
        val csvText = context.applicationContext.assets.open("tanakh_schedule.csv")
            .bufferedReader()
            .use { it.readText() }
        parseTanakhSchedule(csvText)
    }

    val bibleReadingRepository: BibleReadingRepository by lazy {
        BibleReadingRepository(dao = bibleReadingProgressDao, schedule = tanakhSchedule)
    }
}
```

(remove the old trailing `}` when pasting — this replaces the final closing brace of the class.)

- [ ] **Step 6: Run the full suite to confirm nothing else broke**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --rerun-tasks 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/data/BibleReadingRepository.kt \
  app/src/main/java/com/example/readbook/data/AppContainer.kt \
  app/src/test/java/com/example/readbook/data/BibleReadingRepositoryTest.kt
git commit -m "$(cat <<'EOF'
Add BibleReadingRepository and wire the schedule/repository into AppContainer

Thin wrapper over BibleReadingProgressDao + the parsed schedule,
exposing observeStatus (reactive, for the Home screen), currentStatus
(one-shot, for the reminder receiver), and markRead (advances the
cursor by exactly one chapter).
EOF
)"
```

---

## Task 5: `BibleReadingUiState` derivation

**Files:**
- Create: `app/src/main/java/com/example/readbook/ui/home/BibleReadingUiState.kt`
- Test: `app/src/test/java/com/example/readbook/ui/home/BibleReadingUiStateTest.kt`

**Interfaces:**
- Consumes: `BibleReadingStatus` (Task 3).
- Produces: `data class BibleReadingUiState(val chapterText: String?, val buttonEnabled: Boolean, val message: String?, val finished: Boolean)` and `fun deriveBibleReadingUiState(status: BibleReadingStatus): BibleReadingUiState` — used by Task 6 (`HomeViewModel`) and Task 7 (`HomeScreen`).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/example/readbook/ui/home/BibleReadingUiStateTest.kt`:

```kotlin
package com.example.readbook.ui.home

import com.example.readbook.data.BibleReadingStatus
import com.example.readbook.data.ScheduleEntry
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BibleReadingUiStateTest {

    private val entry = ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 6, 14))

    @Test
    fun onSchedule_showsChapterAndEnabledButton_noMessage() {
        val state = deriveBibleReadingUiState(BibleReadingStatus.OnSchedule(entry))

        assertEquals(
            BibleReadingUiState(chapterText = "יהושע י״ט", buttonEnabled = true, message = null, finished = false),
            state,
        )
    }

    @Test
    fun behind_showsChapterAndEnabledButton_withBehindCountMessage() {
        val state = deriveBibleReadingUiState(BibleReadingStatus.Behind(entry, dueCount = 3))

        assertEquals(
            BibleReadingUiState(
                chapterText = "יהושע י״ט", buttonEnabled = true,
                message = "אתה בפיגור של 3 פרקים", finished = false,
            ),
            state,
        )
    }

    @Test
    fun waiting_showsUpcomingChapterAndDisabledButton_withReturnDateMessage() {
        val state = deriveBibleReadingUiState(BibleReadingStatus.Waiting(entry))

        assertEquals(
            BibleReadingUiState(
                chapterText = "יהושע י״ט", buttonEnabled = false,
                message = "נחזור לקרוא ב 14.6.2026", finished = false,
            ),
            state,
        )
    }

    @Test
    fun finished_hasNoChapterOrMessage_andIsFlaggedFinished() {
        val state = deriveBibleReadingUiState(BibleReadingStatus.Finished)

        assertEquals(
            BibleReadingUiState(chapterText = null, buttonEnabled = false, message = null, finished = true),
            state,
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.ui.home.BibleReadingUiStateTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'BibleReadingUiState'` / `'deriveBibleReadingUiState'`.

- [ ] **Step 3: Implement `BibleReadingUiState.kt`**

Create `app/src/main/java/com/example/readbook/ui/home/BibleReadingUiState.kt`:

```kotlin
package com.example.readbook.ui.home

import com.example.readbook.data.BibleReadingStatus
import java.time.format.DateTimeFormatter

data class BibleReadingUiState(
    val chapterText: String?,
    val buttonEnabled: Boolean,
    val message: String?,
    val finished: Boolean,
)

private val DISPLAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")

fun deriveBibleReadingUiState(status: BibleReadingStatus): BibleReadingUiState = when (status) {
    is BibleReadingStatus.Finished -> BibleReadingUiState(
        chapterText = null, buttonEnabled = false, message = null, finished = true,
    )
    is BibleReadingStatus.OnSchedule -> BibleReadingUiState(
        chapterText = "${status.entry.book} ${status.entry.chapterHeb}",
        buttonEnabled = true, message = null, finished = false,
    )
    is BibleReadingStatus.Behind -> BibleReadingUiState(
        chapterText = "${status.entry.book} ${status.entry.chapterHeb}",
        buttonEnabled = true,
        message = "אתה בפיגור של ${status.dueCount} פרקים",
        finished = false,
    )
    is BibleReadingStatus.Waiting -> BibleReadingUiState(
        chapterText = "${status.entry.book} ${status.entry.chapterHeb}",
        buttonEnabled = false,
        message = "נחזור לקרוא ב ${status.entry.date.format(DISPLAY_DATE_FORMATTER)}",
        finished = false,
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.ui.home.BibleReadingUiStateTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/ui/home/BibleReadingUiState.kt \
  app/src/test/java/com/example/readbook/ui/home/BibleReadingUiStateTest.kt
git commit -m "$(cat <<'EOF'
Add BibleReadingUiState derivation for the Home screen card

Maps BibleReadingStatus to display text/button-enabled/message,
including the exact Hebrew copy for the behind and waiting states.
EOF
)"
```

---

## Task 6: Wire `BibleReadingUiState` into `HomeViewModel`

**Files:**
- Modify: `app/src/main/java/com/example/readbook/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/example/readbook/ui/home/HomeViewModelFactory.kt`
- Modify: `app/src/test/java/com/example/readbook/ui/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `BibleReadingRepository` (Task 4), `deriveBibleReadingUiState`/`BibleReadingUiState` (Task 5).
- Produces: `HomeViewModel.bibleReadingUiState: StateFlow<BibleReadingUiState>` and `HomeViewModel.onMarkChapterRead()` — used by Task 7 (`HomeScreen`/`MainActivity`).

- [ ] **Step 1: Write the failing tests**

Open `app/src/test/java/com/example/readbook/ui/home/HomeViewModelTest.kt`. Update the imports at the top to add:

```kotlin
import com.example.readbook.data.BibleReadingProgress
import com.example.readbook.data.BibleReadingRepository
import com.example.readbook.data.BibleReadingStatus
import com.example.readbook.data.ScheduleEntry
```

Replace the `buildViewModel` helper to also construct a `BibleReadingRepository` with a small fixed test schedule and pass it into `HomeViewModel`:

```kotlin
    private val testSchedule = listOf(
        ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 7, 5)),
        ScheduleEntry("יהושע", "כ׳", LocalDate.of(2026, 7, 6)),
    )

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
        val bibleReadingRepository = BibleReadingRepository(db.bibleReadingProgressDao(), testSchedule)
        val viewModel = HomeViewModel(
            dailyProgressDao = db.dailyProgressDao(),
            readingConfigDao = db.readingConfigDao(),
            repository = repository,
            bibleReadingRepository = bibleReadingRepository,
            clock = clock,
            today = { today },
        )
        testScheduler.runCurrent()
        return viewModel to db
    }
```

Add these two tests at the end of the class, just before the closing `}`:

```kotlin

    @Test
    fun bibleReadingUiState_reflectsOnScheduleStatus() = runTest {
        val (viewModel, db) = buildViewModel(FakeClock(), LocalDate.of(2026, 7, 5))
        testScheduler.runCurrent()

        val state = viewModel.bibleReadingUiState.value

        assertEquals("יהושע י״ט", state.chapterText)
        assertEquals(true, state.buttonEnabled)

        db.close()
    }

    @Test
    fun onMarkChapterRead_advancesTheCursor_andUiStateReflectsIt() = runTest {
        val (viewModel, db) = buildViewModel(FakeClock(), LocalDate.of(2026, 7, 5))
        testScheduler.runCurrent()

        viewModel.onMarkChapterRead()
        testScheduler.runCurrent()

        val state = viewModel.bibleReadingUiState.value
        assertEquals("כ׳", state.chapterText?.substringAfter(" "))

        db.close()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.ui.home.HomeViewModelTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'bibleReadingUiState'` / `'bibleReadingRepository'` / `'onMarkChapterRead'`.

- [ ] **Step 3: Implement the wiring in `HomeViewModel`**

Replace the full content of `app/src/main/java/com/example/readbook/ui/home/HomeViewModel.kt`:

```kotlin
package com.example.readbook.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.readbook.data.BibleReadingRepository
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class HomeViewModel(
    dailyProgressDao: DailyProgressDao,
    readingConfigDao: ReadingConfigDao,
    private val repository: ReadingTimerRepository,
    private val bibleReadingRepository: BibleReadingRepository,
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

    val bibleReadingUiState: StateFlow<BibleReadingUiState> = bibleReadingRepository
        .observeStatus(today)
        .map(::deriveBibleReadingUiState)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            BibleReadingUiState(chapterText = null, buttonEnabled = false, message = null, finished = false),
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

    fun onMarkChapterRead() {
        viewModelScope.launch {
            bibleReadingRepository.markRead()
        }
    }
}
```

- [ ] **Step 4: Update `HomeViewModelFactory`**

Replace the full content of `app/src/main/java/com/example/readbook/ui/home/HomeViewModelFactory.kt`:

```kotlin
package com.example.readbook.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.readbook.data.BibleReadingRepository
import com.example.readbook.data.DailyProgressDao
import com.example.readbook.data.ReadingConfigDao
import com.example.readbook.data.ReadingTimerRepository

class HomeViewModelFactory(
    private val dailyProgressDao: DailyProgressDao,
    private val readingConfigDao: ReadingConfigDao,
    private val repository: ReadingTimerRepository,
    private val bibleReadingRepository: BibleReadingRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return HomeViewModel(dailyProgressDao, readingConfigDao, repository, bibleReadingRepository) as T
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.ui.home.HomeViewModelTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error in `MainActivity.kt` (its `HomeViewModelFactory(...)` call is now missing an argument) — this is expected and fixed in Task 7. Confirm the `HomeViewModelTest` tests themselves pass once that's resolved by re-running the whole suite after Task 7's Step. For now, confirm via: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew compileDebugUnitTestKotlin 2>&1 | tail -40` — expect the only error to be `MainActivity.kt`'s `HomeViewModelFactory` call site, nothing inside `HomeViewModelTest.kt` itself.

- [ ] **Step 6: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/ui/home/HomeViewModel.kt \
  app/src/main/java/com/example/readbook/ui/home/HomeViewModelFactory.kt \
  app/src/test/java/com/example/readbook/ui/home/HomeViewModelTest.kt
git commit -m "$(cat <<'EOF'
Wire BibleReadingUiState and onMarkChapterRead into HomeViewModel

Exposes a second, independent StateFlow alongside the existing timer
uiState - MainActivity's call site is fixed in the next task, which
also adds the card that actually consumes this new state.
EOF
)"
```

---

## Task 7: Home screen card and `MainActivity` wiring

**Files:**
- Modify: `app/src/main/java/com/example/readbook/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/example/readbook/MainActivity.kt`

**Interfaces:**
- Consumes: `HomeViewModel.bibleReadingUiState`/`onMarkChapterRead()` (Task 6), `AppContainer.bibleReadingRepository` (Task 4).
- Produces: nothing new for later tasks — this is the last piece needed to see the feature end-to-end in the app.

This task has no new automated tests — this codebase has no Compose UI test suite (only ViewModel/state tests, already covered in Tasks 5-6). Its correctness is confirmed visually in Task 11's on-device pass.

- [ ] **Step 1: Add the `BibleReadingCard` composable and wire it into `HomeScreen`**

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
    bibleReadingUiState: BibleReadingUiState,
    onToggleTimer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onResetToday: () -> Unit,
    onMarkChapterRead: () -> Unit,
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
                BibleReadingCard(bibleReadingUiState, onMarkChapterRead)
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

@Composable
private fun BibleReadingCard(uiState: BibleReadingUiState, onMarkChapterRead: () -> Unit) {
    Column(
        modifier = Modifier.padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (uiState.finished) {
            Text("סיימת את כל התנ״ך!")
        } else {
            uiState.chapterText?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            Button(
                onClick = onMarkChapterRead,
                enabled = uiState.buttonEnabled,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("קראתי")
            }
            uiState.message?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}
```

- [ ] **Step 2: Wire `bibleReadingUiState`/`onMarkChapterRead` into `MainActivity`**

In `app/src/main/java/com/example/readbook/MainActivity.kt`, update the `HomeViewModelFactory(...)` construction:

```kotlin
        homeViewModel = ViewModelProvider(
            this,
            HomeViewModelFactory(
                container.dailyProgressDao,
                container.readingConfigDao,
                container.readingTimerRepository,
                container.bibleReadingRepository,
            ),
        )[HomeViewModel::class.java]
```

And update the `HomeScreen(...)` call:

```kotlin
                    Screen.HOME -> {
                        val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
                        val bibleReadingUiState by homeViewModel.bibleReadingUiState.collectAsStateWithLifecycle()
                        HomeScreen(
                            uiState = uiState,
                            bibleReadingUiState = bibleReadingUiState,
                            onToggleTimer = { homeViewModel.onToggleTimer(this) },
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onOpenHistory = { screen = Screen.HISTORY },
                            onResetToday = { homeViewModel.onResetToday() },
                            onMarkChapterRead = { homeViewModel.onMarkChapterRead() },
                        )
                    }
```

- [ ] **Step 3: Run the full test suite and build**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest assembleDebug --rerun-tasks 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/ui/home/HomeScreen.kt \
  app/src/main/java/com/example/readbook/MainActivity.kt
git commit -m "$(cat <<'EOF'
Add the Bible reading card to the Home screen

New card below the existing timer content: shows the current/upcoming
chapter, a mark-as-read button (enabled only when due), and the
behind/waiting message. Wires HomeViewModel's new state and action
through MainActivity.
EOF
)"
```

---

## Task 8: Bible reading reminder notification channel and builder

**Files:**
- Modify: `app/src/main/java/com/example/readbook/notifications/TimerNotifications.kt`
- Modify: `app/src/test/java/com/example/readbook/notifications/TimerNotificationsTest.kt`

**Interfaces:**
- Consumes: `ScheduleEntry` (Task 1).
- Produces: `TimerNotifications.CHANNEL_BIBLE_READING`, `TimerNotifications.buildBibleReadingReminderNotification(context, entry): Notification` — used by Task 9 (`BibleReadingReminderReceiver`).

- [ ] **Step 1: Write the failing tests**

Open `app/src/test/java/com/example/readbook/notifications/TimerNotificationsTest.kt`. Add these imports:

```kotlin
import com.example.readbook.MainActivity
import com.example.readbook.data.ScheduleEntry
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
```

Add these tests at the end of the class, just before the closing `}`:

```kotlin

    @Test
    fun createChannels_registersBibleReadingChannel_withDefaultImportance() {
        TimerNotifications.createChannels(context)

        val channel = manager.getNotificationChannel(TimerNotifications.CHANNEL_BIBLE_READING)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun buildBibleReadingReminderNotification_usesBibleReadingChannel_andShowsTheChapter() {
        TimerNotifications.createChannels(context)
        val entry = ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 6, 14))

        val notification = TimerNotifications.buildBibleReadingReminderNotification(context, entry)

        assertEquals(TimerNotifications.CHANNEL_BIBLE_READING, notification.channelId)
        assertEquals("יהושע י״ט", shadowContentText(notification))
    }

    @Test
    fun buildBibleReadingReminderNotification_tapOpensMainActivity() {
        TimerNotifications.createChannels(context)
        val entry = ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 6, 14))

        val notification = TimerNotifications.buildBibleReadingReminderNotification(context, entry)

        val shadowPendingIntent = shadowOf(notification.contentIntent)
        assertEquals(MainActivity::class.java.name, shadowPendingIntent.savedIntent.component?.className)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.notifications.TimerNotificationsTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'CHANNEL_BIBLE_READING'` / `'buildBibleReadingReminderNotification'`.

- [ ] **Step 3: Implement the channel and builder**

In `app/src/main/java/com/example/readbook/notifications/TimerNotifications.kt`, update the imports at the top of the file:

```kotlin
package com.example.readbook.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.readbook.MainActivity
import com.example.readbook.R
import com.example.readbook.data.ScheduleEntry
import com.example.readbook.data.WeeklySummary
import com.example.readbook.scheduling.NudgeReceiver
import com.example.readbook.scheduling.NudgeScheduler
import com.example.readbook.service.ReadingTimerService
```

Add the new channel constant next to the existing ones:

```kotlin
    const val CHANNEL_NUDGE = "nudge"
    const val CHANNEL_TIMER = "timer"
    const val CHANNEL_COMPLETION = "completion"
    const val CHANNEL_WEEKLY_SUMMARY = "weekly_summary"
    const val CHANNEL_BIBLE_READING = "bible_reading_reminder"

    private const val START_ACTION_REQUEST_CODE = 300
    private const val SNOOZE_ACTION_REQUEST_CODE = 301
    private const val OPEN_APP_REQUEST_CODE = 302
```

In `createChannels`, add the new channel registration after the weekly summary one:

```kotlin
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WEEKLY_SUMMARY, "Weekly summary", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_BIBLE_READING, "Bible reading reminder", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
```

Add the new builder function at the end of the object, just before the closing `}`:

```kotlin

    fun buildBibleReadingReminderNotification(context: Context, entry: ScheduleEntry): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, OPEN_APP_REQUEST_CODE, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_BIBLE_READING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText("${entry.book} ${entry.chapterHeb}")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.notifications.TimerNotificationsTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, all tests pass (existing 9 + 3 new = 12).

- [ ] **Step 5: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/notifications/TimerNotifications.kt \
  app/src/test/java/com/example/readbook/notifications/TimerNotificationsTest.kt
git commit -m "$(cat <<'EOF'
Add the Bible reading reminder notification channel and builder

Unlike the existing nudge/weekly-summary notifications, this one sets
a contentIntent so tapping it opens MainActivity directly - there's no
in-notification action button, per the design decision to keep
marking-read a deliberate in-app action.
EOF
)"
```

---

## Task 9: `NudgeScheduler` bible reminder alarms and `BibleReadingReminderReceiver`

**Files:**
- Modify: `app/src/main/java/com/example/readbook/scheduling/NudgeScheduler.kt`
- Modify: `app/src/test/java/com/example/readbook/scheduling/NudgeSchedulerTest.kt`
- Create: `app/src/main/java/com/example/readbook/scheduling/BibleReadingReminderReceiver.kt`
- Create: `app/src/test/java/com/example/readbook/scheduling/BibleReadingReminderReceiverTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `BibleReadingRepository`/`BibleReadingStatus` (Tasks 3-4), `TimerNotifications.buildBibleReadingReminderNotification`/`CHANNEL_BIBLE_READING` (Task 8), `isEnabledDay`/`ReadingConfig` (existing).
- Produces: `NudgeScheduler.scheduleBibleReminderHoursForToday(date, config)`, `NudgeScheduler.cancelBibleReminderHoursForToday()`, `NudgeScheduler.ACTION_BIBLE_REMINDER` — used by Task 10 (self-heal wiring).

- [ ] **Step 1: Write the failing scheduler tests**

Open `app/src/test/java/com/example/readbook/scheduling/NudgeSchedulerTest.kt`. Add these tests at the end of the class, just before the closing `}`:

```kotlin

    @Test
    fun scheduleBibleReminderHoursForToday_onAnEnabledDay_schedulesAllFiveFutureHours() {
        clock.millis = epochMillisAt(enabledDay, hour = 6)

        scheduler.scheduleBibleReminderHoursForToday(enabledDay, config)

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        assertEquals(5, alarms.size)
        val triggerTimes = alarms.map { it.triggerAtTime }.sorted()
        assertEquals(
            listOf(9, 10, 11, 12, 13).map { epochMillisAt(enabledDay, it) },
            triggerTimes,
        )
    }

    @Test
    fun scheduleBibleReminderHoursForToday_onADisabledDay_schedulesNothing() {
        clock.millis = epochMillisAt(disabledDay, hour = 6)

        scheduler.scheduleBibleReminderHoursForToday(disabledDay, config)

        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isEmpty())
    }

    @Test
    fun cancelBibleReminderHoursForToday_removesPreviouslyScheduledAlarms() {
        clock.millis = epochMillisAt(enabledDay, hour = 6)
        scheduler.scheduleBibleReminderHoursForToday(enabledDay, config)
        assertEquals(5, shadowOf(alarmManager).getScheduledAlarms().size)

        scheduler.cancelBibleReminderHoursForToday()

        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isEmpty())
    }

    @Test
    fun bibleReminderAlarms_useADifferentRequestCodeRangeThanNudgeAlarms_soBothCanCoexist() {
        clock.millis = epochMillisAt(enabledDay, hour = 6)

        scheduler.scheduleNudgesForToday(enabledDay, config)
        scheduler.scheduleBibleReminderHoursForToday(enabledDay, config)

        // 5 nudge alarms + 5 bible reminder alarms, none cancelling one another out.
        assertEquals(10, shadowOf(alarmManager).getScheduledAlarms().size)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.NudgeSchedulerTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'scheduleBibleReminderHoursForToday'` / `'cancelBibleReminderHoursForToday'`.

- [ ] **Step 3: Implement the new scheduler methods**

In `app/src/main/java/com/example/readbook/scheduling/NudgeScheduler.kt`, add these two public methods right after `cancelNudgesForToday()`:

```kotlin

    /** Same hours/window/enabled-day rule as [scheduleNudgesForToday], targeting
     * [BibleReadingReminderReceiver] instead — a fully separate alarm family so the two
     * features' completion states never interfere with each other. */
    fun scheduleBibleReminderHoursForToday(date: LocalDate, config: ReadingConfig) {
        if (!isEnabledDay(date, config.enabledDaysMask)) return
        for (hour in NUDGE_HOURS) {
            val triggerAt = epochMillisAt(date, hour)
            if (triggerAt <= clock.nowMillis()) continue
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, WINDOW_LENGTH_MS, bibleReminderPendingIntent(hour))
        }
    }

    fun cancelBibleReminderHoursForToday() {
        for (hour in NUDGE_HOURS) {
            alarmManager.cancel(bibleReminderPendingIntent(hour))
        }
    }
```

Add the corresponding private `PendingIntent` builder right after `weeklySummaryPendingIntent()`:

```kotlin

    private fun bibleReminderPendingIntent(hour: Int): PendingIntent {
        val intent = Intent(context, BibleReadingReminderReceiver::class.java).setAction(ACTION_BIBLE_REMINDER)
        return PendingIntent.getBroadcast(
            context, BIBLE_REMINDER_REQUEST_CODE_BASE + hour, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
```

Add the new constants to the companion object:

```kotlin
    companion object {
        val NUDGE_HOURS = listOf(9, 10, 11, 12, 13)
        const val WINDOW_LENGTH_MS = 15 * 60 * 1000L
        const val ROLLOVER_REQUEST_CODE = 100
        const val SNOOZE_REQUEST_CODE = 200
        const val SNOOZE_DELAY_MS = 15 * 60 * 1000L
        const val WEEKLY_SUMMARY_REQUEST_CODE = 400
        const val BIBLE_REMINDER_REQUEST_CODE_BASE = 500
        const val ACTION_NUDGE = "com.example.readbook.action.NUDGE"
        const val ACTION_ROLLOVER = "com.example.readbook.action.ROLLOVER"
        const val ACTION_WEEKLY_SUMMARY = "com.example.readbook.action.WEEKLY_SUMMARY"
        const val ACTION_BIBLE_REMINDER = "com.example.readbook.action.BIBLE_REMINDER"
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.NudgeSchedulerTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'BibleReadingReminderReceiver'` (created next). This is expected mid-task.

- [ ] **Step 5: Write the failing receiver tests**

Create `app/src/test/java/com/example/readbook/scheduling/BibleReadingReminderReceiverTest.kt`:

```kotlin
package com.example.readbook.scheduling

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readbook.data.AppDatabase
import com.example.readbook.data.BibleReadingProgress
import com.example.readbook.data.BibleReadingRepository
import com.example.readbook.data.ScheduleEntry
import com.example.readbook.notifications.TimerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BibleReadingReminderReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val schedule = listOf(
        ScheduleEntry("יהושע", "י״ט", LocalDate.of(2026, 6, 14)),
        ScheduleEntry("יהושע", "כ׳", LocalDate.of(2026, 6, 15)),
    )

    private fun dispatch(receiver: BibleReadingReminderReceiver, action: String) {
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun onReceive_chapterDue_postsAReminderNotification() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        val receiver = BibleReadingReminderReceiver()
        receiver.today = { LocalDate.of(2026, 6, 14) } // exactly on schedule[0]'s date
        receiver.repositoryOverride = BibleReadingRepository(db.bibleReadingProgressDao(), schedule)
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, NudgeScheduler.ACTION_BIBLE_REMINDER)
        testScheduler.advanceUntilIdle()

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications
            .firstOrNull { it.id == BibleReadingReminderReceiver.NOTIFICATION_ID_BIBLE_READING_REMINDER }
        assertEquals(TimerNotifications.CHANNEL_BIBLE_READING, notification?.notification?.channelId)

        db.close()
    }

    @Test
    fun onReceive_notYetDue_postsNothing() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        val receiver = BibleReadingReminderReceiver()
        receiver.today = { LocalDate.of(2026, 6, 13) } // day before schedule[0]'s date
        receiver.repositoryOverride = BibleReadingRepository(db.bibleReadingProgressDao(), schedule)
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, NudgeScheduler.ACTION_BIBLE_REMINDER)
        testScheduler.advanceUntilIdle()

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications
            .firstOrNull { it.id == BibleReadingReminderReceiver.NOTIFICATION_ID_BIBLE_READING_REMINDER }
        assertNull(notification)

        db.close()
    }

    @Test
    fun onReceive_allChaptersFinished_postsNothing() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        db.bibleReadingProgressDao().upsert(BibleReadingProgress(cursorIndex = schedule.size))
        val receiver = BibleReadingReminderReceiver()
        receiver.today = { LocalDate.of(2026, 6, 20) }
        receiver.repositoryOverride = BibleReadingRepository(db.bibleReadingProgressDao(), schedule)
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, NudgeScheduler.ACTION_BIBLE_REMINDER)
        testScheduler.advanceUntilIdle()

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications
            .firstOrNull { it.id == BibleReadingReminderReceiver.NOTIFICATION_ID_BIBLE_READING_REMINDER }
        assertNull(notification)

        db.close()
    }
}
```

- [ ] **Step 6: Implement `BibleReadingReminderReceiver`**

Create `app/src/main/java/com/example/readbook/scheduling/BibleReadingReminderReceiver.kt`:

```kotlin
package com.example.readbook.scheduling

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.readbook.ReadingApp
import com.example.readbook.data.BibleReadingRepository
import com.example.readbook.data.BibleReadingStatus
import com.example.readbook.notifications.TimerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Fired by AlarmManager at each hourly reminder time. A no-op unless a chapter is actually
 * due (OnSchedule or Behind) - never posts for Waiting or Finished. */
class BibleReadingReminderReceiver : BroadcastReceiver() {

    internal var today: () -> LocalDate = { LocalDate.now() }
    internal var repositoryOverride: BibleReadingRepository? = null
    internal var scopeOverride: CoroutineScope? = null

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val repository = repositoryOverride
            ?: (context.applicationContext as ReadingApp).container.bibleReadingRepository
        val scope = scopeOverride ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val entry = when (val status = repository.currentStatus(today())) {
                    is BibleReadingStatus.OnSchedule -> status.entry
                    is BibleReadingStatus.Behind -> status.entry
                    is BibleReadingStatus.Waiting, BibleReadingStatus.Finished -> null
                }
                if (entry != null) {
                    TimerNotifications.createChannels(context)
                    val manager = context.getSystemService(NotificationManager::class.java)
                    manager.notify(
                        NOTIFICATION_ID_BIBLE_READING_REMINDER,
                        TimerNotifications.buildBibleReadingReminderNotification(context, entry),
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val NOTIFICATION_ID_BIBLE_READING_REMINDER = 4
    }
}
```

- [ ] **Step 7: Register the receiver in the manifest**

In `app/src/main/AndroidManifest.xml`, add this `<receiver>` entry right after the existing `.scheduling.WeeklySummaryReceiver` entry:

```xml
        <receiver
            android:name=".scheduling.BibleReadingReminderReceiver"
            android:exported="false" />
```

- [ ] **Step 8: Run tests to verify everything passes**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.NudgeSchedulerTest" --tests "com.example.readbook.scheduling.BibleReadingReminderReceiverTest" --rerun-tasks 2>&1 | tail -60`

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 9: Run the full suite**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest assembleDebug --rerun-tasks 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/scheduling/NudgeScheduler.kt \
  app/src/test/java/com/example/readbook/scheduling/NudgeSchedulerTest.kt \
  app/src/main/java/com/example/readbook/scheduling/BibleReadingReminderReceiver.kt \
  app/src/test/java/com/example/readbook/scheduling/BibleReadingReminderReceiverTest.kt \
  app/src/main/AndroidManifest.xml
git commit -m "$(cat <<'EOF'
Add hourly Bible reading reminder alarms and BibleReadingReminderReceiver

Same NUDGE_HOURS/isEnabledDay pattern as the existing timer nudge, a
fully separate alarm family (request codes 500+) and receiver so the
two features never interfere. No-op unless a chapter is actually due.
EOF
)"
```

---

## Task 10: Self-heal wiring

**Files:**
- Modify: `app/src/main/java/com/example/readbook/ReadingApp.kt`
- Modify: `app/src/main/java/com/example/readbook/scheduling/BootReceiver.kt`
- Modify: `app/src/test/java/com/example/readbook/scheduling/BootReceiverTest.kt`

**Interfaces:**
- Consumes: `NudgeScheduler.scheduleBibleReminderHoursForToday(date, config)` (Task 9).
- Produces: nothing new for later tasks — this is the last wiring step.

`RolloverReceiver` is deliberately **not** touched here — it doesn't call `scheduleWeeklySummary` today either (verified by reading the source), so this task follows that same existing (if slightly inconsistent) pattern rather than expanding scope beyond what this feature needs.

- [ ] **Step 1: Write the failing test — update `BootReceiverTest`'s alarm count**

Open `app/src/test/java/com/example/readbook/scheduling/BootReceiverTest.kt`. `BootReceiver` needs a `ReadingConfigDao` seam the same way it already has `coordinatorOverride`/`schedulerOverride` — reaching into the real `container.readingConfigDao` directly (bypassing the test's own in-memory `db`) would silently depend on a real on-disk database in tests instead of the isolated one this test already builds. Update the test body to set the new override and the alarm-count assertion:

```kotlin
    @Test
    fun onBootCompleted_reSchedulesTodaysNudges_andTheRolloverChain() = runTest {
        val today = LocalDate.of(2026, 7, 5) // Sunday, enabled by default
        val clock = FakeClock(millis = epochMillisAt(today, hour = 6))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .build()
        db.readingConfigDao().upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
        val scheduler = NudgeScheduler(context, clock)
        val coordinator = NudgeSchedulingCoordinator(db.readingConfigDao(), scheduler)

        val receiver = BootReceiver()
        receiver.today = { today }
        receiver.coordinatorOverride = coordinator
        receiver.schedulerOverride = scheduler
        receiver.readingConfigDaoOverride = db.readingConfigDao()
        receiver.scopeOverride = CoroutineScope(StandardTestDispatcher(testScheduler))

        dispatch(receiver, Intent.ACTION_BOOT_COMPLETED)
        testScheduler.advanceUntilIdle()

        val alarms = shadowOf(alarmManager).getScheduledAlarms()
        // 5 nudge alarms + 1 rollover alarm + 1 weekly summary alarm (today is a Sunday in this
        // test, 6am is before the 9am slot) + 5 bible reminder alarms.
        assertEquals(12, alarms.size)
    }
```

(only the comment and the final assertion number change: `7` becomes `12`, plus the new `receiver.readingConfigDaoOverride = db.readingConfigDao()` line.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.BootReceiverTest" --rerun-tasks 2>&1 | tail -40`

Expected: compile error — `Unresolved reference 'readingConfigDaoOverride'`.

- [ ] **Step 3: Add the override seam and wire `BootReceiver`**

In `app/src/main/java/com/example/readbook/scheduling/BootReceiver.kt`, add this import:

```kotlin
import com.example.readbook.data.ReadingConfigDao
```

Add the new overridable field alongside the existing ones:

```kotlin
    internal var today: () -> LocalDate = { LocalDate.now() }
    internal var coordinatorOverride: NudgeSchedulingCoordinator? = null
    internal var schedulerOverride: NudgeScheduler? = null
    internal var readingConfigDaoOverride: ReadingConfigDao? = null
    internal var scopeOverride: CoroutineScope? = null
```

Update `onReceive` to obtain the DAO the same way `coordinator`/`scheduler` already are, and use it inside the `try` block:

```kotlin
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        val container = (context.applicationContext as ReadingApp).container
        val coordinator = coordinatorOverride ?: container.nudgeSchedulingCoordinator
        val scheduler = schedulerOverride ?: container.nudgeScheduler
        val readingConfigDao = readingConfigDaoOverride ?: container.readingConfigDao
        val scope = scopeOverride ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val date = today()
                coordinator.ensureScheduled(date)
                scheduler.scheduleRollover(from = date)
                scheduler.scheduleWeeklySummary(from = date)
                val config = readingConfigDao.getConfig()
                if (config != null) {
                    scheduler.scheduleBibleReminderHoursForToday(date, config)
                }
            } catch (e: Exception) {
                // Never let a boot-time failure crash the receiver — next app-open self-heals.
            } finally {
                pendingResult.finish()
            }
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest --tests "com.example.readbook.scheduling.BootReceiverTest" --rerun-tasks 2>&1 | tail -40`

Expected: `BUILD SUCCESSFUL`, both tests pass. (`onReceive_ignoresIntentsThatAreNotBootCompleted` doesn't need `readingConfigDaoOverride` set — it dispatches a non-boot action, which returns before any DAO/container access happens at all.)

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
        // today's nudges, the rollover chain, the weekly summary alarm, and the bible reading
        // reminder alarms are all scheduled even if the midnight/boot jobs never got to run (OEM
        // battery killers, a missed boot receiver, etc.) — not solely reliant on any single
        // scheduling path.
        appScope.launch {
            ensureConfigSeeded(container.readingConfigDao)
            container.readingTimerRepository.reconcileCrashedSession()
            val today = LocalDate.now()
            container.nudgeSchedulingCoordinator.ensureScheduled(today)
            container.nudgeScheduler.scheduleRollover(from = today)
            container.nudgeScheduler.scheduleWeeklySummary(from = today)
            val config = container.readingConfigDao.getConfig()
            if (config != null) {
                container.nudgeScheduler.scheduleBibleReminderHoursForToday(today, config)
            }
        }
    }
}
```

- [ ] **Step 6: Run the full test suite**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest assembleDebug --rerun-tasks 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`.

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && total=0; failed=0; for f in app/build/test-results/testDebugUnitTest/*.xml; do t=$(grep -oE 'tests="[0-9]+"' "$f" | grep -oE '[0-9]+'); fl=$(grep -oE 'failures="[0-9]+"' "$f" | grep -oE '[0-9]+'); total=$((total+t)); failed=$((failed+fl)); done; echo "TOTAL=$total FAILED=$failed"`

Expected: `FAILED=0`.

- [ ] **Step 7: Commit**

```bash
cd "D:/Users/zivk/Documents/GitHub/ReadBook"
git add \
  app/src/main/java/com/example/readbook/ReadingApp.kt \
  app/src/main/java/com/example/readbook/scheduling/BootReceiver.kt \
  app/src/test/java/com/example/readbook/scheduling/BootReceiverTest.kt
git commit -m "$(cat <<'EOF'
Wire the Bible reminder alarms into app-open/boot self-heal

Same two sites the weekly summary alarm uses (ReadingApp.onCreate,
BootReceiver) - RolloverReceiver is intentionally left alone, matching
its existing behavior (it doesn't self-heal the weekly summary alarm
either).
EOF
)"
```

---

## Task 11: Full-suite verification and on-device confirmation

**Files:** none (verification only).

**Interfaces:**
- Consumes: everything from Tasks 1-10.
- Produces: nothing — this is the final acceptance pass before considering the spec complete.

- [ ] **Step 1: Run the full unit test suite one more time**

Run: `cd "D:/Users/zivk/Documents/GitHub/ReadBook" && ./gradlew testDebugUnitTest assembleDebug --rerun-tasks 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Confirm the device is connected**

Run: `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe devices -l`

Expected: one device listed as `device` (not `unauthorized`/`offline`). If not connected, stop here and wait for the user to reconnect it — do not silently skip device verification.

- [ ] **Step 3: Install over the existing app and confirm the migration doesn't crash**

```bash
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe install -r "D:/Users/zivk/Documents/GitHub/ReadBook/app/build/outputs/apk/debug/app-debug.apk"
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe shell am start -n com.example.readbook/.MainActivity
```

This is an **upgrade install** (`-r`) over the existing app with real accumulated reading history — the one place `MIGRATION_1_2` actually runs (see Task 2's note; in-memory test databases never exercise it). Confirm the app opens normally and the existing Home screen (timer state, streak) looks unchanged — this is what proves the migration applied cleanly against real data. Then check for crashes:

```bash
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe logcat -d -t 200 | grep -iE "readbook.*(exception|error|crash|FATAL)" | grep -v "ForegroundServiceTypeLogger"
```

Expected: no output.

- [ ] **Step 4: Verify the Home screen card's four states**

On the Home screen, confirm the new card appears below the timer content, showing the first unread chapter (יהושע י״ט, unless you've already made progress from earlier testing) with an enabled "קראתי" button and no message (OnSchedule state, assuming today is on/after 14.6.2026 — if today is before that date, it'll show the Waiting state instead with "נחזור לקרוא ב 14.6.2026" and a disabled button, which is also correct — confirm whichever state actually applies).

- [ ] **Step 5: Verify pressing the button advances the cursor**

Tap "קראתי". Confirm the card updates to show the next chapter. If you're behind schedule (today's actual date is well past 14.6.2026), confirm the "אתה בפיגור של N פרקים" message appears with a plausible N, and that N decreases by exactly 1 each time you tap the button again.

- [ ] **Step 6: Verify the waiting state's disabled button**

Keep tapping "קראתי" until the card shows a future-dated chapter (message starting with "נחזור לקרוא ב"). Confirm the button is visibly disabled and tapping it does nothing.

- [ ] **Step 7: Verify the reminder notification**

```bash
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe shell am broadcast -n com.example.readbook/.scheduling.BibleReadingReminderReceiver -a com.example.readbook.action.BIBLE_REMINDER
```

If the card was showing an enabled button just before this (a chapter is due), pull down the notification shade and confirm a "Bible reading reminder" notification appears showing the chapter. Tap it and confirm it opens the app directly to the Home screen. If the card was showing the disabled/waiting state instead, confirm no notification appears — re-run Step 5 first to get back into a due state, then retry this step.

- [ ] **Step 8: Check logcat for crashes across all of the above**

```bash
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe logcat -d -t 500 | grep -iE "readbook.*(exception|error|crash|FATAL)" | grep -v "ForegroundServiceTypeLogger"
```

Expected: no output.

- [ ] **Step 9: Report results to the user**

Summarize which parts were confirmed working on-device (migration/upgrade, all four card states, the reminder notification and its tap target), and flag anything that didn't behave as expected for follow-up before considering this plan complete.
