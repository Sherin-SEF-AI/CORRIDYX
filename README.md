# CORRIDYX

### Turn a fleet of dash phones into a live, city scale map of where self driving actually works.

> Not "can the car drive here." A sharper question: **can a camera even SEE here, right now, in this weather, at this hour?**
> CORRIDYX answers it for every 150 meter stretch of road your fleet touches, 100% on the phone, with zero video ever leaving the device.

CORRIDYX turns each dash mounted fleet phone into a **corridor ODD certification sensor** (ODD = Operational Design Domain, the conditions an autonomous system is actually rated to operate in). While the vehicle drives its normal Bangalore routes, the app continuously scores the road corridor on the dimensions that decide whether vision based ADAS and autonomy can be trusted there: lane marking visibility, GNSS quality, illumination, scene and agent complexity, road roughness, and the one everybody ignores until it ruins their demo, **perception weather**.

Scores are computed per road segment, accumulated on device, rendered live, and exported as tiny numeric **SegmentScorePackets**. Aggregate them across 40 vehicles and you get something no HD map gives you: a quantified, time aware ODD map of an entire city. Which corridors are AV viable, **when** (day vs night vs monsoon), and **which exact dimension fails where.**

---

## The idea in one breath

Everyone is mapping roads. Almost nobody is mapping **whether the road is legible to a camera at 7pm in the rain under a flyover.** That second map is the one that decides if your robotaxi disengages. CORRIDYX builds it cheaply, continuously, and privately, using phones you already have on the dashboard.

---

## GLARYX: the subsystem that knows when your eyes are lying

Most perception stacks score the world. CORRIDYX also scores **its own ability to see the world.** That subsystem is **GLARYX**, four heads running every frame:

| Head | What it catches | Why it matters |
|------|------------------|----------------|
| `lens` | a smudge, raindrop, or dust patch stuck on the lens across many frames | the single highest value live intervention: it fires a **"CLEAN LENS"** driver alert |
| `glare` | sun blowing out the sensor, plus geometric low sun straight into the camera before it even saturates | **"CAMERA BLINDED"** moments your detector silently fails through |
| `atmo` | rain streaks, wiper cadence, fog and haze via a dark channel statistic | the monsoon line on your ODD map |
| `nightq` | high ISO grain, motion blur at speed, headlight only illumination | how good "night" actually is, not just that it is dark |

GLARYX exposes a live **PerceptionConditions** state over a bound local service. Any sibling `ai.deepmost.*` app on the phone (think FERYX, LANYX) can bind to it and ask one question: **"is it safe to trust my detections right now?"** One float, `overallTrust` from 0 to 1, and four severities. That is a perception watchdog the whole device can share.

---

## The rule that makes the data honest

Here is the trap most "road quality from phones" projects fall into: a dirty lens makes lane markings look invisible, so they mark the **road** as having bad lane markings. Now your map is lying.

CORRIDYX refuses to do that. When GLARYX detects that the camera's own seeing is compromised, the affected corridor samples are **invalidated and counted as gated, never scored.** A sensor fault is never allowed to masquerade as a property of the road.

```
lane_vis sample DROPPED when  lens dirt > 0.35  OR  glare severity > 0.60
rough    sample DROPPED when  IMU mount vibration contamination > 0.70
```

Every packet records exactly which samples were gated and why. Your ODD map distinguishes "this corridor genuinely has no markings" from "our lens was filthy here," which is the whole ballgame.

---

## How a drive becomes a map

```
camera frame ──► downscale to luma + RGB ──► fan out to 10 scoring heads (off main thread)
     │                                              │
   IMU 100Hz, GNSS, light sensor ──────────────────►│
                                                     ▼
                            per frame metric samples (0..1 + raw values + engine tag)
                                                     ▼
                 single writer accumulator actor  ──►  per segment Welford stats (mean/var/min/max/n)
                       (geohash7 + heading bucket)      time bucketed, gates applied, gaps recorded
                                                     ▼
                          segment exit (with hysteresis)  ──►  SegmentScorePacket
                                                     ▼
                   blurred evidence + manifest.json ──► Room store ──► WorkManager upload (wifi only)
```

A **segment** is a geohash-7 cell (about 150 m) plus one of 8 heading octants, so the two carriageways of a flyover score separately. The segmenter sits behind an interface, so swapping in OSM way-id map matching later is a drop in.

---

## What gets scored, and how (no black boxes)

Every dimension emits a 0..1 subscore where **1 is best for autonomy**, plus the raw numbers that produced it, so every score is explainable and reproducible. A quick taste of the real formulas (full set lives in the code and in `DIMENSIONS.md` style comments at each head):

- **Lane markings** `lane_vis`: white/yellow HSV gating with an adaptive brightness threshold, constrained Hough voting for near vertical marking structure, marking vs road contrast.
- **GNSS quality** `gnss_q`: satellites in fix, mean and top quartile C/N0, constellation diversity, reported accuracy, and a multipath proxy from C/N0 variance and raw pseudorange rate jitter. This is the urban canyon shadow map under Bangalore's flyovers and tech park glass.
- **Illumination** `illum`: luma histogram exposure, brightness adequacy scored relative to time of day (night measures street lighting quality), streetlight flicker via row banding, cross checked against the ambient light sensor.
- **Agent chaos** `chaos`: object detector (COCO mapped) for agent count, class entropy, vulnerable road user fraction, and frame to frame detection churn via greedy IoU matching. Falls back to motion region counting with the engine labelled honestly.
- **Road roughness** `rough`: band passed vertical acceleration RMS, speed normalized, pothole spike count, plus a mount resonance sanity check so a rattly phone clamp does not get blamed on the road.
- **Perception weather** `lens` `glare` `atmo` `nightq`: the GLARYX heads above.
- **Composite ODD** `odd`: a weighted, gate aware blend of the survivors into a single 0 to 100, with the full per dimension breakdown always preserved. No opaque mega score without its receipts.

