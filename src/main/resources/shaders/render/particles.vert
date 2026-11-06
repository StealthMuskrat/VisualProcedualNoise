#version 430 core
struct Particle {vec4 state;uvec4 identity;};
layout(std430,binding=0) readonly buffer Particles {Particle particles[];};
uniform float aspect;
out float age;
void main(){vec4 s=particles[gl_VertexID].state;gl_Position=vec4(s.xy/vec2(aspect,1.),0,1);gl_PointSize=1.6;age=s.z/max(s.w,.001);}
