// Original gradient-lattice noise implementation. Quintic interpolation keeps
// value and first derivatives continuous. Range is conservatively [-1,1].
uint hashWord(uint n) { n^=n>>16; n*=0x7feb352du; n^=n>>15; n*=0x846ca68bu; return n^(n>>16); }
uint hashCell(ivec3 p) { return hashWord(uint(p.x)^hashWord(uint(p.y))^hashWord(uint(p.z))^seed); }
vec3 gradientAt(ivec3 p) {
    uint h=hashCell(p); vec3 g=vec3(float(h&1023u),float((h>>10)&1023u),float((h>>20)&1023u))/511.5-1.;
    return normalize(g+vec3(0.001));
}
float noise3d(vec3 p) {
    ivec3 cell=ivec3(floor(p)); vec3 f=fract(p),u=f*f*f*(f*(f*6.-15.)+10.);
    float v[8];
    for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
        ivec3 o=ivec3(x,y,z); v[x+2*y+4*z]=dot(gradientAt(cell+o),f-vec3(o));
    }
    return mix(mix(mix(v[0],v[1],u.x),mix(v[2],v[3],u.x),u.y),mix(mix(v[4],v[5],u.x),mix(v[6],v[7],u.x),u.y),u.z)*1.6;
}
float fbm(vec3 p) {
    float sum=0.,a=.5,norm=0.;
    for(int i=0;i<5;i++) { sum+=a*noise3d(p); norm+=a; p=p*2.03+vec3(7.1,3.4,1.7); a*=.5; }
    return sum/norm;
}
vec3 cellular(vec2 p) {
    vec2 cell=floor(p),f=fract(p); float f1=8.,f2=8.,id=0.;
    for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
        vec2 o=vec2(x,y); uint h=hashCell(ivec3(cell+o,0));
        vec2 site=vec2(float(h&65535u),float(h>>16))/65535.;
        float d=length(o+site-f);
        if(d<f1) { f2=f1; f1=d; id=float(h&1023u)/1023.; } else f2=min(f2,d);
    }
    return vec3(f1,f2,id);
}
