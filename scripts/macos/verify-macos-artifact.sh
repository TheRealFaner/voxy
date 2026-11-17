#!/bin/sh
# Verify a macOS-built Voxy NeoForge jar contains usable GL41Metal natives.
# macOS-only. POSIX sh. Exit 0 on success, 1 on hard failure, 2 on usage error.

set -eu
LC_ALL=C
export LC_ALL

usage() {
    cat <<'USAGE'
Usage: verify-macos-artifact.sh <jar> [options]

Options:
  --expected-msl-std <value>  Expected msl_std from natives/macos/gl41metal/build-info.properties
  --expected-min-os <value>   Expected macOS deployment target (minos) for both dylib slices
  --run-load-probe            Compile/run scripts/macos/load-metal-probe.mm against the natives
  -h, --help                  Show this help

Exit codes: 0 = pass, 1 = hard failure, 2 = usage error.
USAGE
}

JAR=""
EXPECTED_MSL_STD=""
EXPECTED_MIN_OS=""
RUN_LOAD_PROBE=0

while [ $# -gt 0 ]; do
    case "$1" in
        --expected-msl-std)
            [ $# -ge 2 ] || { echo "Missing value for $1" >&2; usage >&2; exit 2; }
            EXPECTED_MSL_STD=$2
            shift 2
            ;;
        --expected-min-os)
            [ $# -ge 2 ] || { echo "Missing value for $1" >&2; usage >&2; exit 2; }
            EXPECTED_MIN_OS=$2
            shift 2
            ;;
        --run-load-probe)
            RUN_LOAD_PROBE=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
        *)
            if [ -n "$JAR" ]; then
                echo "Unexpected extra argument: $1" >&2
                usage >&2
                exit 2
            fi
            JAR=$1
            shift
            ;;
    esac
done

[ -n "$JAR" ] || { usage >&2; exit 2; }

if [ "$(uname -s)" != "Darwin" ]; then
    echo "FAIL: verify-macos-artifact.sh requires macOS (found $(uname -s))" >&2
    exit 1
fi

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd "$SCRIPT_DIR/../.." && pwd)

WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/voxy-macos-verify.XXXXXX")
RESULTS="$WORKDIR/results.tsv"
: > "$RESULTS"
HARD_FAIL=0

cleanup() {
    rm -rf "$WORKDIR"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

pass() { printf 'PASS\t%s\t%s\n' "$1" "$2" >> "$RESULTS"; }
fail() { printf 'FAIL\t%s\t%s\n' "$1" "$2" >> "$RESULTS"; HARD_FAIL=1; }
warn() { printf 'WARN\t%s\t%s\n' "$1" "$2" >> "$RESULTS"; }
skip() { printf 'SKIP\t%s\t%s\n' "$1" "$2" >> "$RESULTS"; }

print_table() {
    echo ""
    echo "=== macOS artifact verification ==="
    printf '%-6s %-44s %s\n' "STATUS" "CHECK" "DETAIL"
    printf '%-6s %-44s %s\n' "------" "-----" "------"
    awk -F '\t' '{ printf "%-6s %-44s %s\n", $1, $2, $3 }' "$RESULTS"
    echo ""
    if [ "$HARD_FAIL" -ne 0 ]; then
        echo "RESULT: FAIL"
    else
        echo "RESULT: PASS"
    fi
}

normalize_version() {
    printf '%s' "$1" | awk -F. '{
        n = NF
        while (n > 1 && $n + 0 == 0) n--
        out = $1
        for (i = 2; i <= n; i++) out = out "." $i
        print out
    }'
}

version_gt() {
    # Exit 0 when $1 > $2, else exit 1.
    awk -v a="$1" -v b="$2" 'BEGIN {
        na = split(a, A, "."); nb = split(b, B, ".")
        n = (na > nb) ? na : nb
        for (i = 1; i <= n; i++) {
            av = (i <= na) ? A[i] + 0 : 0
            bv = (i <= nb) ? B[i] + 0 : 0
            if (av > bv) exit 0
            if (av < bv) exit 1
        }
        exit 1
    }'
}

entry_size() {
    # $1 = exact zip entry name; print uncompressed size (or nothing).
    unzip -l "$JAR" 2>/dev/null | awk -v p="$1" '$NF == p { print $1; exit }'
}

for tool in unzip file lipo otool nm strings; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        fail "tool:$tool" "required tool not found in PATH"
    fi
done
if [ "$HARD_FAIL" -ne 0 ]; then
    print_table
    exit 1
