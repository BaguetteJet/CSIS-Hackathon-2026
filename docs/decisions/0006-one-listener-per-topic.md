# 0006 One Kafka listener per topic

**Context.** All our listeners share one consumer group, and Kafka gives each partition of a topic to one member of the group. Two listeners on one topic would split the partitions, and each would never see the other's events (`reading-week6_1.pdf`).

**Decision.** One listener per topic. It reads the event type and calls the right area's code: `PaymentsConsumer` (payments.v1), `ClosuresConsumer` (orders.v1, deals.v1), the starter's `KnownUsersConsumer` (users.v1), and later `SupportConsumer` (support.v1).

**Rejected.**
- A second listener on a topic in the same group: it splits the partitions, as above.
- A consumer group per area: the starter refuses to start a service whose listener isn't in the `SERVICE_NAME` group, and `platform/` is off limits. Each area would also read the topic at its own pace, so one area could act on a deal's later event before another area has handled its earlier one.

**Cost.** Areas sharing a listener agree how it calls them first.

**Check.** `ConsumerGroupCheckTest` (starter): every listener uses the shared group. Review: no second listener on a topic.
