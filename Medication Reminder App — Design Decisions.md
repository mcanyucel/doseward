# Medication Reminder App — Design Decisions

Nov 2, 2026 · @Mustafa Can Yücel

## Overview

A native Kotlin Android app that rings medication alarms on time, even when the phone is locked, dozing or freshly rebooted. It supports fractional doses and tracks stock in tablets, not milligrams.

**v1 scope**

- Medication schedules: fixed times on fixed days (e.g. 3× daily, once daily, every other day, chosen weekdays).
- Fractional doses in quarter steps (e.g. 0.5, 1.5 tablets).
- Reliable alarms that wake a locked phone, with snooze, skip, repeat-if-ignored and a user-chosen alarm mode.
- Taking or skipping an upcoming dose early, from the app.
- Dose history with taken / skipped / missed statuses and retroactive edits.
- Stock tracking with a low-stock warning, set per medication in days of supply.
- JSON export/import and Android Auto Backup.
- A local diagnostics log the user can export; nothing is sent automatically.

**Non-goals for v1**

- iOS, cloud sync, accounts, analytics.
- Drug databases, interaction checks or any dosing advice.
- As-needed (PRN) medications, and fixed-interval dosing that ignores time zones.

**Stack**

| Layer | Choice |
| --- | --- |
| Language | Kotlin, 100% native (Flutter dropped to avoid engine/background friction) |
| Min Android | Android 8.0 (minSdk 26) |
| UI | Jetpack Compose, Material 3 |
| Storage | Room (SQLite); a separate small device-protected database for before-first-unlock |
| Time | java.time (LocalTime, ZonedDateTime) |
| Alarms | AlarmManager, one alarm for the whole app; the user picks the alarm mode (alarm clock, exact or approximate) |
| Safety net | WorkManager periodic job every 30 min, best effort |
| Network | None; no Google SDKs |
| Distribution | Google Play (F-Droid dropped after the stalled review); package `com.mustafacanyucel.doseward` |

## Data model

All quantities are integers in thousandths of a unit (1.5 tablets = 1500), so sums are exact and SQL can add them. Doses are entered in quarter-unit steps (0.25, 0.5, 1.5). Schedules store the rule, versioned by effective date; the dose log records what happened.

| Entity | Key fields | Notes |
| --- | --- | --- |
| Medication | name, lock-screen alias (optional), unit label ("tablet"), stock (milli-units), strength per unit, alert style, repeat interval, max repeats, late window override (optional), low-stock days, low-stock warned, doses generated until, active | Strength (e.g. 20 mg/tablet) is display-only; stock is always in units |
| Schedule | medication, times of day (LocalTime list), recurrence, dose per intake (milli-units), start date, optional end date, effective from, effective until | Recurrence = every N days from an anchor date, or chosen weekdays. Versioned: an edit closes the current version and starts a new one, so past doses keep the rule they came from |
| DoseLog | medication, schedule version, scheduled time (local + instant), status, acted-at, amount, snoozed-until, next ring at, ring count, created by (app / user) | One row per scheduled dose; drives history, stock and undo. Ring state keeps reconcile idempotent |
| StockEvent | medication, delta (milli-units), reason (dose, undo, restock, correction), time | Audit trail for stock changes |
| DiagnosticEvent | time, kind (alarm armed, alarm fired, rang, user action, permission or mode change), detail | Local only, pruned after a set period; exported from the Diagnostics screen |
| UpcomingEvent (device-protected) | fire time, display name (alias if set), dose text, kind (dose / repeat / snooze) | Next 48 h only; readable before first unlock |
| PendingAction (device-protected) | dose reference, action, time | Actions taken before first unlock, merged into DoseLog after unlock |

App settings (alarm mode, last-opened time) live in a small key-value store, excluded from backup.

Dose statuses are **open**, **taken**, **skipped** and **missed**. A snoozed dose is still open, with snoozed-until set.

## Dose semantics

A dose stays open until the user acts on it, its late window ends, or the next dose of the same medication arrives, whichever comes first; then the app closes it as missed.

