"""Evaluation script for WaveGuard CSI activity recognition models.

Computes per-class precision, recall, F1, a full confusion matrix, and
fall-detection–specific sensitivity / specificity metrics.

Usage
-----
.. code-block:: bash

    python evaluate.py \\
        --checkpoint checkpoints/myrun/best.pt \\
        --data_root /data/waveguard \\
        --dataset waveguard \\
        --split test
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Dict, List, Tuple

import matplotlib.pyplot as plt
import numpy as np
import torch
import torch.nn as nn
from sklearn.metrics import (
    ConfusionMatrixDisplay,
    classification_report,
    confusion_matrix,
    precision_recall_fscore_support,
)
from torch import Tensor
from torch.cuda.amp import autocast
from torch.utils.data import DataLoader
from tqdm import tqdm

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from ml.models.csi_classifier import CSIClassifier
from ml.models.wiflexformer import WiFlexFormer
from ml.preprocessing.data_loader import (
    ThreeDODataset,
    UTHARDataset,
    WaveGuardDataset,
)


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

CLASS_NAMES = ["empty", "presence", "movement", "fall"]
FALL_CLASS_IDX = 3


# ---------------------------------------------------------------------------
# Argument parser
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    """Build the CLI argument parser."""
    p = argparse.ArgumentParser(
        description="Evaluate a trained WaveGuard model checkpoint",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    p.add_argument("--checkpoint", type=str, required=True, help="Path to .pt checkpoint file")
    p.add_argument("--data_root", type=str, required=True, help="Root directory of dataset")
    p.add_argument(
        "--dataset",
        choices=["waveguard", "uthar", "3do"],
        default="waveguard",
        help="Dataset type",
    )
    p.add_argument(
        "--split",
        choices=["train", "val", "test"],
        default="test",
        help="Dataset split to evaluate on",
    )
    p.add_argument("--batch_size", type=int, default=128, help="Evaluation batch size")
    p.add_argument("--num_workers", type=int, default=4, help="DataLoader workers")
    p.add_argument("--seed", type=int, default=42, help="Random seed (must match training seed)")
    p.add_argument("--val_ratio", type=float, default=0.15, help="Val split ratio used at training")
    p.add_argument("--test_ratio", type=float, default=0.15, help="Test split ratio used at training")
    p.add_argument(
        "--output_dir",
        type=str,
        default=None,
        help="Directory to save confusion matrix plot (optional)",
    )
    p.add_argument("--no_amp", action="store_true", default=False, help="Disable mixed precision")
    return p


# ---------------------------------------------------------------------------
# Dataset loader
# ---------------------------------------------------------------------------

def load_dataset(args: argparse.Namespace):
    """Instantiate the evaluation dataset.

    Args:
        args: Parsed CLI arguments.

    Returns:
        Dataset instance for the requested split.
    """
    common = dict(
        split=args.split,
        val_ratio=args.val_ratio,
        test_ratio=args.test_ratio,
        seed=args.seed,
    )
    if args.dataset == "waveguard":
        return WaveGuardDataset(path=args.data_root, **common)
    if args.dataset == "uthar":
        return UTHARDataset(root=args.data_root, **common)
    return ThreeDODataset(root=args.data_root, **common)


# ---------------------------------------------------------------------------
# Checkpoint loader
# ---------------------------------------------------------------------------

def load_checkpoint(checkpoint_path: str, device: torch.device) -> Tuple[nn.Module, dict]:
    """Load model and training metadata from a checkpoint file.

    The checkpoint must contain ``model_state_dict`` and ``args`` (the
    dict produced by ``vars(args)`` during training).

    Args:
        checkpoint_path: Path to the ``.pt`` checkpoint.
        device: Target device.

    Returns:
        Tuple of ``(model, training_args_dict)``.
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
            dropout=train_args.get("dropout", 0.1),
        )
    else:
        model = CSIClassifier(
            in_channels=in_channels,
            num_classes=num_classes,
            dropout=train_args.get("dropout", 0.3),
        )

    model.load_state_dict(ckpt["model_state_dict"])
    model.to(device).eval()
    return model, train_args


# ---------------------------------------------------------------------------
# Inference
# ---------------------------------------------------------------------------

@torch.no_grad()
def run_inference(
    model: nn.Module,
    loader: DataLoader,
    device: torch.device,
    use_amp: bool,
) -> Tuple[np.ndarray, np.ndarray]:
    """Run inference and collect all predictions and ground-truth labels.

    Args:
        model: Trained model in eval mode.
        loader: DataLoader over the evaluation split.
        device: Target device.
        use_amp: Whether to use mixed-precision inference.

    Returns:
        Tuple of ``(all_preds, all_labels)`` as NumPy arrays.
    """
    all_preds: List[int] = []
    all_labels: List[int] = []

    for x, y in tqdm(loader, desc="Evaluating", leave=False):
        x = x.to(device, non_blocking=True)
        with autocast(enabled=use_amp):
            logits = model(x)
        preds = logits.argmax(dim=-1).cpu().tolist()
        all_preds.extend(preds)
        all_labels.extend(y.tolist())

    return np.array(all_preds, dtype=np.int64), np.array(all_labels, dtype=np.int64)


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------

def compute_per_class_metrics(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    class_names: List[str],
) -> Dict[str, Dict[str, float]]:
    """Compute per-class precision, recall, and F1.

    Args:
        y_true: Ground-truth labels.
        y_pred: Predicted labels.
        class_names: Human-readable class names.

    Returns:
        Nested dict ``{class_name: {precision, recall, f1, support}}``.
    """
    precision, recall, f1, support = precision_recall_fscore_support(
        y_true, y_pred, labels=list(range(len(class_names))), zero_division=0
    )
    result = {}
    for i, name in enumerate(class_names):
        result[name] = {
            "precision": float(precision[i]),
            "recall": float(recall[i]),
            "f1": float(f1[i]),
            "support": int(support[i]),
        }
    return result


