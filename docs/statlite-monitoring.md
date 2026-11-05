# StatLite monitoring (work in progress)

CoilBase provides opt-in, lightweight application and JVM metrics for
[StatLite](https://github.com/PVRLabs/statlite). It supports basic counters,
request duration, heap usage and uptime without a metrics server or background
sampler in the application. The integration is experimental and its API may
change.

For Jex applications, call `enableMonitoring()` before `start()` to expose
`/statlite/metrics`. Other Java applications can use the Core helper with their
own HTTP wiring. See the [metrics guide](metrics.md) for setup, configuration
and current limitations.

CoilBase focuses this support on a small set of application metrics. It is not
intended to provide the full capabilities of Spring Boot Actuator.