| Status | Set by | Meaning |
| --- | --- | --- |
| Open | App | Due or upcoming, not yet acted on (includes snoozed) |
| Taken | User | User confirmed the dose; stock is decremented |
| Skipped | User | User deliberately did not take it |
| Missed | App | Closed automatically because its late window ended or a later dose superseded it |

**Supersession (per medication)**

- When a medication's next dose time arrives and an earlier dose of the *same* medication is still open, the earlier dose becomes missed.
- Other medications never supersede each other: an open 08:00 Paxil dose is not closed by an 08:15 vitamin alarm.
- The new dose's alarm says what happened, e.g. "08:00 dose was marked missed."

**Late window**

- Each dose has a late window: how long after its scheduled time it can still be marked taken from the alarm. By default it is the shorter of one third of the gap to the medication's next dose and 6 h (3 h gap → 1 h; 8 h → 2 h 40 min; daily or less often → 6 h). The user can override it per medication.
- After the window the dose is missed, even when the next dose is days away (every other day, chosen weekdays).
- A dose found open after an outage but still inside its window rings with its delay shown ("22:00 dose, 1 h late"). Outside the window it is marked missed and never rung.

**Dynamic snooze**

- Snooze options are capped by the next dose of that medication or the end of the late window, whichever is sooner. If the cap is 15 min away, "Snooze 10 min" is offered and "Snooze 30 min" is not.
- Supersession still applies if a snoozed dose is ignored until the next dose.

**Early doses**

- The home screen lists each medication's next dose ("Next meds"). Tapping one opens a menu: Take now, Skip this dose. A swipe can be added later as a shortcut.
- Only the next dose of each medication can be acted on early.
- Acting early creates that dose's DoseLog row ahead of time with the actual time; its alarm is not rung.

**Retroactive edits**

- Any dose in the history can be changed to taken, skipped or missed, with an actual time.
- Stock follows the edit: changing to taken decrements it, changing away from taken restores it.

**No dosing advice**

- Messages state facts only. The app never suggests taking two doses together or catching up a missed dose.

## Reconcile algorithm

One function, `reconcile(now)`, owns all dose-state logic. Every trigger calls it, and running it twice in a row must give the same result (idempotent). Calls are serialized with one mutex and run in one database transaction, so concurrent triggers never interleave. The before-first-unlock path is the one exception (see System lifecycle).

**Triggers**

- Alarm fires
- Boot (locked and unlocked), app update
- App opened
- Timezone or clock changed
- Exact-alarm permission granted
- Any user change: schedule edit, taken / skipped / snoozed, early action, retroactive edit, import
- Periodic safety-net job (best effort)

**Steps, per medication**

1. Create DoseLog rows for scheduled times from the medication's "doses generated until" mark up to `now`, using the schedule version in effect at each time. Never create rows for times before the schedule existed.
2. Mark as missed every past open dose that is not the most recent one, or whose late window has ended. Keep the count for the message ("6 earlier doses were marked missed").
3. If the most recent past dose is still open and inside its late window, it is due. Ring it only if it is not snoozed and its next-ring time has passed; record the ring and set the next-ring time (none once repeats are used up). Because rings are recorded, a second call does not ring again.
4. Collect everything due across all medications within a 60 s tolerance (doses, repeats, expired snoozes) into **one** alarm screen. Notifications use a stable ID per dose and alert only once per ring.
5. Check stock and post a low-stock warning if needed (see Stock tracking).
6. Find the earliest future event across all medications (next dose, next ring, snooze end, late-window end) and arm the single OS alarm for it, in the current alarm mode.
7. Rewrite the device-protected UpcomingEvent table with the next 48 h of events.
8. If the app has not been opened for more than a month, post the inactivity nudge (see Onboarding and permissions).
9. Write a diagnostics entry: what rang, and what was armed for when.

**Clock changes:** when the clock moves back, rows the app created that the user never touched and that are now in the future are deleted, and "doses generated until" moves back with them. Rows the user acted on are kept. Reconcile spots this itself ("doses generated until" is ahead of now), so it needs no flag from the trigger.

