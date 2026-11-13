package me.cortex.voxy.client.core.model;

import java.util.Arrays;
import net.caffeinemc.mods.sodium.client.util.color.ColorSRGB;

// Texturing utils to manipulate data from the model bakery
public class TextureUtils {
  // Returns the number of non pixels not written to
  public static int getWrittenPixelCount(ColourDepthTextureData texture, int checkMode) {
    int count = 0;
    for (int i = 0; i < texture.colour().length; i++) {
      count += wasPixelWritten(texture, checkMode, i) ? 1 : 0;
    }
    return count;
  }

  public static boolean isSolid(ColourDepthTextureData texture) {
    for (int pixel : texture.colour()) {
      if (((pixel >> 24) & 0xFF) != 255) {
        return false;
      }
    }
    return true;
  }

  public static final int WRITE_CHECK_STENCIL = 1;
  public static final int WRITE_CHECK_DEPTH = 2;
  public static final int WRITE_CHECK_ALPHA = 3;

  private static boolean wasPixelWritten(ColourDepthTextureData data, int mode, int index) {
    if (mode == WRITE_CHECK_STENCIL) {
      return (data.depth()[index] & 0xFF) != 0;
    } else if (mode == WRITE_CHECK_DEPTH) {
      return (data.depth()[index] >>> 8) != ((1 << 24) - 1);
    } else if (mode == WRITE_CHECK_ALPHA) {
      // TODO:FIXME: for some reason it has an alpha of 1 even if its ment to be 0
      return ((data.colour()[index] >>> 24) & 0xff) > 1;
    }
    throw new IllegalArgumentException();
  }

  // 0: nothing written
  // 1: none tinted
  // 2: some tinted
  // 3: all tinted
  public static int computeFaceTint(ColourDepthTextureData texture, int checkMode) {
    boolean allTinted = true;
    boolean someTinted = false;
    boolean wasWriten = false;

    final var colourData = texture.colour();
    final var depthData = texture.depth();
    for (int i = 0; i < colourData.length; i++) {
      if (!wasPixelWritten(texture, checkMode, i)) {
        continue;
      }
      if ((colourData[i] & 0xFFFFFF) == 0
          || (colourData[i] >>> 24) == 0) { // If the pixel is fully black (or translucent)
        continue;
      }
      boolean pixelTinited = (depthData[i] & (1 << 7)) != 0;
      wasWriten |= true;
      allTinted &= pixelTinited;
      someTinted |= pixelTinited;
    }
    if (!wasWriten) {
      return 0;
    }
    return someTinted ? (allTinted ? 3 : 2) : 1;
  }

  public static final int DEPTH_MODE_AVG = 1;
  public static final int DEPTH_MODE_MAX = 2;
  public static final int DEPTH_MODE_MIN = 3;
  public static final int DEPTH_MODE_MEDIAN = 4;

  // Computes depth info based on written pixel data
  public static float computeDepth(ColourDepthTextureData texture, int mode, int checkMode) {
    return computeDepth(texture, mode, checkMode, false);
  }

  // halfDepthRange: the GL offscreen bakery (gl41metal path) renders with a projection that maps
  // model z [0,1] to NDC [-1,0], i.e. window depth [0,0.5], so its captured depth must be doubled
  // to recover full-range model depth (this is the reference's original u2fdepth *2 behaviour).
  // The software rasterizer bakery (gl46 path) writes full-range depth and must NOT be doubled.
  public static float computeDepth(
      ColourDepthTextureData texture, int mode, int checkMode, boolean halfDepthRange) {
    final var colourData = texture.colour();
    final var depthData = texture.depth();
    long a = 0;
    long b = 0;
    if (mode == DEPTH_MODE_MIN) {
      a = Long.MAX_VALUE;
    }
    if (mode == DEPTH_MODE_MAX) {
      a = Long.MIN_VALUE;
    }
    int[] samples = mode == DEPTH_MODE_MEDIAN ? new int[16 * 16] : null;
    int sampleCount = 0;
    for (int i = 0; i < colourData.length; i++) {
      if (!wasPixelWritten(texture, checkMode, i)) {
        continue;
      }
      int depth = depthData[i] >>> 8;
      if (mode == DEPTH_MODE_AVG) {
        a++;
        b += depth;
      } else if (mode == DEPTH_MODE_MAX) {
        a = Math.max(a, depth);
      } else if (mode == DEPTH_MODE_MIN) {
        a = Math.min(a, depth);
      } else if (mode == DEPTH_MODE_MEDIAN) {
        samples[sampleCount++] = depth;
      }
    }

    if (mode == DEPTH_MODE_AVG) {
      if (a == 0) {
        return -1;
      }
      return u2fdepth((int) (b / a), halfDepthRange);
    } else if (mode == DEPTH_MODE_MAX) {
      if (a == Long.MIN_VALUE) {
        return -1;
      }
      return u2fdepth((int) a, halfDepthRange);
    } else if (mode == DEPTH_MODE_MIN) {
      if (a == Long.MAX_VALUE) {
        return -1;
      }
      return u2fdepth((int) a, halfDepthRange);
    } else if (mode == DEPTH_MODE_MEDIAN) {
      if (sampleCount == 0) {
        return -1;
      }
      Arrays.sort(samples, 0, sampleCount);
      return u2fdepth(samples[sampleCount / 2], halfDepthRange);
    }
    throw new IllegalArgumentException();
  }

