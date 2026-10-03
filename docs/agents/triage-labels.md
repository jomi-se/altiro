# Triage labels

Record these canonical roles in a ticket's `Status:` line.

| Status | Meaning |
| --- | --- |
| `needs-triage` | Maintainer evaluation is required |
| `needs-info` | Missing information must be obtained |
| `ready-for-agent` | Specified and suitable for autonomous source work |
| `ready-for-human` | Requires human implementation or intervention |
| `wontfix` | Will not be actioned |

Use `in-progress` while executing a claimed task and `done` when its acceptance
conditions are satisfied with evidence. A ticket may be ready-for-agent while
`Blocked by:` establishes its position in the sequence. Split hardware evidence
from source completion instead of claiming an unperformed experiment passed.
