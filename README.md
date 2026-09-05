# BlindNav - AI-Powered Navigation Assistant for the Visually Impaired

> An Android outdoor navigation app that helps visually impaired users by real-time detecting tactile paving, crosswalks, pedestrian traffic lights, and objects through the phone camera, with voice guidance.

---

## About This Project

**This project was primarily built with AI assistance.** I don't know how to code, and everything was written line by line with the help of AI. So the code is quite rough and has many imperfections—please be understanding.

**This project is no longer maintained.** The last updates were around July 2026. I've shifted my focus to the next generation of this app, so I'm releasing this one as open source for anyone who might find it useful.

---

## Project Origin

The idea came from watching the **OpenGlass** navigation glasses project (`OpenAIglasses_for_Navigation`) on YouTube. I thought it was really interesting and wondered if I could convert it into something more accessible—an Android app that works with a regular smartphone, no special glasses hardware needed.

The project initially referenced the OpenGlass concepts and some model weights. The traffic light classification models were later trained by myself.

---

## Features

- **Tactile Paving Navigation**: Real-time detection of tactile paving areas, with voice prompts for going straight, shifting left, or shifting right
- **Crosswalk Detection**: Identifies crosswalks and judges distance stages to guide alignment
- **Crossing Assistance**: Independent state machine that automatically finds crosswalks, waits for green lights, and guides crossing
- **Traffic Light Detection**: Detects pedestrian traffic lights and announces red/green states
- **Item Search**: Supports 601-class product recognition with voice guidance
- **Obstacle Detection**: General obstacle detection (not enabled by default in current version)

---

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Camera**: CameraX 1.3.1
- **Inference Engine**: ONNX Runtime Android 1.16.3
- **AI Models**: YOLOv8-seg (segmentation), ResNet18 / LYTNetV2 (traffic light classification), YOLOv8 (product recognition)

---

## Model Information

⚠️ **Important**: The models in this repository are the **most raw versions, without any optimization**.

### Segmentation Model
- Source: Converted from YOLOv8x-seg
- Purpose: Tactile paving + crosswalk segmentation
- Size: ~274MB (FP32)

### Traffic Light Models
- **ResNet18**: Self-trained for pedestrian traffic light recognition
  - Green light accuracy: ~90%+
  - Red light accuracy: ~90%
  - **Not optimized for filtering car traffic lights**, so false positives may occur
- **LYTNetV2**: Lightweight model, binary red/green classification, ~96.6% accuracy

### Product Recognition Model
- Based on YOLOv8, supports 601 product categories
- Size: ~38MB (FP16)

---

## Known Limitations

1. **Code Quality**: Written by a non-professional developer with AI help—code structure and optimization are rough
2. **Model Performance**: Raw models without quantization, pruning, or mobile optimization
3. **Traffic Light Accuracy**: Red light detection accuracy is lower, and car traffic lights are not filtered, leading to potential false positives
4. **Device Compatibility**: Only tested on realme phone (Snapdragon 660), performance on other devices is unknown
5. **Inference Speed**: Slow cold start, segmentation model loading takes ~10-12 seconds
6. **Tactile Paving Coverage**: Only supports vertical tactile paving, horizontal paving not handled

---

## How to Use

### Requirements
- Android Studio
- Android SDK (API 29+ recommended)
- Android device with CameraX support

### Quick Start

1. Clone the repository
```bash
git clone https://github.com/tianzi41/BlindNav-Android.git
```

2. Open the project in Android Studio

3. Sync Gradle dependencies

4. Run on a real device (phone with wide-angle camera recommended)

### Model Download

Model files are hosted on Hugging Face. Download before first run:

```bash
# Using Hugging Face CLI
huggingface-cli download tianzi41/BlindNav --repo-type model --local-dir ./assets
```

Or visit directly: https://huggingface.co/tianzi41/BlindNav

Copy the downloaded `models`, `voice`, and `music` folders to `app/src/main/assets/`.

---

## Disclaimer

This project is for learning and exchange purposes only.

- This app cannot replace professional navigation tools or guide dogs
- Please pay attention to personal safety and do not rely entirely on this app in dangerous situations
- Traffic light detection may have false positives—always rely on actual observation when crossing roads

---

## What You Can Do

This project is no longer maintained, but if you find certain aspects useful, feel free to:

- **Borrow ideas**: The code contains state machine designs, frame processing optimizations, and voice throttling logic—use them as references
- **Use directly**: Models and code are open, you can use them as-is or build upon them
- **Improve**: If you can optimize models, improve code structure, or adapt to more devices, feel free to Fork and enhance

---

## Credits

- Inspiration: [OpenAIglasses_for_Navigation](https://github.com/username/OpenAIglasses_for_Navigation) (navigation glasses project)
- Model foundation: YOLOv8, ResNet, LYTNetV2
- Development aid: Entirely built with AI-assisted coding

---

## Next Generation

I've already started developing the next generation of navigation apps for the visually impaired, with redesigned architecture, optimized models, and improved accuracy and response speed. If you're interested in collaboration or discussion, feel free to reach out.

---

*This project was developed by a non-professional developer using AI tools. Code quality is limited—please view it rationally.*
