# Optional speech server

This component is an independently runnable, optional self-hosted recognition
endpoint. It does not change Android's local-only behavior or make the server
a prerequisite for building/running the Android app. Follow root guidance and
decision 0014. Android remote-adapter implementation follows decision 0025 in the app/network
modules; server operation remains independent.

Keep portable source and generic setup scripts here. Keep actual hostnames,
tailnets, IPs, home paths, credentials, models, recordings, virtual disks,
Python environments, logs and measurement output out of Git. Examples use
reserved domains. The default service uses a dedicated restricted WSL2 worker;
native Windows scripts are explicit diagnostic alternatives, not sign-in defaults.

Run Python API tests in a compatible runtime, documentation checks, syntax
validation for changed launchers, secret scans, and real GPU/API/isolation checks
when changing the deployed worker or service policy. A passing dependency scan
is not an absence-of-vulnerabilities guarantee. Do not claim GPU/containment
support for another OS or card based only on this profile's evidence.

Respect the existing Android state machine and one-insertion-attempt policy.
Do not implement automatic provider retries/failover as part of this component.
No request-body/access logging, transcript history, LLM cleanup, or public signup.
