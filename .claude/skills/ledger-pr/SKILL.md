---
name: ledger-pr
description: Ship Ledger work as a small, reviewable pull request into main. Use when a change is verified and ready to push.
---

# Ship a PR

1. **Branch:** `git checkout main && git pull`, then `git checkout -b item-<n>/<topic>`.
2. **Commits:** one plain subject line each, with a conventional prefix (`feat(ledger):`, `fix:`, `docs:`, `refactor:`, `test:`). No body.
3. **Before pushing:**
   - `ledger-verify` is done;
   - the diff touches only your area's files;
   - no secrets, and no binary over 1 MB.
4. **Open the PR into `main`.** A plain title, and a body of at most 5 lines: what changed (with LED numbers), how it was tested, and the prompts used (handover §18.4).
5. **Review** with the `code-review` skill (the area's backup); answer comments with `receiving-code-review`.
6. **Merge** only with CI green, as a merge commit, never squash. Delete the branch afterwards. Never force-push a branch someone else has pulled.