**Time-zone changes:** future rows keep their wall-clock time, so their instant moves with the zone. After a jump east, wall-clock times the clock skipped over get rows too: rung late if still inside their window, otherwise marked missed. After a jump west, a wall-clock time that comes round again does not ring twice.

## Alarm architecture

The app keeps exactly one OS alarm registered at any time, for the whole app. The user chooses how it is set (alarm mode below); WorkManager is never used for dose timing.

**Alarm mode (user setting)**

| Mode | Set with | Needs | Timing | Side effects |
| --- | --- | --- | --- | --- |
| Alarm clock (most reliable) | `setAlarmClock` | Exact-alarm permission | On time, even in Doze: Android does not enter deep Doze within an hour of it, and ends Doze for it | Shows as the phone's next alarm in the status bar, clock widget and watches, hiding the user's wake-up alarm. Ends a Do Not Disturb schedule early when its "Alarm can override end time" option is on |
| Exact | `setExactAndAllowWhileIdle` | Exact-alarm permission | On time, even in Doze. Doze limits how often: Android 12+ allows 72 per hour, so repeats are unaffected; Android 8–11 allow about one per 9 min, so 5-min repeats stretch to ~9 | None of the above |
| Approximate | `setAndAllowWhileIdle` | Nothing | Can be late, especially in Doze and on aggressive OEMs | None; a home-screen banner says alarms may be late |

- The settings screen explains each mode's purpose and side effects in plain words.
- There is no default mode: on first launch the app opens this setting and the user picks one. The choice is per phone, so it is excluded from backup and asked again on a new phone, with or without an import.
- Without the exact-alarm permission the app uses Approximate, whatever the setting says, and returns to the chosen mode when the permission is granted.
- Spike results (Galaxy S23 Ultra, Android 17, One UI 9): locked, Alarm clock and Exact fired 0 s late; Approximate was held by light Doze and fired 528 s late, on unlock. Under forced deep Doze, Exact fired 0 s late, and an Alarm clock alarm due within the hour stopped the phone from entering deep Doze at all. Not yet tested: a real overnight Doze.

**Single alarm**

- One fixed `PendingIntent`. Re-scheduling replaces the previous alarm, so there are no alarm IDs to track or stale alarms to cancel.
- The alarm carries no payload. The receiver calls `reconcile(now)`, which reads the database to decide what is due.

**Alarm screen**

- Everything due at the same moment appears on one screen: each medication with Taken, Snooze and Skip, plus "Took all".
- Delivered as a full-screen-intent notification. Android shows it full screen when the phone is locked or idle, and as a heads-up banner when the phone is in use.
- Shows on the lock screen and turns the screen on (`showWhenLocked`, `turnScreenOn`); sound plays on the alarm stream (`USAGE_ALARM`).
- Notifications use `CATEGORY_ALARM`, so Do Not Disturb lets them through when it allows alarms.

**Alarm sound**

- Option A: the app plays the sound itself in a foreground service of type `systemExempted`, which Android allows for apps with the exact-alarm permission that keep alarms ringing. This allows looping and gentle-to-loud escalation.
- Option B: the notification channel's sound. No service, but fixed volume and no escalation. Approximate mode always uses B, since it has no exact-alarm permission.
- The alarm spike tries A on locked, dozing and freshly rebooted phones. If it fails or Play rejects the service type, B is used and escalation is dropped.
- Android 15 blocks some foreground-service types from boot receivers, so the boot path never rings directly: it arms the alarm for now, and the alarm receiver rings.

**Repeat if ignored**

- Default: re-ring every 5 min, up to 5 times. Both values are configurable per medication. On Android 8–11 in Exact mode, Doze can stretch repeats to about 9 min.
- Escalation (gentle first, then louder or vibrating) needs alarm sound option A; the first ring is already full-screen capable.
- After the last repeat the dose stays open until the user acts, its late window ends or the next dose supersedes it.

**Safety net**

- `reconcile(now)` on every app open.
- A WorkManager periodic job every 30 min calls `reconcile(now)`, which re-arms the alarm if needed. It is best effort: Doze and standby buckets can delay it by hours, and it does not run after a force-stop or an exact-alarm revocation. It adds coverage but guarantees nothing.

