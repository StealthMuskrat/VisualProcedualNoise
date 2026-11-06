#version 430 core
in vec2 uv;out vec4 color;
layout(binding=0) uniform sampler2D scene;
layout(binding=1) uniform sampler2D glow;
uniform float exposure,bloom;
uniform int paletteIndex,debugView;
#include "../common/color.glsl"
void main() {
    vec3 c=texture(scene,uv).rgb;
    if(debugView!=10)c=1.-exp(-(c+texture(glow,uv).rgb*bloom)*exposure);
    color=vec4(linearToSrgb(c),1);
}
