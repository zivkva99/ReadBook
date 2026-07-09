# Design: Daily Tanakh reading reminder

Status: DRAFT — approved by user in brainstorming session
Date: 2026-07-09
Repo: ReadBook (Android)

## Context

The user reads one Tanakh (Jewish Bible) chapter a day, Sunday–Thursday, and wants
ReadBook to remind them and track progress. The full 724-chapter schedule — from
יהושע י״ט (Joshua 19) through the end of דברי הימים ב׳ (2 Chronicles 36), one chapter
per weekday, starting 14.6.2026 — was already generated and committed at
`docs/tanakh-reading-schedule/tanakh_schedule.csv` (columns: `Book`, `ChapterNum`,
`ChapterHeb`, `Date` in `d.M.yyyy`).

ReadBook already has a complete, shipped, generic "read 15 minutes a day" nudge loop
(`NudgeScheduler`, `NudgeReceiver`, `TimerNotifications`, Room-backed streak history),
whose default day config (Sun–Thu) happens to match this schedule's cadence exactly.
This feature deliberately **shares** that day-of-week configuration and alarm-scheduling
pattern, but keeps **fully separate completion tracking** — this is a second, unrelated
reading habit, not a re-skin of the 15-minute timer. (Considered and rejected: folding
Tanakh content into the existing nudge/timer loop — see Non-goals.)

## Core model: a persisted cursor into a fixed schedule

The schedule's dates never move (no "reflow" on a missed day). Progress is a single
persisted integer, `cursorIndex` — the index of the next unread chapter. Reading a
chapter always means "the current one," never "whatever's scheduled for today":

- **`currentEntry(cursorIndex)`** = `schedule.getOrNull(cursorIndex)` — `null` once all
  724 chapters are read.
- **`lastDueIndex(today)`** = index of the last schedule entry whose `date <= today`.
- **`dueCount(cursorIndex, today)`** = `lastDueIndex(today) - cursorIndex + 1` — how many
  unread chapters are already due (includes the current one).
- **Status, given `cursorIndex` and `today`:**
  - No `currentEntry` → **Finished** (all 724 read). Distinct from "waiting" below —
    there is no next chapter to show at all.
  - `currentEntry.date == today` → **OnSchedule**: show the chapter, button enabled,
    no extra message.
  - `currentEntry.date < today` → **Behind**: show the chapter (still the next one to
    read — never skip ahead), button enabled, message `"אתה בפיגור של {dueCount} פרקים"`.
    Note this can be true even when `dueCount == 1` (e.g. a Thursday chapter missed and
    today is Friday/Saturday — no new chapter has come due yet, but yesterday's is still
    unread) — the message is keyed on the date comparison, not on `dueCount > 1`.
  - `currentEntry.date > today` → **Waiting**: show the *upcoming* chapter, button
    **disabled**, message `"נחזור לקרוא ב {date}"` using the same `d.M.yyyy` format as
    the schedule file.
- **`markRead()`**: `cursorIndex += 1`, persisted immediately. Always advances by
  exactly one chapter, regardless of how far behind — catching up means pressing the
  button once per chapter, in order, never skipping to "today's" chapter.
- **`undoMarkRead()`**: `cursorIndex -= 1` (floor at 0), reversing the last `markRead()`.
  `markRead()` is otherwise the one irreversible, unrecoverable action anywhere in this
  feature — the Home screen offers a short-lived "undo" snackbar after each press.

**Rejected alternative — "only track today's assigned chapter, no ordered catch-up"**:
the app's existing 15-minute timer habit already works this way (a missed day is just a
gap in the streak; there's no concept of "catching up" on yesterday's session). The
same model was considered for this feature and rejected: Tanakh is read in a fixed
canonical order, so "skip the chapters you missed and read whatever's assigned to
today" isn't a meaningful reading experience the way it is for an undifferentiated
15-minute reading session — it would mean permanently skipping chapters rather than
falling behind on them. The ordered-catch-up model was chosen deliberately, not by
default, even though it's the source of most of this feature's actual complexity (the
`Behind`/`dueCount` machinery and its edge-case tests).

## Data layer

- `app/src/main/assets/tanakh_schedule.csv` — the same file already committed under
  `docs/tanakh-reading-schedule/`, copied into the app so both stay in sync from one
  canonical source.