&#91;embedded content: alarm loop · 7 triggers, 1 function, 5 outputs\]

Every trigger ends in the same reconcile call; its last act is arming the single alarm, which in turn becomes the next trigger.

## System lifecycle

Android drops or invalidates the alarm in several situations, and each one ends in the same `reconcile(now)` call.

| Event | Receiver / signal | Action |
| --- | --- | --- |
| Reboot, before first unlock | `LOCKED_BOOT_COMPLETED` (direct-boot-aware) | Run the reduced before-first-unlock reconcile (below); anything due rings from the alarm receiver, not the boot receiver |
| First unlock after reboot | `BOOT_COMPLETED` / user unlocked | Merge PendingAction rows into DoseLog, full reconcile |
| App updated | `MY_PACKAGE_REPLACED` | Reconcile |
| Timezone changed | `ACTION_TIMEZONE_CHANGED` | Recompute future concrete times from stored local times, reconcile |
| Clock changed | `ACTION_TIME_CHANGED` | Reconcile, including the clock-moved-back cleanup |
| Exact-alarm permission granted | `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` | Return to the chosen alarm mode, reconcile |
| Exact-alarm permission revoked | No broadcast; the app is stopped and its exact alarms are deleted | Detected by `canScheduleExactAlarms()` on app open; switch to Approximate mode; the checklist shows the problem |
| Unused for months (app hibernation, Android 12+) | No broadcast; permissions are revoked and the app is stopped | Checklist item and inactivity nudge (see Onboarding and permissions) |

**Before first unlock (BFU)**

- With file-based encryption, normal (credential-encrypted) storage is unreadable until the first unlock after reboot.
- The alarm path is direct-boot-aware and reads only the device-protected UpcomingEvent table: fire times, medication display names (alias if set) and dose text for the next 48 h.
- Actions taken on the BFU alarm screen are written to PendingAction and merged into DoseLog after unlock.
- This is a second, reduced reconcile (`reconcileLocked`): it rings, snoozes and repeats from UpcomingEvent and PendingAction only. It is kept minimal and has its own tests.
- The device-protected data is a separate Room database: it cannot share a transaction with the main one, and it is excluded from Auto Backup.
- Accepted trade-off: those few fields are readable before unlock. Full history stays in encrypted storage.

**Time zones**

- Dose times follow the phone's current time zone.
- Schedules store wall-clock local times; concrete instants are computed with `java.time`, which handles DST gaps and the repeated hour when clocks go back (each dose rings once).
- Dose rows already created keep their instants; only future times are recomputed.
- Medication setup shows a factual note: dose times follow the phone's time zone and shift when you travel.

## Notifications and alert styles

Each medication picks how hard it alerts, and missed doses are reported per medication inside one notification group.

**Alert style (per medication)**

- Notification: a normal notification, no alarm screen.
- Alarm: full-screen alarm, rings once.
- Alarm with repeats: full-screen alarm plus repeat-if-ignored (default for critical meds).

**Notification actions**

- Taken, Snooze, Skip on every dose notification.
- Snooze choices are capped by the medication's next dose or late window (see Dose semantics).

**Channels**

| Channel | Importance | Used for |
| --- | --- | --- |
| Dose alarms | High, alarm-stream sound | Alarm screen and its full-screen intent |
| Dose reminders | High | Notification-style medications |
| Missed doses | Default | One notification per medication, grouped under one summary |
| Low stock | Default | Low-stock warnings |
| Setup problems | Default | A permission or setting needed for reliable alarms was lost; the inactivity nudge |

**Missed-dose wording**

- Per medication: "Paxil: 08:00 dose was marked missed."
- After an outage: "Paxil: 6 earlier doses were marked missed", shown with the current due dose.

**Lock-screen privacy**

- A medication can have an optional alias (e.g. "Morning pill"). The lock screen, the locked alarm screen and the device-protected table show the alias instead of the name.

## Stock tracking

Stock is counted in units (tablets) and decremented only when a dose is marked taken. The app warns once when fewer days of supply remain than the medication's threshold.

