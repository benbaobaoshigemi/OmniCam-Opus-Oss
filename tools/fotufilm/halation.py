"""Halation kernel of a film on its support: fotufilm-engine Halation.swift, solved once per stock for the data file.

Light the emulsion did not absorb crosses the base, reflects off its back surface (Fresnel, total internal reflection past
the critical angle) and exposes the emulsion again, displaced. The returned light's edge response is fitted with three
centered Gaussians shared by the three records; each record mixes them with its own weights. Everything is in millimetres
on the film, so the kernel does not depend on the render's resolution.
"""
import math

import numpy as np

SCALE_RATIO = 1.7
BOUNCES = 5
ANGLES = 256


class Optics:
    def __init__(self, index):
        n = max(index, 1.001)
        self.index = n
        step = math.pi / 2 / ANGLES
        theta = (np.arange(ANGLES) + 0.5) * step
        self.cos = np.cos(theta)
        self.sin = np.sin(theta)
        sin_out = n * self.sin
        ok = sin_out < 1
        cos_out = np.sqrt(np.clip(1 - sin_out ** 2, 0, None))
        s = (n * self.cos - cos_out) / (n * self.cos + cos_out)
        p = (n * cos_out - self.cos) / (n * cos_out + self.cos)
        self.rs = np.where(ok, s * s, 1.0)
        self.rp = np.where(ok, p * p, 1.0)
        self.lambertian = 2 * self.cos * self.sin
        self.step = step


def survival(t, cos):
    e = math.log(max(t, 1e-30)) / np.maximum(cos, 1e-6)
    return np.where(e < -80, 0.0, np.exp(np.maximum(e, -80)))


def returned_fraction(t, o):
    per = survival(t, o.cos)
    so_far = per.copy()
    total = np.zeros(ANGLES)
    for m in range(1, BOUNCES + 1):
        k = 2 * m - 1
        rear = 0.5 * (o.rs ** k + o.rp ** k)
        total += rear * so_far
        so_far = so_far * per
    total = np.where(per > 0, total, 0.0)
    return float(np.sum(total * o.lambertian) * o.step)


def transmittance(fraction, o):
    if fraction <= 0:
        return 0.0
    low, high = 1e-6, 0.999
    if returned_fraction(high, o) <= fraction:
        return high
    for _ in range(28):
        mid = math.sqrt(low * high)
        if returned_fraction(mid, o) < fraction:
            low = mid
        else:
            high = mid
    return math.sqrt(low * high)


class Profile:
    """Legacy profile: shape inferred from the returned fraction."""

    def __init__(self, fraction, thickness, o):
        self.halo_radius = thickness * 2 * math.tan(math.asin(1 / o.index))
        t = transmittance(fraction, o)
        per = survival(t, o.cos)
        surv = per.copy()
        radii, weights, blocks = [], [], [0]
        total = 0.0
        for m in range(1, BOUNCES + 1):
            reach = 2 * m * thickness
            k = 2 * m - 1
            rear = 0.5 * (o.rs ** k + o.rp ** k)
            w = rear * surv * o.lambertian
            radii.append(reach * o.sin / o.cos)
            weights.append(w)
            blocks.append(blocks[-1] + ANGLES)
            bt = float(w.sum())
            surv = surv * per
            total += bt
            if bt < total * 1e-3:
                break
        self.radii = np.concatenate(radii)
        self.weights = np.concatenate(weights)
        if total > 0:
            self.weights = self.weights / total

    def edge(self, d):
        r, w = self.radii, self.weights
        mask = (r > d) & (w > 0)
        return float(np.sum(w[mask] * np.arccos(np.minimum(d / r[mask], 1.0))) / math.pi)

    def anchor_sigma(self):
        if not np.any(self.weights > 0):
            return 0.0
        low, high = 0.1 * self.halo_radius, 30 * self.halo_radius
        for _ in range(14):
            mid = math.sqrt(low * high)
            if self.edge(mid) > 0.25:
                low = mid
            else:
                high = mid
        return math.sqrt(low * high) / 0.6744897


