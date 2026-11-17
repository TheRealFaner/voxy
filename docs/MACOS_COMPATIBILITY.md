# macOS compatibility (GL41Metal)

GL41Metal is the macOS render backend of this Voxy fork: Metal performs
traversal, LOD selection, and culling, and OpenGL 4.1 rasterizes distant
terrain. It is experimental. This document defines the supported macOS matrix,
the compatibility floor, the evidence behind it, and the procedure for changing
the floor.

## Support matrix

| Hardware | Requirement | Status | Evidence |
| --- | --- | --- | --- |
| Apple Silicon (M1, M2, M3, M4) | macOS at or above the compatibility floor; Metal device | Supported | macOS CI build, artifact verification, and Metal load probe on Apple Silicon runners; manual in-game validation on M1 hardware is part of release acceptance |
| Intel Mac (x86_64) | Metal-capable GPU + OpenGL 4.1; macOS at or above the floor | Experimental | Universal (x86_64 + arm64) dylib; build and CI load checks only; no in-game validation |
| Any Mac without a Metal device | - | Unsupported | The runtime probe reports a Metal device failure and GL41Metal is disabled |
| Intel Mac without OpenGL 4.1 | - | Unsupported | Backend selection rejects GL41Metal before the native probe |

The floor is a deployment target (and an MSL version), not an installation
requirement. Newer Apple Silicon models ship with newer macOS versions, so on
Apple Silicon the floor is only reachable on M1 hardware; Intel Macs that can
run macOS 11 still need a Metal-capable GPU and OpenGL 4.1.

## Compatibility floor (PROVISIONAL)

Current values in `gradle.properties`:

| Property | Value | Meaning |
| --- | --- | --- |
| `gl41metal_msl_std` | `metal2.1` | MSL standard for the shipped compatibility metallib |
| `gl41metal_macos_min` | `11.0` | Deployment target for the dylib and the metallib |
| `gl41metal_ship_fallback` | `false` | Whether a second, current-toolchain metallib is packaged |
| `gl41metal_fallback_msl_std` | `metal3.2` | MSL standard for the fallback metallib (only if shipped) |
| `gl41metal_fallback_macos_min` | `15.0` | Deployment target for the fallback metallib (only if shipped) |

**PROVISIONAL.** These values follow the MSL-to-macOS mapping below and have not
yet been confirmed by a `macos-metal-probe` run. The probe measures the actual
AIR target; do not assume it. A human commits the final values after reviewing
`report.md` and `recommendation.env`. Builds validate the property format only
and never read probe output.

Probe evidence recorded for the floor:

| Item | Value |
| --- | --- |
| Probe date / workflow run | not recorded yet |
| Confirmed `gl41metal_msl_std` | not recorded yet (provisional `metal2.1`) |
| Confirmed `gl41metal_macos_min` | not recorded yet (provisional `11.0`) |
| Measured AIR target | not measured yet |

### MSL to macOS reference

| MSL version | Minimum macOS |
| --- | --- |
| 2.0 | 10.15 |
| 2.1 | 11.0 |
| 2.2 | 11.3 |
| 2.3 | 12.0 |
| 2.4 | 13.0 |
| 3.0 | 13.0 |
| 3.1 | 14.0 |
| 3.2 | 15.0 |

This is Apple's `MTLLanguageVersion` availability mapping. Use it to pick probe
candidates, then verify the produced AIR target with
`metal-objdump --air-version` and `strings` (the
`air64-...-apple-macosx...` triple). Do not infer the AIR target from the flag
alone.

## One metallib vs. dual or source fallback

The preferred configuration is a single compatibility metallib: one code path,
one library to verify, and no runtime candidate ambiguity. Use the lowest MSL
standard that compiles, links, and passes the load probe on the CI runners.

Plan 2 (dual metallib) is used only if the probe proves a gap: the
compatibility metallib loads on older systems but a newer macOS or toolchain
rejects it, or fails to build the `traverse` compute pipeline. Ship the
compatibility library (`voxy_gl41metal.metallib`) plus a current-toolchain
fallback (`voxy_gl41metal-current.metallib`, by default `metal3.2` / `15.0`) by
setting `gl41metal_ship_fallback=true` and the fallback properties. The runtime
tries the compatibility candidate first, then the fallback.

