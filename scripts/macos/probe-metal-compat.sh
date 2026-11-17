#!/bin/sh
# probe-metal-compat.sh - probe Metal Shading Language std / macOS deployment-target
# compatibility for the GL41Metal traversal kernel: compile, link, measure the AIR
# target, and (optionally) ask the local Metal driver to load the metallib and
# build the traverse compute pipeline.
#
# Diagnostic only: builds never read this script's output. Copy the chosen values
# into gradle.properties manually after reviewing report.md.
#
# Usage:
#   sh scripts/macos/probe-metal-compat.sh [options]
#     --shader PATH      Metal source
#                        (default: src/main/native/gl41metal/shaders/traversal_worklist.metal)
#     --out DIR          Output directory (default: build/gl41metal-probe)
#     --stds LIST        Comma-separated MSL stds
#                        (default: metal2.0,metal2.1,metal2.2,metal2.3,metal2.4,metal3.0)
#     --min-os LIST      Comma-separated deployment targets (default: 11.0,11.3,12.0,13.0)
#     --load-probe PATH  load-metal-probe binary (optional; load checks skipped if absent)
#     --dylib PATH       dylib for the JNI-symbol check (optional, passed to load-metal-probe)
#
# Outputs (inside --out): report.tsv, report.md, env.txt, recommendation.env,
#                         logs/<std>-macos<min>.log, chosen/*.metallib
#
# Exit codes: 0 = at least one tuple compiled; 1 = zero tuples compiled;
#             2 = usage/IO error. No `set -e`: compile/link/load failures are data.

SHADER="src/main/native/gl41metal/shaders/traversal_worklist.metal"
OUT="build/gl41metal-probe"
STDS="metal2.0,metal2.1,metal2.2,metal2.3,metal2.4,metal3.0"
MIN_OS_LIST="11.0,11.3,12.0,13.0"
LOAD_PROBE=""
DYLIB=""
TAB=$(printf '\t')

usage() {
  cat <<'EOF'
Usage: sh scripts/macos/probe-metal-compat.sh [options]
  --shader PATH      Metal source (default: src/main/native/gl41metal/shaders/traversal_worklist.metal)
  --out DIR          Output directory (default: build/gl41metal-probe)
  --stds LIST        Comma-separated MSL stds (default: metal2.0,metal2.1,metal2.2,metal2.3,metal2.4,metal3.0)
  --min-os LIST      Comma-separated deployment targets (default: 11.0,11.3,12.0,13.0)
  --load-probe PATH  load-metal-probe binary (optional)
  --dylib PATH       dylib for the JNI-symbol check (optional)
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --shader)
      [ "$#" -ge 2 ] || { usage >&2; exit 2; }
      SHADER="$2"; shift 2 ;;
    --out)
      [ "$#" -ge 2 ] || { usage >&2; exit 2; }
      OUT="$2"; shift 2 ;;
    --stds)
      [ "$#" -ge 2 ] || { usage >&2; exit 2; }
      STDS="$2"; shift 2 ;;
    --min-os)
      [ "$#" -ge 2 ] || { usage >&2; exit 2; }
      MIN_OS_LIST="$2"; shift 2 ;;
    --load-probe)
      [ "$#" -ge 2 ] || { usage >&2; exit 2; }
      LOAD_PROBE="$2"; shift 2 ;;
    --dylib)
      [ "$#" -ge 2 ] || { usage >&2; exit 2; }
      DYLIB="$2"; shift 2 ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "error: unknown argument: $1" >&2
      usage >&2
      exit 2 ;;
  esac
done

if [ ! -f "$SHADER" ]; then
  echo "error: shader not found: $SHADER" >&2
  exit 2
fi
if ! command -v xcrun >/dev/null 2>&1; then
  echo "error: xcrun not found; macOS with Xcode/Command Line Tools is required" >&2
  exit 2
fi

TMP=$(mktemp -d "${TMPDIR:-/tmp}/gl41metal-probe.XXXXXX") || exit 2
cleanup() { rm -rf "$TMP"; }
on_signal() { cleanup; exit 130; }
trap cleanup EXIT
trap on_signal HUP INT TERM

mkdir -p "$OUT/logs" "$OUT/chosen" || exit 2
REPORT_TSV="$OUT/report.tsv"
REPORT_MD="$OUT/report.md"
ENV_TXT="$OUT/env.txt"
RECOMMENDATION="$OUT/recommendation.env"
ROWS="$TMP/rows.tsv"

