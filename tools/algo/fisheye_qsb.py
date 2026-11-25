"""Edits of the QSB fisheye shaders' main() (GLSL ES 1.00, spirv-cross output). The helpers fyS / fyB / fyIn and the
fisheye functions come from fisheye_glsl.py; QSB_PRELUDE defines fySz / fySB / fyTB / fyV at the start of main()."""
import re

from fisheye_glsl import QSB_PRELUDE


def patch_qsb_main(glsl: str) -> str:
    assert glsl.count("void main()\n{\n") == 1
    glsl = glsl.replace("void main()\n{\n", "void main()\n{\n" + QSB_PRELUDE, 1)

    # sampling: the circle is computed on the padded frame, the picture is read back through fyIn
    glsl, n = re.subn(
        r"vec2 (param\w*) = vTexCoord;\n\s*mediump vec2 (param_\d+) = vec2\(ubuf\.imageWidth, ubuf\.imageHeight\);\n\s*"
        r"vec2 (_\d+) = fishEyeCalcCoords\(\1, \2\);\n\s*vec2 newUV = \3;\n(\s*mediump vec4 _\d+;\n)\s*if \(newUV\.x == 1\.0\)"
        r"(.*?)texture2D\((\w+), \3\)",
        lambda m: (
            "vec2 nB = fishEyeCalcCoords(fyTB, fySB);\n"
            "vec2 newUV = fyPad ? fyIn(nB, fySz) : nB;\n"
            + m.group(4).lstrip()
            + "if (nB.x == 1.0 || newUV.x < 0.0 || newUV.x > 1.0 || newUV.y < 0.0 || newUV.y > 1.0)"
            + m.group(5)
            + "texture2D(" + m.group(6) + ", newUV)"
        ),
        glsl, count=1, flags=re.S,
    )
    assert n == 1, "main fisheye sampling"

    # proMist layers start from the padded coordinate and read it with the rim not mirrored
    glsl, n = re.subn(
        r"mix\(vTexCoord, vec2\(vTexCoord\.x, 1\.0 - vTexCoord\.y\), vec2\(float\(ubuf\.proMistLayerflipY\)\)\)",
        "mix(fyV, vec2(fyV.x, 1.0 - fyV.y), vec2(float(ubuf.proMistLayerflipY)))", glsl, count=1)
    assert n == 1, "proMist base"
    glsl, n = re.subn(
        r"(mediump vec2 param_\d+) = vec2\(ubuf\.imageWidth, ubuf\.imageHeight\);(\n\s*_\d+ = fishEyeCalcCoordsR\()",
        r"\1 = fySB;\2", glsl, count=1)
    assert n == 1, "proMist size"

    # edge colour and blend
    glsl, n = re.subn(r"texture2D\(fishEyeEdgeSampler, vTexCoord\)",
                      "texture2D(fishEyeEdgeSampler, clamp(fyPad ? fyIn(fyTB, fySz) : fyTB, 0.0, 1.0))", glsl, count=1)
    assert n == 1, "edge sampler"
    glsl, n = re.subn(
        r"vec2 (param_\d+) = vTexCoord;(\n\s*mediump vec2 param_\d+) = vec2\(ubuf\.imageWidth, ubuf\.imageHeight\);(\n\s*vec3 _\d+ = fishEyeWithEdge\()",
        r"vec2 \1 = fyTB;\2 = fySB;\3", glsl, count=1)
    assert n == 1, "edge blend"
    return glsl
