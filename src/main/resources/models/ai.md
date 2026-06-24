I need to search for current, exact download links rather than rely on memory here.Good, I now have everything I need. Here are exact, verified download locations for each model:

---

## RetinaFace ResNet50

The `retinaface_r50_v1` in the official InsightFace HuggingFace repo is distributed as **MXNet weights** (`.params` + `.json`), not ONNX. The ready-to-use ONNX version is here:

**`retinaface-resnet50.onnx`** (~106 MB)
```
https://huggingface.co/TheEeeeLin/HivisionIDPhotos_matting/resolve/main/retinaface-resnet50.onnx
```
This is the file you already named — the exact filename the code uses. Direct download with `wget` or browser.

---

## ArcFace R50 (`w600k_r50.onnx`, 174 MB)

From the official InsightFace `buffalo_l` pack on HuggingFace, uploaded by HuggingFace staff:

```
https://huggingface.co/public-data/insightface/resolve/main/models/buffalo_l/w600k_r50.onnx
```

SHA256: `4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43`

Rename this file to `arcface_r50.onnx` to match `ModelPaths.java`, or change the filename in `ModelPaths.arcFace()` to `w600k_r50.onnx`.

---

## CLIP ViT-B/32 — image encoder (~340 MB) and text encoder (~254 MB)

The best source for split image/text ONNX exports is the **Immich project** — they maintain exactly this split for production use in their self-hosted photo library (same use case as yours):

**Image encoder:**
```
https://huggingface.co/immich-app/ViT-B-32__openai/resolve/main/visual/model.onnx
```

**Text encoder:**
```
https://huggingface.co/immich-app/ViT-B-32__openai/resolve/main/textual/model.onnx
```

SHA256 of text encoder: `b80cf0af751533a6712d92247f0ddc0c95208748bc59a1a27f33e67be6864e3b`

Rename them to `clip_image_vit_b32.onnx` and `clip_text_vit_b32.onnx` respectively.

---

## CLIP tokenizer vocab files

These come from the original OpenAI CLIP repo. Direct raw links:

**`vocab.json`:**
```
https://raw.githubusercontent.com/openai/CLIP/main/clip/bpe_simple_vocab_16e6.txt.gz
```
⚠️ This is gzip-compressed. Decompress it and rename to `vocab.json` — or use the pre-parsed JSON from:
```
https://huggingface.co/openai/clip-vit-base-patch32/resolve/main/vocab.json
```

**`merges.txt`:**
```
https://huggingface.co/openai/clip-vit-base-patch32/resolve/main/merges.txt
```

---

## One important note on the Immich CLIP models

The Immich exports use input tensor names `input` for both encoders, which matches what `ClipImageEncoder` and `ClipTextEncoder` already use. However, verify the input names with Netron since these exports are maintained independently — if the text encoder uses `input_ids` instead of `input`, change the `Map.of("input", tensor)` call in `ClipTextEncoder.encode()` accordingly.