METAL_VERSION=$(xcrun metal --version 2>&1)
METALLIB_VERSION=$(xcrun -sdk macosx metallib --version 2>&1)
XCODE_VERSION=$(xcodebuild -version 2>&1)
SW_VERS_OUT=$(sw_vers 2>&1)
UNAME_M=$(uname -m 2>&1)
SDK_VERSION=$(xcrun --sdk macosx --show-sdk-version 2>&1)

oneline() { tr '\r\n\t' '   ' | sed -e 's/  */ /g' -e 's/^ //' -e 's/ $//'; }
first_line() { sed -n '1p'; }
file_size() { if [ -f "$1" ]; then wc -c <"$1" | tr -d ' '; else echo 0; fi; }

detect_air_target() { # $1 = metallib, $2 = per-tuple log
  air_target=""
  # Some toolchains advertise --air-version in --help but print the full usage
  # text for it. Only accept output that actually contains an AIR triple.
  if xcrun --sdk macosx metal-objdump --help 2>/dev/null | grep -q -e '--air-version'; then
    candidate=$(xcrun --sdk macosx metal-objdump --air-version "$1" 2>/dev/null | oneline)
    case "$candidate" in
      *air64*) air_target="$candidate" ;;
    esac
  fi
  triple=$(strings -a "$1" 2>/dev/null | grep -aoE 'air64[^[:space:]]+' | sort -u | paste -s -d ',' -)
  if [ -n "$triple" ]; then
    echo "air triple(s): $triple" >>"$2"
  fi
  if [ -n "$triple" ]; then
    printf '%s\n' "$triple"
  elif [ -n "$air_target" ]; then
    printf '%s\n' "$air_target"
  else
    printf '%s\n' "unknown"
  fi
}

printf 'std\tspelling\tmin_os\tcompile_exit\tcompile_error\tair_bytes\tlink_exit\tlink_error\tmetallib_bytes\tair_target\tload_status\tload_detail\n' >"$ROWS"
compiled=0
tuple_total=0

for std in $(printf '%s' "$STDS" | tr ',' ' '); do
  [ -n "$std" ] || continue
  for min_os in $(printf '%s' "$MIN_OS_LIST" | tr ',' ' '); do
    [ -n "$min_os" ] || continue
    tuple_total=$((tuple_total + 1))
    tag="$std-macos$min_os"
    air="$TMP/$tag.air"
    metallib="$TMP/$tag.metallib"
    log="$OUT/logs/$tag.log"
    : >"$log"

    compile_exit=1
    spelling="none"
    first_error=""
    attempt=0
    for candidate in "$std" "macos-$std" "osx-$std"; do
      attempt=$((attempt + 1))
      echo "=== compile attempt $attempt: -std=$candidate -mmacosx-version-min=$min_os" >>"$log"
      xcrun --sdk macosx metal -std="$candidate" -mmacosx-version-min="$min_os" -O -c "$SHADER" -o "$air" >"$TMP/compile.log" 2>&1
      rc=$?
      cat "$TMP/compile.log" >>"$log"
      if [ "$rc" -eq 0 ]; then
        compile_exit=0
        spelling="$candidate"
        break
      fi
      compile_exit=$rc
      if [ "$attempt" -eq 1 ]; then
        first_error=$(first_line <"$TMP/compile.log" | oneline)
      fi
    done
    if [ "$compile_exit" -eq 0 ]; then
      compiled=$((compiled + 1))
      compile_error="-"
    else
      compile_error="$first_error"
      [ -n "$compile_error" ] || compile_error="-"
    fi
    air_bytes=$(file_size "$air")

    link_exit=1
    link_error="-"
    if [ "$compile_exit" -eq 0 ]; then
      echo "=== link: $air -o $metallib" >>"$log"
      xcrun --sdk macosx metallib "$air" -o "$metallib" >"$TMP/link.log" 2>&1
      link_exit=$?
      cat "$TMP/link.log" >>"$log"
      if [ "$link_exit" -ne 0 ]; then
        link_error=$(first_line <"$TMP/link.log" | oneline)
        [ -n "$link_error" ] || link_error="-"
      fi
    fi
    metallib_bytes=$(file_size "$metallib")

    air_target="-"
    if [ "$link_exit" -eq 0 ]; then
      air_target=$(detect_air_target "$metallib" "$log")
      [ -n "$air_target" ] || air_target="-"
    fi

    load_status="skipped"
    load_detail="-"
    if [ "$link_exit" -eq 0 ]; then
      if [ -z "$LOAD_PROBE" ]; then
        load_status="no-load-probe"
      elif [ ! -x "$LOAD_PROBE" ]; then
        load_status="no-load-probe"
        load_detail="not executable: $LOAD_PROBE"
      else
        echo "=== load probe: $LOAD_PROBE --metallib $metallib" >>"$log"
        if [ -n "$DYLIB" ]; then
          "$LOAD_PROBE" --metallib "$metallib" --dylib "$DYLIB" >"$TMP/load.log" 2>&1
        else
          "$LOAD_PROBE" --metallib "$metallib" >"$TMP/load.log" 2>&1
        fi
        rc=$?
        cat "$TMP/load.log" >>"$log"
        echo "=== load probe exit=$rc" >>"$log"
        case "$rc" in
          0)
            load_status="ok" ;;
          3)
            load_status="no-device"
            load_detail="NO_METAL_DEVICE" ;;
          *)
            load_status="fail"
            load_detail=$(tail -n 1 "$TMP/load.log" | oneline)
            [ -n "$load_detail" ] || load_detail="exit $rc" ;;
        esac
      fi
    fi

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$std" "$spelling" "$min_os" "$compile_exit" "$compile_error" "$air_bytes" \
      "$link_exit" "$link_error" "$metallib_bytes" "$air_target" "$load_status" "$load_detail" >>"$ROWS"
  done
