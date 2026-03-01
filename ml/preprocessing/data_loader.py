"""Dataset loaders for CSI-based human activity recognition.

Supported datasets
------------------
* :class:`UTHARDataset`      – UT-HAR benchmark dataset (HDF5 / NPY format)
* :class:`ThreeDODataset`    – 3DO dataset (HDF5 format)
* :class:`WaveGuardDataset`  – Custom WaveGuard recordings (HDF5 format)

All datasets return ``(features, label)`` tensors compatible with
:class:`torch.utils.data.DataLoader` and accept a ``split`` argument
(``"train"``, ``"val"``, ``"test"``) for reproducible stratified splits.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Optional, Tuple, Union

import h5py
import numpy as np
import torch
from sklearn.model_selection import train_test_split
from torch import Tensor
from torch.utils.data import Dataset

from .csi_pipeline import CSIPipeline


# ---------------------------------------------------------------------------
# Type aliases
# ---------------------------------------------------------------------------

ArrayLike = Union[np.ndarray, Tensor]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _stratified_split(
    indices: np.ndarray,
    labels: np.ndarray,
    val_ratio: float = 0.15,
    test_ratio: float = 0.15,
    seed: int = 42,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Return train / val / test index arrays using stratified splitting.

    Args:
        indices: Index array to split.
        labels: Corresponding label array for stratification.
        val_ratio: Fraction of data for validation.
        test_ratio: Fraction of data for testing.
        seed: Random seed for reproducibility.

    Returns:
        Tuple of ``(train_idx, val_idx, test_idx)`` arrays.
    """
    remaining_ratio = val_ratio + test_ratio
    train_idx, temp_idx, _, temp_labels = train_test_split(
        indices, labels[indices],
        test_size=remaining_ratio,
        stratify=labels[indices],
        random_state=seed,
    )
    val_frac_of_temp = val_ratio / remaining_ratio
    val_idx, test_idx = train_test_split(
        temp_idx,
        test_size=1.0 - val_frac_of_temp,
        stratify=temp_labels,
        random_state=seed,
    )
    return train_idx, val_idx, test_idx


# ---------------------------------------------------------------------------
# UTHARDataset
# ---------------------------------------------------------------------------

class UTHARDataset(Dataset):
    """UT-HAR CSI dataset loader.

    Expects an HDF5 file with the following structure::

        /data   – float32 array (N, channels, time)
        /label  – int64   array (N,)

    If an NPY pair (``data.npy`` / ``label.npy``) is found instead,
    those are used as a fallback.

    Args:
        root: Path to the directory containing the dataset file(s).
        split: One of ``"train"``, ``"val"``, ``"test"`` (default ``"train"``).
        preprocess: Whether to apply the CSI preprocessing pipeline.
        pipeline: Optional custom :class:`CSIPipeline` instance.
        val_ratio: Fraction reserved for validation (default 0.15).
        test_ratio: Fraction reserved for testing (default 0.15).
        seed: Random seed for reproducible splits (default 42).
        transform: Optional callable applied to each sample tensor.
    """

    _HDF5_CANDIDATES = ("ut_har.h5", "ut_har.hdf5", "data.h5", "dataset.h5")

    def __init__(
        self,
        root: Union[str, Path],
        split: str = "train",
        preprocess: bool = True,
        pipeline: Optional[CSIPipeline] = None,
        val_ratio: float = 0.15,
        test_ratio: float = 0.15,
        seed: int = 42,
        transform=None,
    ) -> None:
        self.root = Path(root)
        self.split = split.lower()
        self.transform = transform
        self.pipeline = pipeline or (CSIPipeline() if preprocess else None)

        data, labels = self._load()
        all_idx = np.arange(len(labels))
        train_idx, val_idx, test_idx = _stratified_split(
            all_idx, labels, val_ratio, test_ratio, seed
        )

        split_map = {"train": train_idx, "val": val_idx, "test": test_idx}
        if self.split not in split_map:
            raise ValueError(f"split must be one of {list(split_map)}, got {self.split!r}")

        idx = split_map[self.split]
        self.data = data[idx]
        self.labels = labels[idx]

    def _load(self) -> Tuple[np.ndarray, np.ndarray]:
        # Try HDF5 first
        for name in self._HDF5_CANDIDATES:
            p = self.root / name
            if p.exists():
                with h5py.File(p, "r") as f:
                    data = f["data"][:]
                    labels = f["label"][:]
                return data.astype(np.float32), labels.astype(np.int64)

        # Fallback: NPY pair
        data_path = self.root / "data.npy"
        label_path = self.root / "label.npy"
        if data_path.exists() and label_path.exists():
            return (
                np.load(data_path).astype(np.float32),
                np.load(label_path).astype(np.int64),
            )

        raise FileNotFoundError(
            f"Could not find UT-HAR dataset in {self.root}. "
            "Expected an HDF5 file or data.npy / label.npy pair."
        )

    def __len__(self) -> int:
        return len(self.labels)

    def __getitem__(self, idx: int) -> Tuple[Tensor, Tensor]:
        sample = self.data[idx]  # (channels, time)
        label = int(self.labels[idx])

        if self.pipeline is not None:
            sample = self.pipeline(sample.astype(np.float64)).astype(np.float32)

        x = torch.from_numpy(sample)
        y = torch.tensor(label, dtype=torch.long)

        if self.transform is not None:
            x = self.transform(x)

        return x, y


