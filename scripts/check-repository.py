#!/usr/bin/env python3
"""Validate the documentation foundation without an Android SDK or network."""

from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parent.parent
REQUIRED = (
    "AGENTS.md",
    "CLAUDE.md",
    "README.md",
    "CONTRIBUTING.md",
    "LICENSE",
    "NOTICE",
    "docs/product-north-star.md",
    "docs/architecture/implementation-defaults.md",
    "docs/plan/android-dictation/spec.md",
    "docs/plan/current-work.md",
    "docs/plan/vertical-slice-build-order.md",
    "docs/agents/issue-tracker.md",
    "docs/agents/triage-labels.md",
    "docs/agents/domain.md",
)


def main() -> int:
    errors = [f"Missing required file: {name}" for name in REQUIRED if not (ROOT / name).is_file()]
    claude = ROOT / "CLAUDE.md"
    if claude.is_file() and claude.read_text(encoding="utf-8").strip() != "@AGENTS.md":
        errors.append("CLAUDE.md must import the canonical AGENTS.md.")

    documents = sorted(ROOT.glob("*.md")) + sorted((ROOT / "docs").rglob("*.md"))
    for document in documents:
        content = document.read_text(encoding="utf-8")
        # Code examples may describe future file paths; only prose links are checked.
        content = re.sub(r"^```.*?^```[^\n]*$", "", content, flags=re.MULTILINE | re.DOTALL)
        for match in re.finditer(r"!?\[[^\]\n]*\]\(([^)\s]+)\)", content):
            target = urlsplit(match.group(1).strip("<>"))
            if target.scheme or target.netloc or not target.path:
                continue
            destination = (document.parent / unquote(target.path)).resolve()
            if not destination.is_relative_to(ROOT):
                errors.append(f"{document.relative_to(ROOT)}: link escapes repository: {target.path}")
            elif not destination.exists():
                errors.append(f"{document.relative_to(ROOT)}: missing link target: {target.path}")

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Repository checks passed ({len(documents)} Markdown files). Android build/device gates are separate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
