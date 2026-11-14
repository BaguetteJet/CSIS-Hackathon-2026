---
name: comment-refactor
description: Refactor code comments in a change set (branch, files, or package): cut, fix stale, tighten, keep decisions and why; code stays byte-identical. Use when asked to refactor, clean up or rewrite comments.
---

Refactor the **comments** in a change set. The code is frozen: only comment text moves. The goal is that every surviving comment is **live** (true of the code as it stands now) and **earns its line** (says something the code cannot).

**Voice**: write like a teammate leaving a note: plain, human, terse. Concise and coherent beats complete: state the decision and its reason in the fewest words, skip what the code or a nearby comment already says, and never explain the explanation. Use simple English and the imperative ("Decide each order's hold once"), and link a label to its point with `:` rather than commas or semicolons ("Already taken: return the saved answer"). Comment the *decisions* and the *why*; let names and code carry the *what*. One line is the default. Two or more points become a one-line summary plus short `-` bullets, one point per bullet, not a paragraph. A longer block is right exactly where a reader would otherwise get it wrong (a concurrency argument, a lock order, a spec rule that looks odd in code), and there it should be as long as the reasoning needs.

## Steps

1. **Scope.** Default: every file changed on the current branch against its base (`git diff --name-only <base>...HEAD`), plus uncommitted files. Drop generated files and code the project tells you to leave as is (check README / CLAUDE.md, e.g. a vendored `platform` package). If the user named files or a package, use exactly that. *Done when* the file list is written in your reply.
2. **Audit.** Read each file whole. Put every comment (line, block, KDoc/Javadoc, SQL `--`, YAML `#`) into one bucket from the reference below: **keep**, **fix**, **tighten**, **cut**. For every identifier, file, table, column, class, owner or item number a comment names, grep the repo to confirm it still exists and still means that. *Done when* every comment in every file has a bucket; a file you skimmed is not done.
3. **Edit.** Apply the buckets. Match the file's own voice and density: if the codebase writes KDoc on public types, keep KDoc there; if it writes plain sentences, write plain sentences. Prefer one line; keep a block only where the *why* needs it.
4. **Verify.** Every diff hunk touches comment lines only: run `git diff -U0` and read each hunk. Then build and run the fast tests (and any test the project gates CI on). *Done when* no hunk changes a code token and the build/tests are green.
5. **Report.** Per file: counts kept / fixed / tightened / cut, and list every **stale** comment found with what it wrongly claimed. Leave the change uncommitted unless the user asked for a commit.

## Reference: the four buckets

- **Keep**: the comment says *why* (a design reason, a trade-off), states a *contract* the caller must honour ("call inside the transaction that publishes the event"), records a *gotcha* no code confesses (a lock order, a MySQL snapshot quirk, a timeout budget), or cites the *spec* (requirement IDs like `LED-03`, `DEC E7`, an RFC). Spec IDs are the cheapest traceability there is; keep them.
- **Fix**: the comment is **stale**: it names something renamed, moved or deleted, gives a wrong number, describes behaviour the code no longer has, or points at a plan that already happened ("after slice 1, X will..."). Rewrite it to the truth, or cut it if the truth is already obvious from the code.
- **Tighten**: the comment is live and useful but wordy, repeats itself, or restates a nearby comment. Shorten to the one sentence that carries the meaning; keep each fact in one place (on the declaration, not at every call site).
- **Cut**: the comment restates what the next line plainly does, narrates history (who drafted it, what it used to be: that is git's job), carries ownership ("Owner: X", "Igor's from here": that lives in an owners file, where it can change in one place), or is a finished TODO.

## Reference: judgement calls

- A comment that is the *only* record of a non-obvious reason is never cut, however long; tighten it instead.
- Ownership and history lines that the repo deliberately keeps everywhere (a house convention visible in untouched files) are kept.
- Wording that is part of a contract with users (statement text, error messages, log keys) is code, not comment: leave it.
- Comments inside checksummed files are code too: an applied Flyway/Liquibase migration's checksum covers its comments, so editing one breaks every database that already ran it. Report stale comments there; don't edit them.
- When unsure whether a comment is stale, the grep decides, not memory.
