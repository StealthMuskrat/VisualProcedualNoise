// Original bounded artistic variant inspired by Lagae et al. (2009), Eq. 6.
// Two hashed signed impulses per cell, not a Poisson process. A C1 taper makes
// radius-2 support exact. This is unfiltered 2D noise, not research spectral parity.
float gabor(vec2 p,float orientation,float principalFrequency,float bandwidth) {
    if(any(isnan(p))||any(isinf(p))||isnan(orientation)||isinf(orientation)
        ||isnan(principalFrequency)||isinf(principalFrequency)||isnan(bandwidth)||isinf(bandwidth))
        return uintBitsToFloat(0x7fc00000u);
    float a=clamp(bandwidth,.25,4.);
    float frequency=clamp(principalFrequency,0.,16.);
    float angle=mod(orientation,6.28318530718);
    vec2 direction=vec2(cos(angle),sin(angle));
    vec2 q=clamp(p,vec2(-262144.),vec2(262144.))*a;
    ivec2 cell=ivec2(floor(q));
    vec2 local=fract(q);
    float sum=0.;
    for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++) {
        ivec2 offset=ivec2(x,y);
        uint base=hashCell(ivec3(cell+offset,1195463250));
        for(uint i=0u;i<2u;i++) {
            uint hx=hashWord(base^((i+1u)*0x9e3779b9u));
            uint hy=hashWord(hx^0x68bc21ebu);
            vec2 site=vec2(float(hx>>8),float(hy>>8))*(1./16777216.);
            vec2 d=vec2(offset)+site-local;
            float r2=dot(d,d);
            if(r2<4.) {
                float weight=(hashWord(hy^0x02e5be93u)&1u)==0u?-1.:1.;
                float envelope=exp(-3.14159265359*r2)*(1.-smoothstep(2.25,4.,r2));
                sum+=weight*envelope*cos(6.28318530718*(frequency/a)*dot(d,direction));
            }
        }
    }
    return .5*sum;
}
