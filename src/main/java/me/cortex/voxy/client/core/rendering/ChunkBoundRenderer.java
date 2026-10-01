package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.compat.SodiumExtra;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;

// Rasterizes native section bounds.
public class ChunkBoundRenderer {
    private static final int INIT_MAX_CHUNK_COUNT = 1<<12;
    private GlBuffer chunkPosBuffer = new GlBuffer(INIT_MAX_CHUNK_COUNT*8);// Stored as ivec2
    private final GlBuffer uniformBuffer = new GlBuffer(128);
    private final Long2IntOpenHashMap chunk2idx = new Long2IntOpenHashMap(INIT_MAX_CHUNK_COUNT);
    private long[] idx2chunk = new long[INIT_MAX_CHUNK_COUNT];
    private final Shader rasterShader;
    private final LongArrayList visibleSections = new LongArrayList();
    private GlBuffer visibleChunkPosBuffer;
    private int[] membershipScratch = new int[0];

    private final LongOpenHashSet addQueue = new LongOpenHashSet();
    private final LongOpenHashSet remQueue = new LongOpenHashSet();

    private final AbstractRenderPipeline pipeline;
    public ChunkBoundRenderer(AbstractRenderPipeline pipeline) {
        this.chunk2idx.defaultReturnValue(-1);
        this.pipeline = pipeline;

        String vert = ShaderLoader.parse("voxy:chunkoutline/outline.vsh");
        String taa = pipeline.taaFunction("getTAA");
        if (taa != null) {
            vert = vert+"\n\n\n"+taa;
        }
        this.rasterShader = Shader.makeAuto()
                .addSource(ShaderType.VERTEX, vert)
                .defineIf("TAA", taa != null)
                .defineIf("USE_SODIUM_EXTRA_CULLING", SodiumExtra.useSodiumExtraCulling())
                .add(ShaderType.FRAGMENT, "voxy:chunkoutline/outline.fsh")
                .compile()
                .ubo(0, this.uniformBuffer)
                .ssbo(1, this.chunkPosBuffer);
    }

    public void addSection(long pos) {
        if (!this.remQueue.remove(pos)) {
            this.addQueue.add(pos);
        }
    }

    public void removeSection(long pos) {
        if (!this.addQueue.remove(pos)) {
            this.remQueue.add(pos);
        }
    }

    // Bind and render, changing as little gl state as possible so that the caller may configure how it wants to render
    public void render(Viewport<?> viewport, ChunkRenderListIterable renderLists) {
        if (!this.remQueue.isEmpty()) {
            boolean wasEmpty = this.chunk2idx.isEmpty();
            this.remQueue.forEach(this::_remPos);// TODO: REPLACE WITH SCATTER COMPUTE
            this.remQueue.clear();
            if (this.chunk2idx.isEmpty()&&!wasEmpty) {// When going from stuff to nothing need to clear the depth buffer
                viewport.depthBoundingBuffer.clear(0);
        viewport.nativeCoverageRadius = 0;
            }
        }

        // Include new sections this frame, not the next.
        if (!this.addQueue.isEmpty()) {
            this.addQueue.forEach(this::_addPos);
            this.addQueue.clear();
            UploadStream.INSTANCE.commit();
        }
        // Also clear after reset/last removal, including every active viewport.
        viewport.depthBoundingBuffer.clear(0);
        viewport.nativeCoverageRadius = 0;
        // Use Sodium's visible sections, including any modded distance rules.
        int count;
        if (renderLists != null) {
            this.visibleSections.clear();
            NativeSectionCollector.collect(renderLists, VoxyCommon.IS_MINE_IN_ABYSS, this.visibleSections::add);
            count = this.visibleSections.size();
            if (count == 0) { viewport.nativeSectionTableSize = 0; return; }
            long size = count * 8L;
            if (this.visibleChunkPosBuffer == null || this.visibleChunkPosBuffer.size() < size) {
                UploadStream.INSTANCE.commit();
                if (this.visibleChunkPosBuffer != null) this.visibleChunkPosBuffer.free();
                this.visibleChunkPosBuffer = new GlBuffer(Math.max(INIT_MAX_CHUNK_COUNT * 8L, size * 2));
            }
            long positions = UploadStream.INSTANCE.upload(this.visibleChunkPosBuffer, 0, size);
            for (int i = 0; i < count; i++) {
                long pos = this.visibleSections.getLong(i);
                MemoryUtil.memPutInt(positions + i * 8L, (int) pos);
                MemoryUtil.memPutInt(positions + i * 8L + 4, (int) (pos >>> 32));
            }
        } else {
            count = this.chunk2idx.size();
            if (count == 0) { viewport.nativeSectionTableSize = 0; return; }
        }

        if (renderLists == null) {
            this.visibleSections.clear();
            for (int i = 0; i < count; i++) this.visibleSections.add(this.idx2chunk[i]);
        }
        double radiusSquared = 0;
        for (int i = 0; i < this.visibleSections.size(); i++) {
            long key = this.visibleSections.getLong(i);
            double x = Math.abs(SectionPos.x(key)*16.0 + 8 - viewport.cameraX) + 8;
            double y = Math.abs(SectionPos.y(key)*16.0 + 8 - viewport.cameraY) + 8;
            double z = Math.abs(SectionPos.z(key)*16.0 + 8 - viewport.cameraZ) + 8;
            radiusSquared = Math.max(radiusSquared, x*x + y*y + z*z);
        }
        // Leave room for view bobbing and section-exit depth tolerance.
        viewport.nativeCoverageRadius = (float)Math.sqrt(radiusSquared) + 64;
        this.uploadMembership(viewport);

        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        long matPtr = ptr; ptr += 4*4*4;

        final float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance()*16;// In blocks

        {// This is recomputed to be in chunk section space not worldsection
            int sx = (int)(viewport.cameraX);
            int sy = (int)(viewport.cameraY);
            int sz = (int)(viewport.cameraZ);
            new Vector3i(sx, sy, sz).getToAddress(ptr);
            MemoryUtil.memPutInt(ptr + 12, renderLists != null ? 1 : 0);
            ptr += 4*4;

            var negInnerSec = new Vector3f(
                    (float) (viewport.cameraX - sx),
                    (float) (viewport.cameraY - sy),
                    (float) (viewport.cameraZ - sz));

            negInnerSec.getToAddress(ptr); ptr += 4*3;
            viewport.MVP.translate(negInnerSec.negate(), new Matrix4f()).getToAddress(matPtr);
            MemoryUtil.memPutFloat(ptr, renderDistance); ptr += 4;
        }
        UploadStream.INSTANCE.commit();

        {
            // need to reverse the winding order since we want the back faces of the AABB, not the front

            glFrontFace(GL_CW);// Reverse winding order

            //"reverse depth buffer" it goes from 0->1 where 1 is far away
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_GREATER);
        }

