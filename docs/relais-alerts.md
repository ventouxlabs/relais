# Relais — Prometheus alert hints

Suggested alerting rules for a Relais on-device LLM node scraped via its bearer-gated
`/metrics` endpoint. These are *starting points* — tune thresholds and `for:` windows to the
device, model, and expected load. All series below are emitted by `RelaisMetrics.renderProm`.

> Scrape note: counters reset on process restart (a new process). `relais_process_start_time_seconds`
> moves on restart and `relais_restarts_total` (prefs-persisted) increments, so use `rate()`/`increase()`
> rather than raw counter deltas, which Prometheus already handles across resets.

## Sustained shed rate

The node is shedding inference (503 + Retry-After) under thermal backpressure for a sustained
window — the device is running hot enough to refuse work.

```yaml
- alert: RelaisSustainedShedding
  expr: rate(relais_shed_total[5m]) > 0.1
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Relais {{ $labels.instance }} shedding inference (thermal backpressure)"
    description: "relais_shed_total rate has been > 0.1/s for 10m — the device is thermally throttling."
```

## Thermal SEVERE+ events

A transient SEVERE/CRITICAL/SHUTDOWN thermal transition occurred. `relais_thermal_events_total`
captures transitions the `relais_thermal_status` gauge can miss between scrapes.

```yaml
- alert: RelaisThermalSevere
  expr: increase(relais_thermal_events_total{level=~"severe|critical|emergency|shutdown"}[10m]) > 0
  for: 0m
  labels:
    severity: critical
  annotations:
    summary: "Relais {{ $labels.instance }} hit thermal {{ $labels.level }}"
    description: "A {{ $labels.level }} thermal event was recorded — the device crossed a high thermal threshold."
```

## Error rate (5xx)

Server-side failures. A small constant error rate may be benign (e.g. malformed client requests
mapped to 5xx); a rising rate is not.

```yaml
- alert: RelaisErrorRate
  expr: |
    rate(relais_errors_total[5m])
      / clamp_min(sum without (endpoint, status) (rate(relais_requests_total[5m])), 0.001)
      > 0.05
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Relais {{ $labels.instance }} 5xx error ratio > 5%"
    description: "More than 5% of requests have returned 5xx over the last 10m."
```

## Throughput-floor breach (decode collapse)

Decode throughput has collapsed below the floor the thermal governor watches — the GPU clock is
likely throttled even if the OS has not yet reported SEVERE. This is the "graceful latency growth,
not death" signal the node is built to catch. The floor is operator-configurable but clamped to a
strictly positive band, so it can never be disabled.

```yaml
- alert: RelaisThroughputFloor
  expr: relais_decode_tokens_per_second > 0 and relais_decode_tokens_per_second < 3
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Relais {{ $labels.instance }} decode throughput below floor"
    description: "relais_decode_tokens_per_second has been < 3 tok/s for 5m — likely thermal/clock throttling."
```

> Align the literal `3` here with the node's configured `decodeFloorTokS` (default 3.0,
> clamp band 0.5..50.0). If the operator raised the floor, raise this threshold to match.

## Queue saturation (429 rejects)

The admission queue is full and the node is rejecting requests (429) — sustained 429s mean the
device cannot keep up with offered load.

```yaml
- alert: RelaisQueueRejecting
  expr: rate(relais_queue_rejected_total[5m]) > 0.1
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Relais {{ $labels.instance }} rejecting requests (queue full)"
    description: "relais_queue_rejected_total rate > 0.1/s for 10m — offered load exceeds capacity."
```

## Per-endpoint latency regression

Per-endpoint p95 lets you alert on one route degrading without the other masking it.

```yaml
- alert: RelaisEndpointLatencyP95
  expr: |
    histogram_quantile(0.95,
      sum by (le, endpoint) (rate(relais_inference_duration_seconds_bucket{endpoint!=""}[5m]))
    ) > 60
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Relais {{ $labels.instance }} {{ $labels.endpoint }} p95 latency high"
    description: "p95 inference latency for {{ $labels.endpoint }} has been > 60s for 10m."
```

## Time to first token — deliberately NO alert yet

`relais_time_to_first_token_seconds` and `relais_decode_start_latency_seconds` ship with a dashboard
panel and no alert **on purpose**. Every rule in this file has a threshold grounded in a measured
number; TTFT has none yet — it has never been measured on hardware (the open TODO is
`docs/litertlm-native-api.md` §8.2). A "TTFT p95 > N seconds" rule written today would be picking N
out of the air, and an alert that pages on a fabricated number is worse than no alert: it trains
whoever carries the pager to ignore it.

**What would ground one:** run the §8.2 measurement on a real node, per SoC and per model, at a
realistic prompt size. TTFT scales with prompt length, so the threshold has to be stated against a
prompt size or it will page on every long-context request. Once there is a baseline, the natural
rule is a regression against it rather than an absolute:

```yaml
# DO NOT ENABLE until <BASELINE> is a measured number, not a guess.
# - alert: RelaisTtftRegression
#   expr: histogram_quantile(0.95, sum by (le) (rate(relais_time_to_first_token_seconds_bucket[10m]))) > <BASELINE * 2>
#   for: 15m
```

Note also that thermal throttling already surfaces via `RelaisThermalSevere` and
`RelaisThroughputFloor`, and queue wait via `RelaisEndpointLatencyP95` — the most likely causes of a
TTFT spike are therefore already covered by grounded rules. A TTFT alert would add resolution, not
coverage.

## Node down / restart loop

```yaml
- alert: RelaisDown
  expr: up{job="relais"} == 0
  for: 2m
  labels:
    severity: critical
  annotations:
    summary: "Relais {{ $labels.instance }} is not being scraped"

- alert: RelaisRestartLoop
  expr: increase(relais_restarts_total[15m]) > 3
  for: 0m
  labels:
    severity: warning
  annotations:
    summary: "Relais {{ $labels.instance }} restarting repeatedly"
    description: "More than 3 watchdog restarts in 15m — check logs for a crash loop."
```