class PhysicalProfile:
    """Independently calibrated profile (HalationProfile)."""

    def __init__(self, thickness, depth, o, optical_depth, exponent, diffuse_share, diffuse_sigma, retention):
        layer = thickness + max(depth, 0)
        radii, weights = [], []
        total = 0.0
        for b in range(1, BOUNCES + 1):
            angular = (exponent + 1) * o.cos ** exponent * o.sin
            pe = -b * optical_depth / np.maximum(o.cos, 1e-6)
            path = np.where(pe < -80, 0.0, np.exp(np.maximum(pe, -80)))
            rear = 0.5 * (o.rs ** b + o.rp ** b)
            repeats = 1.0 if b == 1 else retention ** (b - 1)
            w = angular * path * rear * repeats
            bt = float(w.sum())
            total += bt
            radii.append(2 * b * layer * o.sin / o.cos)
            weights.append(w)
            if b > 1 and bt < total * 1e-3:
                break
        self.radii = np.concatenate(radii)
        self.weights = np.concatenate(weights)
        if total > 0:
            self.weights = self.weights / total
        self.share = diffuse_share
        self.sigma = diffuse_sigma

    def edge(self, d):
        r, w = self.radii, self.weights
        mask = r > d
        reflex = float(np.sum(w[mask] * np.arccos(np.minimum(d / r[mask], 1.0))) / math.pi)
        return self.share * gaussian_edge(d, self.sigma) + (1 - self.share) * reflex


def gaussian_edge(d, sigma):
    return 0.5 * math.erfc(d / (sigma * math.sqrt(2.0)))


def simplex_fit(basis, target):
    """Least squares over the simplex w >= 0, sum w = 1 (every face, as Halation.simplexFit)."""
    basis = np.asarray(basis, float)
    target = np.asarray(target, float)
    count = len(basis)
    best, best_res = np.zeros(count), math.inf
    for mask in range(1, 1 << count):
        active = [i for i in range(count) if mask & (1 << i)]
        w = np.zeros(count)
        last = active[-1]
        free = active[:-1]
        if not free:
            w[last] = 1
        else:
            a = np.stack([basis[j] - basis[last] for j in free], 1)
            rhs = target - basis[last]
            try:
                sol = np.linalg.solve(a.T @ a, a.T @ rhs)
            except np.linalg.LinAlgError:
                continue
            if np.any(sol < -1e-6) or sol.clip(0).sum() > 1 + 1e-6:
                continue
            for j, v in zip(free, sol):
                w[j] = max(v, 0)
            w[last] = 1 - w.sum()
        res = float(np.sum((w @ basis - target) ** 2))
        if res < best_res:
            best_res, best = res, w
    return best


def mix_matrix(mix, ret):
    out = np.zeros((3, 3))
    for r in range(3):
        row = None
        if ret is not None and len(ret) == 3 and len(ret[r]) == 3:
            row = np.maximum(np.asarray(ret[r], float), 0)
        if row is None or row.sum() <= 0:
            out[r, r] = mix[r]
        else:
            out[r] = mix[r] * row / row.sum()
    return out


