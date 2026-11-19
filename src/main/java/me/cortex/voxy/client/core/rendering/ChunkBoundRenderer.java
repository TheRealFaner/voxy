package me.cortex.voxy.client.core.rendering;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.backend.gl46.pipeline.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

// This is a render subsystem, its very simple in what it does
// it renders an AABB around loaded chunks, thats it
public class ChunkBoundRenderer {
  private static final int INIT_MAX_CHUNK_COUNT = 1 << 12;
  private GlBuffer chunkPosBuffer = new GlBuffer(INIT_MAX_CHUNK_COUNT * 8); // Stored as ivec2
  private final GlBuffer uniformBuffer = new GlBuffer(128);
  private final Long2IntOpenHashMap chunk2idx = new Long2IntOpenHashMap(INIT_MAX_CHUNK_COUNT);
  private long[] idx2chunk = new long[INIT_MAX_CHUNK_COUNT];
  private final Shader rasterShader;

  private final LongOpenHashSet addQueue = new LongOpenHashSet();
  private final LongOpenHashSet remQueue = new LongOpenHashSet();

  // Sections currently sitting in a delayed-removal slot, so removeSection is idempotent (it can
  // be driven from multiple Sodium hooks for the same section without double-removing).
  private final LongOpenHashSet pendingRemoval = new LongOpenHashSet();

  // Delayed removal to prevent pop-out when a section is rebuilt: a queued removal is only moved
  // into remQueue after REMOVAL_DELAY_FRAMES frames (~200ms at 60fps), giving the LOD build
  // window time to prepare before the chunk bound disappears. Port of falling-colud/voxy-forged
  // ChunkBoundRenderer (delayQueueIndex ring + removeSectionImmediate).
  private static final int REMOVAL_DELAY_FRAMES = 12;
  private final LongArrayList[] delayedRemovalQueue = new LongArrayList[REMOVAL_DELAY_FRAMES];
  private int delayQueueIndex = 0;

  private final AbstractRenderPipeline pipeline;

  public ChunkBoundRenderer(AbstractRenderPipeline pipeline) {
    this.chunk2idx.defaultReturnValue(-1);

    for (int i = 0; i < REMOVAL_DELAY_FRAMES; i++) {
      this.delayedRemovalQueue[i] = new LongArrayList();
    }

    String vert = ShaderLoader.parse("voxy:chunkoutline/outline.vsh");
    String taa = pipeline.taaFunction("getTAA");
    if (taa != null) {
      this.pipeline = pipeline;
      vert = vert + "\n\n\n" + taa;
    } else {
      this.pipeline = null;
    }

    this.rasterShader =
        Shader.makeAuto()
            .addSource(ShaderType.VERTEX, vert)
            .defineIf("TAA", taa != null)
            .add(ShaderType.FRAGMENT, "voxy:chunkoutline/outline.fsh")
            .compile()
            .ubo(0, this.uniformBuffer)
            .ssbo(1, this.chunkPosBuffer);
  }

  public void addSection(long pos) {
    // A section coming back while its removal is still counting down cancels the removal: it
    // never left the bound, so there is nothing to remove.
    if (this.pendingRemoval.remove(pos)) {
      for (LongArrayList queue : this.delayedRemovalQueue) {
        if (queue.rem(pos)) {
          break;
        }
      }
      return;
    }
    this.remQueue.remove(pos);
    // Duplicate add: the section is already tracked (the main frame and a portal
    // manager can feed the same position, and remesh events can repeat). Re-adding
    // it is a no-op that only produced the "Chunk already in map" warning storm.
    if (this.chunk2idx.containsKey(pos)) {
      return;
    }
    this.addQueue.add(pos);
  }

  public void removeSection(long pos) {
    if (this.addQueue.remove(pos)) {
      return; // Was a pending add that never became live; just cancel it
    }
    // An immediate removal (section disposal) has already queued this section; the delayed path
    // must not re-park it or a second, spurious removal would hit remQueue 12 frames later.
    if (this.remQueue.contains(pos)) {
      return;
    }
    // Only sections actually being masked need removing. Sodium drives this from several places
    // (build-state change and section disposal), so ignore anything not currently tracked to
    // avoid spurious "Chunk not in map" churn, and dedupe via pendingRemoval.
    if (!this.chunk2idx.containsKey(pos)) {
      return;
    }
    if (this.pendingRemoval.add(pos)) {
      // Delayed removal instead of immediate: gives the LOD system time to prepare before the
      // chunk bound disappears (the transient build-state-change path).
      this.delayedRemovalQueue[this.delayQueueIndex].add(pos);
    }
  }