fi

# 1. Jar exists and is a non-empty file.
if [ ! -f "$JAR" ]; then
    fail "jar exists" "missing file: $JAR"
    print_table
    exit 1
fi
if [ ! -s "$JAR" ]; then
    fail "jar non-empty" "zero-byte jar: $JAR"
    print_table
    exit 1
fi
pass "jar exists" "$JAR ($(wc -c < "$JAR" | awk '{print $1}') bytes)"

ENTRIES="$WORKDIR/entries.txt"
if ! unzip -Z1 "$JAR" > "$ENTRIES" 2> "$WORKDIR/unzip.err"; then
    fail "zip listing" "unzip -Z1 failed: $(tr '\n' ' ' < "$WORKDIR/unzip.err")"
    print_table
    exit 1
fi
pass "zip listing" "$(wc -l < "$ENTRIES" | awk '{print $1}') entries"

DYLIB_ENTRY='natives/macos/libvoxy_gl41metal.dylib'
METALLIB_ENTRY='natives/macos/gl41metal/voxy_gl41metal.metallib'
BUILD_INFO_ENTRY='natives/macos/gl41metal/build-info.properties'

DYLIB_COUNT=$(grep -cxF "$DYLIB_ENTRY" "$ENTRIES" || true)
if [ "$DYLIB_COUNT" -eq 1 ]; then
    pass "dylib entry" "exactly one $DYLIB_ENTRY"
elif [ "$DYLIB_COUNT" -eq 0 ]; then
    fail "dylib entry" "missing $DYLIB_ENTRY"
else
    fail "dylib entry" "expected exactly one $DYLIB_ENTRY, found $DYLIB_COUNT"
fi

METALLIB_COUNT=$(grep -cxF "$METALLIB_ENTRY" "$ENTRIES" || true)
if [ "$METALLIB_COUNT" -eq 1 ]; then
    pass "metallib entry" "exactly one $METALLIB_ENTRY"
elif [ "$METALLIB_COUNT" -eq 0 ]; then
    fail "metallib entry" "missing $METALLIB_ENTRY"
else
    fail "metallib entry" "expected exactly one $METALLIB_ENTRY, found $METALLIB_COUNT"
fi

BUILD_INFO_COUNT=$(grep -cxF "$BUILD_INFO_ENTRY" "$ENTRIES" || true)
if [ "$BUILD_INFO_COUNT" -eq 1 ]; then
    pass "build-info entry" "$BUILD_INFO_ENTRY"
elif [ "$BUILD_INFO_COUNT" -eq 0 ]; then
    fail "build-info entry" "missing $BUILD_INFO_ENTRY"
else
    fail "build-info entry" "expected exactly one $BUILD_INFO_ENTRY, found $BUILD_INFO_COUNT"
fi

# Entry sizes: canonical dylib and every shipped metallib must be non-empty.
if [ "$DYLIB_COUNT" -eq 1 ]; then
    SIZE=$(entry_size "$DYLIB_ENTRY")
    case "$SIZE" in
        ''|*[!0-9]*) fail "dylib non-empty" "could not read size for $DYLIB_ENTRY" ;;
        0) fail "dylib non-empty" "$DYLIB_ENTRY is zero bytes" ;;
        *) pass "dylib non-empty" "$DYLIB_ENTRY ($SIZE bytes)" ;;
    esac
fi

METALLIBS=$(grep -E '^natives/macos/gl41metal/[^/]+\.metallib$' "$ENTRIES" || true)
if [ -z "$METALLIBS" ]; then
    fail "metallib entries" "no natives/macos/gl41metal/*.metallib entries"
fi
for entry in $METALLIBS; do
    SIZE=$(entry_size "$entry")
    case "$SIZE" in
        ''|*[!0-9]*) fail "metallib non-empty" "could not read size for $entry" ;;
        0) fail "metallib non-empty" "$entry is zero bytes" ;;
        *) pass "metallib non-empty" "$entry ($SIZE bytes)" ;;
    esac
done

