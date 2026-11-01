# 0007 — OpenCL cleanup can unload a driver with work pending

Status: in-progress; source correction and fake-driver checks pass, phone cancellation remains pending.

## Reproduction and impact

Source review found that cancellation after a successful enqueue, an uncertain
queue-finish failure or a blocking-read failure could unwind into driver cleanup
without proving completion. The earlier capability probe and new ACL wrapper
released OpenCL objects and called `dlclose` in this state. Queue release is not
a synchronous completion guarantee, so unloading the driver can race outstanding
work. This is a confirmed lifecycle defect in the code; a physical crash has not
been observed or claimed.

## Expected behavior and correction

Track pending work immediately after a successful enqueue and across blocking
reads. Clear that state only after the corresponding completion/read succeeds,
before checking cancellation again. When completion remains uncertain, retain
the loader and dependent resources until the already-isolated worker exits.
Do not introduce an unbounded wait in destructors. Normal completed paths still
release their resources and loader.

## Acceptance evidence

The network-free capability ABI test checks retained objects/loader on uncertain
paths versus complete cleanup on successful paths. The durable ACL fake-driver
fixture exercises the actual wrapper with one- and three-event submissions,
cancellation after the first/second enqueue, enqueue/finish/read failures and
cancellation after confirmed completion. Pending paths release nothing; completed
paths release buffers, programs, kernels, events, queue, context and loader.

See the [host lifecycle procedure](../testing/opencl-int8.md#host-lifecycle-regression).
Phone cancellation and worker termination on the new OpenCL path remain open.

## Comments

The correction is part of the 0.6.9 experiment. It changes no production
recognition backend or automatic insertion behavior.