done

cp "$ROWS" "$REPORT_TSV" || true

# Deployment-target preference order: the values requested via --min-os, in the order given,
# then any other target that compiled and linked, in first-seen order.
MIN_OS_PREFS="$TMP/min-os-prefs.txt"
{
  printf '%s\n' "$MIN_OS_LIST" | tr ',' '\n' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | sed '/^$/d'
  awk -F "$TAB" 'NR > 1 && $4 == "0" && $7 == "0" { print $3 }' "$ROWS"
} | awk '!seen[$0]++' >"$MIN_OS_PREFS"

BEST_STD=""
BEST_MIN=""
BEST_SPELLING=""
BEST_AIR=""
BEST_LOAD=""
BEST_RANK=999

reset_best() {
  BEST_STD=""
  BEST_MIN=""
  BEST_SPELLING=""
  BEST_AIR=""
  BEST_LOAD=""
  BEST_RANK=999
}

std_rank() {
  case "$1" in
    metal2.0) echo 20 ;;
    metal2.1) echo 21 ;;
    metal2.2) echo 22 ;;
    metal2.3) echo 23 ;;
    metal2.4) echo 24 ;;
    metal2.5) echo 25 ;;
    metal3.0) echo 30 ;;
    metal3.1) echo 31 ;;
    metal3.2) echo 32 ;;
    *) echo 99 ;;
  esac
}

try_select() { # $1 = verified | no-device | any
  reset_best
  while IFS= read -r pref_min; do
    [ -n "$pref_min" ] || continue
    while IFS=$TAB read -r std spelling min_os compile_exit compile_error air_bytes \
        link_exit link_error metallib_bytes air_target load_status load_detail; do
      [ "$std" = "std" ] && continue
      [ "$compile_exit" = "0" ] || continue
      [ "$link_exit" = "0" ] || continue
      [ "$min_os" = "$pref_min" ] || continue
      case "$1" in
        verified)
          [ "$load_status" = "ok" ] || continue ;;
        no-device)
          case "$load_status" in
            ok|no-device) : ;;
            *) continue ;;
          esac ;;
        any)
          : ;;
      esac
      rank=$(std_rank "$std")
      if [ "$rank" -lt "$BEST_RANK" ]; then
        BEST_RANK=$rank
        BEST_STD=$std
        BEST_MIN=$min_os
        BEST_SPELLING=$spelling
        BEST_AIR=$air_target
        BEST_LOAD=$load_status
      fi
    done <"$ROWS"
    if [ -n "$BEST_STD" ]; then
      return 0
    fi
  done <"$MIN_OS_PREFS"
  return 1
}