  /** Minimum depth separation (24-bit depth units) between two planes of the same face. */
  public static final int DEPTH_BAND_MIN_SEPARATION = 1 << 20; // ~1/16 of the 24-bit range

  /**
   * Splits the written pixels of one face into at most {@code maxBands} depth bands. Returns
   * ascending band-minimum depths in 24-bit units (never more than maxBands values; one value when
   * all written pixels are coplanar). Empty face -> empty array. The split points are the largest
   * gaps between consecutive distinct sorted written depths that are >= {@link
   * #DEPTH_BAND_MIN_SEPARATION}; ties resolve deterministically (lowest depth wins).
   */
  public static int[] computeDepthBands(ColourDepthTextureData face, int checkMode, int maxBands) {
    final int bandLimit = Math.max(1, Math.min(3, maxBands));
    final int[] depthData = face.depth();
    // Face textures are 16x16; size the scratch array from the face so the split never overflows.
    final int[] depths = new int[depthData.length];
    int count = 0;
    for (int i = 0; i < depthData.length; i++) {
      if (wasPixelWritten(face, checkMode, i)) {
        depths[count++] = depthData[i] >>> 8;
      }
    }
    if (count == 0) {
      return new int[0];
    }
    Arrays.sort(depths, 0, count);

    // Collapse duplicates in place: gaps are only meaningful between distinct depths.
    int distinct = 1;
    for (int i = 1; i < count; i++) {
      if (depths[i] != depths[distinct - 1]) {
        depths[distinct++] = depths[i];
      }
    }

    // Greedily mark the largest qualifying gaps; strict '>' keeps the shallowest split on ties.
    final boolean[] isSplit = new boolean[distinct];
    for (int remaining = bandLimit - 1; remaining > 0; remaining--) {
      int bestIndex = -1;
      int bestGap = DEPTH_BAND_MIN_SEPARATION - 1;
      for (int i = 0; i < distinct - 1; i++) {
        if (isSplit[i]) {
          continue;
        }
        int gap = depths[i + 1] - depths[i];
        if (gap > bestGap) {
          bestGap = gap;
          bestIndex = i;
        }
      }
      if (bestIndex < 0) {
        break; // No remaining gap reaches the minimum separation.
      }
      isSplit[bestIndex] = true;
    }

    int bandCount = 1;
    for (int i = 0; i < distinct - 1; i++) {
      if (isSplit[i]) {
        bandCount++;
      }
    }
    int[] bands = new int[bandCount];
    bands[0] = depths[0];
    int band = 1;
    for (int i = 0; i < distinct - 1; i++) {
      if (isSplit[i]) {
        bands[band++] = depths[i + 1];
      }
    }
    return bands;
  }

