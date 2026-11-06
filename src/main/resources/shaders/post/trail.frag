#version 430 core
in vec2 uv;out vec4 color;
layout(binding=0) uniform sampler2D previousTrail;
uniform float dt;
void main(){color=texture(previousTrail,uv)*exp(-dt*1.5);}
