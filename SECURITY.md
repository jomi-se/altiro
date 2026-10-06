# Security policy

## Supported version

Security fixes apply to the current `main` branch. Altiro is an Android preview;
compilation and reported phone smoke tests do not establish all compatibility
or editor-safety gates.

## Reporting a vulnerability

Use GitHub's private **Report a vulnerability** flow when enabled. Include the
affected version or commit, reproduction steps, and observed impact. Do not
include dictated content, recordings, credentials, signing keys or device
identifiers. Avoid a public issue containing an unpatched exploit.

Useful reports include wrong-field or duplicate insertion, microphone/native
resource leaks, unexpected network access, unsafe model installation, exported
component abuse, and private content in diagnostics or backups.

## Security boundaries

Local recognition needs no account or hosted service. Audio is ephemeral;
transcript history is absent by default. Model acquisition is an explicit
user action and must verify the catalog's exact size and SHA-256 before use.
Accessibility access is limited to the editor authority required for safe
insertion; Altiro does not send messages or replace the default keyboard.

Keep secrets, signing material, private models/audio and machine configuration
out of Git. CI uses pinned actions and minimum permissions. Run
`./scripts/install-gitleaks.sh`, then `./scripts/scan-secrets.sh staged` before
committing and `./scripts/scan-secrets.sh history` before publication. A clean
secret scan does not replace a review of personal data or commit metadata.