  // Removes a section from the occlusion bound on the next frame, skipping the delayed-removal
  // window. Use this when the section is actually disposed (chunk unloaded / left render
  // distance): the LOD is already prepared, so the ~200ms delay would only leave a see-through
  // gap. The delayed path is still used for transient build-state changes (rebuilds).
  public void removeSectionImmediate(long pos) {
    if (this.addQueue.remove(pos)) {
      return; // Was a pending add that never became live; just cancel it
    }
    if (!this.chunk2idx.containsKey(pos)) {
      return;
    }
    // Cancel any in-flight delayed removal so we don't process it twice.
    if (this.pendingRemoval.remove(pos)) {
      for (LongArrayList queue : this.delayedRemovalQueue) {
        if (queue.rem(pos)) {
          break;
        }
      }
    }
    this.remQueue.add(pos);
  }

  // Bind and render, changing as little gl state as possible so that the caller may configure how
  // it wants to render
  public void render(Viewport<?> viewport) {
    this.renderTo(viewport, true);
  }

  /**
   * Rasterizes the known chunk bounds into {@code viewport}. When {@code mutateQueues} is false the
   * pending add/remove queues are left untouched for the main pass (portal views;
   * PORTAL_VIEW_API.md §4.1): the viewport's own depth bound buffer is still cleared and
   * re-rasterized from the committed set so near-chunk masking stays correct, but a portal pass
   * never consumes main-frame queue state.
   */
  public void renderTo(Viewport<?> viewport, boolean mutateQueues) {
    if (mutateQueues) {
      // Delayed-removal ring: the slot ahead of the write head holds the oldest entries (parked
      // REMOVAL_DELAY_FRAMES renders ago); move them into remQueue and advance the head into that
      // slot. Portal passes (mutateQueues=false) skip all queue mutation - the ring does not
      // advance and no pending add/remove is consumed; they rasterize the committed set only.
      int oldestSlot = (this.delayQueueIndex + 1) % REMOVAL_DELAY_FRAMES;
      LongArrayList oldestQueue = this.delayedRemovalQueue[oldestSlot];
      if (!oldestQueue.isEmpty()) {
        for (int i = 0; i < oldestQueue.size(); i++) {
          long p = oldestQueue.getLong(i);
          this.remQueue.add(p);
          this.pendingRemoval.remove(p);
        }
        oldestQueue.clear();
      }
      this.delayQueueIndex = oldestSlot;

      if (!this.remQueue.isEmpty()) {
        boolean wasEmpty = this.chunk2idx.isEmpty();
        this.remQueue.forEach(this::_remPos); // TODO: REPLACE WITH SCATTER COMPUTE
        this.remQueue.clear();
        if (this.chunk2idx.isEmpty()
            && !wasEmpty) { // When going from stuff to nothing need to clear the depth buffer
          viewport.depthBoundingBuffer.clear(0);
        }
      }
    }

    if (this.chunk2idx.isEmpty() && (this.addQueue.isEmpty() || !mutateQueues)) {
      if (!mutateQueues) {
        viewport.depthBoundingBuffer.clear(0);
      }
      return;
    }

    viewport.depthBoundingBuffer.clear(0);

    long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
    long matPtr = ptr;
    ptr += 4 * 4 * 4;

    final float renderDistance =
        Minecraft.getInstance().options.getEffectiveRenderDistance() * 16; // In blocks

    { // This is recomputed to be in chunk section space not worldsection

      // Camera block pos
      int bx = (int) (viewport.cameraX);
      int by = (int) (viewport.cameraY);
      int bz = (int) (viewport.cameraZ);
      new Vector3i(bx, by, bz).getToAddress(ptr);
      ptr += 4 * 4;

      var negInnerBlock =
          new Vector3f(
              (float) (viewport.cameraX - bx),
              (float) (viewport.cameraY - by),
              (float) (viewport.cameraZ - bz));

      negInnerBlock.getToAddress(ptr);
      ptr += 4 * 3;
      viewport.MVP.translate(negInnerBlock.negate(), new Matrix4f()).getToAddress(matPtr);
      MemoryUtil.memPutFloat(ptr, renderDistance);
      ptr += 4;
    }
    UploadStream.INSTANCE.commit();

    {
      // need to reverse the winding order since we want the back faces of the AABB, not the front

      glFrontFace(GL_CW); // Reverse winding order

      // "reverse depth buffer" it goes from 0->1 where 1 is far away
      glEnable(GL_CULL_FACE);
      glEnable(GL_DEPTH_TEST);
      glDepthFunc(GL_GREATER);
    }

    glBindVertexArray(GlVertexArray.STATIC_VAO);
    viewport.depthBoundingBuffer.bind();
    this.rasterShader.bind();
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE_BB_BYTE.id());
    if (this.pipeline != null) this.pipeline.bindUniforms(); // shader TAA

