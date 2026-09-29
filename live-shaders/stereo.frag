#version 330 core
uniform sampler2D worldColor;
uniform sampler2D worldDepth;
uniform sampler2D uiLayer;
uniform sampler2D finalColor;
uniform vec2 uiScale;
uniform vec2 sourceSize;
uniform vec2 cursor;
uniform float cursorVisible;
uniform float eye;
uniform float strength;
uniform float depthSpan;
uniform float convergence;
uniform int hasWorld;
in vec2 uv;
out vec4 color;

float referenceDepth() {
    // Robust central reference, avoiding dependence on any one moving character pixel.
    float v[9];
    for(int i=0;i<9;i++) {
        vec2 p=vec2(0.5)+vec2(float(i%3-1),float(i/3-1))*0.065;
        v[i]=texture(worldDepth,p).r;
    }
    for(int i=1;i<9;i++) for(int j=i;j>0;j--) {
        float a=min(v[j-1],v[j]), b=max(v[j-1],v[j]); v[j-1]=a; v[j]=b;
    }
    return v[4]+convergence*depthSpan;
}
float shiftAt(float x,float center) {
    float d=texture(worldDepth,vec2(clamp(x,0.0,1.0),uv.y)).r;
    if(d<=0.0 || d>=1.0) return 0.0;
    float maximum=strength/120.0;
    return eye*clamp((center-d)/depthSpan*2.0,-1.0,1.0)*maximum;
}
vec3 sceneColor() {
    vec3 final=texture(finalColor,uv).rgb;
    if(hasWorld==0 || strength<=0.0) return final;
    float center=referenceDepth();
    float best=uv.x, bestError=100.0, nearest=1.0;
    float maximum=strength/120.0;
    // Gather three inverse-warp candidates. Prefer nearer valid surfaces at overlaps.
    for(int seed=-1;seed<=1;seed++) {
        float x=uv.x+float(seed)*maximum;
        for(int i=0;i<6;i++) x=uv.x-shiftAt(x,center);
        x=clamp(x,0.5/sourceSize.x,1.0-0.5/sourceSize.x);
        float error=abs(x+shiftAt(x,center)-uv.x)*sourceSize.x;
        float d=texture(worldDepth,vec2(x,uv.y)).r;
        bool valid=error<1.2;
        if((valid && (bestError>=1.2 || d<nearest)) || (!valid && bestError>=1.2 && error<bestError)) {
            best=x; bestError=error; nearest=d;
        }
    }
    vec3 base=texture(worldColor,uv).rgb;
    vec3 warped=texture(worldColor,vec2(best,uv.y)).rgb;
    float alpha=clamp(texture(uiLayer,uv*uiScale).a,0.0,1.0);
    // Preserve the game's complete final image, changing only the world contribution.
    // For premultiplied UI, final = world*(1-alpha)+UI. The same UI stays in both eyes.
    return final+(warped-base)*(1.0-alpha);
}
void main() {
    // Draw the fixed-plane cursor in stereo, selected flat mode, and flat fallback.
    vec3 result=sceneColor();
    if(cursorVisible>0.5) {
        vec2 p=(uv-cursor)*sourceSize;
        // Fixed-plane pointer for native OS cursors absent from the framebuffer.
        float cross=max(float(abs(p.x)<1.0 && abs(p.y)<7.0),float(abs(p.y)<1.0 && abs(p.x)<7.0));
        float outline=max(float(abs(p.x)<2.0 && abs(p.y)<8.0),float(abs(p.y)<2.0 && abs(p.x)<8.0));
        result=mix(result,vec3(0),outline*0.8); result=mix(result,vec3(1),cross);
    }
    color=vec4(clamp(result,0.0,1.0),1);
}