        glBindVertexArray(GlVertexArray.STATIC_VAO);
        viewport.depthBoundingBuffer.bind();
        this.rasterShader.bind();
        if (renderLists != null) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.visibleChunkPosBuffer.id);
        }
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE_BB_BYTE.id());
        this.pipeline.bindUniforms();

        // Batch the draws into groups of size 32
        if (count >= 32) {
            glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3 * 32, GL_UNSIGNED_BYTE, 0, count/32);
        }
        if (count%32 != 0) {
            glDrawElementsInstancedBaseInstance(GL_TRIANGLES, 6 * 2 * 3 * (count%32), GL_UNSIGNED_BYTE, 0, 1, (count/32)*32);
        }

        {
            glFrontFace(GL_CCW);// Restore winding order

            glDepthFunc(GL_LEQUAL);

            // TODO: check this is correct
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
        }

    }

    private void uploadMembership(Viewport<?> viewport) {
        // Fade-off leaves need actual section membership, not a ray's furthest box.
        if (!(this.pipeline instanceof NormalRenderPipeline)
                || VoxyConfig.CONFIG.borderFade) {
            viewport.nativeSectionTableSize = 0;
            return;
        }
        int capacity = 1;
        while (capacity < this.visibleSections.size()*2) capacity <<= 1;
        if (this.membershipScratch.length < capacity*4) this.membershipScratch = new int[capacity*4];
        int[] table = this.membershipScratch;
        java.util.Arrays.fill(table, 0, capacity*4, 0);
        for (int i = 0; i < this.visibleSections.size(); i++) {
            long key = this.visibleSections.getLong(i);
            int x = SectionPos.x(key), y = SectionPos.y(key), z = SectionPos.z(key);
            int hash = x*73856093 ^ y*19349663 ^ z*83492791;
            int slot = hash & (capacity-1);
            while (table[slot*4+3] != 0) {
                if (table[slot*4] == x && table[slot*4+1] == y && table[slot*4+2] == z) break;
                slot = (slot+1)&(capacity-1);
            }
            table[slot*4] = x; table[slot*4+1] = y; table[slot*4+2] = z; table[slot*4+3] = 1;
        }
        long bytes = capacity*16L;
        if (viewport.nativeSectionMembership == null || viewport.nativeSectionMembership.size() < bytes) {
            UploadStream.INSTANCE.commit();
            if (viewport.nativeSectionMembership != null) viewport.nativeSectionMembership.free();
            viewport.nativeSectionMembership = new GlBuffer(bytes);
        }
        long ptr = UploadStream.INSTANCE.upload(viewport.nativeSectionMembership, 0, bytes);
        for (int i = 0; i < capacity*4; i++) MemoryUtil.memPutInt(ptr+i*4L, table[i]);
        viewport.nativeSectionTableSize = capacity;
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
        long ePos = this.idx2chunk[this.chunk2idx.size()];// since is already removed size is correct end idx
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
        this.ensureSize1();// Resize if needed

        int idx = this.chunk2idx.size();
        this.chunk2idx.put(pos, idx);
        this.idx2chunk[idx] = pos;

        this.put(idx, pos);
    }

    private void ensureSize1() {
        if (this.chunk2idx.size() < this.idx2chunk.length) return;
        // Commit any copies, ensures is synced to new buffer
        UploadStream.INSTANCE.commit();

        int size = (int) (this.idx2chunk.length*1.5);
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
        ((AutoBindingShader)this.rasterShader).ssbo(1, this.chunkPosBuffer);
    }

    private void put(int idx, long pos) {
        long ptr2 = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 8L*idx, 8);
        // Need to do it in 2 parts because ivec2 is 2 parts
        MemoryUtil.memPutInt(ptr2, (int)(pos&0xFFFFFFFFL)); ptr2 += 4;
        MemoryUtil.memPutInt(ptr2, (int)((pos>>>32)&0xFFFFFFFFL));
    }

    public void reset() {
        this.chunk2idx.clear();
        this.addQueue.clear();
        this.remQueue.clear();
    }

    public void free() {
        this.rasterShader.free();
        this.uniformBuffer.free();
        this.chunkPosBuffer.free();
        if (this.visibleChunkPosBuffer != null) this.visibleChunkPosBuffer.free();
    }
}