- **Decrement:** on Taken, by the dose amount (e.g. 1.5). Undo and retroactive edits restore or re-apply it. Every change writes a StockEvent.
- **Restock:** the user adds a quantity; a manual correction sets the count directly.
- **Daily rate:** total units per schedule cycle ÷ cycle length in days. Examples: 3 × 1 tablet daily = 3/day; 1.5 tablets daily = 1.5/day; 1 tablet every other day = 0.5/day.
- **Days left:** stock ÷ daily rate.
- **Warning:** fires when days left drops below the threshold (5 days at 3/day → below 15 tablets; 21 days at 1 tablet a week → below 3). It fires once and re-arms only after a restock lifts days left back above the threshold.
- **Threshold:** entered by the user in days when adding the medication ("Warn when less than X days of meds are left"), prefilled with 5 and editable later. Days work for any schedule; a weekly medication simply needs a larger value.

## Onboarding and permissions

A single checklist screen shows each requirement with a green check or a Fix button. It is re-checked on every app launch, because users and OEM updates can revoke any of these silently.

| Item | Why it matters | Fix action |
| --- | --- | --- |
| Notifications | Nothing can be shown without it (Android 13+) | Runtime permission request |
| Alarm mode | Each mode trades reliability against side effects (see Alarm architecture) | Opened automatically on first launch; explains each mode |
| Exact alarms ("Alarms & reminders") | Alarm clock and Exact modes need it; denied by default on Android 14+ for new installs. Optional: without it the app uses Approximate mode | Open the app's exact-alarm settings page |
| Full-screen alerts | Needed to wake a locked phone with the alarm screen (Android 14+). Optional | Open the full-screen intent settings page; fall back to a high-priority notification if denied |
| Pause app activity if unused | Android 12+ revokes permissions and stops apps not opened for months | Check with `getUnusedAppRestrictionsStatus`; open the app's settings page to turn it off |
| Battery optimization | Reduces OEM interference | Open the battery optimization settings list (no special permission needed) |
| Manufacturer autostart / background limits | Xiaomi, Samsung, Huawei, Oppo and others kill apps beyond stock Android | Brand-specific instructions, following dontkillmyapp.com |

If an item regresses after setup, the home screen shows a banner and the Setup problems channel posts a notification.

**Inactivity nudge:** the app records a timestamp whenever one of its screens is shown, including the alarm screen. If reconcile finds more than a month since then, it posts: "Permissions might be revoked and alarms may stop. Tap to open the app." Opening the app resets the timer. The nudge is skipped when "Pause app activity if unused" is already off.

## Backup, export and import

Backups use only Android system features, so Google Drive works without any Google SDK, OAuth setup or Drive API scopes.

