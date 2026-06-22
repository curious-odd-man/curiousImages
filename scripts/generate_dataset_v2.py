
"""
generate_dataset_v2.py

Advanced image organizer dataset generator.

Highlights:
- Deterministic from seed
- Originals + exact duplicates + visual duplicates + metadata variants
- JPG/JPEG/PNG + optional BMP/TIFF/WEBP
- EXIF metadata
- GPS metadata
- Unicode filenames
- Deep folder trees
- Corrupted files
- Manifest with group IDs and hashes
- CR2 cache folder support (real CR2 samples can be dropped into cache/raw_samples)

Dependencies:
pip install pillow piexif numpy tqdm

Example:
python generate_dataset_v2.py --output D:\dataset --count 5000 --seed 42 --extended-formats
"""
import argparse, hashlib, json, random, shutil, uuid
from pathlib import Path
from datetime import datetime, timedelta

import numpy as np
import piexif
from PIL import Image, ImageDraw, ImageEnhance

BASE_FORMATS = ["jpg","jpeg","png"]
EXT_FORMATS = ["bmp","tiff","webp"]

GPS_POINTS = [
    ("Riga",56.9496,24.1052),
    ("London",51.5072,-0.1276),
    ("Tokyo",35.6764,139.6500),
    ("New York",40.7128,-74.0060),
]

CAMERAS = [
    ("Canon","EOS R5"),
    ("Sony","A7 IV"),
    ("Nikon","Z8"),
    ("Fujifilm","X-T5"),
]

UNICODE_NAMES = ["東京","Rīga","Природа","😀","München"]

def rngs(seed):
    return random.Random(seed), np.random.default_rng(seed)

def sha256(p):
    h=hashlib.sha256()
    with open(p,"rb") as f:
        while True:
            b=f.read(1024*1024)
            if not b: break
            h.update(b)
    return h.hexdigest()

def deg_to_exif(v):
    v=abs(v)
    d=int(v); m=int((v-d)*60); s=int((((v-d)*60)-m)*60*100)
    return ((d,1),(m,1),(s,100))

def exif_blob(rng, with_gps=True):
    make, model = rng.choice(CAMERAS)
    dt = datetime(2000,1,1)+timedelta(days=rng.randint(0,9000))
    gps={}
    if with_gps:
        _, lat, lon = rng.choice(GPS_POINTS)
        gps = {
            piexif.GPSIFD.GPSLatitudeRef: b"N" if lat>=0 else b"S",
            piexif.GPSIFD.GPSLatitude: deg_to_exif(lat),
            piexif.GPSIFD.GPSLongitudeRef: b"E" if lon>=0 else b"W",
            piexif.GPSIFD.GPSLongitude: deg_to_exif(lon),
        }
    return piexif.dump({
        "0th":{
            piexif.ImageIFD.Make:make.encode(),
            piexif.ImageIFD.Model:model.encode(),
            piexif.ImageIFD.Artist:b"DatasetGeneratorV2",
        },
        "Exif":{
            piexif.ExifIFD.DateTimeOriginal:dt.strftime("%Y:%m:%d %H:%M:%S").encode()
        },
        "GPS":gps
    })

def create_image(np_rng, cluster):
    w, h = (
        np_rng.choice([640, 800, 1024, 1920]),
        np_rng.choice([480, 600, 768, 1080]),
    )

    stripe_width = np_rng.integers(5, 15)
    n_stripes = (w + stripe_width - 1) // stripe_width

    colors = np_rng.integers(
        0, 256, (n_stripes, 3), dtype=np.uint8
    )

    arr = np.repeat(colors, stripe_width, axis=0)[:w]
    arr = np.tile(arr[None, :, :], (h, 1, 1))

    img = Image.fromarray(arr)

    d = ImageDraw.Draw(img)
    d.text((20, 20), cluster, fill=(255, 255, 255))

    return img

def save(img, path, rng, gps=True):
    path.parent.mkdir(parents=True, exist_ok=True)
    ext=path.suffix.lower()
    if ext in (".jpg",".jpeg"):
        img.save(path, quality=rng.randint(75,95), exif=exif_blob(rng,gps))
    elif ext==".webp":
        img.save(path, quality=rng.randint(75,95))
    else:
        img.save(path)

