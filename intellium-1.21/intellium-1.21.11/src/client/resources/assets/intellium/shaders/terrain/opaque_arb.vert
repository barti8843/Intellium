#version 330 core

layout(location = 0) in uint aPackedPos0;
layout(location = 1) in uint aPackedPos1;
layout(location = 2) in uint aPackedColor;
layout(location = 3) in uint aPackedTex;
layout(location = 4) in uint aPackedLightMaterial;

uniform mat4 uProjectionMatrix;
uniform mat4 uModelViewMatrix;

out vec4 vColor;
out vec2 vTexCoord;
out vec2 vLightCoord;
out vec3 vWorldPos;
flat out uint vMaterial;

void main() {
    uvec3 positionQuantized = (
        uvec3(aPackedPos0 >> 0u, aPackedPos0 >> 10u, aPackedPos0 >> 20u) & 0x3FFu
    ) << 10u;
    positionQuantized |= uvec3(aPackedPos1 >> 0u, aPackedPos1 >> 10u, aPackedPos1 >> 20u) & 0x3FFu;
    vec3 aPosition = vec3(positionQuantized) * (32.0 / 1048576.0) - 8.0;

    vec4 aColor = vec4(
        float(aPackedColor & 0xFFu),
        float((aPackedColor >> 8u) & 0xFFu),
        float((aPackedColor >> 16u) & 0xFFu),
        float((aPackedColor >> 24u) & 0xFFu)
    ) / 255.0;
    vec2 aTexCoord = vec2(aPackedTex & 0x7FFFu, (aPackedTex >> 16u) & 0x7FFFu) / 32768.0;
    vec2 aLightCoord = vec2(
        float(aPackedLightMaterial & 0xFFu),
        float((aPackedLightMaterial >> 8u) & 0xFFu)
    ) / 256.0;
    uint material = (aPackedLightMaterial >> 16u) & 0xFFu;

    vColor = aColor;
    vTexCoord = aTexCoord;
    vLightCoord = aLightCoord;
    vWorldPos = aPosition;
    vMaterial = material;
    gl_Position = uProjectionMatrix * uModelViewMatrix * vec4(aPosition, 1.0);
}
