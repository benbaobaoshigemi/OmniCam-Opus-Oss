"""Photon GXUP0008 Fuji adapter (kind 11): asset packing and reference."""
import json
import struct
import numpy as np
from pathlib import Path

STYLES = ["provia", "velvia", "astia", "classic_chrome", "classic_negative", "nostalgic_neg", "reala_ace"]

def pack(assets, out):
    root = json.loads((assets/"fuji/gxup0008/profiles.json").read_text())
    assert root["format_version"] == 2 and set(root["profiles"]) == set(STYLES)
    for style in STYLES:
        p = root["profiles"][style]
        forward = np.array(p["rgb_to_ycc_coefficients9"]).reshape(3,3)/1024
        c = np.array(p["chroma_coefficients4"])/1024
        op = np.array([[1,0,0],[0,c[2],c[3]],[0,c[1],c[0]]])
        color = np.linalg.inv(forward) @ op @ forward
        edges = np.r_[0,np.cumsum(2.0**(np.array(p["tables31_shared_controls"])+5))]
        assert edges[-1] == 16384 and p["lut_node_transform"] == 0
        cube = np.fromfile(assets/f"fuji/gxup0008/lut-{p['lut_index']}.bin","<u2").reshape(18,18,18,4)
        assert np.all(cube[17] == 0) and np.all(cube[:,17] == 0) and np.all(cube[:,:,17] == 0)
        values = np.r_[np.array(p["curve40"])/512,np.array(p["tables31"]).ravel()/16384,edges,
                       np.array(p["coefficients9"])/1024,color.ravel(),forward[0],cube[:17,:17,:17,:3].ravel()/4095]
        assert np.isfinite(values).all()
        (out/f"fuji_{style}.bin").write_bytes(b"MCOLLOOK"+struct.pack("<II",1,11)+values.astype("<f4").tobytes())

def decode(kind,b):
    assert kind == 11
    v=np.frombuffer(b,"<f4",offset=16).astype(float)
    assert len(v) == 40+93+11+9+9+3+17**3*3
    return dict(tone=v[:40],curves=v[40:133].reshape(3,31),edges=v[133:144],
                matrix=v[144:153].reshape(3,3),color=v[153:162].reshape(3,3),luma=v[162:165],cube=v[165:].reshape(17,17,17,3))

def bezier(values,edges,x):
    k=np.clip(np.searchsorted(edges,x,side="right")-1,0,len(edges)-2)
    t=(x-edges[k])/(edges[k+1]-edges[k]); s=1-t
    return values[k*3]*s**3+3*values[k*3+1]*t*s*s+3*values[k*3+2]*t*t*s+values[k*3+3]*t**3

def render(kind,p,pp,cct):
    import looks as L
    assert kind == 11
    if True:
        rgb=pp @ (np.linalg.inv(L.SRGB_TO_XYZ)@L.cat(L.D50,L.D65)@L.PROPHOTO_TO_XYZ).T
        lum=rgb@p["luma"]
        edges=np.array([0,16,32,64,128,256,512,1024,2048,4096,6144,8192,12288,16384])
        x=np.clip(lum*16384,0,16384)
        output=x/16384*bezier(p["tone"],edges,x)
        gain=np.divide(output,lum,out=np.ones_like(lum),where=lum>0)
        mixed=(rgb*gain[:,None])@p["matrix"].T
        encoded=np.stack([bezier(p["curves"][i],p["edges"],np.clip(mixed[:,i]*16384,0,16384)) for i in range(3)],axis=1)
        code=np.clip(encoded@p["color"].T,0,1)*4095
        base=np.minimum(np.floor(code/256).astype(int),15)
        frac=(code-base*256)/np.where(base==15,255,256)
        encoded=np.zeros_like(code)
        for z in range(2):
            for y in range(2):
                for x in range(2):
                    weight=np.prod(np.where(np.array([x,y,z]),frac,1-frac),axis=1)
                    encoded+=weight[:,None]*p["cube"][base[:,2]+z,base[:,1]+y,base[:,0]+x]
    lin=np.where(encoded<=0.04045,encoded/12.92,((encoded+0.055)/1.055)**2.4)
    return np.clip(lin@L.SRGB_TO_P3.T,0,1)