def kernel(returned, thickness, indices, profile=None, haze=0.0, return_matrix=None):
    """Returns (sigma_mm[3], weights[3][3] record x scale, matrix[3][3] receiver x source)."""
    optics = [Optics(i) for i in indices]
    if profile is None:
        profiles = [Profile(returned[l], thickness, optics[l]) for l in range(3)]
        anchor = max(max(p.anchor_sigma() for p in profiles), 1e-6)
        sigmas = [anchor / SCALE_RATIO, anchor, anchor * SCALE_RATIO]
        low, high = 0.02 * sigmas[0], 8 * sigmas[2]
        dist = [0.0] + list(np.exp(np.linspace(math.log(low), math.log(high), 40)))
        basis = [[gaussian_edge(d, s) for d in dist] for s in sigmas]
        weights = [simplex_fit(basis, [p.edge(d) for d in dist]) for p in profiles]
    else:
        depth = profile.get("recordDepthMM") or [0, 0, 0]
        profiles = [PhysicalProfile(thickness, depth[l], optics[l], max(profile["roundTripOpticalDepth"][l], 0),
                                    max(profile["angularExponent"][l], 0), min(max(profile["diffuseShare"][l], 0), 1),
                                    max(profile["diffuseSigmaMM"][l], 1e-6), min(max(profile["bounceRetention"][l], 0), 1))
                    for l in range(3)]
        radius = max(2 * (thickness + max(depth[l], 0)) * math.tan(math.asin(1 / max(indices[l], 1.001))) for l in range(3))
        radius = max(radius, 1e-6)
        dist = [k / 160 * 4 * radius for k in range(161)] + [4 * radius * math.exp(s / 48 * math.log(4)) for s in range(1, 49)]
        targets = [[p.edge(d) for d in dist] for p in profiles]
        cands = [radius * math.exp(math.log(0.02) + t / 13 * (math.log(4) - math.log(0.02))) for t in range(14)]
        cands += [p.sigma for p in profiles if p.share > 0]
        cands.sort()
        uniq = []
        for c in cands:
            if not uniq or abs(uniq[-1] - c) > 1e-6 * radius:
                uniq.append(c)
        cb = [[gaussian_edge(d, s) for d in dist] for s in uniq]
        best = (math.inf, None, None)
        n = len(uniq)
        for a in range(n - 2):
            for b in range(a + 1, n - 1):
                for c in range(b + 1, n):
                    basis = [cb[a], cb[b], cb[c]]
                    ws = [simplex_fit(basis, t) for t in targets]
                    res = sum(float(np.sum((w @ np.asarray(basis) - np.asarray(t)) ** 2)) for w, t in zip(ws, targets))
                    if res < best[0]:
                        best = (res, [uniq[a], uniq[b], uniq[c]], ws)
        sigmas, weights = best[1], best[2]
    if haze > 0:
        sigmas = [math.sqrt(s * s + haze * haze) for s in sigmas]
    mix = [max(r, 0) / (1 + max(r, 0)) for r in returned]
    return np.asarray(sigmas), np.asarray(weights), mix_matrix(mix, return_matrix)


def _sample_spec(arr, band):
    return float(arr[0] if len(arr) == 1 else arr[band])


