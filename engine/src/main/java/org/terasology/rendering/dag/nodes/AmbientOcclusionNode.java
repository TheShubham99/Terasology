/*
 * Copyright 2017 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering.dag.nodes;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.terasology.assets.ResourceUrn;
import org.terasology.assets.management.AssetManager;
import org.terasology.config.Config;
import org.terasology.config.RenderingConfig;
import org.terasology.context.Context;
import org.terasology.engine.SimpleUri;
import org.terasology.math.TeraMath;
import org.terasology.math.geom.Vector3f;
import org.terasology.monitoring.PerformanceMonitor;
import org.terasology.registry.CoreRegistry;
import org.terasology.rendering.assets.material.Material;
import org.terasology.rendering.assets.texture.Texture;
import org.terasology.rendering.assets.texture.TextureData;
import org.terasology.rendering.cameras.Camera;
import org.terasology.rendering.dag.ConditionDependentNode;
import org.terasology.rendering.dag.stateChanges.BindFbo;
import org.terasology.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.rendering.dag.stateChanges.SetViewportToSizeOf;
import org.terasology.rendering.nui.properties.Range;
import org.terasology.rendering.opengl.FBO;
import org.terasology.rendering.opengl.FBOConfig;
import org.terasology.rendering.opengl.FBOManagerSubscriber;
import org.terasology.rendering.opengl.fbms.DisplayResolutionDependentFBOs;
import org.terasology.rendering.world.WorldRenderer;
import org.terasology.utilities.Assets;
import org.terasology.utilities.random.FastRandom;
import org.terasology.utilities.random.Random;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Optional;

import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.terasology.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.DepthStencilTexture;
import static org.terasology.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.NormalsTexture;
import static org.terasology.rendering.opengl.OpenGLUtils.renderFullscreenQuad;
import static org.terasology.rendering.opengl.ScalingFactors.FULL_SCALE;

/**
 * Instances of this node work in tandem with instances of the BlurredAmbientOcclusionNode class.
 * Together they constitute an ambient occlusion pass.
 *
 * This particular node generates a first, sharper ambient occlusion output. Subsequently that's
 * used by the BlurredAmbientOcclusionNode to make it softer.
 *
 * At this stage only the output of BlurredAmbientOcclusionNode is used to enhance the image eventually
 * shown on screen to the user. It is currently not possible to use the sharper output produced by
 * this node alone, i.e. to have lower quality but faster ambient occlusions.
 *
 * Ambient occlusion is a subtle visual effect that makes the rendering of the world more pleasing
 * at the cost of some additional milliseconds per frame. Disabling it may lead to increased frame
 * rate while the gameplay remains unaffected.
 *
 * See http://en.wikipedia.org/wiki/Ambient_occlusion for more information on this technique.
 */
public class AmbientOcclusionNode extends ConditionDependentNode implements FBOManagerSubscriber {
    public static final SimpleUri SSAO_FBO_URI = new SimpleUri("engine:fbo.ssao");
    private static final ResourceUrn SSAO_MATERIAL_URN = new ResourceUrn("engine:prog.ssao");
    private static final float NOISE_TEXEL_SIZE = 0.25f;
    public static final int SSAO_KERNEL_ELEMENTS = 32;
    public static final int SSAO_NOISE_SIZE = 4;