# build-info.properties: msl_std / macos_min must match expected values when provided.
if [ "$BUILD_INFO_COUNT" -eq 1 ]; then
    unzip -p "$JAR" "$BUILD_INFO_ENTRY" > "$WORKDIR/build-info.properties" 2>/dev/null || true
    if [ ! -s "$WORKDIR/build-info.properties" ]; then
        fail "build-info non-empty" "$BUILD_INFO_ENTRY could not be extracted or is empty"
    else
        pass "build-info non-empty" "$BUILD_INFO_ENTRY"
        BI_STD=$(sed -n 's/^[[:space:]]*msl_std[[:space:]]*=[[:space:]]*//p' "$WORKDIR/build-info.properties" | tail -n 1 | tr -d '\r' | sed 's/[[:space:]]*$//')
        BI_MIN=$(sed -n 's/^[[:space:]]*macos_min[[:space:]]*=[[:space:]]*//p' "$WORKDIR/build-info.properties" | tail -n 1 | tr -d '\r' | sed 's/[[:space:]]*$//')
        if [ -n "$EXPECTED_MSL_STD" ]; then
            if [ "$BI_STD" = "$EXPECTED_MSL_STD" ]; then
                pass "build-info msl_std" "$BI_STD"
            else
                fail "build-info msl_std" "expected '$EXPECTED_MSL_STD', found '${BI_STD}'"
            fi
        elif [ -n "$BI_STD" ]; then
            pass "build-info msl_std" "$BI_STD (not checked)"
        else
            warn "build-info msl_std" "key not found (no --expected-msl-std)"
        fi
        if [ -n "$EXPECTED_MIN_OS" ]; then
            if [ "$(normalize_version "$BI_MIN")" = "$(normalize_version "$EXPECTED_MIN_OS")" ]; then
                pass "build-info macos_min" "$BI_MIN"
            else
                fail "build-info macos_min" "expected '$EXPECTED_MIN_OS', found '${BI_MIN}'"
            fi
        elif [ -n "$BI_MIN" ]; then
            pass "build-info macos_min" "$BI_MIN (not checked)"
        else
            warn "build-info macos_min" "key not found (no --expected-min-os)"
        fi
    fi
fi

# 2. Dylib inspection.
DYLIB="$WORKDIR/libvoxy_gl41metal.dylib"
if [ "$DYLIB_COUNT" -eq 1 ]; then
    if unzip -p "$JAR" "$DYLIB_ENTRY" > "$DYLIB" 2>/dev/null && [ -s "$DYLIB" ]; then
        pass "dylib extraction" "$DYLIB_ENTRY"
    else
        fail "dylib extraction" "could not extract $DYLIB_ENTRY"
    fi
fi