- **Manual export / import:** a versioned JSON file (medications, schedules, dose log, stock events), written and read through the Storage Access Framework (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`). The system picker already offers Google Drive, local storage and other providers.
- **Import replaces all data.** Before replacing, the app exports the current data automatically.
- **Automatic:** Android Auto Backup with explicit backup rules, restoring the database on a new phone. The device-protected database and app settings are excluded.
- **After a restore on a new phone:** medications and history come back, but permissions do not, and nothing rings until the user opens the app and completes the checklist.
- **After import or restore:** run `reconcile(now)` and reschedule the alarm, since imported doses may already be in the past.

## Diagnostics

The app keeps a local log of alarm events so failures on specific phones can be traced without collecting any data.

- DiagnosticEvent rows record when the alarm was armed, when it fired, what rang, user actions, and permission or mode changes.
- A Diagnostics screen shows the log and exports it as a file. If a user reports a missed alarm, they can send that file to the developer.
- Nothing leaves the phone unless the user exports it.

## Google Play requirements

Publishing on Play rules out the auto-granted alarm permissions, so the app relies on user-granted permissions plus the onboarding checklist.

| Topic | Decision | Source |
| --- | --- | --- |
| Exact alarms | Declare `SCHEDULE_EXACT_ALARM`, not `USE_EXACT_ALARM`; Play limits the latter to alarm-clock, timer and calendar apps | [Android docs](https://d.android.com/training/scheduling/alarms) |
| Full-screen intent | Complete the Play Console declaration and request alarm core functionality; since Jan 22, 2025 only calling and alarm apps get it by default, so the app treats it as optional and handles user grant and denial | [Play Help](https://support.google.com/googleplay/android-developer/answer/13392821) |
| Foreground service | If the alarm spike keeps option A, declare the `systemExempted` type and its use in Play Console | — |
| Battery optimization | Do not declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; open the settings list instead | — |
| Health apps declaration | Required; medication reminders are a listed health feature | [Play Help](https://support.google.com/googleplay/android-developer/answer/14738291) |
| Privacy policy | Required for health apps; data is local-only, no collection or sharing | [Play Help](https://support.google.com/googleplay/android-developer/answer/16555673) |
| Data safety form | No data collected or shared | — |
| In-app disclaimer | The app does not give medical advice; consult a doctor or pharmacist | [Play Help](https://support.google.com/googleplay/android-developer/answer/13996367) |
| Closed testing | New personal developer accounts need 12 testers opted in for 14 days before production; organization accounts are exempt. Check whether health apps require an organization account | — |

## Testing strategy

Reliability is only provable by testing on real devices, so scheduling logic is unit-tested and every lifecycle event has a manual device test.

**Unit tests (pure Kotlin, injectable clock)**

- Schedule engine: recurrences, weekdays, every-N-days anchors, schedule versions, DST gaps and repeated hours, timezone switches.
- Reconcile: supersession, late window, catch-up after an outage, snooze caps, repeats, ring idempotence, clock moved back, early doses.
- BFU reconcile: rings, snoozes and repeats from device-protected data only; PendingAction merge.
- Stock: fixed-point arithmetic, daily rate, warning fire and re-arm.

**Device tests**

- Doze: `adb shell dumpsys deviceidle force-idle`, then wait for the alarm with the screen off.
- Alarm registration: `adb shell dumpsys alarm` shows the app's single alarm and its type.
- Each alarm mode, including a Do Not Disturb schedule with "Alarm can override end time" on.
- BFU: reboot and do not unlock; the due alarm must still ring.
- Timezone and clock changes; exact-alarm permission revoked and re-granted; app update.
- Standby buckets (`adb shell am set-standby-bucket <package> restricted`), force-stop and app hibernation.
- Device matrix: the developer's own Samsung phone, plus a Pixel emulator image as the stock-Android baseline.
- Soak: run a real schedule for at least a week before release.

## Open questions and build order

The alarm spike comes first: if a locked, dozing, freshly rebooted phone rings on time, the rest is ordinary app work.

**Open questions**

- [x] Minimum Android version: Android 8.0 (minSdk 26).
- [x] As-needed (PRN) medications: not in v1.
- [x] Low-stock threshold: entered by the user per medication, in days of supply.
- [x] Import behaviour: replace all data, after an automatic export.
- [ ] Alarm sound: app-played in a `systemExempted` foreground service (option A) or the notification sound (option B). Decided by the alarm spike.
- [x] Default late window: the shorter of one third of the gap to the next dose and 6 h; overridable per medication.
- [x] Dose input steps: quarter units.
- [x] Default alarm mode: none; the user picks one on first launch.
- [ ] Closed-testing plan for Play.
- [x] App name: Doseward ("DoseKeep" is taken by a similar offline iOS reminder app).

**Build order**

1. Alarm spike: each alarm mode + full-screen alarm on a locked phone, tested under forced Doze and a BFU reboot; settle the alarm sound option. An internal-testing upload shows what Play asks about option A's foreground service.
2. Data model (schedule versions, ring state), schedule engine and `reconcile(now)`, with unit tests.
3. Alarm screen, notifications and actions (Taken, Snooze, Skip, Took all), plus early Take / Skip from Next meds.
4. Lifecycle receivers, device-protected storage, the BFU reconcile and the safety-net job.
5. Stock tracking and low-stock warning.
6. Onboarding checklist, alarm-mode setting and inactivity nudge.
7. JSON export/import, Auto Backup and the diagnostics log.
8. UI design and theming.
9. Play Console declarations and submission.
