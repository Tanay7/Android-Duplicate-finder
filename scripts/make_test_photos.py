#!/usr/bin/env python3
"""Builds the photos the on-device test copies to the emulator, laid out as on a phone:

    make_test_photos.py OUT        (needs Pillow)

DCIM/Camera/IMG_0001.jpg          a photo from the camera (EXIF: camera, capture time)
Pictures/Copies/IMG_0001.jpg      the same file, byte for byte
Download/IMG_0001 (1).jpg         the same file again, with a copy name
Pictures/Edited/small.jpg         the photo at half size, saved again          -> look-alike
Pictures/Edited/turned.jpg        the photo turned a quarter, saved again      -> look-alike (turned)
DCIM/Camera/BURST_0[1-5].jpg      5 shots a second apart while panning; the 3rd is blurred -> burst
Pictures/Other/*.jpg, *.png       unrelated photos                             -> nothing
Pictures/Night/dark.jpg           a very dark shot                             -> blurry or dark
Pictures/Big/panorama.jpg         8000 x 2000 px                               -> nothing (read reduced)
Pictures/Broken/truncated.jpg     the first 6 KB of a photo                    -> can't be decoded
Pictures/Broken/not-a-photo.jpg   text with a photo's name                     -> can't be decoded

Expected from a scan of everything: one look-alike group of 5 (the camera original kept, 4 ticked),
one burst of 5 (a sharp frame kept, 4 ticked) and the dark shot listed on its own; the broken files
are reported, and stop nothing.
"""
import os
import random
import shutil
import sys

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter


def scene(seed, w=1600, h=1200):
    """A busy picture: a gradient, coloured shapes with outlines, and lines of 'text'."""
    rnd = random.Random(seed)
    im = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(im)
    c0 = [rnd.randint(40, 220) for _ in range(3)]
    c1 = [rnd.randint(40, 220) for _ in range(3)]
    for y in range(h):
        t = y / (h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(c0[i] * (1 - t) + c1[i] * t) for i in range(3)))
    for _ in range(30):
        col = tuple(rnd.randint(20, 235) for _ in range(3))
        x, y = rnd.randint(0, w), rnd.randint(0, h)
        rx, ry = rnd.randint(40, w // 4), rnd.randint(40, h // 4)
        box = [x - rx, y - ry, x + rx, y + ry]
        if rnd.random() < 0.5:
            d.ellipse(box, fill=col, outline=(0, 0, 0), width=3)
        else:
            d.rectangle(box, fill=col, outline=(255, 255, 255), width=3)
    for _ in range(40):
        x, y = rnd.randint(0, w - 200), rnd.randint(0, h - 20)
        d.line([(x, y), (x + rnd.randint(60, 200), y)], fill=(rnd.randint(0, 60),) * 3, width=4)
    return im


def exif(when, make="TestCam", model="One"):
    e = Image.Exif()
    e[0x010F] = make
    e[0x0110] = model
    sub = e.get_ifd(0x8769)
    sub[0x9003] = when          # DateTimeOriginal
    return e


def save(im, out, rel, quality=92, ex=None):
    path = os.path.join(out, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    kw = {"quality": quality} if rel.lower().endswith(".jpg") else {}
    if ex is not None:
        kw["exif"] = ex
    im.save(path, **kw)
    return path


def main(out):
    a = scene(1)
    orig = save(a, out, "DCIM/Camera/IMG_0001.jpg", ex=exif("2024:05:01 10:00:00"))
    for rel in ("Pictures/Copies/IMG_0001.jpg", "Download/IMG_0001 (1).jpg"):
        os.makedirs(os.path.join(out, os.path.dirname(rel)), exist_ok=True)
        shutil.copyfile(orig, os.path.join(out, rel))
    save(a.resize((800, 600), Image.LANCZOS), out, "Pictures/Edited/small.jpg", quality=75)
    save(a.transpose(Image.Transpose.ROTATE_90), out, "Pictures/Edited/turned.jpg", quality=70)

    wide = scene(2, 1700, 1200)
    for k in range(5):
        frame = wide.crop((8 * k, 0, 8 * k + 1600, 1200))
        if k == 2:
            frame = frame.filter(ImageFilter.GaussianBlur(6))
        save(frame, out, f"DCIM/Camera/BURST_0{k + 1}.jpg", ex=exif(f"2024:05:01 10:05:0{k}"))

    save(scene(3), out, "Pictures/Other/harbour.jpg")
    save(scene(4, 1024, 768), out, "Pictures/Other/diagram.png")
    save(scene(5), out, "Pictures/Other/street.jpg", ex=exif("2024:06:01 12:00:00", "OtherCam", "Two"))
    save(ImageEnhance.Brightness(scene(6)).enhance(0.12), out, "Pictures/Night/dark.jpg")
    save(scene(7, 8000, 2000), out, "Pictures/Big/panorama.jpg", quality=80)

    broken = os.path.join(out, "Pictures/Broken")
    os.makedirs(broken, exist_ok=True)
    with open(orig, "rb") as f:
        head = f.read(6000)
    with open(os.path.join(broken, "truncated.jpg"), "wb") as f:
        f.write(head)
    with open(os.path.join(broken, "not-a-photo.jpg"), "w") as f:
        f.write("This is a text file with a photo's name.\n" * 50)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