  /**
   * Splits baked faces into depth-band plane sets for the multi-plane root impostor. Returns
   * {planeCount x 6} faces; plane k keeps, per face, only pixels whose 24-bit depth is in
   * [bands[f][k], bands[f][k+1]) (last band: &gt;=). Other pixels get alpha 0. Depth channels are
   * shared with the source (read-only after bake); colour alpha is the absolute depth already
   * written by encodeRootPixelDepth. Empty input -&gt; empty array.
   */
  public static ColourDepthTextureData[][] splitDepthBandPlanes(
      ColourDepthTextureData[] faces, int checkMode, int maxBands) {
    if (faces.length == 0) {
      return new ColourDepthTextureData[0][];
    }
    final int[][] bandsPerFace = new int[faces.length][];
    int planeCount = 0;
    for (int f = 0; f < faces.length; f++) {
      bandsPerFace[f] = computeDepthBands(faces[f], checkMode, maxBands);
      planeCount = Math.max(planeCount, bandsPerFace[f].length);
    }
    if (planeCount == 0) {
      return new ColourDepthTextureData[0][];
    }
    final ColourDepthTextureData[][] planes =
        new ColourDepthTextureData[planeCount][faces.length];
    for (int f = 0; f < faces.length; f++) {
      final ColourDepthTextureData src = faces[f];
      final int[] bands = bandsPerFace[f];
      final int[] srcColour = src.colour();
      // TODO(Stage 3): the depth/stencil channels are shared with the full source face, so every
      // plane's face metadata still derives its MIN/bounds from that full face. Emission must not
      // treat the flat face offset as the band minimum; if per-plane metadata precision matters,
      // give each plane its own depth/stencil copy instead of sharing this array.
      final int[] depth = src.depth(); // Read-only after bake; shared by every plane.
      // Faces with fewer bands than the global plane count reuse one all-transparent copy.
      ColourDepthTextureData empty = null;
      for (int k = 0; k < planeCount; k++) {
        if (k >= bands.length) {
          if (empty == null) {
            int[] colour = new int[srcColour.length];
            for (int i = 0; i < colour.length; i++) {
              colour[i] = srcColour[i] & 0x00FFFFFF;
            }
            empty = new ColourDepthTextureData(colour, depth, src.width(), src.height());
          }
          planes[k][f] = empty;
          continue;
        }
        final int low = bands[k];
        final boolean lastBand = k == bands.length - 1;
        final int high = lastBand ? 0 : bands[k + 1];
        int[] colour = new int[srcColour.length];
        for (int i = 0; i < colour.length; i++) {
          if (!wasPixelWritten(src, checkMode, i)) {
            colour[i] = srcColour[i] & 0x00FFFFFF;
            continue;
          }
          int d24 = depth[i] >>> 8;
          if (d24 >= low && (lastBand || d24 < high)) {
            colour[i] = srcColour[i];
          } else {
            // Keep rgb, drop alpha: non-band pixels must not render. Alpha is the absolute depth
            // written by encodeRootPixelDepth (do not make it band-relative).
            colour[i] = srcColour[i] & 0x00FFFFFF;
          }
        }
        planes[k][f] = new ColourDepthTextureData(colour, depth, src.width(), src.height());
      }
    }
    return planes;
  }

  private static float u2fdepth(int depth, boolean halfDepthRange) {
    float depthF = (float) ((double) depth / ((1 << 24) - 1));
    // https://registry.khronos.org/OpenGL-Refpages/gl4/html/glDepthRange.xhtml
    // due to this and the unsigned bullshit, the GL-baked depth value needs to get multiplied
    // by 2 (see computeDepth). The software bakery writes full-range depth and skips this.
    if (halfDepthRange) {
      depthF *= 2;
      if (depthF > 1.00001f) { // Basicly only happens when a model goes out of bounds (thing)
        depthF = 1.0f;
      }
    }
    return depthF;
  }

  /**
   * Encodes the per-pixel indentation of a root model into the texture alpha channel.
   *
   * <p>Written pixels become {@code 128 + round(depth * 127)} (7-bit depth in 0..1), transparent
   * pixels stay 0. The gl46 shader decodes this only for faces flagged with the per-pixel-depth bit
   * and displaces those fragments along the face normal; every other model keeps the old
   * single-indentation behaviour and its alpha semantics.
   */
  public static void encodeRootPixelDepth(ColourDepthTextureData[] faces, int checkMode) {
    for (int f = 0; f < faces.length; f++) {
      ColourDepthTextureData src = faces[f];
      int[] colour = src.colour().clone();
      int[] depth = src.depth();
      for (int i = 0; i < colour.length; i++) {
        int rgb = colour[i] & 0x00FFFFFF;
        if (!wasPixelWritten(src, checkMode, i)) {
          colour[i] = rgb;
          continue;
        }
        int d24 = depth[i] >>> 8;
        int enc = 128 + (int) (((long) d24 * 127L + (1 << 23)) >>> 24);
        colour[i] = rgb | (enc << 24);
      }
      faces[f] = new ColourDepthTextureData(colour, depth, src.width(), src.height());
    }
  }

