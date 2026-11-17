// load-metal-probe.mm - verify that a Metallib loads on the current Metal driver
// and that its traversal kernel builds a compute pipeline.
//
// Build (macOS with Xcode / Command Line Tools):
//   mkdir -p build/macos-metal-probe
//   xcrun --sdk macosx clang++ -std=c++17 -fobjc-arc -O2 -Wall \
//     scripts/macos/load-metal-probe.mm \
//     -framework Foundation -framework Metal \
//     -o build/macos-metal-probe/load-metal-probe
//
// Usage:
//   ./load-metal-probe --metallib PATH [--dylib PATH] [--function NAME]
//
// Exit codes:
//   0  metallib loaded and the compute pipeline built
//   1  usage error / unreadable input
//   2  --dylib dlopen failed or the JNI symbol is missing
//   3  NO_METAL_DEVICE (headless/unsupported runner; not a product failure)
//   4  newLibraryWithURL failed
//   5  kernel function is missing from the library
//   6  newComputePipelineStateWithFunction failed

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <dlfcn.h>
#import <stdio.h>
#import <string.h>

static const char* kGetUnsupportedReasonSymbol =
    "Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_jni_NativeBindings_"
    "getUnsupportedReason";

static void usage(const char* argv0) {
  fprintf(stderr, "usage: %s --metallib PATH [--dylib PATH] [--function NAME]\n",
          argv0);
}

static void fail(NSString* step, NSString* message) {
  fprintf(stderr, "ERROR: %s: %s\n", [step UTF8String], [message UTF8String]);
}

int main(int argc, const char* argv[]) {
  @autoreleasepool {
    NSString* metallibPath = nil;
    NSString* dylibPath = nil;
    NSString* functionName = @"traverse";

    for (int i = 1; i < argc; i++) {
      if (strcmp(argv[i], "--metallib") == 0 && i + 1 < argc) {
        metallibPath = [NSString stringWithUTF8String:argv[++i]];
      } else if (strcmp(argv[i], "--dylib") == 0 && i + 1 < argc) {
        dylibPath = [NSString stringWithUTF8String:argv[++i]];
      } else if (strcmp(argv[i], "--function") == 0 && i + 1 < argc) {
        functionName = [NSString stringWithUTF8String:argv[++i]];
      } else {
        usage(argv[0]);
        return 1;
      }
    }

    NSString* cwd = [NSFileManager.defaultManager currentDirectoryPath];
    if (metallibPath == nil) {
      fail(@"USAGE", @"--metallib PATH is required");
      usage(argv[0]);
      return 1;
    }
    NSString* resolvedMetallib =
        [metallibPath isAbsolutePath]
            ? metallibPath
            : [cwd stringByAppendingPathComponent:metallibPath];
    if (![NSFileManager.defaultManager fileExistsAtPath:resolvedMetallib]) {
      fail(@"USAGE", [NSString stringWithFormat:@"metallib not found: %@",
                                               resolvedMetallib]);
      return 1;
    }

    if (dylibPath != nil) {
      NSString* resolvedDylib =
          [dylibPath isAbsolutePath]
              ? dylibPath
              : [cwd stringByAppendingPathComponent:dylibPath];
      fprintf(stdout, "[DYLIB] dlopen(%s)\n", [resolvedDylib UTF8String]);
      void* handle =
          dlopen([resolvedDylib fileSystemRepresentation], RTLD_NOW | RTLD_LOCAL);
      if (handle == NULL) {
        const char* err = dlerror();
        fail(@"DYLIB", err != NULL ? [NSString stringWithUTF8String:err]
                                   : @"dlopen returned NULL");
        return 2;
      }
      void* symbol = dlsym(handle, kGetUnsupportedReasonSymbol);
      if (symbol == NULL) {
        fail(@"SYMBOL", [NSString stringWithFormat:@"missing %s",
                                                   kGetUnsupportedReasonSymbol]);
        return 2;
      }
      fprintf(stdout, "[SYMBOL] %s found\n", kGetUnsupportedReasonSymbol);
    } else {
      fprintf(stdout, "[DYLIB] skipped (--dylib not provided)\n");
    }

    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    if (device == nil) {
      fprintf(stdout, "NO_METAL_DEVICE\n");
      return 3;
    }
    fprintf(stdout, "[DEVICE] %s\n", [device.name UTF8String]);

    NSError* error = nil;
    NSURL* url = [NSURL fileURLWithPath:resolvedMetallib];
    id<MTLLibrary> library = [device newLibraryWithURL:url error:&error];
    if (library == nil) {
      fail(@"LIBRARY", error != nil ? error.localizedDescription
                                    : @"newLibraryWithURL returned nil");
      return 4;
    }
    fprintf(stdout, "[LIBRARY] loaded %s\n", [resolvedMetallib UTF8String]);

    id<MTLFunction> function = [library newFunctionWithName:functionName];
    if (function == nil) {
      fail(@"FUNCTION", [NSString stringWithFormat:@"kernel '%@' not found",
                                                   functionName]);
      return 5;
    }
    fprintf(stdout, "[FUNCTION] %s found\n", [functionName UTF8String]);

    error = nil;
    id<MTLComputePipelineState> pipeline =
        [device newComputePipelineStateWithFunction:function error:&error];
    if (pipeline == nil) {
      fail(@"PIPELINE", error != nil ? error.localizedDescription
                                     : @"pipeline creation returned nil");
      return 6;
    }
    fprintf(stdout, "[PIPELINE] maxTotalThreadsPerThreadgroup=%lu\n",
            (unsigned long)pipeline.maxTotalThreadsPerThreadgroup);
    fprintf(stdout, "LOAD_PROBE_OK\n");
    return 0;
  }
}