    // Batch the draws into groups of size 32
    int count = this.chunk2idx.size();
    if (count >= 32) {
      glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3 * 32, GL_UNSIGNED_BYTE, 0, count / 32);
    }
    if (count % 32 != 0) {
      glDrawElementsInstancedBaseInstance(
          GL_TRIANGLES, 6 * 2 * 3 * (count % 32), GL_UNSIGNED_BYTE, 0, 1, (count / 32) * 32);
    }

    {
      glFrontFace(GL_CCW); // Restore winding order

      glDepthFunc(GL_LEQUAL);

      // TODO: check this is correct
      glEnable(GL_CULL_FACE);
      glEnable(GL_DEPTH_TEST);
    }

    if (mutateQueues && !this.addQueue.isEmpty()) {
      this.addQueue.forEach(this::_addPos); // TODO: REPLACE WITH SCATTER COMPUTE
      this.addQueue.clear();
      UploadStream.INSTANCE.commit();
    }
  }

  private void _remPos(long pos) {
    int idx = this.chunk2idx.remove(pos);
    if (idx == -1) {
      Logger.warn("Chunk not in map: " + pos);
      return;
    }
    if (idx == this.chunk2idx.size()) {
      // Dont need to do anything as heap is already compact
      return;
    }
    if (this.idx2chunk[idx] != pos) {
      throw new IllegalStateException();
    }

    // Move last entry on heap to this index
    long ePos =
        this.idx2chunk[this.chunk2idx.size()]; // since is already removed size is correct end idx
    if (this.chunk2idx.put(ePos, idx) == -1) {
      throw new IllegalStateException();
    }
    this.idx2chunk[idx] = ePos;

    // Put the end pos into the new idx
    this.put(idx, ePos);
  }

  private void _addPos(long pos) {
    if (this.chunk2idx.containsKey(pos)) {
      Logger.warn("Chunk already in map: " + pos);
      return;
    }
    this.ensureSize1(); // Resize if needed

    int idx = this.chunk2idx.size();
    this.chunk2idx.put(pos, idx);
    this.idx2chunk[idx] = pos;

    this.put(idx, pos);
  }

  private void ensureSize1() {
    if (this.chunk2idx.size() < this.idx2chunk.length) return;
    // Commit any copies, ensures is synced to new buffer
    UploadStream.INSTANCE.commit();

    int size = (int) (this.idx2chunk.length * 1.5);
    Logger.info("Resizing chunk position buffer to: " + size);
    // Need to resize
    var old = this.chunkPosBuffer;
    this.chunkPosBuffer = new GlBuffer(size * 8L);
    glCopyNamedBufferSubData(old.id, this.chunkPosBuffer.id, 0, 0, old.size());
    old.free();
    var old2 = this.idx2chunk;
    this.idx2chunk = new long[size];
    System.arraycopy(old2, 0, this.idx2chunk, 0, old2.length);
    // Replace the old buffer with the new one
    ((AutoBindingShader) this.rasterShader).ssbo(1, this.chunkPosBuffer);
  }

  private void put(int idx, long pos) {
    long ptr2 = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 8L * idx, 8);
    // Need to do it in 2 parts because ivec2 is 2 parts
    MemoryUtil.memPutInt(ptr2, (int) (pos & 0xFFFFFFFFL));
    ptr2 += 4;
    MemoryUtil.memPutInt(ptr2, (int) ((pos >>> 32) & 0xFFFFFFFFL));
  }

  public void reset() {
    this.chunk2idx.clear();
    // Drop the pending queues too: otherwise the next renderTo() would re-add sections that were
    // just reset away (or run _remPos against an emptied map).
    this.addQueue.clear();
    this.remQueue.clear();
    this.pendingRemoval.clear();
    for (LongArrayList queue : this.delayedRemovalQueue) {
      queue.clear();
    }
    // All slots are empty now, so rewinding the write head to 0 is safe and restores constructor
    // state; entries written after the reset then wait the full REMOVAL_DELAY_FRAMES window.
    this.delayQueueIndex = 0;
  }

  public void free() {
    this.rasterShader.free();
    this.uniformBuffer.free();
    this.chunkPosBuffer.free();
  }
}
