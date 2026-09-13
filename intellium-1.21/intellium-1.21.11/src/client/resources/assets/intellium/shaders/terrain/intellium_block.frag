#version 330 core

in vec4 vColor;
in vec2 vTexCoord;
in vec2 vLightCoord;
in vec3 vWorldPos;

uniform sampler2D uBlockAtlas;
uniform sampler2D uLightMap;
uniform vec4 uFogColor;
uniform vec3 uCameraPosition;
uniform float uFogStart;
uniform float uFogEnd;
uniform int uFogShape;
uniform float uAlphaCutoff;
uniform int uIsCutoutPass;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(uBlockAtlas, vTexCoord) * vColor;
    color *= texture(uLightMap, vLightCoord);

    if (uIsCutoutPass != 0 && color.a < uAlphaCutoff) {
        discard;
    }

    float distanceToCamera = uFogShape == 0
        ? distance(vWorldPos, uCameraPosition)
        : distance(vWorldPos.xz, uCameraPosition.xz);
    float fogFactor = 1.0 - clamp(
        (distanceToCamera - uFogStart) / max(uFogEnd - uFogStart, 0.001),
        0.0,
        1.0
    );
    fragColor = mix(uFogColor, color, fogFactor);
}
