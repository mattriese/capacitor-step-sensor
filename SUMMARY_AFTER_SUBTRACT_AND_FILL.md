---

What changed and why

Problem 1 — Temporal inaccuracy. When a watch syncs 240 steps to Health Connect, the old code dumped all 240
into whichever single 30s bucket coincided with the sync poll. The DB said 240 steps in one 30-second window,
which is wrong.

Problem 2 — Double-counting. The old MAX(phoneDelta, hcDelta) only worked within a single tick. If the phone
recorded 30 steps/tick into buckets [0:00]–[1:30], then a watch sync arrived at [2:00] with 120 steps covering
that same range, the old code wrote MAX(31, 120)=120 into bucket [2:00]. Total: ~120 phone + 120 HC = 240 in
the DB. Double the real steps.

Problem 3 — Boundary leakage. An HC record spanning 09:58–10:02 would credit pre-commitment steps (09:58–10:00)
into the commitment window if the commitment starts at 10:00.

Problem 4 — No plausibility filter. Nothing prevented a single 30s bucket from containing physically impossible
values (e.g., 500 steps in 30 seconds).

The fix — subtract-and-fill with boundary clamping and cap:

- Instead of aggregating HC into a single delta, collectHcRecords() gets individual StepsRecord entries with
  their original timestamps
- subtractAndFill() subtracts phone steps already in the DB for the covered range, then distributes the surplus
  into only the zero-step buckets (gaps the phone missed)
- Only buckets within [commitmentStart, commitmentEnd) receive steps
- Each zero-step bucket is capped at 90 steps/30s (180 steps/min)

---

Does the plugin retroactively update records?

Yes, but only while the service is running. Every 30 seconds, onTimerTick():

1. Writes phone delta to the current bucket
2. Calls collectHcRecords() — if a watch sync just landed, this returns records covering past time ranges
3. Reads existing DB rows for that past range
4. Runs subtractAndFill to compute surplus
5. Writes surplus into past zero-step buckets via insertOrUpdate (which uses MAX(steps, excluded.steps), so
   phone data is never reduced)

So a bucket that was 0 at T=0:30 (phone on desk) can become 30 at T=2:30 when the watch sync arrives. This is
the core feature — the plugin back-fills past buckets with watch data as it arrives.

---

The critical gap: what happens after the service stops

The stop alarm fires at commitmentEnd and calls stopService(). At that point:

- The timer stops, onTimerTick() no longer runs
- Any watch sync that arrives after the service stops is never processed
- The changes token is lost (it's in-memory, not persisted)

This means the last ~2-5 minutes of a commitment window are at risk of undercounting if the user was wearing a
watch with the phone on a desk and the final watch sync hasn't landed yet.

Similarly, if the consuming app calls getTrackedSteps(deleteAfterRead: true) while the service is still
running, it deletes rows that the next HC sync might have back-filled. The service will recreate those rows
when HC data arrives, but it won't know about the phone steps that were already deleted — so it treats every
bucket as zero-step and distributes the full HC count, potentially giving you the HC data on top of phone data
you already consumed.

---

Consuming app responsibilities

1. Don't use deleteAfterRead during active tracking

For progress display during a commitment, use getTrackedSteps({ since: commitmentStart }) without
deleteAfterRead. Read the same rows repeatedly. They may increase as HC data back-fills them.

2. Wait a buffer after the commitment ends before doing a final read

Samsung Galaxy Watch syncs every ~2-5 minutes (undocumented). The service stops at commitmentEnd. If the last
watch sync hasn't arrived yet, those final buckets may be zero.

Recommended pattern:
// Schedule the commitment window
await StepSensor.scheduleStepTracking({
windows: [{ startAt, endAt }],
});

// ... commitment runs ...

// After endAt, wait a buffer before final read
setTimeout(async () => {
const result = await StepSensor.getTrackedSteps({
since: startAt,
deleteAfterRead: true,
});
// This is your final count
const total = result.steps.reduce((sum, b) => sum + b.steps, 0);
}, 5 _ 60 _ 1000); // 5-minute buffer

But note: the service is already stopped during this buffer. No HC processing happens during the buffer. The
buffer only helps if the last watch sync happened to land in the final tick before the service stopped.

3. The 5-minute buffer doesn't actually solve the gap

This is an honest limitation. If the phone was on a desk for the last 3 minutes and the watch sync arrives 2
minutes after commitmentEnd, those steps are lost. The service isn't running to process them.

Possible mitigation the consuming app could implement:

- Extend the tracking window — schedule endAt to be 5 minutes past the actual commitment deadline. The service
  keeps running, catches the final sync, and the boundary clamping ensures only in-window buckets get credited
- This is probably the best approach: pad the window, let the subtract-and-fill + boundary clamping do their
  job

4. Use hcMetadata for diagnostics

Buckets that received HC data have a non-null hcMetadata field (JSON string). The app can parse this to see the
raw HC records — their original time ranges, step counts, and data origins. This is useful for debugging but
not required for normal operation.

5. Understand the MAX upsert semantics

Buckets only ever go up. If the app reads a bucket showing 10 steps, and later reads the same bucket showing 50
steps, the bucket was back-filled by HC data. The app should use the latest value, not sum the two reads.

---

Summary of data accuracy guarantees

Scenario: Phone in pocket the whole time
Accuracy: Exact per-bucket
Why: Phone sensor is real-time, 30s granularity
────────────────────────────────────────
Scenario: Phone on desk, watch on wrist, service running when sync arrives
Accuracy: Good — distributed across correct time range
Why: subtract-and-fill back-fills zero-step buckets
────────────────────────────────────────
Scenario: Phone on desk, watch sync arrives after service stops
Accuracy: Steps lost
Why: No running service to process HC records
────────────────────────────────────────
Scenario: Both phone + watch active
Accuracy: Correct total, no double-counting
Why: subtract-and-fill subtracts phone steps before distributing HC surplus
────────────────────────────────────────
Scenario: HC record overlaps commitment boundary
Accuracy: Correct — only in-window buckets written
Why: Boundary clamping with full inclusion of hcCount

The biggest thing the consuming app can do for accuracy is pad the tracking window a few minutes past the
actual deadline so the service is still running to catch the final watch sync.
