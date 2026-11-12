#!/usr/bin/env python3
"""Builds the photos the on-device test copies to the emulator, laid out as on a phone:

    make_test_photos.py OUT        (needs Pillow)

DCIM/Camera/IMG_0001.jpg          a photo from the camera (EXIF: camera, capture time)
Pictures/Copies/IMG_0001.jpg      the same file, byte for byte
Download/IMG_0001 (1).jpg         the same file again, with a copy name
Pictures/Edited/small.jpg         the photo at half size, saved again          -> look-alike
Pictures/Edited/turned.jpg        the photo turned a quarter, saved again      -> look-alike (turned)
Pictures/Edited/brighter.jpg      the photo made brighter: an edit             -> look-alike, not ticked
DCIM/Camera/BURST_0[1-5].jpg      5 shots a second apart while panning; the 3rd is blurred -> burst
DCIM/Camera/DOC_0[1-5].jpg        5 pages of a document, photographed 2 s apart -> nothing: not a burst
Pictures/Screenshots/settings_[12].png  two different settings screens          -> nothing: not look-alikes
Pictures/Other/*.jpg, *.png       unrelated photos                             -> nothing
Pictures/Night/dark.jpg           a very dark shot from a camera               -> blurry or dark
Pictures/Big/panorama.jpg         8000 x 2000 px                               -> nothing (read reduced)
Pictures/Broken/truncated.jpg     the first 6 KB of a photo                    -> can't be decoded
Pictures/Broken/not-a-photo.jpg   text with a photo's name                     -> can't be decoded
Pictures/CrashTest/*.jpg          3 photos; a test build of the app dies on purpose when it reads
                                  the one named photocleaner-crash-test.jpg   -> left out the third time

The pages and the screens are chosen so that a 64-bit perceptual hash alone takes them for burst
frames and look-alikes (each page is within 10 bits of the one before, the screens 6 bits apart);
the screens, mostly white, would also count as overexposed if screenshots were judged like photos.

Expected from a scan of everything: one look-alike group of 6 (the camera original kept, 4 ticked,
the brighter edit left alone), one burst of 5 (a sharp frame kept, 4 ticked) and the dark shot
listed on its own; the broken files are reported, and stop nothing.
"""
import os
import random
import shutil
import sys

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter


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


def page(seed, w=1654, h=2339):
    """A photographed page of "text": paragraphs of grey words on white paper, unevenly lit.

    Any two pages share their layout (margins, line spacing) and nothing else.
    """
    rnd = random.Random(seed)
    im = Image.new("RGB", (w, h), (252, 252, 250))
    d = ImageDraw.Draw(im)
    left, right, pitch = w * 9 // 100, w - w * 9 // 100, h * 19 // 1000
    y = h * 7 // 100
    while y < h - h * 9 // 100:
        if rnd.randrange(4) == 0:
            d.rectangle([left, y, left + (right - left) * rnd.randint(20, 60) // 100, y + pitch], fill=(60, 60, 60))
            y += pitch * 2
        lines = rnd.randint(2, 9)
        for line in range(lines):
            if y > h - h * 9 // 100:
                break
            end = left + (right - left) * (rnd.randint(30, 90) if line == lines - 1 else rnd.randint(88, 100)) // 100
            x = left
            while x < end:
                word = rnd.randint(18, 90)
                d.rectangle([x, y, min(end, x + word), y + pitch * 55 // 100], fill=(120, 120, 120))
                x += word + 12
            y += pitch
        y += pitch * rnd.randint(7, 16) // 10
    light = Image.new("L", (2, 2))
    light.putdata([rnd.randint(205, 255) for _ in range(4)])
    return ImageChops.multiply(im, light.resize((w, h), Image.BILINEAR).convert("RGB"))


def screen(seed, w=1080, h=2400):
    """A phone's settings screen: a title, then rows of a coloured icon and two lines, some with a switch."""
    rnd = random.Random(seed)
    im = Image.new("RGB", (w, h), (250, 250, 252))
    d = ImageDraw.Draw(im)
    d.rectangle([w * 6 // 100, h * 8 // 100, w * rnd.randint(30, 70) // 100, h * 11 // 100], fill=(40, 40, 40))
    y = h * 17 // 100
    r = w * 4 // 100
    while y < h - h * 8 // 100:
        cx = w * 10 // 100
        d.ellipse([cx - r, y, cx + r, y + 2 * r], fill=tuple(rnd.randint(60, 220) for _ in range(3)))
        d.rectangle([w * 18 // 100, y + r // 3, w * rnd.randint(30, 65) // 100, y + r], fill=(70, 70, 70))
        d.rectangle([w * 18 // 100, y + r * 4 // 3, w * rnd.randint(35, 80) // 100, y + r * 11 // 6], fill=(150, 150, 150))
        if rnd.randrange(10) < 3:
            d.rounded_rectangle([w * 82 // 100, y + r // 2, w * 93 // 100, y + r * 3 // 2], r // 2, fill=(60, 110, 220))
        y += h * rnd.randint(62, 84) // 1000
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
    save(ImageEnhance.Brightness(a).enhance(1.2), out, "Pictures/Edited/brighter.jpg", quality=90)

    wide = scene(2, 1700, 1200)
    for k in range(5):
        frame = wide.crop((8 * k, 0, 8 * k + 1600, 1200))
        if k == 2:
            frame = frame.filter(ImageFilter.GaussianBlur(6))
        save(frame, out, f"DCIM/Camera/BURST_0{k + 1}.jpg", ex=exif(f"2024:05:01 10:05:0{k}"))

    for k, seed in enumerate((13, 12, 31, 32, 24)):
        save(page(seed), out, f"DCIM/Camera/DOC_0{k + 1}.jpg", quality=90, ex=exif(f"2024:05:01 10:20:0{2 * k}"))
    for k, seed in enumerate((15, 26)):
        save(screen(seed), out, f"Pictures/Screenshots/settings_{k + 1}.png")

    save(scene(3), out, "Pictures/Other/harbour.jpg")
    save(scene(4, 1024, 768), out, "Pictures/Other/diagram.png")
    save(scene(5), out, "Pictures/Other/street.jpg", ex=exif("2024:06:01 12:00:00", "OtherCam", "Two"))
    save(ImageEnhance.Brightness(scene(6)).enhance(0.12), out, "Pictures/Night/dark.jpg", ex=exif("2024:05:02 23:10:00"))
    save(scene(7, 8000, 2000), out, "Pictures/Big/panorama.jpg", quality=80)

    broken = os.path.join(out, "Pictures/Broken")
    os.makedirs(broken, exist_ok=True)
    with open(orig, "rb") as f:
        head = f.read(6000)
    with open(os.path.join(broken, "truncated.jpg"), "wb") as f:
        f.write(head)
    with open(os.path.join(broken, "not-a-photo.jpg"), "w") as f:
        f.write("This is a text file with a photo's name.\n" * 50)

    save(scene(8), out, "Pictures/CrashTest/photocleaner-crash-test.jpg")
    save(scene(9), out, "Pictures/CrashTest/ok_1.jpg")
    save(scene(10), out, "Pictures/CrashTest/ok_2.jpg")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
