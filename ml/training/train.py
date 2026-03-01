"""Training script for WaveGuard CSI activity recognition models.

Supports WiFlexFormer and CSIClassifier architectures.

Usage
-----
.. code-block:: bash

    python train.py \\
        --data_root /data/waveguard \\
        --dataset waveguard \\
        --model wiflexformer \\
        --epochs 100 \\
        --batch_size 64 \\
        --lr 1e-3 \\
        --output_dir checkpoints/ \\
        --wandb_project waveguard

All hyperparameters are configurable via argparse.  WandB logging is
optional; pass ``--no_wandb`` to disable it.
"""

from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path
from typing import Optional, Tuple

import numpy as np
import torch
import torch.nn as nn
from sklearn.metrics import f1_score
from torch import Tensor
from torch.cuda.amp import GradScaler, autocast
from torch.optim import AdamW
from torch.optim.lr_scheduler import CosineAnnealingLR
from torch.utils.data import DataLoader
from tqdm import tqdm

# Add project root to path so imports work when run directly
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from ml.models.csi_classifier import CSIClassifier
from ml.models.wiflexformer import WiFlexFormer
from ml.preprocessing.data_loader import (
    ThreeDODataset,
    UTHARDataset,
    WaveGuardDataset,
)


# ---------------------------------------------------------------------------
# Argument parser
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    """Build the CLI argument parser.

    Returns:
        Configured :class:`argparse.ArgumentParser` instance.
    """
    p = argparse.ArgumentParser(
        description="Train WaveGuard activity recognition model",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )

    # Data
    data = p.add_argument_group("Data")
    data.add_argument("--data_root", type=str, required=True, help="Root directory of dataset")
    data.add_argument(
        "--dataset",
        choices=["waveguard", "uthar", "3do"],
        default="waveguard",
        help="Dataset type",
    )
    data.add_argument("--num_workers", type=int, default=4, help="DataLoader workers")
    data.add_argument("--val_ratio", type=float, default=0.15, help="Validation split ratio")
    data.add_argument("--test_ratio", type=float, default=0.15, help="Test split ratio")
    data.add_argument("--seed", type=int, default=42, help="Random seed")

    # Model
    model_g = p.add_argument_group("Model")
    model_g.add_argument(
        "--model",
        choices=["wiflexformer", "csi_classifier"],
        default="wiflexformer",
        help="Model architecture",
    )
    model_g.add_argument("--in_channels", type=int, default=52, help="CSI subcarrier channels")
    model_g.add_argument("--num_classes", type=int, default=4, help="Number of classes")
    model_g.add_argument("--embed_dim", type=int, default=32, help="WiFlexFormer embed dim")
    model_g.add_argument("--num_heads", type=int, default=16, help="WiFlexFormer attention heads")
    model_g.add_argument("--num_layers", type=int, default=4, help="WiFlexFormer encoder layers")
    model_g.add_argument("--dropout", type=float, default=0.1, help="Dropout probability")

    # Training
    train_g = p.add_argument_group("Training")
    train_g.add_argument("--epochs", type=int, default=100, help="Maximum training epochs")
    train_g.add_argument("--batch_size", type=int, default=64, help="Batch size")
    train_g.add_argument("--lr", type=float, default=1e-3, help="Peak learning rate")
    train_g.add_argument("--weight_decay", type=float, default=1e-4, help="AdamW weight decay")
    train_g.add_argument(
        "--patience", type=int, default=15, help="Early stopping patience (epochs)"
    )
    train_g.add_argument(
        "--min_delta", type=float, default=1e-4, help="Minimum F1 improvement for early stopping"
    )
    train_g.add_argument("--amp", action="store_true", default=True, help="Use mixed precision")
    train_g.add_argument("--no_amp", dest="amp", action="store_false")
    train_g.add_argument(
        "--use_class_weights",
        action="store_true",
        default=False,
        help="Weight CE loss by inverse class frequency",
    )

    # Output
    out_g = p.add_argument_group("Output")
    out_g.add_argument("--output_dir", type=str, default="checkpoints", help="Checkpoint dir")
    out_g.add_argument("--run_name", type=str, default=None, help="Run name (defaults to timestamp)")

    # WandB
    wb = p.add_argument_group("WandB")
    wb.add_argument("--wandb_project", type=str, default="waveguard", help="WandB project name")
    wb.add_argument(
        "--no_wandb", action="store_true", default=False, help="Disable WandB logging"
    )

    return p


# ---------------------------------------------------------------------------
# Dataset factory
# ---------------------------------------------------------------------------