def _solve_layered_receiver(lt, receiver, band, angular_samples=512, tol=1e-7):
    """Specular + Beer-Lambert multi-layer transport for one receiver and wavelength (LayeredTransportSolver.solve)."""
    source = float(lt["recordDepthMM"][receiver])
    positions = [0.0]
    for lyr in lt["layers"]:
        positions.append(positions[-1] + float(lyr["thicknessMM"]))
    edges = sorted(set(positions + [source]))
    source_edge = edges.index(source)
    count = len(edges) - 1

    indices, absorption = [], []
    for j in range(count):
        center = 0.5 * (edges[j] + edges[j + 1])
        l_idx = next(i for i in range(len(lt["layers"])) if center < positions[i + 1])
        indices.append(_sample_spec(lt["layers"][l_idx]["refractiveIndex"], band))
        absorption.append(_sample_spec(lt["layers"][l_idx]["absorptionPerMM"], band))

    n0 = indices[source_edge]
    q = _sample_spec(lt["angularExponent"][receiver], band)
    cap = _sample_spec(lt["captureProbability"][receiver], band)
    front_n = _sample_spec(lt.get("frontIndex", [1.0]), band)
    rear_n = _sample_spec(lt.get("rearIndex", [1.0]), band)
    rear_refl = _sample_spec(lt["rearReflectance"], band) if lt.get("rearReflectance") is not None else None
    rear_plate = _sample_spec(lt["rearPlateReflectance"], band) if lt.get("rearPlateReflectance") is not None else None

    ang = (np.arange(angular_samples, dtype=np.float64) + 0.5) / angular_samples
    u = ang ** (1.0 / (q + 1.0))
    inv = n0 * np.sqrt(np.maximum(0.0, 1.0 - u * u))
    w0 = np.full(angular_samples, 0.5 / angular_samples, dtype=np.float64)

    seg_dr, seg_surv = [], []
    for j in range(count):
        nj = indices[j]
        depth = edges[j + 1] - edges[j]
        cos_j = np.sqrt(np.maximum(1e-18, 1.0 - np.minimum((inv / nj) ** 2, 1.0 - 1e-15)))
        seg_dr.append(depth * inv / nj / cos_j)
        seg_surv.append(np.exp(-np.minimum(absorption[j] * depth / cos_j, 80.0)))

    def vec_fresnel(n1_val, n2_val):
        tot = (inv >= n1_val) | (inv >= n2_val)
        c1 = np.sqrt(np.maximum(0.0, 1.0 - np.minimum((inv / n1_val) ** 2, 1.0)))
        c2 = np.sqrt(np.maximum(0.0, 1.0 - np.minimum((inv / n2_val) ** 2, 1.0)))
        ds = np.maximum(n1_val * c1 + n2_val * c2, 1e-18)
        dp = np.maximum(n2_val * c1 + n1_val * c2, 1e-18)
        rs = np.where(tot, 1.0, ((n1_val * c1 - n2_val * c2) / ds) ** 2)
        rp = np.where(tot, 1.0, ((n2_val * c1 - n1_val * c2) / dp) ** 2)
        return rs, rp

    refl_down, refl_up = [], []
    for j in range(count):
        if j + 1 >= count:
            rs, rp = vec_fresnel(indices[j], rear_n)
            if rear_refl is not None:
                rs = np.full_like(inv, rear_refl)
                rp = np.full_like(inv, rear_refl)
            elif rear_plate is not None:
                rs = np.where(rs >= 1.0, 1.0, rs + (1.0 - rs) ** 2 * rear_plate / np.maximum(1.0 - rs * rear_plate, 1e-12))
                rp = np.where(rp >= 1.0, 1.0, rp + (1.0 - rp) ** 2 * rear_plate / np.maximum(1.0 - rp * rear_plate, 1e-12))
        else:
            rs, rp = vec_fresnel(indices[j], indices[j + 1])
        refl_down.append((rs, rp))
        refl_up.append(vec_fresnel(indices[j], front_n if j - 1 < 0 else indices[j - 1]))

    init_visits = tuple([0] * count)
    queue = {(source_edge, True, False, init_visits): (np.zeros_like(inv), w0.copy(), w0.copy())}
    radii_list, mass_list = [], []
    cutoff = tol * 1e-4

    for _ in range(512):
        if not queue:
            break
        nxt = {}

        def add_pkt(key, r_v, s_v, p_v):
            if float((s_v + p_v).sum()) < cutoff:
                return
            if key in nxt:
                or_v, os_v, op_v = nxt[key]
                nxt[key] = (or_v, os_v + s_v, op_v + p_v)
            else:
                nxt[key] = (r_v, s_v, p_v)

        for (j, down, returned, visits), (r_v, s_v, p_v) in queue.items():
            vis_list = list(visits)
            vis_list[j] += 1
            vis_new = tuple(vis_list)
            r_new = r_v + seg_dr[j]
            s_new = s_v * seg_surv[j]
            p_new = p_v * seg_surv[j]

            edge = j + 1 if down else j
            if edge == source_edge:
                scored = (s_new + p_new) * cap
                if returned:
                    radii_list.append(r_new)
                    mass_list.append(scored)
                s_new = s_new * (1.0 - cap)
                p_new = p_new * (1.0 - cap)

            rs, rp = refl_down[j] if down else refl_up[j]
            ref_s, ref_p = s_new * rs, p_new * rp
            trn_s, trn_p = s_new - ref_s, p_new - ref_p

            next_j = j + 1 if down else j - 1
            if 0 <= next_j < count:
                add_pkt((next_j, down, returned, vis_new), r_new, trn_s, trn_p)
            add_pkt((j, not down, returned or (next_j >= count), vis_new), r_new, ref_s, ref_p)
        queue = nxt

    if not radii_list:
        return np.array([0.0]), np.array([1.0])
    all_r = np.concatenate(radii_list)
    all_m = np.concatenate(mass_list)
    keep = all_m > 0
    all_r, all_m = all_r[keep], all_m[keep]
    order = np.argsort(all_r)
    all_r, all_m = all_r[order], all_m[order]
    all_m /= all_m.sum()
    return all_r, all_m


def _radial_edge_spread(radii, mass, dist):
    out = np.zeros_like(dist, dtype=np.float64)
    for idx, d in enumerate(dist):
        if d <= 0.0:
            out[idx] = 0.5
        else:
            m = radii > d
            if np.any(m):
                out[idx] = float(np.sum(mass[m] * np.arccos(np.minimum(d / radii[m], 1.0))) / math.pi)
    return out