PROBE_STATUS="none"
if try_select verified; then
  PROBE_STATUS="verified"
elif try_select no-device; then
  PROBE_STATUS="unverified-no-metal-device"
elif try_select any; then
  if [ "$BEST_LOAD" = "no-load-probe" ] || [ -z "$LOAD_PROBE" ]; then
    PROBE_STATUS="unverified-no-load-probe"
  else
    PROBE_STATUS="unverified-load-failed"
  fi
fi

CHOSEN_REL=""
if [ -n "$BEST_STD" ]; then
  chosen_source="$TMP/$BEST_STD-macos$BEST_MIN.metallib"
  if [ -f "$chosen_source" ]; then
    CHOSEN_REL="chosen/voxy_gl41metal-$BEST_STD-macos$BEST_MIN.metallib"
    cp "$chosen_source" "$OUT/$CHOSEN_REL"
  fi
fi

{
  echo "date_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "uname_m=$(printf '%s' "$UNAME_M" | oneline)"
  echo "sw_vers=$(printf '%s' "$SW_VERS_OUT" | oneline)"
  echo "xcodebuild_version=$(printf '%s' "$XCODE_VERSION" | oneline)"
  echo "sdk_version=$(printf '%s' "$SDK_VERSION" | oneline)"
  echo "xcrun_metal_version=$(printf '%s' "$METAL_VERSION" | oneline)"
  echo "xcrun_metallib_version=$(printf '%s' "$METALLIB_VERSION" | oneline)"
  echo "shader=$SHADER"
  echo "stds=$STDS"
  echo "min_os=$MIN_OS_LIST"
  echo "load_probe=${LOAD_PROBE:--}"
  echo "dylib=${DYLIB:--}"
  echo "tuples_total=$tuple_total"
  echo "tuples_compiled=$compiled"
} >"$ENV_TXT"

{
  echo "# Generated by scripts/macos/probe-metal-compat.sh."
  echo "# Review report.md before copying these values into gradle.properties."
  echo "# Builds never read this file directly."
  echo "GL41METAL_PROBE_STATUS=$PROBE_STATUS"
  if [ -n "$BEST_STD" ]; then
    echo "GL41METAL_MSL_STD=$BEST_SPELLING"
    echo "GL41METAL_MACOS_MIN=$BEST_MIN"
    echo "GL41METAL_SHIP_FALLBACK=false"
    echo "GL41METAL_AIR_TARGET=${BEST_AIR:--}"
    echo "# chosen_std=$BEST_STD"
    echo "# chosen_spelling=$BEST_SPELLING"
    if [ -n "$CHOSEN_REL" ]; then
      echo "# chosen_metallib=$CHOSEN_REL"
    fi
  else
    echo "GL41METAL_MSL_STD="
    echo "GL41METAL_MACOS_MIN="
    echo "GL41METAL_SHIP_FALLBACK=false"
    echo "GL41METAL_AIR_TARGET="
  fi
} >"$RECOMMENDATION"

