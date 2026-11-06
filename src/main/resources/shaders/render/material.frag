#version 430 core
in vec2 uv;
out vec4 color;
uniform vec2 viewport,motionPhase;
uniform uint seed;
uniform float scale,warp,detail,height,exposure,colorPhase,time,role;
uniform int recipe,paletteIndex,debugView,feedbackEnabled,pigmentEnabled;
layout(binding=1) uniform sampler2D flowTexture;
layout(binding=2) uniform sampler2D pigmentTexture;
layout(binding=3) uniform sampler2D feedbackTexture;
uniform vec4 pointer;
uniform int reactionEnabled,particlesEnabled,historyCount,historyHead,historyLayers;
uniform float historyMix;
layout(binding=4) uniform sampler2DArray historyTexture;
layout(binding=5) uniform sampler2D chemicalTexture;
layout(binding=6) uniform sampler2D ageTexture;
layout(binding=7) uniform sampler2D trailTexture;
#include "../common/noise.glsl"
#include "../common/color.glsl"
float filteredFbm(vec3 p) {
    float sum=0.,a=.5,frequency=1.;
    float footprint=2.*scale/viewport.y*(1.+warp*6.+detail*8.);
    for(int i=0;i<5;i++) {sum+=a*noise3d(p)*(1.-smoothstep(.25,.65,footprint*frequency));p=p*2.03+vec3(7.1,3.4,1.7);frequency*=2.03;a*=.5;}
    return sum/.96875;
}
float field(vec2 p) {
    vec2 drift=vec2(cos(motionPhase.x),sin(motionPhase.x));
    vec2 q=vec2(filteredFbm(vec3(p*.7+drift,.4)),filteredFbm(vec3(p*.7-drift,8.1)));
    vec2 r=vec2(filteredFbm(vec3(p+warp*q,3.2+sin(motionPhase.y))),filteredFbm(vec3(p+warp*q,9.8+cos(motionPhase.y))));
    return filteredFbm(vec3(p+warp*q+detail*r,cos(motionPhase.x)));
}
void main() {
    vec2 p=(uv*2.-1.)*vec2(viewport.x/viewport.y,1.);
    p+=pointer.w*.08*exp(-dot(p-pointer.xy,p-pointer.xy)/(pointer.z*pointer.z))*vec2(sin(motionPhase.x),cos(motionPhase.x));
    p*=scale;
    if(recipe==4) { float a=atan(p.y,p.x),r=length(p); a=abs(mod(a,1.04719755)-.52359878); p=r*vec2(cos(a),sin(a)); }
    if(recipe==9)p*=vec2(1.+.35*sin(p.y+motionPhase.x),1.+.3*cos(p.x));
    if(recipe==12) {float portal=smoothstep(.62,.58,length(p));p=mix(p,p*2.+vec2(3.,1.),portal);}
    float f=field(p),cell=cellular(p).x;
    if(recipe==7)f=field(p+vec2(f,field(p*.4))*1.5);
    if(recipe==13)f=mix(f,cell-.5,.5+.5*sin(motionPhase.y));
    if(recipe==14)f=field(p+vec2(sin(texture(ageTexture,uv).r*.2),cos(texture(ageTexture,uv).r*.2))*.4);
    if(recipe==10)f+=.2*sin(p.x*6.+motionPhase.x)*sin(p.y*7.-motionPhase.y);
    f=mix(f,cell-.5,role);
    if(recipe==1) { float phase=f*32.; float aa=max(fwidth(phase),.01); f=.8-smoothstep(.08,.08+aa,abs(sin(phase))); }
    vec2 grad=vec2(dFdx(f),dFdy(f))*viewport.y*.35;
    vec3 normal=normalize(vec3(-grad*height,1.));
    vec3 light=normalize(vec3(-.4,.6,1.));
    float diffuse=.3+.7*max(dot(normal,light),0.);
    float spec=pow(max(dot(reflect(-light,normal),vec3(0,0,1)),0.),32.);
    vec3 linear=palette(f*1.8+colorPhase)*diffuse+spec*.24;
    if(pigmentEnabled!=0) {
        vec3 pigment=texture(pigmentTexture,uv).rgb;
        linear=mix(linear,palette(pigment.g-pigment.r+colorPhase),1.-exp(-dot(pigment,vec3(.5))));
    }
    if(reactionEnabled!=0) {
        vec2 chemical=texture(chemicalTexture,uv).rg;
        float boundary=cellular(p*.7).y-cellular(p*.7).x;
        linear=palette(chemical.y*3.+boundary*.3+colorPhase)*(.25+chemical.y*2.)+vec3(.02,.08,.05)*chemical.x;
    }
    if(particlesEnabled!=0)linear=linear*.08+texture(trailTexture,uv).rgb;
    if((recipe==8||recipe==5)&&historyCount>0) {
        float delay=clamp((f*.5+.5)*float(historyCount-1),0.,float(historyCount-1));
        int a=(historyHead-int(floor(delay))+historyLayers)%historyLayers;
        int b=(historyHead-int(ceil(delay))+historyLayers)%historyLayers;
        vec3 old=mix(texture(historyTexture,vec3(uv,float(a))).rgb,texture(historyTexture,vec3(uv,float(b))).rgb,fract(delay));
        linear=mix(linear,old,historyMix);
    }
    if(feedbackEnabled!=0)linear=mix(linear,texture(feedbackTexture,uv).rgb,.65);
    if(debugView==2)linear=vec3(cellular(p).z);
    if(debugView==3)linear=vec3(texture(flowTexture,uv).xy*.5+.5,.5);
    if(debugView==6)linear=texture(pigmentTexture,uv).rgb;
    if(debugView==4)linear=vec3(texture(chemicalTexture,uv).r);
    if(debugView==5)linear=vec3(texture(chemicalTexture,uv).g);
    if(debugView==7)linear=vec3(log(1.+texture(ageTexture,uv).r)/6.9);
    if(debugView==9)linear=vec3(float(historyCount)/float(historyLayers));
    if(debugView==1)linear=vec3(f*.5+.5);
    if(debugView==8)linear=normal*.5+.5;
    color=vec4(max(linear,0.),1);
}
