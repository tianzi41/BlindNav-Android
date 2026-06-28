# -*- coding: utf-8 -*-
"""
盲人导航安卓应用 - 模型转换脚本
将 PyTorch .pt 模型转换为 ONNX 格式，供安卓端 ONNX Runtime 使用

使用方法:
    python convert_models.py

前置条件:
    pip install ultralytics torch

输出:
    app/src/main/assets/models/yolo_seg.onnx        (盲道分割)
    app/src/main/assets/models/yoloe_detect.onnx     (障碍物/物品检测)
    app/src/main/assets/models/trafficlight.onnx     (红绿灯检测)
    app/src/main/assets/models/shopping.onnx         (物品识别)

注意:
    - hand_landmarker.task 是 MediaPipe 格式，无需转换，直接复制即可
    - 模型文件较大(50-150MB)，APK 会很大，建议后续改为首次启动下载
"""

import os
import sys
import shutil
from pathlib import Path

# 路径配置
PROJECT_ROOT = Path(__file__).parent
ORIGINAL_MODEL_DIR = Path(r"Q:/xiaolongxia/xiaxia/OpenAIglasses_for_Navigation-main/model")
OUTPUT_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "models"

# 模型映射: 原始文件名 -> (输出文件名, ONNX opset版本, 是否为YOLOE模型)
MODEL_MAP = {
    "yolo-seg.pt": ("yolo_seg.onnx", 11, False),
    "yoloe-11l-seg.pt": ("yoloe_detect.onnx", 12, True),  # YOLO-E 使用 einsum，需要 opset 12+
    "trafficlight.pt": ("trafficlight.onnx", 11, False),
    "shoppingbest5.pt": ("shopping.onnx", 11, False),
}

# YOLOE 障碍物检测白名单（对应原项目 obstacle_detector_client.py）
YOLOE_WHITELIST = [
    'bicycle', 'car', 'motorcycle', 'bus', 'truck', 'animal', 'scooter', 'stroller', 'dog',
    'pole', 'post', 'column', 'pillar', 'stanchion', 'bollard', 'utility pole',
    'telegraph pole', 'light pole', 'street pole', 'signpost', 'support post',
    'vertical post', 'bench', 'chair', 'potted plant', 'hydrant', 'cone', 'stone', 'box'
]


def check_dependencies():
    """检查必要的依赖是否安装"""
    try:
        import ultralytics
        print(f"✅ ultralytics 版本: {ultralytics.__version__}")
    except ImportError:
        print("❌ 未安装 ultralytics，请运行: pip install ultralytics")
        sys.exit(1)

    try:
        import torch
        print(f"✅ PyTorch 版本: {torch.__version__}")
    except ImportError:
        print("❌ 未安装 PyTorch，请运行: pip install torch")
        sys.exit(1)


def convert_model(pt_path: Path, onnx_path: Path, img_size: int = 640, opset: int = 11, is_yoloe: bool = False):
    """
    将 PyTorch YOLO 模型转换为 ONNX 格式

    Args:
        pt_path: 原始 .pt 模型路径
        onnx_path: 输出 .onnx 路径
        img_size: 输入图像尺寸 (默认 640x640)
        opset: ONNX opset 版本 (默认 11，YOLO-E 需要 12+)
        is_yoloe: 是否为 YOLOE 开放词汇模型（需要 set_classes）
    """
    from ultralytics import YOLO

    print(f"\n{'='*60}")
    print(f"转换: {pt_path.name} -> {onnx_path.name}")
    print(f"输入尺寸: {img_size}x{img_size}, opset: {opset}")
    print(f"{'='*60}")

    # 加载模型
    model = YOLO(str(pt_path))

    # 如果是 YOLOE 模型，需要设置类别后再导出
    if is_yoloe:
        try:
            from ultralytics import YOLOE
            yoloe_model = YOLOE(str(pt_path))
            text_pe = yoloe_model.get_text_pe(YOLOE_WHITELIST)
            yoloe_model.set_classes(YOLOE_WHITELIST, text_pe)
            model = yoloe_model
            print(f"✅ YOLOE 已设置 {len(YOLOE_WHITELIST)} 个类别: {YOLOE_WHITELIST[:5]}...")
        except ImportError:
            print("⚠️ 未安装 YOLOE 支持，使用默认 YOLO 类别导出")
        except Exception as e:
            print(f"⚠️ YOLOE set_classes 失败: {e}，使用默认类别导出")

    # 导出为 ONNX
    export_path = model.export(
        format="onnx",
        imgsz=img_size,
        opset=opset,
        simplify=True,      # 简化计算图
        dynamic=False,       # 固定输入尺寸，安卓端更高效
        half=False,          # 不用 FP16，保证 CPU 兼容性
    )

    print(f"✅ 导出成功: {export_path}")

    # 移动到目标目录
    export_file = Path(export_path)
    if export_file.exists():
        shutil.copy2(export_file, onnx_path)
        size_mb = onnx_path.stat().st_size / (1024 * 1024)
        print(f"✅ 已复制到: {onnx_path} ({size_mb:.1f} MB)")
        # 清理临时导出文件
        if export_file != onnx_path:
            export_file.unlink(missing_ok=True)
    else:
        print(f"❌ 导出文件不存在: {export_path}")
        return False

    return True


