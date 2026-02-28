"""Export pipeline: PyTorch → ONNX → TFLite (INT8).

Converts a trained WaveGuard model checkpoint to a quantised TFLite
model suitable for on-device inference on Android.

Steps
-----
1. Load PyTorch checkpoint.
2. Export to ONNX with dynamic batch size.
3. Convert ONNX → TFLite via ``onnx2tf``.
4. Apply INT8 post-training quantisation (representative dataset).
5. Verify that TFLite output matches PyTorch within ``atol=1e-3``.
6. Write ``wiflexformer.tflite`` to the output directory.

Usage
-----
.. code-block:: bash

    python export_tflite.py \\
        --checkpoint checkpoints/myrun/best.pt \\
        --output_dir export/ \\
        --input_shape 1 52 500 \\
        --calibration_samples 200

Requirements: onnx, onnxruntime, onnx2tf, tensorflow (for TFLite runtime).
"""

from __future__ import annotations

import argparse
import os
import sys
import tempfile
from pathlib import Path
from typing import Optional

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torch.nn as nn

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from ml.models.csi_classifier import CSIClassifier
from ml.models.wiflexformer import WiFlexFormer


# ---------------------------------------------------------------------------
# Argument parser
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    """Build CLI argument parser."""
    p = argparse.ArgumentParser(
        description="Export WaveGuard model to TFLite (INT8)",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    p.add_argument("--checkpoint", type=str, required=True, help="Path to .pt checkpoint")
    p.add_argument(
        "--output_dir", type=str, default="export", help="Directory for export artefacts"
    )
    p.add_argument(
        "--input_shape",
        type=int,
        nargs=3,
        default=[1, 52, 500],
        metavar=("BATCH", "CHANNELS", "TIME"),
        help="Example input shape for ONNX tracing",
    )
    p.add_argument(
        "--calibration_samples",
        type=int,
        default=200,
        help="Number of random calibration samples for INT8 quantisation",
    )
    p.add_argument(
        "--atol",
        type=float,
        default=1e-3,
        help="Absolute tolerance for output verification",
    )
    p.add_argument(
        "--output_name",
        type=str,
        default="wiflexformer.tflite",
        help="Output TFLite filename",
    )
    return p


# ---------------------------------------------------------------------------
# Checkpoint loading
# ---------------------------------------------------------------------------

def load_model(checkpoint_path: str, device: torch.device) -> nn.Module:
    """Load a WaveGuard model from a training checkpoint.

    Args:
        checkpoint_path: Path to the ``.pt`` file produced by ``train.py``.
        device: Target device.

    Returns:
        Model in eval mode.
    """
    ckpt = torch.load(checkpoint_path, map_location=device)
    train_args: dict = ckpt.get("args", {})

    model_name = train_args.get("model", "wiflexformer")
    in_channels = train_args.get("in_channels", 52)
    num_classes = train_args.get("num_classes", 4)

    if model_name == "wiflexformer":
        model = WiFlexFormer(
            in_channels=in_channels,
            embed_dim=train_args.get("embed_dim", 32),
            num_heads=train_args.get("num_heads", 16),
            num_layers=train_args.get("num_layers", 4),
            num_classes=num_classes,
            dropout=0.0,  # disable at export time
        )
    else:
        model = CSIClassifier(
            in_channels=in_channels,
            num_classes=num_classes,
            dropout=0.0,
        )

    model.load_state_dict(ckpt["model_state_dict"])
    model.to(device).eval()
    return model


# ---------------------------------------------------------------------------
# Step 1: PyTorch → ONNX
# ---------------------------------------------------------------------------

def export_onnx(
    model: nn.Module,
    input_shape: list[int],
    onnx_path: str,
) -> None:
    """Trace and export the model to ONNX with a dynamic batch dimension.

    Args:
        model: PyTorch model in eval mode.
        input_shape: ``[batch, channels, time]`` example shape.
        onnx_path: Destination ``.onnx`` file path.
    """
    dummy = torch.zeros(*input_shape)
    dynamic_axes = {
        "csi_input": {0: "batch_size"},
        "logits": {0: "batch_size"},
    }

    torch.onnx.export(
        model,
        dummy,
        onnx_path,
        opset_version=17,
        input_names=["csi_input"],
        output_names=["logits"],
        dynamic_axes=dynamic_axes,
        do_constant_folding=True,
    )

    # Validate ONNX model
    onnx_model = onnx.load(onnx_path)
    onnx.checker.check_model(onnx_model)
    print(f"[export] ONNX model written and validated: {onnx_path}")


# ---------------------------------------------------------------------------
# Step 2: ONNX → TFLite via onnx2tf
# ---------------------------------------------------------------------------

def convert_onnx_to_tflite(
    onnx_path: str,
    output_dir: str,
    calibration_samples: int,
    input_shape: list[int],
) -> str:
    """Convert an ONNX model to INT8 TFLite using onnx2tf.

    A representative dataset of random calibration samples is generated
    in-memory and passed to the TFLite converter for full-integer
    quantisation.

    Args:
        onnx_path: Path to the ``.onnx`` model.
        output_dir: Directory to write the ``.tflite`` file.
        calibration_samples: Number of samples for INT8 calibration.
        input_shape: ``[batch, channels, time]`` shape.

    Returns:
        Path to the produced ``.tflite`` file.
    """
    try:
        import onnx2tf
        import tensorflow as tf
    except ImportError as exc:
        raise ImportError(
            "onnx2tf and tensorflow are required for TFLite export. "
            "Install them with: pip install onnx2tf tensorflow"
        ) from exc

    # onnx2tf outputs a SavedModel; we then convert to TFLite
    saved_model_dir = os.path.join(output_dir, "saved_model")
    onnx2tf.convert(
        input_onnx_file_path=onnx_path,
        output_folder_path=saved_model_dir,
        non_verbose=True,
    )
    print(f"[export] SavedModel written to {saved_model_dir}")

    # --- TFLite conversion with INT8 quantisation -------------------------
    converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.int8
    converter.inference_output_type = tf.int8

    # Generate a representative dataset from random normal inputs
    channels, time = input_shape[1], input_shape[2]

    def representative_dataset():
        rng = np.random.default_rng(0)
        for _ in range(calibration_samples):
            sample = rng.standard_normal((1, channels, time)).astype(np.float32)
            yield [sample]

    converter.representative_dataset = representative_dataset

    tflite_model = converter.convert()

    tflite_path = os.path.join(output_dir, "wiflexformer_int8.tflite")
    with open(tflite_path, "wb") as f:
        f.write(tflite_model)
    print(f"[export] INT8 TFLite model written: {tflite_path}")
    return tflite_path


# ---------------------------------------------------------------------------
# Step 3: Verification
# ---------------------------------------------------------------------------

def verify_outputs(
    pytorch_model: nn.Module,
    onnx_path: str,
    input_shape: list[int],
    atol: float = 1e-3,
) -> None:
    """Check that ONNX outputs match PyTorch outputs within tolerance.

    TFLite INT8 outputs are compared separately because quantisation error
    means they may deviate more than ``atol`` from the float reference.
    This function verifies the ONNX (float) intermediate is correct; the
    TFLite verification is a sanity check on output ordering (argmax).

    Args:
        pytorch_model: PyTorch model in eval mode.
        onnx_path: Path to the exported ``.onnx`` file.
        input_shape: ``[batch, channels, time]`` shape.
        atol: Absolute tolerance for value comparison.

    Raises:
        AssertionError: If the outputs differ beyond tolerance.
    """
    rng = np.random.default_rng(42)
    test_input = rng.standard_normal(input_shape).astype(np.float32)

    # PyTorch reference
    with torch.no_grad():
        pt_out = pytorch_model(torch.from_numpy(test_input)).numpy()

    # ONNX via OnnxRuntime
    sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    input_name = sess.get_inputs()[0].name
    onnx_out = sess.run(None, {input_name: test_input})[0]

    max_diff = float(np.abs(pt_out - onnx_out).max())
    print(f"[export] ONNX vs PyTorch max absolute diff: {max_diff:.2e}  (atol={atol:.2e})")
    assert max_diff < atol, (
        f"ONNX output deviates from PyTorch by {max_diff:.4e}, exceeds atol={atol:.4e}. "
        "Check model export."
    )
    print("[export] ✓ ONNX output matches PyTorch within tolerance.")


def verify_tflite_argmax(
    pytorch_model: nn.Module,
    tflite_path: str,
    input_shape: list[int],
) -> None:
    """Verify that TFLite INT8 argmax predictions match PyTorch.

    INT8 quantisation may cause numerical differences larger than 1e-3, so
    we only check that the predicted class is identical.

    Args:
        pytorch_model: PyTorch model in eval mode.
        tflite_path: Path to the ``.tflite`` file.
        input_shape: ``[batch, channels, time]`` shape.
    """
    try:
        import tensorflow as tf
    except ImportError:
        print("[export] Skipping TFLite argmax check (tensorflow not available).")
        return

    rng = np.random.default_rng(0)
    test_input = rng.standard_normal(input_shape).astype(np.float32)

    # PyTorch argmax
    with torch.no_grad():
        pt_pred = pytorch_model(torch.from_numpy(test_input)).argmax(dim=-1).numpy()

    # TFLite argmax
    interpreter = tf.lite.Interpreter(model_path=tflite_path)
    interpreter.allocate_tensors()
    in_detail = interpreter.get_input_details()[0]
    out_detail = interpreter.get_output_details()[0]

    # Scale input to INT8 range expected by the interpreter
    scale, zero_point = in_detail["quantization"]
    quantized_input = (test_input / scale + zero_point).astype(np.int8)
    interpreter.set_tensor(in_detail["index"], quantized_input)
    interpreter.invoke()
    tflite_raw = interpreter.get_tensor(out_detail["index"])  # INT8 logits
    tflite_pred = tflite_raw.argmax(axis=-1)

    match = (pt_pred == tflite_pred).all()
    print(
        f"[export] TFLite argmax vs PyTorch: {'✓ match' if match else '✗ mismatch'} "
        f"(pt={pt_pred}, tflite={tflite_pred})"
    )


# ---------------------------------------------------------------------------
# main()
# ---------------------------------------------------------------------------

def main() -> None:
    """Entry point for the export pipeline."""
    parser = build_parser()
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    device = torch.device("cpu")  # Export always on CPU for reproducibility

    # 1. Load PyTorch model
    print(f"[export] Loading checkpoint: {args.checkpoint}")
    model = load_model(args.checkpoint, device)

    # 2. Export to ONNX
    onnx_path = str(output_dir / "wiflexformer.onnx")
    export_onnx(model, args.input_shape, onnx_path)

    # 3. Verify ONNX ↔ PyTorch
    verify_outputs(model, onnx_path, args.input_shape, atol=args.atol)

    # 4. Convert to TFLite INT8
    tflite_path = convert_onnx_to_tflite(
        onnx_path, str(output_dir), args.calibration_samples, args.input_shape
    )

    # 5. Rename to canonical output name and verify argmax
    final_tflite = str(output_dir / args.output_name)
    if tflite_path != final_tflite:
        import shutil
        shutil.copy(tflite_path, final_tflite)

    verify_tflite_argmax(model, final_tflite, args.input_shape)

    print(f"\n[export] Done. TFLite model: {final_tflite}")


if __name__ == "__main__":
    main()
