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

## Data layer

- `app/src/main/assets/tanakh_schedule.csv` — the same file already committed under
  `docs/tanakh-reading-schedule/`, copied into the app so both stay in sync from one
  canonical source.
- `TanakhSchedule` — loads the asset once (lazily) into an in-memory
  `List<ScheduleEntry>`, `ScheduleEntry(book: String, chapterHeb: String, date: LocalDate)`.
  ~30KB parsed once per process lifetime; no DB storage needed for the schedule itself.
- `BibleReadingProgress` — new one-row Room entity, same singleton pattern as
  `ReadingConfig`: `@PrimaryKey val id: Int = 0, val cursorIndex: Int = 0`. New DAO
  alongside it.
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
  read happens on the in-app button, per explicit decision.
- **Self-heal**: the app has exactly one self-heal entry point today,
  `NudgeSchedulingCoordinator.ensureScheduled(date)`, called from three places —
  `ReadingApp.kt` (app-open), `BootReceiver.kt` (boot), `RolloverReceiver.kt` (midnight
  rollover) — the same three sites `scheduler.scheduleWeeklySummary(from = today)` was
  wired into for the weekly summary feature. The Bible reminder's alarm-(re)arming call
  is added at those same three sites, no new receiver classes needed for self-heal
  itself.
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

- Button label: "קראתי" (or similar — final copy decided at implementation time).
  Pressing it calls `markRead()`; the card recomposes immediately with the new status.
- `BibleReadingUiState` — small state class computed from `currentStatus()`, exposed by
  `HomeViewModel` alongside its existing timer state — same derivation pattern already
  used for the timer card, no changes to that existing state.
- No date shown for `OnSchedule`/`Behind` (only chapter identity matters day-to-day,
  per explicit decision) — the date only appears in the `Waiting` message.

## Testing approach

Mirrors the codebase's existing layering exactly:

- **Pure logic (plain JUnit)**: `TanakhScheduleTest` (parses to exactly 724 entries,
  first/last match known values, i.e. יהושע י״ט / 14.6.2026 and דברי הימים ב׳ ל״ו /
  21.3.2029), and the status/`dueCount`/`markRead` logic in `BibleReadingRepository` —
  same style as `EnabledDaysTest`/`WeeklySummaryTest`/`StreakCalculatorTest`.
- **DAO (Robolectric + Room)**: `BibleReadingProgressDaoTest`, mirroring
  `ReadingConfigDaoTest`.
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
  vs. a sibling class) and exact button copy — left to `writing-plans`.

## Next step

Hand off to `writing-plans` for the implementation task breakdown, then run `autoplan`
(CEO/Design/Eng/DevEx review) on the resulting plan before `subagent-driven-development`
execution — per user's explicit request.