Plan 3 (compile MSL source at runtime) is not implemented and is considered
only if both file-based candidates cannot cover a measured gap. Do not add it
without a probe-proven failure.

## Build pipeline

macOS-only tasks in the root `build.gradle`:

- `compileGl41MetalNative` builds a universal dylib with `clang++` using
  `-std=c++17`, `-ObjC++`, `-fobjc-arc`, `-O3`, `-arch x86_64`,
  `-arch arm64`, and `-mmacosx-version-min=<gl41metal_macos_min>`, linked
  against Foundation and Metal. Output:
  `build/gl41metal-native/libvoxy_gl41metal.dylib`.
- `compileGl41MetalShaders` runs `xcrun metal` with
  `-std=<gl41metal_msl_std>`, `-mmacosx-version-min=<gl41metal_macos_min>`,
  and `-O` over `src/main/native/gl41metal/shaders/*.metal` and writes one
  metallib. `MACOSX_DEPLOYMENT_TARGET` is set to the same minimum.
- `compileGl41MetalFallbackShaders` exists only when
  `gl41metal_ship_fallback=true` and writes
  `voxy_gl41metal-current.metallib` with the fallback std and minimum.
- `generateGl41MetalBuildInfo` writes
  `build/gl41metal-native/build-info.properties` with `xcode_version`,
  `metal_version`, `msl_std`, `macos_min`, `commit`, and `build_time`.

`neoforge:processResources` packages the dylib, the metallib(s), and
`build-info.properties` under `natives/macos/` only when the build host is
macOS. On any other host it logs a warning and produces a jar that cannot use
GL41Metal on a Mac.

`./gradlew :neoforge:verifyMacNatives` checks the built jar for non-empty
`natives/macos` entries. It is part of `check` on macOS and becomes a hard
failure on any host with `-PrequireMacNatives=true`.

## Runtime selection and diagnostics

GL41Metal is selected when forced with `-Dvoxy.renderBackend=gl41metal`, or by
`auto` after GL46 is unavailable while OpenGL 4.1 is present. Shader library
candidates are resolved in this order:

1. `natives/macos/gl41metal/voxy_gl41metal.metallib` (bundled), otherwise
   `build/gl41metal-shaders/voxy_gl41metal.metallib` in a dev run.
2. `natives/macos/gl41metal/voxy_gl41metal-current.metallib` (bundled),
   otherwise the dev path, when the fallback was shipped.

`createContext` tries the candidates in order. A missing candidate is skipped;
a native failure or a zero handle is logged and the next candidate is tried.
When all candidates fail, an aggregated `IllegalStateException` is raised. The
pre-selection probe turns unusable natives into a `DISABLED` backend with a
precise reason; if backend creation still throws later, `VoxyRenderSystem`
logs the failure and rethrows it. `MixinLevelRenderer` may then disable Iris
shaders, otherwise the exception propagates.

`-Dvoxy.gl41metal.shaderCandidate=<value>` controls candidates:

- a non-empty value is a case-insensitive substring match on candidate names,
  for example `current` selects only `voxy_gl41metal-current.metallib`;
- `missing` or `none` disables candidates to exercise the graceful-failure
  path;
- a value that matches nothing fails resolution with the available names and
  expected paths.

Diagnostic log lines to expect in `latest.log`:

- `GL41Metal shader library candidates: ...` at startup;
- `Voxy GL41Metal backend selected [experimental]: macOS <version>, arch=<arch>`;
- `Voxy macOS runtime: os=..., native=..., shaders=..., build-info=..., device=<Metal device>`
  from the one-time probe;
- `GL41Metal shader library candidate '<name>' loaded`;
- `Created Voxy GL41Metal drawlist backend: ...`;
- failure paths: `Failed to load Voxy GL41Metal native library: ...`,
  `Metal context initialization failed on ...`, and
  `Failed to create Voxy render backend 'GL41METAL': ...`.

## Delivery rules

Only jars built on macOS contain `natives/macos/`. The canonical macOS build is
produced by `.github/workflows/build-macos.yml` and published to the
`neoforge-latest` release as the canonical jar, a commit-stamped copy, and
`SHA256SUMS`.

