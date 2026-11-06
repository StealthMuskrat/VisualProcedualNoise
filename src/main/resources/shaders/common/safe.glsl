// Explicit safe helpers. Ordinary / keeps ordinary GLSL semantics.
float safeDiv(float a,float b){return abs(b)<1e-6?0.:a/b;}
vec2 safeDiv(vec2 a,vec2 b){return vec2(safeDiv(a.x,b.x),safeDiv(a.y,b.y));}
vec3 safeDiv(vec3 a,vec3 b){return vec3(safeDiv(a.x,b.x),safeDiv(a.y,b.y),safeDiv(a.z,b.z));}
vec4 safeDiv(vec4 a,vec4 b){return vec4(safeDiv(a.x,b.x),safeDiv(a.y,b.y),safeDiv(a.z,b.z),safeDiv(a.w,b.w));}
float sqrtSafe(float a){return sqrt(max(a,0.));}
vec2 sqrtSafe(vec2 a){return sqrt(max(a,0.));}vec3 sqrtSafe(vec3 a){return sqrt(max(a,0.));}vec4 sqrtSafe(vec4 a){return sqrt(max(a,0.));}
float powSafe(float a,float b){return pow(max(a,1e-6),clamp(b,-16.,16.));}
vec2 powSafe(vec2 a,vec2 b){return pow(max(a,vec2(1e-6)),clamp(b,-16.,16.));}
vec3 powSafe(vec3 a,vec3 b){return pow(max(a,vec3(1e-6)),clamp(b,-16.,16.));}
vec4 powSafe(vec4 a,vec4 b){return pow(max(a,vec4(1e-6)),clamp(b,-16.,16.));}
float normalizeSafe(float a){return abs(a)<1e-6?0.:sign(a);}
vec2 normalizeSafe(vec2 a){return a/max(length(a),1e-6);}vec3 normalizeSafe(vec3 a){return a/max(length(a),1e-6);}vec4 normalizeSafe(vec4 a){return a/max(length(a),1e-6);}