# ---------------------------------------------------------------------------
# ThreeDODataset
# ---------------------------------------------------------------------------

class ThreeDODataset(Dataset):
    """3DO CSI dataset loader.

    Expects an HDF5 file with the following structure::

        /csi_data  – float32 array (N, channels, time)
        /labels    – int64   array (N,)

    Label mapping: 0=empty, 1=presence, 2=movement, 3=fall
    (remapped from the original 3DO label scheme if necessary).

    Args:
        root: Path to the directory containing ``3do.h5`` (or ``data.h5``).
        split: One of ``"train"``, ``"val"``, ``"test"``.
        preprocess: Whether to apply the CSI preprocessing pipeline.
        pipeline: Optional custom :class:`CSIPipeline` instance.
        label_remap: Dict mapping original labels to WaveGuard labels.
        val_ratio: Validation fraction (default 0.15).
        test_ratio: Test fraction (default 0.15).
        seed: Random seed (default 42).
        transform: Optional callable applied to each sample.
    """

    _HDF5_CANDIDATES = ("3do.h5", "3do.hdf5", "data.h5", "dataset.h5")

    def __init__(
        self,
        root: Union[str, Path],
        split: str = "train",
        preprocess: bool = True,
        pipeline: Optional[CSIPipeline] = None,
        label_remap: Optional[dict] = None,
        val_ratio: float = 0.15,
        test_ratio: float = 0.15,
        seed: int = 42,
        transform=None,
    ) -> None:
        self.root = Path(root)
        self.split = split.lower()
        self.transform = transform
        self.label_remap = label_remap or {}
        self.pipeline = pipeline or (CSIPipeline() if preprocess else None)

        data, labels = self._load()
        all_idx = np.arange(len(labels))
        train_idx, val_idx, test_idx = _stratified_split(
            all_idx, labels, val_ratio, test_ratio, seed
        )

        split_map = {"train": train_idx, "val": val_idx, "test": test_idx}
        if self.split not in split_map:
            raise ValueError(f"split must be one of {list(split_map)}, got {self.split!r}")

        idx = split_map[self.split]
        self.data = data[idx]
        self.labels = labels[idx]

    def _load(self) -> Tuple[np.ndarray, np.ndarray]:
        for name in self._HDF5_CANDIDATES:
            p = self.root / name
            if p.exists():
                with h5py.File(p, "r") as f:
                    # Support both key naming conventions
                    data_key = "csi_data" if "csi_data" in f else "data"
                    label_key = "labels" if "labels" in f else "label"
                    data = f[data_key][:]
                    labels = f[label_key][:]
                # Apply label remapping
                if self.label_remap:
                    remapped = labels.copy()
                    for src, dst in self.label_remap.items():
                        remapped[labels == src] = dst
                    labels = remapped
                return data.astype(np.float32), labels.astype(np.int64)

        raise FileNotFoundError(
            f"Could not find 3DO dataset in {self.root}. "
            "Expected an HDF5 file (3do.h5 or data.h5)."
        )

    def __len__(self) -> int:
        return len(self.labels)

    def __getitem__(self, idx: int) -> Tuple[Tensor, Tensor]:
        sample = self.data[idx]
        label = int(self.labels[idx])

        if self.pipeline is not None:
            sample = self.pipeline(sample.astype(np.float64)).astype(np.float32)

        x = torch.from_numpy(sample)
        y = torch.tensor(label, dtype=torch.long)

        if self.transform is not None:
            x = self.transform(x)

        return x, y