Jars built on Windows or Linux (including `manual-artifact.yml` and
`manual-latest`) contain no `natives/macos` entries. On a Mac they report
`native library unavailable: missing /natives/macos/libvoxy_gl41metal.dylib`,
and GL41Metal cannot be selected. Do not ship those jars to macOS users.

`verifyMacNatives` only warns about missing natives on non-macOS hosts. Use
`-PrequireMacNatives=true` to turn that into a hard failure.

## Evidence levels

| Level | What it proves | Where |
| --- | --- | --- |
| Artifact verification | Dylib is universal with the expected `minos`, only system dependencies, and JNI exports for both slices; metallib is non-empty with an AIR target; `build-info.properties` matches `gradle.properties` | `scripts/macos/verify-macos-artifact.sh` |
| CI Metal load | The Metal driver creates a device, loads the metallib, finds `traverse`, and builds its compute pipeline | `macos-metal-probe` workflow on `macos-15` and `macos-26`; optional `macos-15-intel` job |
| In-game | Rendering correctness and stability on real hardware | Manual Apple Silicon test: high-altitude flight, render distance 32+, opaque/translucent/water/fog/SSAO, one Iris shader pack. Not performed on Intel |

Big Sur (macOS 11) cannot be runtime-tested in CI: GitHub does not provide
macOS 11 runners, and Apple Silicon VMs below macOS 12 are unavailable. The
macOS 11.0 floor is therefore evidenced by the MSL 2.1 / AIR deployment target
and the dylib `minos` value, not by an in-game run on 11.0. If a probe report
shows a higher AIR target, the documented floor and `gl41metal_macos_min` must
be raised.

A headless runner without a Metal device reports `NO_METAL_DEVICE` (load-probe
exit 3). That is a skip, not a product failure, and such a run cannot confirm a
floor.

## Running the probe and updating the floor

CI: run Actions -> `macos-metal-probe` -> Run workflow. Defaults probe
`metal2.0` through `metal3.0` against deployment targets `11.0`, `11.3`,
`12.0`, and `13.0` on `macos-15` and `macos-26`; enable `intel_probe` to add
`macos-15-intel`. The workflow never publishes. A monthly schedule re-runs it.
Read `report.md` in the job summary and the uploaded artifact;
`recommendation.env` contains the selection.

Local run on a Mac:

```shell
mkdir -p build/macos-metal-probe
xcrun --sdk macosx clang++ -std=c++17 -fobjc-arc -O2 -Wall \
  scripts/macos/load-metal-probe.mm \
  -framework Foundation -framework Metal \
  -o build/macos-metal-probe/load-metal-probe
sh scripts/macos/probe-metal-compat.sh \
  --load-probe build/macos-metal-probe/load-metal-probe \
  --out build/gl41metal-probe
```

Exit code 1 means no tuple compiled; individual compile, link, and load
failures are recorded as data, and usage or IO errors exit 2. Each tuple tries
the `metalX.Y`, `macos-metalX.Y`, and `osx-metalX.Y` flag spellings.

Selection rule: among tuples that compile, link, and pass the load probe, choose
the lowest deployment target in the order given by `--min-os` (default `11.0` ->
`11.3` -> `12.0` -> `13.0`), then any other measured target, then the lowest MSL
standard. A floor of 11.3 is acceptable if documented. A
floor of 12.0 or higher requires Plan 2 if Big Sur support is required; 13.0+
requires Plan 2, with Plan 3 considered only if macOS 11 support is mandatory.
`unverified-no-metal-device` and `unverified-no-load-probe` results are not
shippable floors.

To apply a confirmed result:

1. Update `gl41metal_msl_std` and `gl41metal_macos_min` in `gradle.properties`
   manually (add the fallback properties and
   `gl41metal_ship_fallback=true` only for Plan 2).
2. Update this document with the probe date, run link, confirmed values, and
   measured AIR target.
3. Rebuild on macOS and verify:

```shell
./gradlew :neoforge:build :neoforge:verifyMacNatives -PrequireMacNatives=true
bash scripts/macos/verify-macos-artifact.sh <jar> \
  --expected-msl-std <value> --expected-min-os <value> --run-load-probe
```

Do not change the floor without a probe run: the build validates the property
format only.
