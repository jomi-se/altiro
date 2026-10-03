# Issue tracker: repository Markdown

Altiro tracks work in version-controlled Markdown. External issue-tracker
access is not required.

## Work categories

- Confirmed defects: `docs/issues/`, indexed by `docs/issues/README.md`.
- Unaccepted proposals: `docs/ideas/`.
- Accepted specifications and tasks: `docs/plan/`.
- Architectural decisions: `docs/decisions/`.

Do not silently promote an idea into an accepted plan.

## Implementation tickets

For a multi-ticket effort use `docs/plan/<feature>/spec.md` and numbered
`docs/plan/<feature>/issues/<NN>-<slug>.md` tickets. Begin at `01`.

Each ticket includes `Status:`, `Blocked by:` when applicable, the deliverable,
acceptance evidence, and `## Comments` for later discussion. Use the
[triage statuses](triage-labels.md); finished work uses `done` with evidence.
Dependencies record sequencing, not a claim that the dependent work is broken.

When fetching or updating a ticket, read the file and preserve its acceptance
criteria. Update the index/current-work handoff when completion changes the
next task. Do not call device-gated work done merely because source compiles.

## Wayfinding

Decision mapping may use `docs/plan/<effort>/map.md` with numbered child tickets,
`Type: research | prototype | grilling | task`, and
`Status: claimed | resolved`. The frontier consists of open, unblocked,
unclaimed tickets in numeric order.

Markdown is canonical. An adjacent visualization may help review but does not
replace recorded status, decisions, and acceptance evidence.
