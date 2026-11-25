"""GLSL text of the fisheye port (newer ROM's algorithm, rear ultra-wide values; focalLength below 1.6, type 0).

The newer ROM crops a fisheye capture by pad -> render -> trim: CopyEffect CropType 1 draws the (4:3) input unscaled into
a frame whose short edge is 7/6 as long (black bars), the fisheye circle is inscribed in that frame, CropType 2 trims the
bars again. The circle is therefore 7/6 of the picture's short edge and its sides are cut. fyB / fyS / fyIn do the same
in coordinates, so the direct GL route renders it without the copies (the copies and the graphics pipeline do not suit the
filters that call RenderEffectYuvGps themselves).
"""

CALC_COORDS_GLSL = """highp float smootherstep01(highp float x){x=clamp(x,0.,1.);return x*x*x*(x*(x*6.-15.)+10.);}
highp vec2 fishEyeCalcCoordsR(highp vec2 v,mediump vec2 ts,mediump int needReflect){
mediump float scale=.6;
highp float a=max(ts.x,ts.y)/min(ts.x,ts.y);
highp vec2 s=ts.x>=ts.y?vec2(a,.97):vec2(.97,a);
highp vec2 u=(v*scale-vec2(.5)*scale)*s;
highp float dist=length(u);
mediump float cr=.5*scale;
if(dist>cr)return vec2(1.);
highp float nd=dist/.5;
highp float nw=nd;
if(nd>0.){
highp float r2=nd*nd;
highp float ef=clamp((nd-.4)/.6,0.,1.);
highp float ee=nd>.4?1.2*(ef*ef+ef*ef*ef):0.;
highp float tot=(r2+r2*nd)*.8+ee*.2;
highp float bt=smoothstep(.7,1.,clamp(dist/cr,0.,1.));
highp float b2=bt*bt;
tot+=.4*(.4*b2+.6*b2*b2);
nw=nd*(1.+tot);
if(needReflect>0){float t=smootherstep01((nw-.77)/.12);nw=max(mix(nw,1.66-nw,t),0.);}else{nw=min(nw,.94);}
}
highp vec2 dir=dist>.000001?u/dist:vec2(0.);
return clamp(dir*nw*.5/s+vec2(.5),0.,1.);
}
highp vec2 fishEyeCalcCoords(highp vec2 v,mediump vec2 ts){return fishEyeCalcCoordsR(v,ts,1);}
highp vec2 fyS(highp vec2 s){return s.x>=s.y?vec2(s.x,s.y*1.1666667):vec2(s.x*1.1666667,s.y);}
highp vec2 fyB(highp vec2 t,highp vec2 s){return s.x>=s.y?vec2(t.x,.5+(t.y-.5)*.85714287):vec2(.5+(t.x-.5)*.85714287,t.y);}
"""

EDGE_BLEND_GLSL = """vec3 fishEyeWithEdge(mediump vec3 c,mediump vec3 e,highp vec2 tc,mediump vec2 ts){
float a=max(ts.x,ts.y)/min(ts.x,ts.y);
vec2 s=ts.x>=ts.y?vec2(a,.97):vec2(.97,a);
float d=length((tc-vec2(.5))*s)*2.;
e*=min(1.,1.-smoothstep(.85,1.,d)*.7);
return mix(c,e,smoothstep(.85,.945,d));
}
highp vec2 fyIn(highp vec2 b,highp vec2 s){return s.x>=s.y?vec2(b.x,.5+(b.y-.5)*1.1666667):vec2(.5+(b.x-.5)*1.1666667,b.y);}
"""

QSB_EDGE_BLEND_GLSL = """vec3 fishEyeWithEdge(inout mediump vec3 currentColor, mediump vec3 edgeColor, vec2 texCoord, mediump vec2 _textureSize)
{
float a=max(_textureSize.x,_textureSize.y)/min(_textureSize.x,_textureSize.y);
vec2 s=_textureSize.x>=_textureSize.y?vec2(a,.97):vec2(.97,a);
float d=length((texCoord-vec2(.5))*s)*2.;
edgeColor*=min(1.,1.-smoothstep(.85,1.,d)*.7);
currentColor=mix(currentColor,edgeColor,vec3(smoothstep(.85,.945,d)));
return currentColor;
}
highp vec2 fyIn(highp vec2 b,highp vec2 s){return s.x>=s.y?vec2(b.x,.5+(b.y-.5)*1.1666667):vec2(.5+(b.x-.5)*1.1666667,b.y);}
"""