def compute_fall_metrics(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    fall_idx: int = FALL_CLASS_IDX,
) -> Dict[str, float]:
    """Compute fall-detection sensitivity and specificity.

    Treats the fall class as positive and all others as negative.

    Args:
        y_true: Ground-truth labels.
        y_pred: Predicted labels.
        fall_idx: Class index corresponding to *fall* (default 3).

    Returns:
        Dict with keys ``sensitivity``, ``specificity``, and ``f1``.
    """
    binary_true = (y_true == fall_idx).astype(int)
    binary_pred = (y_pred == fall_idx).astype(int)

    tp = int(((binary_pred == 1) & (binary_true == 1)).sum())
    tn = int(((binary_pred == 0) & (binary_true == 0)).sum())
    fp = int(((binary_pred == 1) & (binary_true == 0)).sum())
    fn = int(((binary_pred == 0) & (binary_true == 1)).sum())

    sensitivity = tp / (tp + fn) if (tp + fn) > 0 else 0.0  # recall for falls
    specificity = tn / (tn + fp) if (tn + fp) > 0 else 0.0
    precision_fall = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    f1_fall = (
        2 * precision_fall * sensitivity / (precision_fall + sensitivity)
        if (precision_fall + sensitivity) > 0
        else 0.0
    )

    return {
        "sensitivity": sensitivity,
        "specificity": specificity,
        "precision": precision_fall,
        "f1": f1_fall,
        "tp": tp,
        "tn": tn,
        "fp": fp,
        "fn": fn,
    }


def plot_confusion_matrix(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    class_names: List[str],
    save_path: Optional[str] = None,
) -> None:
    """Plot and optionally save a normalised confusion matrix.

    Args:
        y_true: Ground-truth labels.
        y_pred: Predicted labels.
        class_names: Human-readable class names.
        save_path: If provided, save the figure to this path.
    """
    cm = confusion_matrix(y_true, y_pred, labels=list(range(len(class_names))))
    cm_norm = cm.astype(float) / cm.sum(axis=1, keepdims=True).clip(min=1)

    fig, ax = plt.subplots(figsize=(6, 5))
    disp = ConfusionMatrixDisplay(confusion_matrix=cm_norm, display_labels=class_names)
    disp.plot(ax=ax, colorbar=True, cmap="Blues", values_format=".2f")
    ax.set_title("Normalised Confusion Matrix")
    plt.tight_layout()

    if save_path is not None:
        plt.savefig(save_path, dpi=150)
        print(f"[evaluate] Confusion matrix saved to {save_path}")
    else:
        plt.show()
    plt.close(fig)


# ---------------------------------------------------------------------------
# main()
# ---------------------------------------------------------------------------

def main() -> None:
    """Entry point for the evaluation script."""
    parser = build_parser()
    args = parser.parse_args()

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    use_amp = not args.no_amp

    # ---- Load model -------------------------------------------------------
    print(f"[evaluate] Loading checkpoint: {args.checkpoint}")
    model, train_args = load_checkpoint(args.checkpoint, device)
    print(f"[evaluate] Model: {train_args.get('model', 'unknown')} | params: {model.num_parameters:,}")

    # ---- Load dataset -----------------------------------------------------
    print(f"[evaluate] Loading {args.dataset} {args.split} split …")
    dataset = load_dataset(args)
    loader = DataLoader(
        dataset,
        batch_size=args.batch_size,
        shuffle=False,
        num_workers=args.num_workers,
        pin_memory=True,
    )
    print(f"[evaluate] Samples: {len(dataset)}")

    # ---- Inference --------------------------------------------------------
    y_pred, y_true = run_inference(model, loader, device, use_amp)

    # ---- Per-class metrics ------------------------------------------------
    print("\n" + "=" * 60)
    print("Per-class metrics")
    print("=" * 60)
    per_class = compute_per_class_metrics(y_true, y_pred, CLASS_NAMES)
    for cls, m in per_class.items():
        print(
            f"  {cls:<12}  precision={m['precision']:.3f}  "
            f"recall={m['recall']:.3f}  f1={m['f1']:.3f}  "
            f"support={m['support']}"
        )

    print("\nClassification report:")
    print(classification_report(y_true, y_pred, target_names=CLASS_NAMES, zero_division=0))

    # ---- Fall-specific metrics -------------------------------------------
    print("=" * 60)
    print("Fall-detection metrics")
    print("=" * 60)
    fall = compute_fall_metrics(y_true, y_pred)
    print(f"  Sensitivity (recall):  {fall['sensitivity']:.3f}")
    print(f"  Specificity:           {fall['specificity']:.3f}")
    print(f"  Precision:             {fall['precision']:.3f}")
    print(f"  F1 (fall):             {fall['f1']:.3f}")
    print(f"  TP={fall['tp']}  TN={fall['tn']}  FP={fall['fp']}  FN={fall['fn']}")

    # ---- Confusion matrix ------------------------------------------------
    cm_path = None
    if args.output_dir is not None:
        out_dir = Path(args.output_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        cm_path = str(out_dir / "confusion_matrix.png")
    plot_confusion_matrix(y_true, y_pred, CLASS_NAMES, save_path=cm_path)


if __name__ == "__main__":
    main()
