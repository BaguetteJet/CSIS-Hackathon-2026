- **Owner: Sam · Backup: Shakeeba**

### What this means

- Payments tells Ledger when a student adds money. The top-up handler passes that message to Igor’s database operation.
- For a new credit, Ledger saves a confirmation event alongside the balance change so it can send the event after a restart.
- The contract tests check that Ledger’s events have the fields other teams expect.

### Checklist

- Consume `TopUpRecorded` (LED-02) and call Igor’s top-up operation.
- For a newly applied top-up, write `TopUpCredited` to the outbox in the same transaction as the credit.
- Own Ledger’s event contract tests. Check that replaying a `topUpId` produces no extra credit or event.

### Implementation

- Follow the starter’s `example/KnownUsers.kt` for the listener pattern. Listen on `payments.v1` with `@KafkaListener(id = "...", idIsGroup = false, ...)`, use the shared service group, and handle `TopUpRecorded`. Ignore unrelated event types.
- Use `EventInbox.once` for duplicate envelope IDs. Call Igor’s credit operation for business deduplication by `topUpId`. A new event ID with the same top-up ID, even with a changed amount, must leave the original credit in place. Accept the reset event at sequence 0. Route invalid amounts through the starter’s dead-letter handling.
- For a newly applied credit, call `Outbox.publish` inside the inbox transaction using the contract’s `TopUpCredited` type. Include `topUpId`, `studentId`, `amountCents`, and the resulting `availableCents` and `heldCents`. Pass the incoming event ID as `causationId`. The starter relay handles Kafka delivery.
- Extend `ContractTest.kt` for the Ledger events this slice emits. Get the actual `HoldPlaced` and `HoldRejected` output cases from Hersh so the tests cover the event shapes the service really sends.

### Ready to review

- Run `./gradlew contractTest`. Verify the same envelope replay and a second envelope with the same `topUpId` both leave one credit and one logical `TopUpCredited` event.
