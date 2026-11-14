#!/usr/bin/env python3
"""Only needed if your client still reads textures/environment/moon_phases.png.
Extract the vanilla moon_phases.png from the client jar (assets/minecraft/textures/environment/),
then run:  python make_legacy_moon.py vanilla_moon_phases.png
It pastes the blood moon over the FULL moon tile (top-left) and writes the patched file into the pack."""
import sys
from PIL import Image
van = Image.open(sys.argv[1]).convert("RGBA")
tw, th = van.width // 4, van.height // 2
tile = Image.open("blood_moon_tile_32.png").convert("RGBA").resize((tw, th), Image.NEAREST)
van.paste(tile, (0, 0))
van.save("assets/minecraft/textures/environment/moon_phases.png")
print("patched moon_phases.png written")
