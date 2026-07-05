# TODOS

Deferred items from the `/autoplan` CEO review (2026-07-05) of
`docs/design-readbook-nudge-app.md`. Not required for v1 correctness — see
`docs/autoplan-review-readbook.md` Phase 1 for the reasoning behind each deferral.

- **Export/backup reading history to a file.** Why: a phone loss/reset currently
  destroys the entire Room-backed streak history with no recovery path. Priority: P2.
- **Home-screen widget showing today's status/streak.** Why: high delight, but a
  distinct feature surface from the core nudge loop. Priority: P3.
- **Notification "Start timer" action button** (office-hours' deferred Approach C).
  Why: rated highest delight-per-tap by the CEO review's independent subagent voice,
  which argued it should outrank the History/Stats screen in priority — flagged as a
  taste call in the review, not auto-decided; revisit once the core loop is solid.
  Priority: P2.
- **Wear OS tile / quick settings tile.** Why: nice-to-have surface, no evidence it's
  needed for the stated goal. Priority: P3.
- **Multi-book tracking / per-session notes.** Why: genuinely separate feature beyond
  "read for 15 minutes," no evidence requested. Priority: P3.
- **Weekly summary notification** ("You read 4/5 days this week"). Why: nice
  motivational touch, not required for the core loop. Priority: P3.
- **Snooze nudge by 15 minutes** instead of just dismiss/ignore. Why: cheap UX polish,
  not required for correctness. Priority: P3.
- **Revisit whether a Tasker/MacroDroid spike would have been a faster path**, if the
  native build turns out harder than expected. Why: the CEO review's independent
  subagent voice raised this as a genuine strategic challenge — not auto-decided,
  logged here so it isn't lost. Priority: P3 (informational — revisit only if native
  build stalls).
