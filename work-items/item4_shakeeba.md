- **Owner: Shakeeba · Backup: Hersh**

### What this means

- The dev kit simulates the other services so you can test Ledger before they are ready.
- Run both Ledger instances and show a real top-up, a successful hold, and a refusal in the demo.
- Retry the same order through the second instance and capture the API responses and Kafka events as evidence.

### Checklist

- Run the dev kit and two Ledger replicas. Route fake Orders to Ledger.
- Run `./sim money-in --repeat` and show one credit per top-up in Kafka UI.
- Capture a funded hold, an insufficient-funds refusal, and a replay through the other replica with no extra movement or event (LED-19/20).

### Demo runbook

1. Follow [SETUP.md](../SETUP.md) with the same container engine for the dev kit and Ledger. Start the dev kit, build Ledger with `./gradlew bootJar`, and check both replicas at `http://localhost:8081/actuator/health` and `http://localhost:8082/actuator/health`.
2. In the dev kit set `FAKE_EXCLUDE=ledger` and `LEDGER_URL=http://ledger:8080` for container runs. Recreate the gateway and fakes if needed. Verify the gateway routes to the actual Ledger before collecting evidence.
3. From the dev-kit checkout, run `./sim money-in --repeat`. In [Kafka UI](http://localhost:8180/topics/ledger.v1), find `TopUpCredited` and check each top-up ID is credited once. Keep the seeded student ID and available cents for the next steps.
4. Use a funded student to place a hold and find `HoldPlaced` in Kafka. Submit a fresh order for one cent more than the available balance and capture HTTP 200 `INSUFFICIENT_FUNDS`. Replay the same valid request against the other replica, then compare the original answer and check there is no new movement or logical event for that order. Use the direct replica ports for the internal hold route.
5. Ask Hersh for the request fixture with `orderId`, `studentId`, `dealId`, `cutoffAt`, and `amountCents`. Capture the IDs, before and after balances, responses, and Kafka event evidence. Record which replica handled each call. Keep tokens and credentials out of the evidence.

### Ready to review

- A teammate can reproduce the demo with your commands and tie the responses to the same Kafka IDs. Once the basic flow passes, capture the two-replica race where only one of two distinct holds can spend the same funds.
