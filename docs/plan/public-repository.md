# Public repository preparation

Status: local preparation and GitHub settings verified; first push pending.

The public destination is [jomi-se/altiro](https://github.com/jomi-se/altiro).
The owner requested public setup and confirmed that existing author identities
should remain. Do not rewrite their public email or commit history.

## Review completed before publication

- Gitleaks 8.30.1 scanned all existing reachable history without findings.
- A separate review scanned 353 unique historical tracked blobs for personal
  email addresses, absolute home paths, private machine/operator identifiers,
  tailnet IP addresses and operator setup commands; none matched.
- Reviewed historical filenames and commit metadata. No model binaries,
  recordings, signing keys, generated APKs or local design-tool payloads are
  tracked. The supplied example utterances are specification examples.
- Local Impeccable installations across harness directories are ignored.

These checks reduce publication risk; they do not prove that every future
change is free of private data. Review explicit staged paths and rerun scans
before the first push.

## GitHub settings

The repository is public. Verified settings enable secret scanning, secret
push protection, Dependabot alerts/security updates, private vulnerability
reporting, and read-only default Actions permissions without permission to
approve pull requests. Local configuration adds GitHub Actions and Gradle
Dependabot updates and a full-history Gitleaks workflow.

Main-branch rules prevent deletion and force pushes and require linear history.
A separate pull-request/check rule requires `build`, `emulator` and `gitleaks`;
administrators can bypass that rule, matching the existing public-project
workflow. Check requirements exempt initial branch creation so the first push
can trigger CI. History protections have no bypass actors. Rebase is the
enabled merge method.

The repository was created empty. Its security settings are verified, but
its workflows have not run and its local source has not been pushed. The
operator owns push actions under repository guidance. An APK release, model
asset publication and store submission are separate actions.
