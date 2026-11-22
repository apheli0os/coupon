# Repository Automation Contract (mall-coupon-platform)

## 1. Mandatory Verification Before Commit
- Every change must pass `powershell -ExecutionPolicy Bypass -File scripts/verify-all.ps1` (or `bash scripts/verify-all.sh`) before creating a commit or PR.
- Before shipping, run an internal adversarial self-review (`thermos`, `blast-radius`, and `no-comments`) on the working diff and resolve confirmed issues.
- Any modification to distributed concurrency, state machines, Lua scripts, Kafka producers/consumers, or lifecycle jobs must update the corresponding model in `formal/models/*.tla` and keep `verify-tla` exiting `0`.

## 2. Zero-Comment Code Standard
- Do not add explanatory or narrating comments (`//`, `/* */`, `/** */`, `#`, `--`) in new or modified source, test, SQL, script, CI, or config files.
- Express intent through clear class/method/variable names and small single-purpose functions.

## 3. WSL2 Disk-I/O & Container Hygiene
- Never start local Docker Desktop or run local Testcontainers unless the user explicitly asks or `-WithContainers` is passed.
- Local `scripts/verify-all.ps1` runs zero-container checks by default (`unit + ArchUnit + Checkstyle + SpotBugs + IT compile + TLA+ + frontend build`) and delegates `*IT.java` container execution to GitHub Actions Ubuntu runners.
- All Testcontainers `*IT.java` classes must:
  - Reuse singleton containers per JVM (`static` lifecycle + `forkCount=1`, `reuseForks=true`).
  - Back MySQL `/var/lib/mysql` and Kafka `/tmp/kraft-combined-logs` with `tmpfs` (`mode=1777`) and disable binary logging / doublewrite / Redis RDB+AOF persistence.
  - Never bind-mount Windows host paths (`D:\...`) into containers.

## 4. Branch, PR & Auto-Merge Workflow
- Never commit directly or force-push to `main` / `master`.
- Never use `--no-verify` or bypass hooks/gates.
- Use `scripts/ai-ship.ps1 -Branch "ai/<slug>" -Message "<commit message>"` to verify, branch off `main`, commit, push, open a PR, and enable squash auto-merge.

## 5. High-Risk Change Policy
- Changes touching any of these paths trigger CI `high-risk-gate` and require manual approval via the PR label `approved-high-risk`:
  - `sql/**`
  - `**/db/migration/**`
  - `.github/**`
  - `AGENTS.md`
  - `config/checkstyle/**`
  - `config/spotbugs/**`
- When modifying a high-risk path, clearly explain the schema/gate impact in the PR description and wait for the owner to add `approved-high-risk`.
