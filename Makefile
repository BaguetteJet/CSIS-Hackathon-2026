# macOS and Linux: `make check`. Git Bash on Windows has no make: run ./check instead.
.PHONY: check test container-test run down
check:          ; ./check
test:           ; ./gradlew test
container-test: ; ./gradlew containerTest
run:            ; ./scripts/run-local.sh
down:           ; docker compose down
