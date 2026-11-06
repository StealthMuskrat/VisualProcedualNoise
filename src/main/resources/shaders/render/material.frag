#version 430 core
in vec2 uv;
out vec4 color;
uniform vec2 viewport,motionPhase;
uniform uint seed;
uniform float scale,warp,detail,height,exposure,colorPhase,time,role;
uniform int recipe,paletteIndex,debugView;
uniform vec4 pointer;
#include "../common/noise.glsl"
#include "../common/color.glsl"
float field(vec2 p) {
    vec2 drift=vec2(cos(motionPhase.x),sin(motionPhase.x));
    vec2 q=vec2(fbm(vec3(p*.7+drift,.4)),fbm(vec3(p*.7-drift,8.1)));
    vec2 r=vec2(fbm(vec3(p+warp*q,3.2+sin(motionPhase.y))),fbm(vec3(p+warp*q,9.8+cos(motionPhase.y))));
    return fbm(vec3(p+warp*q+detail*r,cos(motionPhase.x)));
}
void main() {
    vec2 p=(uv*2.-1.)*vec2(viewport.x/viewport.y,1.);
    p+=pointer.w*.08*exp(-dot(p-pointer.xy,p-pointer.xy)/(pointer.z*pointer.z))*vec2(sin(motionPhase.x),cos(motionPhase.x));
    p*=scale;
    if(recipe==4) { float a=atan(p.y,p.x),r=length(p); a=abs(mod(a,1.04719755)-.52359878); p=r*vec2(cos(a),sin(a)); }
    float f=field(p),cell=cellular(p).x;
    f=mix(f,cell-.5,role);
    if(recipe==1) { float phase=f*32.; float aa=max(fwidth(phase),.01); f=.8-smoothstep(.08,.08+aa,abs(sin(phase))); }
    vec2 grad=vec2(dFdx(f),dFdy(f))*viewport.y*.35;
    vec3 normal=normalize(vec3(-grad*height,1.));
    vec3 light=normalize(vec3(-.4,.6,1.));
    float diffuse=.3+.7*max(dot(normal,light),0.);
    float spec=pow(max(dot(reflect(-light,normal),vec3(0,0,1)),0.),32.);
    vec3 linear=palette(f*1.8+colorPhase)*diffuse+spec*.24;
    if(debugView==1)linear=vec3(f*.5+.5);
    if(debugView==8)linear=normal*.5+.5;
    linear=1.-exp(-linear*exposure);
    color=vec4(linearToSrgb(linear),1);
}
