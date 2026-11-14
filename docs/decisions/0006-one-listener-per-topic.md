# 0006 One Kafka listener per topic

**Context.** All listeners share one consumer group. Two on one topic split its partitions, and each misses the other's events (`reading-week6_1.pdf`).

**Decision.** One listener per topic, which only routes: `PaymentsConsumer`, `ClosuresConsumer`, the starter's `KnownUsersConsumer` (kept for platform tests and `/hello`), and later `SupportConsumer`.

**Rejected.**
- Several listeners, one group: partitions split; each misses events.
- Group per area: separate offsets; areas race on the same hold.

**Cost.** Areas sharing a listener agree the hook first.

**Check.** `ConsumerGroupCheckTest`; review refuses a second listener on a topic.