Every score records **which engine produced it**: `classical` on day one, or `<modelId>@<version>` once you drop a fine tuned `.tflite` into the model registry. Models are an upgrade, never a prerequisite.

---

## Privacy is not a feature, it is the architecture

- **100% on device analysis.** Every dimension, every GLARYX head, runs locally with classical CV and optional LiteRT. There are **no cloud vision or LLM calls.** The only network use is uploading the tiny numeric packets, WorkManager, wifi only by default. The app scores a full drive in **airplane mode** and uploads later.
- **Continuous video is never stored or sent. Ever.** The only imagery that touches disk is a capped set of **evidence thumbnails** (default 2 per segment) that illustrate extreme scores, for example the one frame proving "lane markings invisible here."
- Every evidence thumbnail is **face blurred and number plate blurred before it is written**, using offline ML Kit face detection and a heuristic plate strip blur. Blur is always on for uploads and cannot be switched off.

---

## Built for a real 8 to 10 hour shift

- Foreground service, screen off safe, battery conscious (analysis FPS, not video recording).
- **Thermal aware duty cycling**: PowerManager thermal status plus an inference time watchdog quietly drop the analysis FPS under heat, and record the duty cycle state in every packet so fleet aggregation can weight degraded capture accordingly.
- Bounded everything: fixed size sensor rings, capped evidence, a global storage cap with oldest uploaded first eviction (manifests are never evicted).
- Single writer accumulator actor (no locks), per head try/catch (one broken head never kills the session), process death safe accumulator snapshots, Timber logging per module.

---

## The five screens

1. **DRIVE**: live camera with the dominant degradation drawn over it (lens dirt blocks, glare blobs), a big monospace ODD readout, per dimension mini bars, live GLARYX trust, and rate limited driver alerts with optional offline TTS.
2. **CORRIDOR MAP**: a custom Canvas map of this device's accumulated segments in local projected space, pan, zoom, follow. Tap a segment for a per dimension day vs night breakdown. Filter the whole map by one dimension to see, for example, only the GNSS shadow map or only the night lighting map.
3. **SESSIONS**: per shift summaries, km scored, segments visited, alerts raised, packet queue status, and one tap export of a session as a zip.
4. **FLEET NODE**: vehicle id, endpoint and token config, lifetime totals, and the model registry showing the live engine and accelerator (GPU, NNAPI, or CPU) per head.
5. **SETTINGS**: analysis FPS, per dimension enables and weights, gate thresholds, alert thresholds and TTS, evidence options, segment hysteresis, upload mode, storage cap, thermal aggressiveness, min speed gates.

Aesthetic: "operational materialism." Matte near black, every number in monospace, exactly one earned accent color reserved for live state, alerts, and the single worst dimension. Hairline dividers, dense data, instant transitions.

---

## Run it

```bash
git clone https://github.com/Sherin-SEF-AI/CORRIDYX.git
cd CORRIDYX
# Android Studio: open and Run, or:
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Kotlin, minSdk 26, target 35. Jetpack Compose, CameraX, LiteRT (GPU then NNAPI then CPU fallback), Room, WorkManager, DataStore, ML Kit face detection, pure Kotlin classical CV (no OpenCV).
- Day one runnable with **no custom models**. Every head produces a real score from classical methods. Fine tuned `.tflite` upgrades drop into `assets/model_registry.json` per head.
- Mount the phone dash forward, grant camera, fine location, and notifications, then tap **START SHIFT CAPTURE**.

---

## Fleet aggregation, the payoff

Packets combine across the fleet by **per segment, per time bucket weighted pooling**. Dimension means are pooled weighted by sample count, by GNSS capability (raw measurements beat status only), and by duty cycle state, so a thermally throttled phone contributes less. Roughness is re normalized across vehicle speeds. The composite ODD is recomputed from the pooled means with the same gates, so the fleet map agrees with what each phone saw. The gate records reveal where sensor faults dominated versus where the road itself is genuinely hostile to a camera.

The output: a living ODD map of a city. Green corridors where vision autonomy is viable, the same corridors going red at night or in the monsoon, and a per dimension answer to **why**.

---

## Honest limitations

- Geohash segments are not yet map matched. About 150 m cells plus heading octants approximate carriageways. OSM way-id matching is the intended upgrade and the interface is already in place.
- The classical heads are physically motivated proxies, labelled as such, designed to be superseded by registered `.tflite` models per head.
- Auto rickshaws, everywhere in Bangalore, are not a COCO class, so the bundled detector path maps them to a small vehicle proxy until a locally fine tuned detector is registered.

---

## Author

**Sherin Joseph Roy**
Email: sherin.joseph2217@gmail.com
GitHub: [Sherin-SEF-AI](https://github.com/Sherin-SEF-AI)

If this sparks an idea for your fleet, your research, or your city, star the repo and reach out.

## License

MIT. Build on it.
