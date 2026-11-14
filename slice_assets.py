#!/usr/bin/env python3
"""Slices the reference screenshots into resource-pack textures.

Usage: python slice_assets.py <moon.png> <orbs.png> <ranks.png> <pack_dir>
  moon.png  = Image 1 (blood moon square)
  orbs.png  = Image 3 (9 orb sheet)
  ranks.png = Image 5 (8 rank icons)
Needs: pip install pillow numpy scipy
"""
import sys, os
import numpy as np
from PIL import Image
from scipy import ndimage as ndi

RANK_NAMES = ["copper", "iron", "gold", "platinum", "diamond", "netherite", "nemesis", "archnemesis"]
# (centre_x, centre_y, radius) as fractions of the 881x898 orb sheet
ORB_LAYOUT = {
    "strength_orb": (137, 165), "speed_orb": (346, 165), "water_breathing_orb": (555, 165),
    "dolphins_grace_orb": (764, 165), "resistance_orb": (138, 395), "fire_resistance_orb": (346, 395),
    "regeneration_orb": (555, 395), "haste_orb": (764, 395), "underworld_orb": (137, 685),
}
ORB_R = 88
REF_W, REF_H = 881, 898

def square(im, size):
    w, h = im.size
    s = max(w, h)
    canvas = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    canvas.paste(im, ((s - w) // 2, (s - h) // 2), im)
    return canvas.resize((size, size), Image.NEAREST if size <= 64 else Image.LANCZOS)

def slice_ranks(path, out):
    im = Image.open(path).convert("RGBA")
    rgb = np.array(im)[:, :, :3].astype(int)
    fg = rgb.max(axis=2) > 40
    fg = ndi.binary_dilation(fg, iterations=3)
    lab, n = ndi.label(fg)
    sizes = ndi.sum(fg, lab, range(1, n + 1))
    keep = np.argsort(sizes)[::-1][:8] + 1
    blobs = []
    for k in keep:
        ys, xs = np.where(lab == k)
        blobs.append((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1, k))
    blobs.sort(key=lambda b: (b[0] + b[2]) / 2)  # left to right
    os.makedirs(out, exist_ok=True)
    for name, (x0, y0, x1, y1, k) in zip(RANK_NAMES, blobs):
        mask = ndi.binary_fill_holes(lab[y0:y1, x0:x1] == k)
        crop = im.crop((x0, y0, x1, y1))
        arr = np.array(crop)
        arr[:, :, 3] = (mask * 255).astype(np.uint8)
        # un-dilate: drop near-black fringe pixels
        fringe = arr[:, :, :3].max(axis=2) < 22
        arr[fringe, 3] = 0
        square(Image.fromarray(arr), 128).save(os.path.join(out, name + ".png"))
        print("rank", name, (x0, y0, x1, y1))

def slice_orbs(path, out):
    im = Image.open(path).convert("RGBA")
    W, H = im.size
    os.makedirs(out, exist_ok=True)
    for name, (cx, cy) in ORB_LAYOUT.items():
        cx, cy, r = cx / REF_W * W, cy / REF_H * H, ORB_R / REF_W * W
        crop = im.crop((int(cx - r), int(cy - r), int(cx + r), int(cy + r)))
        arr = np.array(crop)
        h, w = arr.shape[:2]
        yy, xx = np.ogrid[:h, :w]
        circle = (xx - w / 2) ** 2 + (yy - h / 2) ** 2 <= (w / 2 - 1) ** 2
        # drop the light card background: keep dark outline + coloured orb
        bright = (arr[:, :, :3].min(axis=2) > 205)
        bg = ndi.binary_dilation(bright & ~ndi.binary_erosion(circle, iterations=6), iterations=1)
        mask = circle & ~bright
        mask = ndi.binary_fill_holes(mask)
        mask = ndi.binary_opening(mask, structure=np.ones((3, 3)), iterations=2)
        lab2, n2 = ndi.label(mask)
        if n2 > 1:
            sz = ndi.sum(mask, lab2, range(1, n2 + 1))
            mask = lab2 == (int(np.argmax(sz)) + 1)
        mask = ndi.binary_fill_holes(mask)
        arr[:, :, 3] = (mask * 255).astype(np.uint8)
        # trim to bounding box of the orb, make square, 32x32
        ys, xs = np.where(mask)
        if len(xs) == 0:
            continue
        sub = Image.fromarray(arr).crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))
        square(sub, 32).save(os.path.join(out, name + ".png"))
        print("orb", name)

def make_moon(path, pack):
    im = Image.open(path).convert("RGBA")
    rgb = np.array(im)[:, :, :3].astype(int)
    red = (rgb[:, :, 0] > 120) & (rgb[:, :, 0] > rgb[:, :, 1] * 1.6)
    ys, xs = np.where(ndi.binary_opening(red, iterations=1))
    sq = im.crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))
    tile = sq.resize((32, 32), Image.NEAREST)
    d1 = os.path.join(pack, "assets/minecraft/textures/environment/celestial/moon")
    os.makedirs(d1, exist_ok=True)
    tile.save(os.path.join(d1, "full_moon.png"))
    tile.save(os.path.join(pack, "blood_moon_tile_32.png"))
    print("moon tile", sq.size, "->32x32")

if __name__ == "__main__":
    moon, orbs, ranks, pack = sys.argv[1:5]
    ns = os.path.join(pack, "assets/rankedsmp")
    slice_ranks(ranks, os.path.join(ns, "textures/font/rank"))
    slice_orbs(orbs, os.path.join(ns, "textures/item"))
    make_moon(moon, pack)