def visual_dup(img, rng):
    img=img.copy()
    if rng.random()<0.5:
        img=img.rotate(rng.uniform(-3,3))
    if rng.random()<0.5:
        img=ImageEnhance.Brightness(img).enhance(rng.uniform(0.95,1.05))
    return img

def corrupt(path, rng):
    t=rng.choice(["zero","junk","truncated"])
    path.parent.mkdir(parents=True, exist_ok=True)
    if t=="zero":
        open(path,"wb").close()
    elif t=="junk":
        path.write_bytes(b"not an image")
    else:
        path.write_bytes(b"\xff\xd8\xff")

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--output", required=True)
    ap.add_argument("--count", type=int, required=True)
    ap.add_argument("--seed", type=int, required=True)
    ap.add_argument("--extended-formats", action="store_true")
    ap.add_argument("--duplicate-ratio", type=float, default=0.2)
    ap.add_argument("--visual-duplicate-ratio", type=float, default=0.1)
    ap.add_argument("--metadata-duplicate-ratio", type=float, default=0.05)
    ap.add_argument("--corruption-ratio", type=float, default=0.02)
    args=ap.parse_args()

    rng,np_rng=rngs(args.seed)
    root=Path(args.output)
    root.mkdir(parents=True,exist_ok=True)

    formats=BASE_FORMATS + (EXT_FORMATS if args.extended_formats else [])
    clusters=["Vacation","Family","Pets","Screenshots","Landscapes"]

    originals=[]
    manifest={"seed":args.seed,"files":[]}

    for i in range(args.count):
        cluster=rng.choice(clusters)
        ext=rng.choice(formats)

        depth=rng.randint(1,6)
        folder=root
        for d in range(depth):
            folder=folder / f"lvl_{rng.randint(1,20)}"

        name = f"img_{i:06d}"
        if rng.random()<0.05:
            name += "_" + rng.choice(UNICODE_NAMES)

        img=create_image(np_rng, cluster)
        path=folder/f"{name}.{ext}"

        save(img,path,rng,gps=rng.random()<0.7)

        gid=str(uuid.UUID(int=rng.getrandbits(128)))
        originals.append((gid,path,img))

        manifest["files"].append({
            "group":gid,
            "type":"original",
            "path":str(path.relative_to(root)),
            "sha256":sha256(path)
        })

    # exact duplicates
    for i in range(int(args.count*args.duplicate_ratio)):
        gid,src,_=rng.choice(originals)
        dst=root/"Duplicates"/f"dup_{i:06d}{src.suffix}"
        dst.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(src,dst)

        manifest["files"].append({
            "group":gid,
            "type":"exact_duplicate",
            "path":str(dst.relative_to(root)),
            "sha256":sha256(dst)
        })

    # visual duplicates
    for i in range(int(args.count*args.visual_duplicate_ratio)):
        gid,src,img=rng.choice(originals)
        dst=root/"VisualDuplicates"/f"vdup_{i:06d}.jpg"
        save(visual_dup(img,rng),dst,rng)

        manifest["files"].append({
            "group":gid,
            "type":"visual_duplicate",
            "path":str(dst.relative_to(root)),
            "sha256":sha256(dst)
        })

    # metadata variants
    for i in range(int(args.count*args.metadata_duplicate_ratio)):
        gid,_,img=rng.choice(originals)
        dst=root/"MetadataVariants"/f"meta_{i:06d}.jpg"
        save(img,dst,rng,gps=not (rng.random()<0.5))

        manifest["files"].append({
            "group":gid,
            "type":"metadata_variant",
            "path":str(dst.relative_to(root)),
            "sha256":sha256(dst)
        })

    # corrupt
    for i in range(int(args.count*args.corruption_ratio)):
        dst=root/"Corrupted"/f"broken_{i:06d}.jpg"
        corrupt(dst,rng)
        manifest["files"].append({
            "group":None,
            "type":"corrupt",
            "path":str(dst.relative_to(root))
        })

    # CR2 support placeholder
    raw_cache=root/"cache"/"raw_samples"
    raw_cache.mkdir(parents=True, exist_ok=True)

    with open(root/"dataset_manifest.json","w",encoding="utf-8") as f:
        json.dump(manifest,f,indent=2,ensure_ascii=False)

    print("Generated", len(manifest["files"]), "files")

if __name__ == "__main__":
    main()