if [ -s "$DYLIB" ]; then
    FILE_OUT=$(file -b "$DYLIB" 2>/dev/null || true)
    case "$FILE_OUT" in
        *"Mach-O universal binary"*) pass "dylib file type" "$FILE_OUT" ;;
        *) fail "dylib file type" "expected 'Mach-O universal binary', got: $FILE_OUT" ;;
    esac
    case "$FILE_OUT" in *x86_64*) ;; *) fail "dylib arch x86_64" "x86_64 not reported by file" ;; esac
    case "$FILE_OUT" in *arm64*) ;; *) fail "dylib arch arm64" "arm64 not reported by file" ;; esac

    if lipo -verify_arch x86_64 arm64 "$DYLIB" >/dev/null 2>&1; then
        pass "lipo architectures" "x86_64 and arm64 present"
    else
        fail "lipo architectures" "lipo -verify_arch x86_64 arm64 failed"
    fi

    MINOS_FILE="$WORKDIR/minos.txt"
    : > "$MINOS_FILE"
    MINOS_SOURCE=""
    VTOOL=$(xcrun -f vtool 2>/dev/null || command -v vtool 2>/dev/null || true)
    if [ -n "$VTOOL" ] && "$VTOOL" -show-build -arch all "$DYLIB" > "$WORKDIR/vtool-all.txt" 2>&1; then
        sed -n 's/^[[:space:]]*minos[[:space:]]\{1,\}//p' "$WORKDIR/vtool-all.txt" | tr -d '\r' | sed 's/[[:space:]]*$//' > "$MINOS_FILE"
        MINOS_SOURCE="vtool -show-build -arch all"
    fi
    if [ "$(grep -c . "$MINOS_FILE" || true)" -lt 2 ] && [ -n "$VTOOL" ]; then
        : > "$MINOS_FILE"
        for arch in x86_64 arm64; do
            "$VTOOL" -show-build -arch "$arch" "$DYLIB" > "$WORKDIR/vtool-$arch.txt" 2>&1 || true
            sed -n 's/^[[:space:]]*minos[[:space:]]\{1,\}//p' "$WORKDIR/vtool-$arch.txt" | tr -d '\r' | sed 's/[[:space:]]*$//' >> "$MINOS_FILE"
        done
        MINOS_SOURCE="vtool -show-build per arch"
    fi
    if [ "$(grep -c . "$MINOS_FILE" || true)" -lt 2 ]; then
        : > "$MINOS_FILE"
        otool -l "$DYLIB" > "$WORKDIR/otool-l.txt" 2>&1 || true
        sed -n 's/^[[:space:]]*minos[[:space:]]\{1,\}//p' "$WORKDIR/otool-l.txt" | tr -d '\r' | sed 's/[[:space:]]*$//' > "$MINOS_FILE"
        MINOS_SOURCE="otool -l"
    fi
    MINOS_COUNT=$(grep -c . "$MINOS_FILE" || true)
    if [ "$MINOS_COUNT" -lt 2 ]; then
        fail "dylib minos" "expected minos for both slices via $MINOS_SOURCE, found $MINOS_COUNT value(s)"
    elif [ -n "$EXPECTED_MIN_OS" ]; then
        BAD_MINOS=""
        while IFS= read -r v; do
            [ -n "$v" ] || continue
            [ "$(normalize_version "$v")" = "$(normalize_version "$EXPECTED_MIN_OS")" ] || BAD_MINOS="$BAD_MINOS $v"
        done < "$MINOS_FILE"
        if [ -z "$BAD_MINOS" ]; then
            pass "dylib minos" "all slices: $(tr '\n' ' ' < "$MINOS_FILE")($MINOS_SOURCE)"
        else
            fail "dylib minos" "expected $EXPECTED_MIN_OS; mismatches:$BAD_MINOS ($MINOS_SOURCE)"
        fi
    else
        warn "dylib minos" "observed: $(tr '\n' ' ' < "$MINOS_FILE")(no --expected-min-os)"
    fi

    # otool -L also prints the dylib's own LC_ID_DYLIB install name per architecture, so parse
    # otool -l and collect only actual load commands. Each command is a `cmd LC_*` line followed
    # by a `name <path> (offset N)` line; inspect all slices.
    if otool -l "$DYLIB" > "$WORKDIR/otool-l-deps.txt" 2>&1; then
        DEPS=$(awk '
            $1 == "cmd" { expect_name = ($2 == "LC_LOAD_DYLIB" || $2 == "LC_LOAD_WEAK_DYLIB" || $2 == "LC_REEXPORT_DYLIB"); next }
            expect_name && $1 == "name" { print $2; expect_name = 0 }' "$WORKDIR/otool-l-deps.txt")
        BAD_DEPS=""
        for dep in $DEPS; do
            case "$dep" in
                @rpath*|@loader_path*|@executable_path*)
                    BAD_DEPS="$BAD_DEPS $dep(relative-install-name)" ;;
                /System/Library/Frameworks/Foundation.framework/*|\
                /System/Library/Frameworks/Metal.framework/*|\
                /usr/lib/libSystem.B.dylib|\
                /usr/lib/libc++.1.dylib|\
                /usr/lib/libobjc.A.dylib)
                    ;;
                *)
                    BAD_DEPS="$BAD_DEPS $dep" ;;
            esac
        done
        if [ -z "$DEPS" ]; then
            fail "dylib dependencies" "otool -l reported no LC_LOAD_DYLIB/LC_LOAD_WEAK_DYLIB/LC_REEXPORT_DYLIB entries"
        elif [ -z "$BAD_DEPS" ]; then
            pass "dylib dependencies" "only system frameworks/libraries"
        else
            fail "dylib dependencies" "unexpected:$BAD_DEPS"
        fi
    else
        fail "dylib dependencies" "otool -l failed"
    fi

    for arch in arm64 x86_64; do
        if nm -gU -arch "$arch" "$DYLIB" > "$WORKDIR/nm-$arch.txt" 2>/dev/null; then
            JNI_COUNT=$(grep -c 'Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_' "$WORKDIR/nm-$arch.txt" 2>/dev/null || true)
            case "$JNI_COUNT" in ''|*[!0-9]*) JNI_COUNT=0 ;; esac
            if [ "$JNI_COUNT" -gt 0 ]; then
                pass "JNI exports ($arch)" "$JNI_COUNT symbol(s)"
            else
                fail "JNI exports ($arch)" "no Java_..._NativeBindings_ symbols"
            fi
        else
            fail "JNI exports ($arch)" "nm -gU -arch $arch failed"
        fi
    done
fi

# 3. Metallib inspection (canonical + any extra shipped metallib).
PROBE_METALLIB=""
for entry in $METALLIBS; do
    BASE_NAME=$(basename "$entry")
    OUT="$WORKDIR/$BASE_NAME"
    if ! unzip -p "$JAR" "$entry" > "$OUT" 2>/dev/null || [ ! -s "$OUT" ]; then
        fail "metallib extraction" "$entry"
        continue
    fi
    pass "metallib extraction" "$entry ($(wc -c < "$OUT" | awk '{print $1}') bytes)"
    if [ "$entry" = "$METALLIB_ENTRY" ]; then
        PROBE_METALLIB="$OUT"
    fi

    if xcrun -f metal-objdump >/dev/null 2>&1; then
        if xcrun metal-objdump --air-version "$OUT" > "$WORKDIR/metal-objdump-$BASE_NAME.txt" 2>&1 \
            && grep -q 'air64' "$WORKDIR/metal-objdump-$BASE_NAME.txt"; then
            pass "metal-objdump --air-version ($BASE_NAME)" "$(tr '\n' ' ' < "$WORKDIR/metal-objdump-$BASE_NAME.txt" | cut -c1-200)"
        else
            warn "metal-objdump --air-version ($BASE_NAME)" "unsupported option on this toolchain; using the strings AIR target below"
        fi
    else
        skip "metal-objdump --air-version ($BASE_NAME)" "tool not available"
    fi

    TARGETS=$(strings -a "$OUT" | grep -aoE 'air64[^[:space:]]+' | sort -u || true)
    if [ -z "$TARGETS" ]; then
        fail "metallib AIR target ($BASE_NAME)" "no air64 target string found"
    else
        pass "metallib AIR target ($BASE_NAME)" "$(printf '%s' "$TARGETS" | tr '\n' ' ')"
        # Only the canonical compat library must match the floor; an explicit fallback
        # (gl41metal_ship_fallback) may intentionally target a newer macOS.
        if [ "$entry" = "$METALLIB_ENTRY" ]; then
            case "$(normalize_version "$EXPECTED_MIN_OS")" in
                11|11.3)
                    NORM_EXPECTED=$(normalize_version "$EXPECTED_MIN_OS")
                    for target in $TARGETS; do
                        TARGET_VER=$(printf '%s' "$target" | sed -n 's/.*-apple-macosx\([0-9][0-9.]*\).*/\1/p')
                        [ -n "$TARGET_VER" ] || continue
                        if version_gt "$(normalize_version "$TARGET_VER")" "$NORM_EXPECTED"; then
                            warn "metallib AIR target ($BASE_NAME)" "target $target looks newer than deployment target $EXPECTED_MIN_OS; confirm with macos-metal-probe report"
                        fi
                    done
                    ;;
            esac
        fi
    fi