def build_datasets(args: argparse.Namespace):
    """Instantiate train / val / test splits for the chosen dataset.

    Args:
        args: Parsed arguments.

    Returns:
        Tuple of ``(train_ds, val_ds, test_ds)``.
    """
    common_kwargs = dict(
        val_ratio=args.val_ratio,
        test_ratio=args.test_ratio,
        seed=args.seed,
    )

    if args.dataset == "waveguard":
        cls = WaveGuardDataset
        path_kwarg = {"path": args.data_root}
    elif args.dataset == "uthar":
        cls = UTHARDataset
        path_kwarg = {"root": args.data_root}
    else:  # 3do
        cls = ThreeDODataset
        path_kwarg = {"root": args.data_root}

    train_ds = cls(split="train", **path_kwarg, **common_kwargs)
    val_ds = cls(split="val", **path_kwarg, **common_kwargs)
    test_ds = cls(split="test", **path_kwarg, **common_kwargs)
    return train_ds, val_ds, test_ds


# ---------------------------------------------------------------------------
# Model factory
# ---------------------------------------------------------------------------

def build_model(args: argparse.Namespace) -> nn.Module:
    """Instantiate the chosen model.

    Args:
        args: Parsed arguments.

    Returns:
        Initialised model (not yet moved to device).
    """
    if args.model == "wiflexformer":
        return WiFlexFormer(
            in_channels=args.in_channels,
            embed_dim=args.embed_dim,
            num_heads=args.num_heads,
            num_layers=args.num_layers,
            num_classes=args.num_classes,
            dropout=args.dropout,
        )
    return CSIClassifier(
        in_channels=args.in_channels,
        num_classes=args.num_classes,
        dropout=args.dropout,
    )


# ---------------------------------------------------------------------------
# EarlyStopping
# ---------------------------------------------------------------------------

class EarlyStopping:
    """Monitor validation F1 and signal when to stop training.

    Args:
        patience: Number of epochs without improvement before stopping.
        min_delta: Minimum improvement to reset the patience counter.
    """

    def __init__(self, patience: int = 15, min_delta: float = 1e-4) -> None:
        self.patience = patience
        self.min_delta = min_delta
        self.best_score = -np.inf
        self.counter = 0
        self.should_stop = False

    def step(self, score: float) -> bool:
        """Update state based on the latest validation score.

        Args:
            score: Validation metric (higher is better, e.g. macro-F1).

        Returns:
            ``True`` if training should stop.
        """
        if score > self.best_score + self.min_delta:
            self.best_score = score
            self.counter = 0
        else:
            self.counter += 1
            if self.counter >= self.patience:
                self.should_stop = True
        return self.should_stop


# ---------------------------------------------------------------------------
# Training and validation loops
# ---------------------------------------------------------------------------

def train_epoch(
    model: nn.Module,
    loader: DataLoader,
    optimizer: torch.optim.Optimizer,
    criterion: nn.Module,
    scaler: GradScaler,
    device: torch.device,
    use_amp: bool,
) -> Tuple[float, float]:
    """Run one training epoch.

    Returns:
        Tuple of ``(mean_loss, macro_f1)``.
    """
    model.train()
    total_loss = 0.0
    all_preds: list[int] = []
    all_labels: list[int] = []

    for x, y in loader:
        x = x.to(device, non_blocking=True)
        y = y.to(device, non_blocking=True)

        optimizer.zero_grad()
        with autocast(enabled=use_amp):
            logits = model(x)
            loss = criterion(logits, y)

        scaler.scale(loss).backward()
        scaler.unscale_(optimizer)
        nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
        scaler.step(optimizer)
        scaler.update()

        total_loss += loss.item()
        preds = logits.argmax(dim=-1).cpu().tolist()
        all_preds.extend(preds)
        all_labels.extend(y.cpu().tolist())

    mean_loss = total_loss / max(len(loader), 1)
    macro_f1 = f1_score(all_labels, all_preds, average="macro", zero_division=0)
    return mean_loss, macro_f1


@torch.no_grad()
def validate(
    model: nn.Module,
    loader: DataLoader,
    criterion: nn.Module,
    device: torch.device,
    use_amp: bool,
) -> Tuple[float, float]:
    """Evaluate model on a validation or test loader.

    Returns:
        Tuple of ``(mean_loss, macro_f1)``.
    """
    model.eval()
    total_loss = 0.0
    all_preds: list[int] = []
    all_labels: list[int] = []

    for x, y in loader:
        x = x.to(device, non_blocking=True)
        y = y.to(device, non_blocking=True)
        with autocast(enabled=use_amp):
            logits = model(x)
            loss = criterion(logits, y)
        total_loss += loss.item()
        all_preds.extend(logits.argmax(dim=-1).cpu().tolist())
        all_labels.extend(y.cpu().tolist())

    mean_loss = total_loss / max(len(loader), 1)
    macro_f1 = f1_score(all_labels, all_preds, average="macro", zero_division=0)
    return mean_loss, macro_f1


