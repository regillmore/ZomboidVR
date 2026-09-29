package pzvr.live;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.*;

/** Compatibility state plus modern bindings touched by our offscreen pass. */
final class GlState implements AutoCloseable {
    final int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
    final int program = glGetInteger(GL_CURRENT_PROGRAM), vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
    final int active = glGetInteger(GL_ACTIVE_TEXTURE), renderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING);
    final int pack = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING), unpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
    final int[] textures = new int[4], samplers = new int[4];
    GlState() {
        glPushAttrib(GL_ALL_ATTRIB_BITS);
        glPushClientAttrib(GL_CLIENT_PIXEL_STORE_BIT);
        for (int i=0;i<4;i++) {
            glActiveTexture(GL_TEXTURE0+i);
            textures[i]=glGetInteger(GL_TEXTURE_BINDING_2D);
            samplers[i]=glGetInteger(GL_SAMPLER_BINDING);
            glBindSampler(i,0);
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER,0); glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
        glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST);
        glDisable(GL_SCISSOR_TEST); glDisable(GL_CULL_FACE); glDisable(GL_ALPHA_TEST);
        glDisable(GL_FRAMEBUFFER_SRGB); glDisable(GL_RASTERIZER_DISCARD); glDisable(GL_COLOR_LOGIC_OP);
        glColorMask(true,true,true,true); glDepthMask(false); glPolygonMode(GL_FRONT_AND_BACK,GL_FILL);
    }
    @Override public void close() {
        glBindFramebuffer(GL_READ_FRAMEBUFFER,read); glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);
        glBindRenderbuffer(GL_RENDERBUFFER,renderbuffer);
        glUseProgram(program); glBindVertexArray(vao);
        for(int i=0;i<4;i++) { glActiveTexture(GL_TEXTURE0+i); glBindTexture(GL_TEXTURE_2D,textures[i]); glBindSampler(i,samplers[i]); }
        glActiveTexture(active);
        glBindBuffer(GL_PIXEL_PACK_BUFFER,pack); glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpack);
        glPopClientAttrib(); glPopAttrib();
    }
}