    private Material ssaoMaterial;
    private float outputFboWidth;
    private float outputFboHeight;

    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.01f, max = 12.0f)
    private float ssaoStrength = 1.75f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.1f, max = 25.0f)
    private float ssaoRad = 1.5f;

    private FBO ssaoFbo;

    private Camera activeCamera;

    private final Random random = new FastRandom();

    private FloatBuffer ssaoSamples;

    public AmbientOcclusionNode(Context context) {
        super(context);

        activeCamera = context.get(WorldRenderer.class).getActiveCamera();

        RenderingConfig renderingConfig = context.get(Config.class).getRendering();
        renderingConfig.subscribe(RenderingConfig.SSAO, this);
        requiresCondition(renderingConfig::isSsao);

        addDesiredStateChange(new EnableMaterial(SSAO_MATERIAL_URN));
        ssaoMaterial = getMaterial(SSAO_MATERIAL_URN);

        DisplayResolutionDependentFBOs displayResolutionDependentFBOs = context.get(DisplayResolutionDependentFBOs.class);
        ssaoFbo = requiresFBO(new FBOConfig(SSAO_FBO_URI, FULL_SCALE, FBO.Type.DEFAULT), displayResolutionDependentFBOs);
        addDesiredStateChange(new BindFbo(ssaoFbo));
        addDesiredStateChange(new SetViewportToSizeOf(ssaoFbo));
        update(); // Cheeky way to initialise outputFboWidth, outputFboHeight
        displayResolutionDependentFBOs.subscribe(this);

        // TODO: check for input textures brought in by the material

        FBO lastUpdatedGBuffer = displayResolutionDependentFBOs.getGBufferPair().getLastUpdatedFbo();

        int texId = 0;
        addDesiredStateChange(new SetInputTextureFromFbo(texId++, lastUpdatedGBuffer, DepthStencilTexture, displayResolutionDependentFBOs, SSAO_MATERIAL_URN, "texDepth"));
        addDesiredStateChange(new SetInputTextureFromFbo(texId, lastUpdatedGBuffer, NormalsTexture, displayResolutionDependentFBOs, SSAO_MATERIAL_URN, "texNormals"));

        if (ssaoSamples == null) {
            ssaoSamples = BufferUtils.createFloatBuffer(SSAO_KERNEL_ELEMENTS * 3);

            for (int i = 0; i < SSAO_KERNEL_ELEMENTS; ++i) {
                Vector3f vec = new Vector3f();
                vec.x = random.nextFloat(-1.0f, 1.0f);
                vec.y = random.nextFloat(-1.0f, 1.0f);
                vec.z = random.nextFloat();

                vec.normalize();
                vec.scale(random.nextFloat(0.0f, 1.0f));
                float scale = i / (float) SSAO_KERNEL_ELEMENTS;
                scale = TeraMath.lerp(0.25f, 1.0f, scale * scale);

                vec.scale(scale);

                ssaoSamples.put(vec.x);
                ssaoSamples.put(vec.y);
                ssaoSamples.put(vec.z);
            }

            ssaoSamples.flip();
        }

        ssaoMaterial.setFloat3("ssaoSamples", ssaoSamples);
    }

    /**
     * If Ambient Occlusion is enabled in the render settings, this method generates and
     * stores the necessary images into their own FBOs. The stored images are eventually
     * combined with others.
     * <p>
     * For further information on Ambient Occlusion see: http://en.wikipedia.org/wiki/Ambient_occlusion
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/ambientOcclusion");

        Texture ssaoNoiseTexture = updateNoiseTexture();

        // TODO: Convert this to StateChange
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        glBindTexture(GL11.GL_TEXTURE_2D, ssaoNoiseTexture.getId());
        ssaoMaterial.setInt("texNoise", 2, true);

        ssaoMaterial.setFloat4("ssaoSettings", ssaoStrength, ssaoRad, 0.0f, 0.0f, true);

        ssaoMaterial.setMatrix4("invProjMatrix", activeCamera.getInverseProjectionMatrix(), true);
        ssaoMaterial.setMatrix4("projMatrix", activeCamera.getProjectionMatrix(), true);

        ssaoMaterial.setFloat2("texelSize", 1.0f / outputFboWidth, 1.0f / outputFboHeight, true);
        ssaoMaterial.setFloat2("noiseTexelSize", NOISE_TEXEL_SIZE, NOISE_TEXEL_SIZE, true);

        renderFullscreenQuad();

        PerformanceMonitor.endActivity();
    }

    @Override
    public void update() {
        outputFboWidth = ssaoFbo.width();
        outputFboHeight = ssaoFbo.height();
    }

    private Texture updateNoiseTexture() {
        // TODO: take advantage of Texture.subscribeToDisposal(Runnable) to reobtain the asset only if necessary
        Optional<Texture> texture = CoreRegistry.get(AssetManager.class).getAsset("engine:ssaoNoise", Texture.class);
        if (!texture.isPresent()) {
            ByteBuffer noiseValues = BufferUtils.createByteBuffer(SSAO_NOISE_SIZE * SSAO_NOISE_SIZE * 4);

            for (int i = 0; i < SSAO_NOISE_SIZE * SSAO_NOISE_SIZE; ++i) {
                Vector3f noiseVector = new Vector3f(random.nextFloat(-1.0f, 1.0f), random.nextFloat(-1.0f, 1.0f), 0.0f);
                noiseVector.normalize();

                noiseValues.put((byte) ((noiseVector.x * 0.5 + 0.5) * 255.0f));
                noiseValues.put((byte) ((noiseVector.y * 0.5 + 0.5) * 255.0f));
                noiseValues.put((byte) ((noiseVector.z * 0.5 + 0.5) * 255.0f));
                noiseValues.put((byte) 0x0);
            }

            noiseValues.flip();

            return Assets.generateAsset(new ResourceUrn("engine:ssaoNoise"), new TextureData(SSAO_NOISE_SIZE, SSAO_NOISE_SIZE,
                    new ByteBuffer[]{noiseValues}, Texture.WrapMode.REPEAT, Texture.FilterMode.NEAREST), Texture.class);
        }
        return texture.get();
    }
}