# ---------------------------------------------------------------------------
# main()
# ---------------------------------------------------------------------------

def main() -> None:
    """Entry point for the training script."""
    parser = build_parser()
    args = parser.parse_args()

    # ---- Reproducibility --------------------------------------------------
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)

    # ---- Device -----------------------------------------------------------
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"[train] Using device: {device}")

    # ---- WandB (optional) -------------------------------------------------
    wandb_run = None
    if not args.no_wandb:
        try:
            import wandb

            run_name = args.run_name or f"{args.model}_{int(time.time())}"
            wandb_run = wandb.init(
                project=args.wandb_project,
                name=run_name,
                config=vars(args),
            )
            print(f"[train] WandB run: {wandb_run.name}")
        except Exception as exc:
            print(f"[train] WandB init failed ({exc}), continuing without logging.")

    # ---- Datasets ---------------------------------------------------------
    print(f"[train] Loading {args.dataset} dataset from {args.data_root} …")
    train_ds, val_ds, test_ds = build_datasets(args)
    print(
        f"[train] Split sizes – train: {len(train_ds)}, "
        f"val: {len(val_ds)}, test: {len(test_ds)}"
    )

    train_loader = DataLoader(
        train_ds, batch_size=args.batch_size, shuffle=True,
        num_workers=args.num_workers, pin_memory=True,
    )
    val_loader = DataLoader(
        val_ds, batch_size=args.batch_size, shuffle=False,
        num_workers=args.num_workers, pin_memory=True,
    )

    # ---- Model ------------------------------------------------------------
    model = build_model(args).to(device)
    print(f"[train] Model: {args.model} | params: {model.num_parameters:,}")

    # ---- Loss function ----------------------------------------------------
    if args.use_class_weights and hasattr(train_ds, "class_weights"):
        weights = train_ds.class_weights.to(device)
        criterion = nn.CrossEntropyLoss(weight=weights)
        print("[train] Using inverse-frequency class weights.")
    else:
        criterion = nn.CrossEntropyLoss()

    # ---- Optimiser + scheduler -------------------------------------------
    optimizer = AdamW(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)
    scheduler = CosineAnnealingLR(optimizer, T_max=args.epochs, eta_min=args.lr * 1e-2)
    scaler = GradScaler(enabled=args.amp)
    early_stop = EarlyStopping(patience=args.patience, min_delta=args.min_delta)

    # ---- Output directory -------------------------------------------------
    run_name = args.run_name or f"{args.model}_{int(time.time())}"
    output_dir = Path(args.output_dir) / run_name
    output_dir.mkdir(parents=True, exist_ok=True)
    best_ckpt = output_dir / "best.pt"

    # ---- Training loop ----------------------------------------------------
    best_val_f1 = -np.inf

    for epoch in range(1, args.epochs + 1):
        t0 = time.time()
        train_loss, train_f1 = train_epoch(
            model, train_loader, optimizer, criterion, scaler, device, args.amp
        )
        val_loss, val_f1 = validate(model, val_loader, criterion, device, args.amp)
        scheduler.step()

        elapsed = time.time() - t0
        print(
            f"Epoch {epoch:3d}/{args.epochs} | "
            f"train loss {train_loss:.4f} f1 {train_f1:.4f} | "
            f"val loss {val_loss:.4f} f1 {val_f1:.4f} | "
            f"lr {scheduler.get_last_lr()[0]:.2e} | {elapsed:.1f}s"
        )

        # Log to WandB
        if wandb_run is not None:
            wandb_run.log(
                {
                    "epoch": epoch,
                    "train/loss": train_loss,
                    "train/f1": train_f1,
                    "val/loss": val_loss,
                    "val/f1": val_f1,
                    "lr": scheduler.get_last_lr()[0],
                }
            )

        # Save best checkpoint
        if val_f1 > best_val_f1:
            best_val_f1 = val_f1
            torch.save(
                {
                    "epoch": epoch,
                    "model_state_dict": model.state_dict(),
                    "optimizer_state_dict": optimizer.state_dict(),
                    "val_f1": val_f1,
                    "args": vars(args),
                },
                best_ckpt,
            )
            print(f"  ✓ Saved best checkpoint (val_f1={val_f1:.4f})")

        if early_stop.step(val_f1):
            print(f"[train] Early stopping triggered after {epoch} epochs.")
            break

    print(f"\n[train] Best validation F1: {best_val_f1:.4f} → {best_ckpt}")

    if wandb_run is not None:
        wandb_run.finish()


if __name__ == "__main__":
    main()