# ---------------------------------------------------------------------------
# WaveGuardDataset
# ---------------------------------------------------------------------------

class WaveGuardDataset(Dataset):
    """Custom WaveGuard CSI dataset loader.

    Expected HDF5 structure::

        /csi          – float32 (N, channels, time)  – raw CSI windows
        /labels       – int64   (N,)                  – activity class
        /session_ids  – int64   (N,)                  – recording session (optional)
        /timestamps   – float64 (N,)                  – UNIX timestamps  (optional)

    Activity classes: 0=empty, 1=presence, 2=movement, 3=fall.

    Args:
        path: Path to the WaveGuard HDF5 file.
        split: One of ``"train"``, ``"val"``, ``"test"`` (default ``"train"``).
        preprocess: Apply :class:`CSIPipeline` preprocessing (default ``True``).
        pipeline: Optional custom :class:`CSIPipeline` instance.
        val_ratio: Validation fraction (default 0.15).
        test_ratio: Test fraction (default 0.15).
        seed: Random seed for split reproducibility (default 42).
        transform: Optional callable applied to each sample tensor.
        normalize: Z-score normalise each sample independently (default ``True``).
    """

    CLASS_NAMES = ["empty", "presence", "movement", "fall"]

    def __init__(
        self,
        path: Union[str, Path],
        split: str = "train",
        preprocess: bool = True,
        pipeline: Optional[CSIPipeline] = None,
        val_ratio: float = 0.15,
        test_ratio: float = 0.15,
        seed: int = 42,
        transform=None,
        normalize: bool = True,
    ) -> None:
        self.path = Path(path)
        if not self.path.exists():
            raise FileNotFoundError(f"WaveGuard dataset not found: {self.path}")

        self.split = split.lower()
        self.transform = transform
        self.normalize = normalize
        self.pipeline = pipeline or (CSIPipeline() if preprocess else None)

        data, labels = self._load()
        all_idx = np.arange(len(labels))
        train_idx, val_idx, test_idx = _stratified_split(
            all_idx, labels, val_ratio, test_ratio, seed
        )

        split_map = {"train": train_idx, "val": val_idx, "test": test_idx}
        if self.split not in split_map:
            raise ValueError(f"split must be one of {list(split_map)}, got {self.split!r}")

        idx = split_map[self.split]
        self.data = data[idx]
        self.labels = labels[idx]

    def _load(self) -> Tuple[np.ndarray, np.ndarray]:
        with h5py.File(self.path, "r") as f:
            data = f["csi"][:]
            labels = f["labels"][:]
        return data.astype(np.float32), labels.astype(np.int64)

    def __len__(self) -> int:
        return len(self.labels)

    def __getitem__(self, idx: int) -> Tuple[Tensor, Tensor]:
        sample = self.data[idx]  # (channels, time)
        label = int(self.labels[idx])

        if self.pipeline is not None:
            sample = self.pipeline(sample.astype(np.float64)).astype(np.float32)

        if self.normalize:
            mean = sample.mean(axis=-1, keepdims=True)
            std = sample.std(axis=-1, keepdims=True) + 1e-8
            sample = (sample - mean) / std

        x = torch.from_numpy(sample)
        y = torch.tensor(label, dtype=torch.long)

        if self.transform is not None:
            x = self.transform(x)

        return x, y

    @property
    def class_weights(self) -> Tensor:
        """Inverse-frequency class weights for use with CrossEntropyLoss."""
        counts = np.bincount(self.labels, minlength=len(self.CLASS_NAMES)).astype(np.float32)
        counts = np.where(counts == 0, 1.0, counts)  # avoid division by zero
        weights = 1.0 / counts
        return torch.from_numpy(weights / weights.sum())
