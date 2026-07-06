# Design: ReadBook polish features — reset, notification actions, weekly summary

Status: DRAFT — approved by user in brainstorming session
Date: 2026-07-06
Repo: ReadBook (Android)

## Context

ReadBook's core loop (Room schema, timer service, AlarmManager nudge scheduling, and
all three screens — Home/Settings/History) is complete and verified on-device
(`docs/design-readbook-nudge-app.md`, `TODOS.md`). This spec covers four follow-up
features requested directly by the user, three of which were already deferred items
in `TODOS.md`:

1. A "Reset today" button (new — not previously in scope).
2. Notification "Start timer" action (`TODOS.md`, deferred Approach C from office-hours).
3. Weekly summary notification (`TODOS.md`).
4. Snooze nudge by 15 minutes (`TODOS.md`).

## Feature 1: Reset today button

**Behavior:** Restores today's remaining time to the currently configured full
duration. If today was already marked complete, Reset also un-completes it and rolls
back `Stats` — this was an explicit user decision (not the simpler "only reset
before completion" alternative), since the user wants the ability to redo a day.

**Availability:** Only shown when the timer is **not running** — i.e. in
`HomeUiState.InProgress(isRunning = false)` (paused, mid-session) or
`HomeUiState.Done`. Never shown while running, to avoid a race with the foreground
service's in-memory auto-complete job that doesn't know the DB was reset out from
under it. (The alternative — making Reset available anytime and having it signal the
running service to cancel its job — was considered and explicitly rejected in favor
of this simpler, race-free option.)

**Data layer — `ReadingTimerRepository.resetToday(date: LocalDate)`:**
- Reads today's `DailyProgress` row and current `ReadingConfig`.
- If `completed == true`:
  - Sets `completed = false`, `completedAt = null`.
  - Decrements `Stats.totalCompletedDays` by 1.
  - Recalculates `Stats.currentStreak` by re-invoking the existing `StreakCalculator`
    against the completed-dates set with today excluded (no new streak logic — same
    function the completion path already uses, just called with today no longer in
    the set).
- Sets `remainingSeconds = config.targetSeconds`, `activeSessionStartedAt = null`
  (regardless of whether it was completed).
- Does **not** touch `ReadingSession` rows — past logged sessions for today stay
  visible in History; only the day's progress/completion state resets.
- No-ops safely if there is no row for today at all (nothing to reset).

**UI:** `HomeViewModel.onResetToday()` calls the repository directly — no
`ReadingTimerService` involvement, since by construction nothing is running when this
button is visible. `HomeScreen` renders a "Reset" button alongside the existing
Start/Stop toggle in the `InProgress(isRunning=false)` and `Done` branches.

## Feature 2: Notification actions — "Start" and "Snooze 15m"

Both action buttons are added to the existing hourly nudge notification
(`TimerNotifications.buildNudgeNotification`), built via
`NotificationCompat.Builder.addAction(...)`.

**"Start" action:** `PendingIntent.getForegroundService(context, requestCode,
Intent(context, ReadingTimerService::class.java).setAction(ACTION_START),
FLAG_IMMUTABLE)`. Tapping it starts reading immediately without opening the app.
Android permits starting a foreground service this way because the tap is a direct,
user-initiated notification interaction.

**Hardening this surfaces:** nothing currently stops a stale "Start" tap from
restarting an already-completed day (e.g., finished reading through another path,
then tapped an old notification still showing Start). Fix: `ReadingTimerRepository
.start()` gets a guard — if today's row is already `completed == true`, `start()` is
a no-op returning the row unchanged. This protects every entry point (Home screen
button, notification action alike), not just this new one.

**"Snooze 15m" action:** No new receiver class. `NudgeReceiver` gets a second branch:
if the incoming `Intent` carries a boolean extra (`EXTRA_SNOOZE`), it does NOT run the
normal check-and-post logic. Instead it:
1. Cancels the current nudge notification (`NotificationManager.cancel(NOTIFICATION_ID_NUDGE)`).
2. Calls a new `NudgeScheduler.scheduleSnooze()`, which schedules exactly one
   `AlarmManager.setWindow(...)` alarm 15 minutes out, targeting `NudgeReceiver` with
   the normal `ACTION_NUDGE` (no snooze extra) and its own fixed request code — so it
   can never collide with the five regular hourly slots.

When that snoozed alarm fires 15 minutes later, it's a completely ordinary
`NudgeReceiver` invocation: same completion check, same notification, same two
action buttons. No duplicated logic.

**Manifest/permissions:** no changes needed — reuses the existing `NudgeReceiver`
declaration and existing `POST_NOTIFICATIONS`/`FOREGROUND_SERVICE*` permissions.

## Feature 3: Weekly summary notification

**Timing:** Fires Sunday at 9:00 (the same time as the first daily nudge slot),
summarizing the calendar week that just ended (last Sunday through last Saturday).

**Pure function — `computeWeeklySummary(enabledDaysMask: Int, weekStart: LocalDate,
completedDates: Set<LocalDate>): WeeklySummary`:**
```
data class WeeklySummary(val completedCount: Int, val enabledCount: Int)
```
Walks the 7 days from `weekStart` (last Sunday) through `weekStart + 6` (last
Saturday), counting how many are enabled per the *current* `ReadingConfig` and how
many of those enabled days are in `completedDates`. If `enabledCount == 0` (user has
disabled every day), the caller skips posting entirely — nothing meaningful to report.

**Notification:** New 4th channel, `CHANNEL_WEEKLY_SUMMARY`, so the weekly notification
can be muted independently of daily nudges. Content: `"You read
$completedCount/$enabledCount days last week"`, same invitational tone as the rest of
the app.

**Scheduling — new `WeeklySummaryReceiver`:** follows the exact same shape as
`RolloverReceiver`. On fire (always a Sunday, by construction): computes
`weekStart = today.minusDays(7)` (last Sunday) through `today.minusDays(1)` (last
Saturday) and posts the summary for that range, then calls
`nudgeScheduler.scheduleWeeklySummary(from = today)` again to schedule the *next*
Sunday 9:00 — self-chaining, same pattern already proven for the midnight rollover
job. A new `NudgeScheduler.scheduleWeeklySummary(from: LocalDate)` computes the next
Sunday 9:00 (today if `from` is a Sunday and it's still before 9:00, otherwise the
following Sunday) and schedules via `setWindow` with its own dedicated request code.

**Self-heal wiring:** scheduled from the same three places every other alarm is:
`ReadingApp.onCreate()` (app-open self-heal), `BootReceiver` (alarms don't survive
reboots), and its own self-rescheduling after firing. Manifest gets one new
`<receiver>` entry for `WeeklySummaryReceiver`, `exported="false"`, no intent-filter
(only ever triggered by our own `PendingIntent`, like `RolloverReceiver`).

## Testing approach

Same discipline as the rest of the app: TDD for all new pure functions and DAO/
repository/ViewModel logic (Robolectric + the established `StandardTestDispatcher`
pinning pattern for anything touching Room or `AlarmManager`), then a full manual
verification pass on the physical device for the actual notification actions and
Compose UI changes — notification action taps in particular need on-device
confirmation since Robolectric's notification-action `PendingIntent` firing is not
something the existing test suite exercises yet.

## Out of scope

- Configuring the weekly summary's day/time — hardcoded to Sunday 9:00 per this spec;
  no Settings UI for it.
- Snoozing more than once per nudge, or a configurable snooze duration — fixed at 15
  minutes, one snooze slot at a time (a second snooze before the first fires just
  reschedules the same request code, which is fine — AlarmManager replaces it).
