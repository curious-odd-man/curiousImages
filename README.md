# Overview

⚠ AI Slop Warning!

This app was written with a heavy use of Claude and ChatGPT. Free tier only :D
I am trying to keep it not let it go completely, but I also don't review every line of code produced by AI. Soo..
Use with caution.

# Initial setup

1. download models (see details in [here](src\main\resources\models\ai.md)):

```shell
wget https://huggingface.co/TheEeeeLin/HivisionIDPhotos_matting/resolve/main/retinaface-resnet50.onnx mv retinaface-resnet50.onnx src/main/resources/models/retinaface-resnet50.onnx
wget https://huggingface.co/public-data/insightface/resolve/main/models/buffalo_l/w600k_r50.onnx && mv w600k_r50.onnx src/main/resources/models/arcface_r50.onnx
wget https://huggingface.co/immich-app/ViT-B-32__openai/resolve/main/visual/model.onnx && mv model.onnx src/main/resources/models/clip_image_vit_b32.onnx
wget https://huggingface.co/immich-app/ViT-B-32__openai/resolve/main/textual/model.onnx && mv model.onnx src/main/resources/models/clip_text_vit_b32.onnx
# Tokenizer belongs to clip-tokenizer directory (included into repository)
# wget https://raw.githubusercontent.com/openai/CLIP/main/clip/bpe_simple_vocab_16e6.txt.gz
```

2. download CUDA
   drivers: https://developer.nvidia.com/cuda-downloads?target_os=Windows&target_arch=x86_64&target_version=10&target_type=exe_network

# Core Photo Library

The minimum viable product usually includes:

- Import photos from:
    - Local folders
    - SD cards
    - Cameras
    - Mobile devices
- Photo browsing
    - Grid view
    - Timeline view
    - Full-screen viewer
    - Zoom and pan
- Folder and album management
    - Albums
    - Smart albums
    - Favorites
    - Collections
- Basic metadata
    - File name
    - Size
    - Resolution
    - Date taken
    - Camera information (EXIF)
- Duplicate detection

# Search and Organization

This is where photo management becomes valuable.

- Tags
    - Manual tags
    - Hierarchical tags
- Ratings
- Stars (1–5)
- Color labels
- Favorites
- Filters

Filter by:

- Date
- Camera
- Lens
- Location
- Tags
- Rating
- File type
- Smart Collections

Examples:

- Photos taken this month
- Unedited photos
- 5-star photos
- Photos from Riga

# AI Features

These are often the most appreciated features.

- Face Recognition
- Detect people
- Group photos by person
- Merge duplicate face groups
- Object Recognition

Search:

- Dog
- Beach
- Car
- Sunset
- Mountain
- Natural Language Search
