#version 430 core
in vec2 uv;out vec4 color;
layout(binding=0) uniform sampler2D scene;
void main() {
    vec2 e=1./vec2(textureSize(scene,0));vec3 c=vec3(0);
    for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++)c+=max(texture(scene,uv+vec2(x,y)*e*2.).rgb-.65,0.);
    color=vec4(c/9.,1);
}