- `TanakhSchedule` — loads the asset once (lazily) into an in-memory
  `List<ScheduleEntry>`, `ScheduleEntry(book: String, chapterHeb: String, date: LocalDate)`.
  ~30KB parsed once per process lifetime; no DB storage needed for the schedule itself.
- `BibleReadingProgress` — new one-row Room entity, same singleton pattern as
  `ReadingConfig`: `@PrimaryKey val id: Int = 0, val cursorIndex: Int = 0`. New DAO
  alongside it. Adding this table is the app's first-ever Room schema change (version
  1→2), verified with an automated `MigrationTestHelper` test against the schema already
  exported to `app/schemas/` — confirmed to work as a plain Robolectric/JVM test in this
  project's actual Room version, not just via an on-device upgrade-install check.
- `BibleReadingRepository` — wraps the DAO + `TanakhSchedule`, exposing
  `currentStatus(today: LocalDate): BibleReadingStatus` (sealed class: `Finished`,
  `OnSchedule(entry)`, `Behind(entry, dueCount)`, `Waiting(entry)`) and
  `suspend fun markRead()`. All the date-comparison logic above lives here as plain,
  Android-free functions wherever possible, so it's unit-testable without Robolectric.
- **No read-history/log table.** Only the cursor is persisted — matches what was
  explicitly asked for; nothing else needs tracking.

## Notification & scheduling

- New scheduling methods (exact class split — `NudgeScheduler` vs. a sibling — decided
  at plan-writing time), reusing the existing `NUDGE_HOURS` (9–13), `WINDOW_LENGTH_MS`,
  and `isEnabledDay`/`ReadingConfig` — so both features stay on the same day-of-week
  setting without duplicating that logic.
- New `BibleReadingReminderReceiver`, mirroring `NudgeReceiver`: on each hourly fire,
  computes `currentStatus(today)`. If not due (`OnSchedule`/`Behind` false, i.e.
  `Waiting` or `Finished`), silent no-op. If due (`OnSchedule` or `Behind`), posts a
  notification on a **new channel** ("Bible reading reminder") showing the chapter
  (e.g. "יהושע י״ט"). **No action buttons** — tapping opens `MainActivity` only; marking
  read happens on the in-app button, per explicit decision. Tapping the notification
  uses `FLAG_ACTIVITY_CLEAR_TOP`/`FLAG_ACTIVITY_SINGLE_TOP` so it resumes an existing
  `MainActivity` instance rather than stacking a duplicate one — the first notification
  in this app to open an Activity at all, so there was no existing precedent to follow.
- **Accepted tradeoff — notification volume**: falling behind means two independent
  hourly-until-done alarm families (the existing 15-minute nudge and this new reminder)
  can both fire up to 5 times a day, every enabled day, indefinitely, with no snooze or
  backoff (consistent with the "no action button" decision above). This is a known,
  accepted risk for a personal habit tool rather than an oversight — not addressed
  further in this design.
- **Self-heal**: the app has one self-heal entry point, `NudgeSchedulingCoordinator
  .ensureScheduled(date)`, called from three places — `ReadingApp.kt` (app-open),
  `BootReceiver.kt` (boot), `RolloverReceiver.kt` (midnight rollover). The weekly
  summary alarm, however, is only re-armed from two of those three —
  `ReadingApp.onCreate()` and `BootReceiver` — `RolloverReceiver` calls `ensureScheduled`
  and `scheduleRollover` only, not `scheduleWeeklySummary` (verified by reading the
  current source; not a hypothetical). The Bible reminder follows the weekly summary's
  actual pattern exactly: its alarm-(re)arming call is added at those same two sites
  (`ReadingApp.kt`, `BootReceiver.kt`), not a third `RolloverReceiver` site. No new
  receiver classes needed for self-heal itself.
- **Rejected approach**: folding this into `NudgeReceiver`'s existing hourly alarms
  (posting both notifications from one receiver). Fewer alarms, less scaffolding, but
  tangles two independent habits with independent completion state into one receiver —
  contradicts the "separate tracking" decision and makes both harder to test/change
  independently.

## Home screen UI

New card on `HomeScreen`, alongside the existing 15-minute timer card:

| Status | Chapter shown | Button | Message below |
|---|---|---|---|
| `Finished` | — (none left) | hidden | completion message |
| `OnSchedule` | current entry | enabled | none |
| `Behind(entry, n)` | current entry (oldest unread) | enabled | `"אתה בפיגור של {n} פרקים"` |
| `Waiting(entry)` | upcoming entry | disabled | `"נחזור לקרוא ב {date}"` |

