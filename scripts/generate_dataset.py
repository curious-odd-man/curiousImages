
"""
generate_dataset.py

Synthetic image dataset generator for image organizer testing.

Requirements:
    pip install pillow piexif numpy requests tqdm

Usage:
    python generate_dataset.py --output "D:\Programming\sample-data\sample-images" --count 1000 --seed 42
"""

import argparse
import hashlib
import json
import random
import shutil
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import piexif
from PIL import Image, ImageDraw

CAMERAS = [
    ("Canon", "EOS R5"),
    ("Canon", "5D Mark IV"),
    ("Sony", "A7 IV"),
    ("Nikon", "Z8"),
    ("Fujifilm", "X-T5"),
]

FORMATS = ["jpg", "jpeg", "png"]
EXTENDED = ["bmp", "tiff", "webp"]


def make_rng(seed):
    return random.Random(seed), np.random.default_rng(seed)


def random_image(np_rng, w, h):
    arr = np_rng.integers(0, 255, (h, w, 3), dtype=np.uint8)
    return Image.fromarray(arr)


def exif_bytes(rng):
    make, model = rng.choice(CAMERAS)
    dt = datetime(2000, 1, 1) + timedelta(days=rng.randint(0, 10000))

    exif = {
        "0th": {
            piexif.ImageIFD.Make: make.encode(),
            piexif.ImageIFD.Model: model.encode(),
            piexif.ImageIFD.Artist: b"DatasetGenerator",
            piexif.ImageIFD.Copyright: b"Test Dataset",
        },
        "Exif": {
            piexif.ExifIFD.DateTimeOriginal: dt.strftime("%Y:%m:%d %H:%M:%S").encode()
        },
        "GPS": {}
    }
    return piexif.dump(exif)


def save_image(img, path, rng):
    path.parent.mkdir(parents=True, exist_ok=True)
    ext = path.suffix.lower()

    if ext in (".jpg", ".jpeg"):
        img.save(path, quality=rng.randint(75, 95), exif=exif_bytes(rng))
    elif ext == ".png":
        img.save(path)
    elif ext == ".bmp":
        img.save(path)
    elif ext == ".tiff":
        img.save(path)
    elif ext == ".webp":
        img.save(path, quality=rng.randint(75, 95))
    else:
        img.save(path)


def visual_duplicate(img, rng):
    img = img.copy()
    if rng.random() < 0.5:
        img = img.rotate(rng.uniform(-3, 3))
    if rng.random() < 0.5:
        w, h = img.size
        crop = int(min(w, h) * 0.03)
        img = img.crop((crop, crop, w - crop, h - crop)).resize((w, h))
    d = ImageDraw.Draw(img)
    if rng.random() < 0.3:
        d.text((10, 10), "dup", fill=(255, 255, 255))
    return img


def corrupt_file(path):
    with open(path, "wb") as f:
        f.write(b"broken")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(1024 * 1024)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--output", required=True)
    p.add_argument("--count", type=int, required=True)
    p.add_argument("--seed", type=int, required=True)
    p.add_argument("--extended-formats", action="store_true")
    p.add_argument("--duplicate-ratio", type=float, default=0.2)
    p.add_argument("--visual-duplicate-ratio", type=float, default=0.1)
    p.add_argument("--corruption-ratio", type=float, default=0.02)
    p.add_argument("--raw-ratio", type=float, default=0.05)
    args = p.parse_args()

    rng, np_rng = make_rng(args.seed)

    root = Path(args.output)
    root.mkdir(parents=True, exist_ok=True)

    formats = FORMATS + (EXTENDED if args.extended_formats else [])

    manifest = {
        "seed": args.seed,
        "originals": [],
        "duplicates": [],
        "visual_duplicates": [],
        "corrupted": []
    }

    originals = []

    for i in range(args.count):
        ext = rng.choice(formats)
        folder = root / str(rng.randint(2018, 2026))
        img = random_image(np_rng, rng.choice([640, 800, 1024, 1920]), rng.choice([480, 600, 768, 1080]))
        path = folder / f"img_{i:06d}.{ext}"
        save_image(img, path, rng)

        originals.append((path, img))
        manifest["originals"].append({
            "path": str(path.relative_to(root))
        })

    dup_count = int(args.count * args.duplicate_ratio)
    for i in range(dup_count):
        src, _ = rng.choice(originals)
        dst = root / "Duplicates" / f"dup_{i:06d}{src.suffix}"
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)

        manifest["duplicates"].append({
            "source": str(src.relative_to(root)),
            "path": str(dst.relative_to(root))
        })

    vis_count = int(args.count * args.visual_duplicate_ratio)
    for i in range(vis_count):
        src, img = rng.choice(originals)
        dst = root / "VisualDuplicates" / f"vdup_{i:06d}.jpg"
        dst.parent.mkdir(parents=True, exist_ok=True)

        save_image(visual_duplicate(img, rng), dst, rng)

        manifest["visual_duplicates"].append({
            "source": str(src.relative_to(root)),
            "path": str(dst.relative_to(root))
        })

    corrupt_count = int(args.count * args.corruption_ratio)
    for i in range(corrupt_count):
        dst = root / "Corrupted" / f"broken_{i:06d}.jpg"
        dst.parent.mkdir(parents=True, exist_ok=True)
        corrupt_file(dst)

        manifest["corrupted"].append({
            "path": str(dst.relative_to(root))
        })

    manifest["summary"] = {
        "original_count": args.count,
        "duplicate_count": dup_count,
        "visual_duplicate_count": vis_count,
        "corrupt_count": corrupt_count,
    }

    with open(root / "dataset_manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    print("Dataset generated:", root)


if __name__ == "__main__":
    main()