done

# 4. Optional runtime load probe.
if [ "$RUN_LOAD_PROBE" -eq 1 ]; then
    PROBE_SRC="$REPO_ROOT/scripts/macos/load-metal-probe.mm"
    PROBE_BIN="$WORKDIR/load-metal-probe"
    if [ ! -f "$PROBE_SRC" ]; then
        fail "load probe" "missing $PROBE_SRC"
    elif [ ! -s "$DYLIB" ] || [ -z "$PROBE_METALLIB" ]; then
        fail "load probe" "dylib/metallib unavailable"
    elif ! xcrun clang++ -std=c++17 -fobjc-arc -O2 -Wall -framework Foundation -framework Metal \
            -o "$PROBE_BIN" "$PROBE_SRC" > "$WORKDIR/probe-build.log" 2>&1; then
        fail "load probe build" "$(sed -n '1,5p' "$WORKDIR/probe-build.log" | tr '\n' ' ')"
    else
        pass "load probe build" "$PROBE_BIN"
        PROBE_RC=0
        "$PROBE_BIN" --dylib "$DYLIB" --metallib "$PROBE_METALLIB" > "$WORKDIR/probe.log" 2>&1 || PROBE_RC=$?
        case "$PROBE_RC" in
            0) pass "load probe" "$(tr '\n' ' ' < "$WORKDIR/probe.log" | cut -c1-300)" ;;
            3) skip "load probe" "NO_METAL_DEVICE (exit 3): $(tr '\n' ' ' < "$WORKDIR/probe.log" | cut -c1-200)" ;;
            *) fail "load probe" "exit $PROBE_RC: $(tr '\n' ' ' < "$WORKDIR/probe.log" | cut -c1-500)" ;;
        esac
    fi
else
    skip "load probe" "not requested (pass --run-load-probe)"
fi

print_table
exit "$HARD_FAIL"