  public static long[] generateMask(ColourDepthTextureData data, int checkMode) {
    return generateMask(data, checkMode, new long[data.width() * data.height() / 64]);
  }

  public static long[] generateMask(ColourDepthTextureData data, int checkMode, long[] outMsk) {
    Arrays.fill(outMsk, 0);
    int i = 0;
    for (int y = 0; y < data.height(); y++) {
      for (int x = 0; x < data.width(); x++) {
        if (wasPixelWritten(data, checkMode, i)) {
          outMsk[i / 64] |= 1L << (i & 63);
        }
        i++;
      }
    }
    return outMsk;
  }

  // NOTE: data goes from bottom left to top right (x first then y)
  public static int[] computeBounds(ColourDepthTextureData data, int checkMode) {
    // Compute x bounds first
    int minX = 0;
    minXCheck:
    do {
      for (int y = 0; y < data.height(); y++) {
        int idx = minX + (y * data.width());
        if (wasPixelWritten(data, checkMode, idx)) {
          break minXCheck; // pixel was written too so break from loop
        }
      }
      minX++;
    } while (minX != data.width());

    int maxX = data.width() - 1;
    maxXCheck:
    do {
      for (int y = data.height() - 1; y != -1; y--) {
        int idx = maxX + (y * data.width());
        if (wasPixelWritten(data, checkMode, idx)) {
          break maxXCheck; // pixel was written too so break from loop
        }
      }
      maxX--;
    } while (maxX != -1);
    // maxX++;

    // Compute y bounds
    int minY = 0;
    minYCheck:
    do {
      for (int x = 0; x < data.width(); x++) {
        int idx = (minY * data.height()) + x;
        if (wasPixelWritten(data, checkMode, idx)) {
          break minYCheck; // pixel was written too
        }
      }
      minY++;
    } while (minY != data.height());

    int maxY = data.height() - 1;
    maxYCheck:
    do {
      for (int x = data.width() - 1; x != -1; x--) {
        int idx = (maxY * data.height()) + x;
        if (wasPixelWritten(data, checkMode, idx)) {
          break maxYCheck; // pixel was written too so break from loop
        }
      }
      maxY--;
    } while (maxY != -1);
    // maxY++;

    return new int[] {minX, maxX, minY, maxY};
  }

  public static int mipColours(boolean darkend, int C00, int C01, int C10, int C11) {
    darkend = !darkend; // Invert to make it easier
    float r = 0.0f;
    float g = 0.0f;
    float b = 0.0f;
    float a = 0.0f;
    if (darkend || (C00 >>> 24) != 0) {
      r += ColorSRGB.srgbToLinear((C00 >> 0) & 0xFF);
      g += ColorSRGB.srgbToLinear((C00 >> 8) & 0xFF);
      b += ColorSRGB.srgbToLinear((C00 >> 16) & 0xFF);
      a += darkend ? (C00 >>> 24) : ColorSRGB.srgbToLinear(C00 >>> 24);
    }
    if (darkend || (C01 >>> 24) != 0) {
      r += ColorSRGB.srgbToLinear((C01 >> 0) & 0xFF);
      g += ColorSRGB.srgbToLinear((C01 >> 8) & 0xFF);
      b += ColorSRGB.srgbToLinear((C01 >> 16) & 0xFF);
      a += darkend ? (C01 >>> 24) : ColorSRGB.srgbToLinear(C01 >>> 24);
    }
    if (darkend || (C10 >>> 24) != 0) {
      r += ColorSRGB.srgbToLinear((C10 >> 0) & 0xFF);
      g += ColorSRGB.srgbToLinear((C10 >> 8) & 0xFF);
      b += ColorSRGB.srgbToLinear((C10 >> 16) & 0xFF);
      a += darkend ? (C10 >>> 24) : ColorSRGB.srgbToLinear(C10 >>> 24);
    }
    if (darkend || (C11 >>> 24) != 0) {
      r += ColorSRGB.srgbToLinear((C11 >> 0) & 0xFF);
      g += ColorSRGB.srgbToLinear((C11 >> 8) & 0xFF);
      b += ColorSRGB.srgbToLinear((C11 >> 16) & 0xFF);
      a += darkend ? (C11 >>> 24) : ColorSRGB.srgbToLinear(C11 >>> 24);
    }

    return ColorSRGB.linearToSrgb(
        r / 4, g / 4, b / 4, darkend ? ((int) a) / 4 : ARGB.linearToSrgbChannel(a / 4));
  }
}