# RenderEffectYuvGps shader (COMPOUND_YUV_FISHEYE variant): (old text, new text); every old text occurs once.
YUV_DECL = "uniform mediump sampler2D fishEyeEdgeSampler;\n"
YUV_DECL_NEW = YUV_DECL + "uniform mediump sampler2D fishEyeBlackSampler;\n"

YUV_REPLACEMENTS = [
    (
        "highp vec2 newUV = fishEyeCalcCoords(texCoord, vec2(imageWidth, imageHeight));\nif (newUV.x == 1.0) {\n",
        "highp vec2 sz = vec2(imageWidth, imageHeight);\n"
        "highp vec2 sB = fyS(sz);\n"
        "highp vec2 tB = fyB(texCoord, sz);\n"
        "highp vec2 nB = fishEyeCalcCoords(tB, sB);\n"
        "highp vec2 newUV = fyIn(nB, sz);\n"
        "if (nB.x == 1.0 || newUV.x < 0. || newUV.x > 1. || newUV.y < 0. || newUV.y > 1.) {\n",
    ),
    (
        "coords = fishEyeCalcCoords(coords,  vec2(imageWidth, imageHeight));",
        "coords = fyIn(fishEyeCalcCoords(tB, sB), sz);",
    ),
    (
        "layerCoord = fishEyeCalcCoords(layerCoord,  vec2(imageWidth, imageHeight));",
        "layerCoord = fishEyeCalcCoordsR(mix(tB, vec2(tB.x, 1.0 - tB.y), float(proMistLayerflipY)), sB, 0);",
    ),
    (
        "mediump vec4 fishEdgeColor = texture(fishEyeEdgeSampler, vec2(texCoord.x, 1.f - texCoord.y));\n"
        "currentColor.rgb = fishEyeWithEdge(currentColor.rgb, fishEdgeColor.rgb, texCoord, vec2(imageWidth, imageHeight));\n"
        "mediump float aspectRatio = imageWidth > imageHeight ? (imageWidth / imageHeight) : (imageHeight / imageWidth);\n"
        "mediump vec2 aspectCorrectedUV = (texCoord - vec2(0.5, 0.5)) * (imageWidth > imageHeight ? vec2(aspectRatio, 1.0) : vec2(1.0, aspectRatio));\n"
        "mediump float dist = length(aspectCorrectedUV);\n"
        "if (dist > 0.5) {\n"
        "currentColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
        "}\n",
        "highp vec2 eU = clamp(fyIn(tB, sz), 0., 1.);\n"
        "mediump vec4 fishEdgeColor = texture(fishEyeEdgeSampler, vec2(eU.x, 1.f - eU.y));\n"
        "currentColor.rgb = fishEyeWithEdge(currentColor.rgb, fishEdgeColor.rgb, tB, sB);\n"
        "vec4 b=texture(fishEyeBlackSampler,(tB-vec2(.5))*(sB.x>sB.y?vec2(sB.x/sB.y*.75,1.):vec2(.75,sB.y/sB.x))+.5);\n"
        "currentColor.rgb=mix(currentColor.rgb,b.rgb,b.a);\n",
    ),
]

# Same coordinates for the graphics pipeline (QSB, GLSL ES 1.00): preview, thumbnails and the filters that reach the
# compound pass through DoProcessEffects (LightFlow). fyV: the coordinate the proMist layers start from.
QSB_PRELUDE = (
    "bool fyPad = ubuf.fishEyeOn == 1;\n"
    "vec2 fySz = vec2(ubuf.imageWidth, ubuf.imageHeight);\n"
    "vec2 fySB = fyPad ? fyS(fySz) : fySz;\n"
    "vec2 fyTB = fyPad ? fyB(vTexCoord, fySz) : vTexCoord;\n"
    "vec2 fyV = ubuf.fishEyeOn > 0 ? fyTB : vTexCoord;\n"
)