def layered_kernel(lt, layer_sens, ref_illum, scale=1.0, haze=0.0, return_matrix=None):
    """Solves LayeredTransport optical stack into (sigma_mm[3], weights[3][3], matrix[3][3])."""
    has_spectral = (
        any(len(lyr["refractiveIndex"]) > 1 or len(lyr["absorptionPerMM"]) > 1 for lyr in lt["layers"])
        or any(len(row) > 1 for row in lt["angularExponent"])
        or any(len(row) > 1 for row in lt["captureProbability"])
    )
    rad_kernels, returned = [], []
    scale = max(float(scale or 0.0), 0.0)
    for r in range(3):
        w_spec = np.maximum(np.asarray(layer_sens[r], dtype=np.float64) * np.asarray(ref_illum, dtype=np.float64), 0.0)
        w_spec = w_spec / w_spec.sum() if w_spec.sum() > 0 else np.full(len(ref_illum), 1.0 / len(ref_illum))
        rtd_arr = np.asarray(lt["returnedToDirect"][r], dtype=np.float64)
        rtd_eff = float(rtd_arr[0]) if len(rtd_arr) == 1 else float(np.sum(w_spec * rtd_arr))
        returned.append(max(rtd_eff * scale, 0.0))

        if has_spectral:
            w_ret = w_spec * (rtd_arr if len(rtd_arr) == len(w_spec) else 1.0)
            if w_ret.sum() <= 0:
                w_ret = w_spec
            band = int(np.clip(round(float(np.sum(np.arange(len(w_spec)) * w_ret) / w_ret.sum())), 0, len(w_spec) - 1))
        else:
            band = 0

        rk, mk = _solve_layered_receiver(lt, r, band)
        cdf = np.cumsum(mk)
        qs = (np.arange(256) + 0.5) / 256.0
        idx = np.clip(np.searchsorted(cdf, qs), 0, len(rk) - 1)
        rad_kernels.append((rk[idx], np.full(256, 1.0 / 256.0)))

    active_recs = [r for r in range(3) if returned[r] > 1e-7]
    if not active_recs:
        sigmas = [0.14, 0.24, 0.41]
        weights = [np.array([1.0, 0.0, 0.0]) for _ in range(3)]
    else:
        q50 = max(rk[int(0.50 * len(rk))] for (rk, _), s in zip(rad_kernels, returned) if s > 1e-7)
        q99 = max(rk[int(0.99 * len(rk))] for (rk, _), s in zip(rad_kernels, returned) if s > 1e-7)
        radius = max(q99 * 0.5, 0.05)
        dist = np.array([k / 160 * 4 * radius for k in range(161)] + [4 * radius * math.exp(s / 48 * math.log(4)) for s in range(1, 49)])
        targets = [_radial_edge_spread(rk, mk, dist) for rk, mk in rad_kernels]

        s_min = max(0.35 * q50, 0.08)
        s_max = max(4.0 * radius, s_min * 4.0)
        cands = [s_min * math.exp(t / 15 * math.log(s_max / s_min)) for t in range(16)]
        cb = [np.array([gaussian_edge(d, s) for d in dist]) for s in cands]
        best = (math.inf, None, None)
        n = len(cands)
        tot_ret = sum(returned[r] for r in active_recs)
        for a in range(n - 2):
            for b in range(a + 1, n - 1):
                for c_idx in range(b + 1, n):
                    basis = [cb[a], cb[b], cb[c_idx]]
                    ws = [simplex_fit(basis, targets[r]) if returned[r] > 1e-7 else np.array([0.0, 1.0, 0.0]) for r in range(3)]
                    res = sum(returned[r] * float(np.sum((ws[r] @ np.asarray(basis) - targets[r]) ** 2)) for r in active_recs)
                    unused = sum(1.0 for k in range(3) if sum(returned[r] * ws[r][k] for r in active_recs) < 0.01 * tot_ret)
                    score = res + tot_ret * (1e-10 * unused + 1e-13 * (c_idx - a))
                    if score < best[0]:
                        best = (score, [cands[a], cands[b], cands[c_idx]], ws)
        sigmas, weights = best[1], best[2]

    if haze > 0:
        sigmas = [math.sqrt(s * s + haze * haze) for s in sigmas]
    mix = [s / (1.0 + s) for s in returned]
    return np.asarray(sigmas), np.asarray(weights), mix_matrix(mix, return_matrix)
