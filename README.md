# Voxy 1.21.1 Multi-loader Fork

![voxy-on-m4-max](assets/voxy-on-m4-max.png)

[中文](README.zh-CN.md)

An experimental Minecraft 1.21.1 fork of
[MCRcortex/Voxy](https://github.com/MCRcortex/voxy), focused on native Fabric and
NeoForge builds and a macOS-compatible GL 4.1 + Metal rendering path.

> [!WARNING]
> This is an unofficial development fork. Please report issues with builds from
> this repository here rather than to upstream support channels.

## Branch targets

| Component | Target |
| --- | --- |
| Minecraft | 1.21.1 |
| Java | 21 |
| Loaders | Fabric and NeoForge |
| Sodium | 0.8.12 |
| Iris | 1.8.x; shader-pack compatibility varies |
| Render backends | GL46 and experimental GL41Metal |

This branch has diverged from the current upstream development branches and
does not automatically include their latest fixes.

## Rendering pipeline

### GL46

The GL46 backend follows Voxy's conventional GPU-driven renderer: OpenGL
compute shaders perform hierarchical traversal, LOD selection, and culling,
then the selected terrain is submitted through the GL46 indirect draw path.

### GL41Metal

The experimental macOS backend splits the work between Metal and OpenGL 4.1:

```text
Voxy world updates
  -> mirrored terrain and model buffers
  -> Metal traversal, LOD selection, and culling
  -> compact per-frame worklists in unified memory
  -> OpenGL 4.1 distant-terrain rasterization
  -> Iris/Sodium depth and translucent composition
```

Metal owns distant-terrain selection; OpenGL and Iris remain responsible for
rasterization, the near scene, shader-pack passes, and final presentation. The
backend supports opaque and translucent terrain, distant water, fog, SSAO, and
near-scene depth occlusion.

Backend selection is automatic: capable systems use GL46, while supported
macOS systems fall back to GL41Metal when GL46 is unavailable.

#### macOS support

- Apple Silicon (M1-M4) is supported. The current compatibility floor is macOS
  11.0 with MSL `macos-metal2.0` (`gl41metal_msl_std` / `gl41metal_macos_min` in
  `gradle.properties`), confirmed by the `macos-metal-probe` workflow on
  2026-11-18. The arm64 CI runner was unavailable at that time; the metallib is
  architecture-independent and the universal dylib is verified on both slices.
- Intel Macs are experimental: the dylib is universal (x86_64 + arm64), but
  Intel is covered by build and CI load checks only, with no in-game
  validation.
- A Metal-capable GPU and OpenGL 4.1 are required. Macs without a Metal device
  are unsupported.
- See [docs/MACOS_COMPATIBILITY.md](docs/MACOS_COMPATIBILITY.md) for the full
  matrix, evidence levels, and the probe procedure.

## Shader-pack support

Iris shader packs are supported through a bridge that exposes Voxy depth,
samplers, matrices, and compatible pack uniforms to the distant-terrain pass.
Opaque terrain is integrated before the pack's hand-depth copy, while distant
translucent terrain is composed before Sodium draws the near translucent scene.

Support is limited by Apple's OpenGL 4.1 implementation. Apple Silicon reports
16 fragment texture units, but using all 16 can crash the driver while linking
a program. GL41Metal therefore uses a safe budget of 15 texture units: one is
reserved for Voxy's block atlas, leaving at most 14 shader-pack samplers. Packs
that require more samplers cannot use the direct bridge path. This is a sampler
limit, not a limit of 15 ordinary GLSL uniforms.

## Other changes

- Shared renderer core with native Fabric and NeoForge subprojects.
- Explicit backend lifecycle through the `VoxyRenderSystem` facade.
- Iris and Sodium integration adapted for both rendering backends.
- Sable/Aero contraption compatibility ported and adapted from M4G4MED's
  `mc_1211-aero` branch.
- Formatting and source layout standardized for ongoing maintenance.

## Diagnostics and A/B switches

All switches keep the production default when unset; none changes rendering
unless explicitly enabled.

- `/voxy dump <x> <y> <z> [radius] [level]` — read-only (no world writes) LOD
  cell dump for evidence gathering. `radius` is 0..2 cells and `level` is 0..4.
  Every line is prefixed `[VoxyDump]` and written to `latest.log`; chat shows at
  most 200 lines. Sections come from `WorldEngine.acquireIfExists`: cached
  sections are reused, but a section stored on disk but not cached may be loaded
  synchronously, so radius >1 far from the player can hitch; prefer radius 0..1
  near the player. With `-Dvoxy.dtRemeshOnMaterialize=true` (default off),
  materializing a stored section may additionally enqueue neighbour remeshes.
  The dump reports the section/cell key, Mapper block id and state, Dynamic
  Trees classification, base model metadata and per-face face data, the
  raw/canonical variant key slots and variant registry state, the section mask
  bits and `maskPatched` status, the branch cap-filter/drop mask/depth mode, and
  a kept-vs-dropped re-run of the quad pipeline for the cell.
- `-Dvoxy.vanillaTerrain=true` — near-terrain A/B switch (default `false`). When
  on, Voxy does not cancel Sodium's chunk pass and skips its own near-chunk LOD0
  sync stages, so Sodium/vanilla renders the near scene normally; Voxy still
  renders LOD beyond the vanilla render distance. Independent of
  `VoxyClient.disableSodiumChunkRender()`, which is the occlusion-debug path
  where Voxy replaces Sodium's near chunks and stays `false` by default. The
  runtime toggle is `/voxy vanillaTerrain on|off`; `/voxy vanillaTerrain` prints
  the state.
- `-Dvoxy.dtVariants=false` — forces every Dynamic Trees branch/surface-root
  variant request to `UNAVAILABLE` without inserting a key or touching the cap,
  so LOD0 keeps the base models. Default `true`.
- `-Dvoxy.dtBranchCapFilter=all|per-side|none` — existing branch boundary-cap
  filter mode. Default `all`.
- `-Dvoxy.dtBranchDepthMode=max|avg|min` — existing branch variant face depth
  mode. Default `max`.
- `-Dvoxy.gl41metal.shaderCandidate=<value>` — GL41Metal shader library
  candidate control. A non-empty value is a case-insensitive substring match
  (`current` selects `voxy_gl41metal-current.metallib`); `missing` or `none`
  disables candidates to exercise the graceful-failure path. By default the
  bundled `voxy_gl41metal.metallib` is tried first, then
  `voxy_gl41metal-current.metallib` when the fallback was shipped.

GL41Metal also logs `Voxy macOS runtime: ...` (OS, version, arch, native
source, shader candidates, build info, Metal device) once per launch, plus
`GL41Metal shader library candidate '<name>' loaded` and
`Created Voxy GL41Metal drawlist backend: ...`. The pre-selection probe turns
unusable natives into a `DISABLED` backend with a precise reason; if backend
creation still throws later, `VoxyRenderSystem` logs and rethrows it
(`MixinLevelRenderer` may disable Iris shaders, otherwise the exception
propagates).

## Known limitations

- GL41Metal is experimental and requires macOS, a Metal-capable GPU, OpenGL
  4.1, and the bundled native and Metal shader assets. Apple Silicon is
  supported at the documented macOS floor; Intel is experimental, with
  build/CI load checks only and no in-game validation.
- The `natives/macos` assets are packaged only by builds that run on macOS.
  Jars built on Windows or Linux cannot use GL41Metal on a Mac; use the
  macOS-built jar from the `neoforge-latest` release.
- macOS 11.0 (Big Sur) is not runtime-tested in CI: Apple Silicon CI runners
  cannot run macOS 11. The floor is evidenced by the MSL/AIR deployment target
  and the dylib `minos`, not by an in-game run.
- Iris support does not guarantee compatibility with every shader pack.
- Shader packs exceeding the safe GL41Metal sampler budget cannot use its
  direct shader bridge.
- GL46 and GL41Metal are not yet visually or performance-identical.
- GL41Metal performance is partly limited by Apple's legacy OpenGL 4.1 driver,
  especially shader compilation, draw submission, synchronization, and
  fragment-stage resource limits. Metal accelerates traversal and culling but
  does not replace the OpenGL rasterization path.
- Fabric and NeoForge both build from the shared core, but renderer behavior
  still requires in-game validation on each loader.
- This is a development fork; no compatibility or support guarantee is made for
  official Voxy releases or other forks.

## Building

Java 21 is required.

```shell
./gradlew spotlessCheck
./gradlew release
```

`release` explicitly builds the distributable Fabric and NeoForge artifacts.
At present, `./gradlew build` also selects both loader `build` tasks and produces
the same jars; `release` is the clearer repository-level command and may gain
additional release-only steps later.

Loader-specific builds remain available:

```shell
./gradlew :fabric:build
./gradlew :neoforge:build
```

GL41Metal native code and Metal shaders are built and packaged on macOS.
macOS-only verification:

```shell
./gradlew :neoforge:verifyMacNatives -PrequireMacNatives=true
bash scripts/macos/verify-macos-artifact.sh \
  neoforge/build/libs/voxy-<version>+1.21.1-neoforge.jar \
  --expected-msl-std <value> --expected-min-os <value> --run-load-probe
```

The compatibility floor is measured with the `macos-metal-probe` workflow
(Actions -> macos-metal-probe, also scheduled monthly). See
[docs/MACOS_COMPATIBILITY.md](docs/MACOS_COMPATIBILITY.md) before changing
`gl41metal_msl_std` or `gl41metal_macos_min`. On non-macOS hosts the jar is
built without macOS natives, and `verifyMacNatives` only warns unless
`-PrequireMacNatives=true` is set.

## Upstream and credits

Voxy was created by [MCRcortex](https://github.com/MCRcortex/voxy). This branch
also incorporates work from
[m3t4f1v3/voxy](https://github.com/m3t4f1v3/voxy) and
[M4G4MED/voxy](https://github.com/M4G4MED/voxy), including Minecraft 1.21.1,
multi-loader, compatibility, and Aero/Sable work. See the Git history for
individual attribution.

## License

See [LICENSE.md](LICENSE.md).