def copy_mediapipe_model():
    """复制 MediaPipe hand_landmarker.task（无需转换）"""
    src = ORIGINAL_MODEL_DIR / "hand_landmarker.task"
    dst = OUTPUT_DIR / "hand_landmarker.task"

    if src.exists():
        shutil.copy2(src, dst)
        size_mb = dst.stat().st_size / (1024 * 1024)
        print(f"\n✅ 复制 MediaPipe 模型: {dst} ({size_mb:.1f} MB)")
    else:
        print(f"\n⚠️ MediaPipe 模型不存在: {src}")


def verify_onnx_models():
    """验证生成的 ONNX 模型是否有效"""
    try:
        import onnxruntime as ort
    except ImportError:
        print("\n⚠️ 未安装 onnxruntime，跳过验证。建议运行: pip install onnxruntime")
        return

    print(f"\n{'='*60}")
    print("验证 ONNX 模型")
    print(f"{'='*60}")

    for onnx_file in OUTPUT_DIR.glob("*.onnx"):
        try:
            session = ort.InferenceSession(str(onnx_file))
            inputs = session.get_inputs()
            outputs = session.get_outputs()

            print(f"\n✅ {onnx_file.name}")
            print(f"   输入: {[f'{i.name} {i.shape} {i.type}' for i in inputs]}")
            print(f"   输出: {[f'{o.name} {o.shape} {o.type}' for o in outputs]}")

        except Exception as e:
            print(f"\n❌ {onnx_file.name} 验证失败: {e}")


def main():
    print("=" * 60)
    print("盲人导航安卓应用 - 模型转换工具")
    print("=" * 60)

    # 检查依赖
    check_dependencies()

    # 创建输出目录
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"\n输出目录: {OUTPUT_DIR}")

    # 检查原始模型目录
    if not ORIGINAL_MODEL_DIR.exists():
        print(f"❌ 原始模型目录不存在: {ORIGINAL_MODEL_DIR}")
        print("请确认原项目路径正确")
        sys.exit(1)

    # 逐个转换
    success_count = 0
    for pt_name, (onnx_name, opset, is_yoloe) in MODEL_MAP.items():
        pt_path = ORIGINAL_MODEL_DIR / pt_name
        onnx_path = OUTPUT_DIR / onnx_name

        if not pt_path.exists():
            print(f"\n⚠️ 跳过 {pt_name}（文件不存在）")
            continue

        if onnx_path.exists():
            print(f"\n⏭️ 跳过 {onnx_name}（已存在，删除后重新转换）")
            continue

        try:
            if convert_model(pt_path, onnx_path, opset=opset, is_yoloe=is_yoloe):
                success_count += 1
        except Exception as e:
            print(f"\n❌ 转换 {pt_name} 失败: {e}")
            import traceback
            traceback.print_exc()

    # 复制 MediaPipe 模型
    copy_mediapipe_model()

    # 验证
    verify_onnx_models()

    # 汇总
    print(f"\n{'='*60}")
    print(f"转换完成！成功: {success_count}/{len(MODEL_MAP)}")
    print(f"模型目录: {OUTPUT_DIR}")
    print(f"{'='*60}")

    # 列出所有输出文件
    print("\n生成的文件:")
    for f in sorted(OUTPUT_DIR.iterdir()):
        size_mb = f.stat().st_size / (1024 * 1024)
        print(f"  {f.name:30s} {size_mb:8.1f} MB")


if __name__ == "__main__":
    main()
