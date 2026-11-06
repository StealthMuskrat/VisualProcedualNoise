#version 430 core
in float age;out vec4 color;
uniform int paletteIndex;uniform float colorPhase;
#include "../common/color.glsl"
void main(){float a=exp(-dot(gl_PointCoord-.5,gl_PointCoord-.5)*10.);color=vec4(palette(age+colorPhase)*a*.09,a);}