{
  echo "# GL41Metal MSL compatibility probe"
  echo
  echo "Generated: $(date -u '+%Y-%m-%dT%H:%M:%SZ') by \`scripts/macos/probe-metal-compat.sh\`."
  echo
  echo "## Host environment"
  echo
  echo '```'
  echo "uname -m: $UNAME_M"
  echo "sw_vers:"
  printf '%s\n' "$SW_VERS_OUT"
  echo "xcodebuild -version:"
  printf '%s\n' "$XCODE_VERSION"
  echo "SDK version: $SDK_VERSION"
  echo "xcrun metal --version:"
  printf '%s\n' "$METAL_VERSION"
  echo "xcrun -sdk macosx metallib --version:"
  printf '%s\n' "$METALLIB_VERSION"
  echo '```'
  echo
  echo "## Matrix ($tuple_total tuples, $compiled compiled)"
  echo
  echo '| std | flag spelling | min macOS | compile | link | load | AIR target | metallib bytes |'
  echo '| --- | --- | --- | --- | --- | --- | --- | --- |'
  while IFS=$TAB read -r std spelling min_os compile_exit compile_error air_bytes \
      link_exit link_error metallib_bytes air_target load_status load_detail; do
    [ "$std" = "std" ] && continue
    if [ "$compile_exit" = "0" ]; then
      compile_disp="PASS"
    else
      compile_disp="FAIL: $compile_error"
    fi
    if [ "$link_exit" = "0" ]; then
      link_disp="PASS"
    else
      link_disp="FAIL: $link_error"
    fi
    if [ "$load_detail" != "-" ]; then
      load_disp="$load_status: $load_detail"
    else
      load_disp="$load_status"
    fi
    compile_disp=$(printf '%s' "$compile_disp" | tr '|' '/')
    link_disp=$(printf '%s' "$link_disp" | tr '|' '/')
    load_disp=$(printf '%s' "$load_disp" | tr '|' '/')
    air_disp=$(printf '%s' "$air_target" | tr '|' '/')
    mark=""
    if [ -n "$BEST_STD" ] && [ "$std" = "$BEST_STD" ] && [ "$min_os" = "$BEST_MIN" ]; then
      mark=" **<= chosen**"
    fi
    echo "| $std | $spelling | $min_os | $compile_disp | $link_disp | $load_disp | $air_disp | $metallib_bytes |$mark"
  done <"$ROWS"
  echo
  echo "## Recommendation"
  echo
  case "$PROBE_STATUS" in
    verified)
      echo "**Verified on this runner:** MSL \`$BEST_STD\` (accepted \`-std\` spelling \`$BEST_SPELLING\`), macOS deployment target \`$BEST_MIN\`, AIR target \`$BEST_AIR\`."
      case "$BEST_MIN" in
        11.0)
          echo "Plan 1 (single metallib): the Apple Silicon macOS 11.0 floor is met." ;;
        11.3)
          echo "Floor is macOS 11.3 (MSL 2.2 minimum). Acceptable per plan; document 11.3." ;;
        12.0)
          echo "Floor is macOS 12.0. Plan 2 (dual metallib) is required if Big Sur support is mandatory." ;;
        13.0)
          echo "Floor is macOS 13.0+. Plan 2 is required; consider Plan 3 only if macOS 11 support is mandatory." ;;
        *)
          echo "Floor is macOS $BEST_MIN; review Plan 2/3." ;;
      esac ;;
    unverified-no-metal-device)
      echo "**Unverified:** the chosen tuple compiled and linked, but this runner has no Metal device (exit 3, NO_METAL_DEVICE). Re-run on a Metal-capable runner before setting gradle.properties." ;;
    unverified-no-load-probe)
      echo "**Unverified:** no load probe was supplied; the choice is based on compile+link only. Re-run with \`--load-probe\` on a Metal-capable runner." ;;
    unverified-load-failed)
      echo "**Unverified:** every tuple that compiled+linked failed to load on this Metal device. Treat the recommendation as an input to Plan 2/3 analysis, not as a shippable choice." ;;
    none)
      echo "**No candidate:** no tuple compiled and linked successfully. Investigate compile errors before changing gradle.properties." ;;
  esac
  if [ -n "$BEST_STD" ]; then
    echo
    echo "Use \`gl41metal_msl_std=$BEST_SPELLING\` (the accepted \`-std\` spelling) and \`gl41metal_macos_min=$BEST_MIN\` in \`gradle.properties\` after review."
  fi
  echo
  echo "\`GL41METAL_SHIP_FALLBACK=false\` is fixed by this probe; a fallback metallib is a Plan 2 decision made after reviewing this report."
  echo
  echo "Chosen metallib: ${CHOSEN_REL:-none}"
} >"$REPORT_MD"

if [ -n "$BEST_STD" ]; then
  echo "CHOSEN: std=$BEST_STD min_os=$BEST_MIN spelling=$BEST_SPELLING load=$BEST_LOAD air=$BEST_AIR status=$PROBE_STATUS"
  if [ "$PROBE_STATUS" = "verified" ]; then
    echo "WHY: lowest MSL std among load-verified tuples, preferring deployment targets in --min-os order ($MIN_OS_LIST), then any other measured target."
  else
    echo "WHY: best compile+link tuple using the same deployment-target preference order ($MIN_OS_LIST), then any other measured target; load verification status: $PROBE_STATUS."
  fi
else
  echo "CHOSEN: none (status=$PROBE_STATUS)"
fi

if [ "$compiled" -eq 0 ]; then
  echo "probe: zero tuples compiled - see $REPORT_MD" >&2
  exit 1
fi
echo "probe: $compiled/$tuple_total tuples compiled; report: $REPORT_MD"
exit 0