- Button label: "קראתי". Pressing it calls `markRead()` and shows a short-lived "undo"
  snackbar (`onUndoMarkChapterRead()` on tap) — the card recomposes immediately with the
  new status either way.
- `BibleReadingUiState` — small state class computed from `currentStatus()`, exposed by
  `HomeViewModel` alongside its existing timer state — same derivation pattern already
  used for the timer card, no changes to that existing state. Includes a
  `messageIsUrgent` flag (true only for `Behind`) so that message can be styled
  distinctly (the error color) from the neutral `Waiting` message — otherwise the one
  message meant to prompt catch-up action would look identical to an informational one.
- No date shown for `OnSchedule`/`Behind` (only chapter identity matters day-to-day,
  per explicit decision) — the date only appears in the `Waiting` message, and its
  digits are Unicode-bidi-isolated to avoid reordering risk inside the surrounding RTL
  Hebrew sentence.
- The Home screen's outer layout is top-anchored and scrollable (not vertically
  centered) so this card's — and its irreversible button's — on-screen position stays
  stable regardless of which timer state is showing above it.

## Testing approach

Mirrors the codebase's existing layering exactly:

- **Pure logic (plain JUnit)**: `TanakhScheduleTest` (parses to exactly 724 entries,
  first/last match known values, i.e. יהושע י״ט / 14.6.2026 and דברי הימים ב׳ ל״ו /
  21.3.2029, and dates are strictly ascending with no duplicates — the due/behind
  algorithm silently depends on this), and the status/`dueCount`/`markRead`/
  `undoMarkRead` logic in `BibleReadingRepository` — same style as
  `EnabledDaysTest`/`WeeklySummaryTest`/`StreakCalculatorTest`.
- **DAO (Robolectric + Room)**: `BibleReadingProgressDaoTest`, mirroring
  `ReadingConfigDaoTest`. Plus `AppDatabaseMigrationTest` using `MigrationTestHelper`
  against the exported v1 schema (see Data layer section).
- **Receiver (Robolectric, fakes)**: `BibleReadingReminderReceiverTest`, mirroring
  `NudgeReceiverTest`/`WeeklySummaryReceiverTest` — fake clock, DAO/scheduler
  overrides, notify-vs-no-op assertions per status.
- **Self-heal**: extend the existing `BootReceiverTest`/`RolloverReceiverTest`/
  `NudgeSchedulingCoordinatorTest` cases to also assert the new alarm gets (re)armed,
  rather than new test files.
- **UI state**: `BibleReadingUiStateTest`, mirroring `HomeUiStateTest`.
- **Edge cases to cover explicitly**:
  - First-ever app open (no `BibleReadingProgress` row yet — defaults to `cursorIndex = 0`).
  - Catching up: pressing the button several times in one sitting, `dueCount`
    decrementing correctly each press.
  - The prior-day-but-not-yet-due-count>1 case (Thursday missed, today Friday/Saturday
    — `Behind` with `dueCount == 1`).
  - Transition into `Finished` right at the 724th chapter.
  - Changing the day-of-week config in Settings after the fact doesn't affect
    `isDue`/status logic at all (it's driven purely by elapsed calendar dates on the
    fixed schedule, not by which days are "enabled" — that config only gates when the
    *notification* fires, not whether a chapter counts as due).
- **Real-device verification** (standing project requirement): the notification firing,
  all four card states, and the disabled→enabled button transition need confirmation on
  the user's Galaxy device, not just Robolectric.

## Non-goals (explicitly decided against)

- Folding Tanakh content into the existing 15-minute timer/nudge notification text or
  completion state.
- A read-history/log table beyond the single cursor.
- A "Mark as read" action button on the notification itself — tapping only opens the app.
- Schedule reflow — the calendar dates in `tanakh_schedule.csv` are permanent; falling
  behind never shifts them.
- Deciding exact file/class boundaries for the new scheduler methods (`NudgeScheduler`
  vs. a sibling class) — left to `writing-plans` (resolved there as new methods on
  `NudgeScheduler` itself, consistent with how it already houses every other alarm
  family in this app).

## Next step

Hand off to `writing-plans` for the implementation task breakdown, then run `autoplan`
(CEO/Design/Eng/DevEx review) on the resulting plan before `subagent-driven-development`
execution — per user's explicit request.
