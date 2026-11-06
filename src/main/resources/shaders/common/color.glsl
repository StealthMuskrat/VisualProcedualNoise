vec3 srgbToLinear(vec3 c) { return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c)); }
vec3 linearToSrgb(vec3 c) { c=max(c,0.); return mix(c*12.92,1.055*pow(c,vec3(1./2.4))-.055,step(vec3(.0031308),c)); }
vec3 palette(float v) {
    v=.5+.5*sin(v*3.14159265);
    vec3 a,b,c,d;
    if(paletteIndex==0) { a=vec3(.025,.03,.10); b=vec3(.12,.62,.78); c=vec3(.68,.32,.80); d=vec3(.98,.82,.58); }
    else if(paletteIndex==1) { a=vec3(.02,.06,.05); b=vec3(.08,.46,.3); c=vec3(.62,.8,.38); d=vec3(1.,.88,.65); }
    else if(paletteIndex==2) { a=vec3(.04,.015,.09); b=vec3(.4,.08,.38); c=vec3(.96,.28,.14); d=vec3(1.,.86,.4); }
    else { a=vec3(.015,.03,.07); b=vec3(.06,.23,.5); c=vec3(.25,.65,.83); d=vec3(.85,.94,1.); }
    return srgbToLinear(v<.4?mix(a,b,v/.4):v<.75?mix(b,c,(v-.4)/.35):mix(c,d,(v-.75)/.25));
}
