package pl.smjetanka_.intellium.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Minimal shader program wrapper using OpenGL 3.3-compatible calls.
 */
public final class IntelTerrainProgram implements AutoCloseable {
    private static final String VERTEX_SHADER_PATH = "intellium:shaders/intellium_block.vert";
    private static final String FRAGMENT_SHADER_PATH = "intellium:shaders/intellium_block.frag";

    private int programId;
    private int projectionLocation;
    private int modelViewLocation;
    private int cameraLocation;
    private int fogColorLocation;
    private int fogStartLocation;
    private int fogEndLocation;
    private int fogShapeLocation;
    private int alphaCutoffLocation;
    private int cutoutPassLocation;
    private int blockAtlasLocation;
    private int lightMapLocation;

    public IntelTerrainProgram() {
        programId = createProgram();
        findUniforms();
    }

    private int createProgram() {
        int vertexShader = 0;
        int fragmentShader = 0;
        try {
            vertexShader = compileShader(VERTEX_SHADER_PATH, GL20.GL_VERTEX_SHADER);
            fragmentShader = compileShader(FRAGMENT_SHADER_PATH, GL20.GL_FRAGMENT_SHADER);

            int program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertexShader);
            GL20.glAttachShader(program, fragmentShader);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(program);
                GL20.glDeleteProgram(program);
                throw new IllegalStateException("Shader program linking failed: " + log);
            }
            return program;
        } finally {
            if (vertexShader != 0) {
                GL20.glDeleteShader(vertexShader);
            }
            if (fragmentShader != 0) {
                GL20.glDeleteShader(fragmentShader);
            }
        }
    }

    private int compileShader(String path, int type) {
        String source;
        Identifier location = Identifier.parse(path);
        try (InputStream stream = Minecraft.getInstance().getResourceManager()
                .getResource(location)
                .orElseThrow(() -> new IOException("Missing shader resource: " + path))
                .open()) {
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load shader " + path, exception);
        }

        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Shader compilation failed for " + path + ": " + log);
        }
        return shader;
    }

    private void findUniforms() {
        GL20.glUseProgram(programId);
        projectionLocation = GL20.glGetUniformLocation(programId, "uProjectionMatrix");
        modelViewLocation = GL20.glGetUniformLocation(programId, "uModelViewMatrix");
        cameraLocation = GL20.glGetUniformLocation(programId, "uCameraPosition");
        fogColorLocation = GL20.glGetUniformLocation(programId, "uFogColor");
        fogStartLocation = GL20.glGetUniformLocation(programId, "uFogStart");
        fogEndLocation = GL20.glGetUniformLocation(programId, "uFogEnd");
        fogShapeLocation = GL20.glGetUniformLocation(programId, "uFogShape");
        alphaCutoffLocation = GL20.glGetUniformLocation(programId, "uAlphaCutoff");
        cutoutPassLocation = GL20.glGetUniformLocation(programId, "uIsCutoutPass");
        blockAtlasLocation = GL20.glGetUniformLocation(programId, "uBlockAtlas");
        lightMapLocation = GL20.glGetUniformLocation(programId, "uLightMap");
        if (blockAtlasLocation >= 0) {
            GL20.glUniform1i(blockAtlasLocation, 0);
        }
        if (lightMapLocation >= 0) {
            GL20.glUniform1i(lightMapLocation, 1);
        }
        GL20.glUseProgram(0);
    }

    public void setup(float alphaCutoff, boolean cutoutPass) {
        GL20.glUseProgram(programId);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer projection = stack.mallocFloat(16);
            float fov = Minecraft.getInstance().options.fov().get().floatValue();
            Minecraft.getInstance().gameRenderer.getProjectionMatrix(fov).get(projection).flip();
            if (projectionLocation >= 0) {
                GL20.glUniformMatrix4fv(projectionLocation, false, projection);
            }

            FloatBuffer modelView = stack.mallocFloat(16);
            RenderSystem.getModelViewMatrix().get(modelView).flip();
            if (modelViewLocation >= 0) {
                GL20.glUniformMatrix4fv(modelViewLocation, false, modelView);
            }
        }

        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        if (cameraLocation >= 0) {
            GL20.glUniform3f(cameraLocation, (float) camera.x, (float) camera.y, (float) camera.z);
        }
        if (fogColorLocation >= 0) {
            GL20.glUniform4f(fogColorLocation, 0.0f, 0.0f, 0.0f, 1.0f);
        }
        if (fogStartLocation >= 0) {
            GL20.glUniform1f(fogStartLocation, 0.0f);
        }
        if (fogEndLocation >= 0) {
            GL20.glUniform1f(fogEndLocation, 1.0f);
        }
        if (fogShapeLocation >= 0) {
            GL20.glUniform1i(fogShapeLocation, 0);
        }
        if (alphaCutoffLocation >= 0) {
            GL20.glUniform1f(alphaCutoffLocation, alphaCutoff);
        }
        if (cutoutPassLocation >= 0) {
            GL20.glUniform1i(cutoutPassLocation, cutoutPass ? 1 : 0);
        }
    }

    void use() {
        GL20.glUseProgram(programId);
    }

    public boolean verifyCompactChunkVertexLayout() {
        return programId != 0 && GL30.glGetInteger(GL30.GL_MAJOR_VERSION) >= 3;
    }

    int getCutoutPassLocation() {
        return cutoutPassLocation;
    }

    int getProjectionLocation() {
        return projectionLocation;
    }

    int getModelViewLocation() {
        return modelViewLocation;
    }

    int getAlphaCutoffLocation() {
        return alphaCutoffLocation;
    }

    public void teardown() {
        GL20.glUseProgram(0);
    }

    public void delete() {
        close();
    }

    @Override
    public void close() {
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
            programId = 0;
        }
    }
}
