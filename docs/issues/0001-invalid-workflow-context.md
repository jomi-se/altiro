# 0001: Android workflow rejected before execution

Status: in-progress

## Reproduction and impact

The published Android workflow fails before any jobs start. Its build and device
jobs put `${{ runner.temp }}` in job-level `env`, where GitHub does not allow
the runner context. No Android build or emulator result can follow that rejection.
Secret scanning runs independently and its success does not prove Android checks.

The pinned actionlint 1.7.12 reproduces both invalid-context errors on the prior
workflow. [GitHub's context table](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#context-availability)
confirms that `runner` is available in steps, but not job-level `env`.

## Fix and acceptance

Each Android job now writes its output directory to `GITHUB_ENV` from the
runner's `RUNNER_TEMP` variable in its first step. All subsequent build and
artifact steps retain the same output directory. The stable `emulator` aggregate
still requires every device configuration to pass.

A checksum-pinned actionlint installer, explicit local check and independent
read-only workflow validate Actions syntax and expressions. Local validation
passes, including the validator's own workflow. The installer was executed
and its ARM release verified against the pinned archive checksum.

Close this issue after the operator pushes the fix and GitHub starts the
corrected Android build and all three device jobs. Passing those jobs is a
separate acceptance requirement; local workflow validation does not execute them.
