"""Lightweight 1D CNN classifier for edge deployment.

Designed for INT8 quantization on microcontrollers / mobile devices.
Approximately 50 K trainable parameters.

Input shape:  (batch, channels, time)  – same as WiFlexFormer.
Output shape: (batch, num_classes)     – raw logits.
"""

import torch
import torch.nn as nn
from torch import Tensor


class DepthwiseSeparableConv1d(nn.Module):
    """Depthwise-separable convolution block (depthwise + pointwise).

    Reduces parameter count compared to a standard Conv1d while preserving
    receptive field, making it suitable for quantized edge deployment.

    Args:
        in_channels: Number of input channels.
        out_channels: Number of output channels.
        kernel_size: Kernel size for the depthwise convolution.
        stride: Stride for the depthwise convolution.
        padding: Padding for the depthwise convolution.
    """

    def __init__(
        self,
        in_channels: int,
        out_channels: int,
        kernel_size: int,
        stride: int = 1,
        padding: int = 0,
    ) -> None:
        super().__init__()
        self.depthwise = nn.Conv1d(
            in_channels, in_channels, kernel_size,
            stride=stride, padding=padding, groups=in_channels, bias=False,
        )
        self.pointwise = nn.Conv1d(in_channels, out_channels, kernel_size=1, bias=False)
        self.bn = nn.BatchNorm1d(out_channels)
        self.act = nn.ReLU(inplace=True)

    def forward(self, x: Tensor) -> Tensor:
        x = self.depthwise(x)
        x = self.pointwise(x)
        x = self.bn(x)
        return self.act(x)


class CSIClassifier(nn.Module):
    """Lightweight 1D CNN for CSI-based human activity recognition.

    Architecture:
        - 3 depthwise-separable Conv1D blocks with increasing channel depth
        - Global average pooling to collapse the time dimension
        - Two fully connected layers with dropout

    The design targets ~50 K parameters and is compatible with INT8
    post-training quantization via PyTorch's ``torch.quantization`` API.

    Args:
        in_channels: Number of CSI subcarrier channels (default 52).
        num_classes: Number of output classes (default 4).
        base_channels: Base channel width; subsequent layers double this
            value (default 32).
        dropout: Dropout probability before the final classifier (default 0.3).

    Input shape:
        ``(batch, in_channels, time)``

    Output shape:
        ``(batch, num_classes)``
    """

    CLASSES = ["empty", "presence", "movement", "fall"]

    def __init__(
        self,
        in_channels: int = 52,
        num_classes: int = 4,
        base_channels: int = 32,
        dropout: float = 0.3,
    ) -> None:
        super().__init__()

        c1 = base_channels        # 32
        c2 = base_channels * 2   # 64
        c3 = base_channels * 4   # 128

        # --- Conv stem (standard conv to handle variable in_channels) ------
        self.stem = nn.Sequential(
            nn.Conv1d(in_channels, c1, kernel_size=7, padding=3, bias=False),
            nn.BatchNorm1d(c1),
            nn.ReLU(inplace=True),
        )

        # --- Three depthwise-separable blocks with max-pooling -------------
        self.block1 = nn.Sequential(
            DepthwiseSeparableConv1d(c1, c1, kernel_size=5, padding=2),
            nn.MaxPool1d(kernel_size=2, stride=2),
        )
        self.block2 = nn.Sequential(
            DepthwiseSeparableConv1d(c1, c2, kernel_size=5, padding=2),
            nn.MaxPool1d(kernel_size=2, stride=2),
        )
        self.block3 = nn.Sequential(
            DepthwiseSeparableConv1d(c2, c3, kernel_size=3, padding=1),
            nn.MaxPool1d(kernel_size=2, stride=2),
        )

        # --- Global average pooling ----------------------------------------
        self.gap = nn.AdaptiveAvgPool1d(1)

        # --- Fully connected head ------------------------------------------
        fc_hidden = c3 // 2  # 64
        self.head = nn.Sequential(
            nn.Flatten(),
            nn.Linear(c3, fc_hidden),
            nn.ReLU(inplace=True),
            nn.Dropout(dropout),
            nn.Linear(fc_hidden, num_classes),
        )

        self._init_weights()

    # ------------------------------------------------------------------
    # Weight initialisation
    # ------------------------------------------------------------------

    def _init_weights(self) -> None:
        for m in self.modules():
            if isinstance(m, nn.Conv1d):
                nn.init.kaiming_normal_(m.weight, mode="fan_out", nonlinearity="relu")
                if m.bias is not None:
                    nn.init.zeros_(m.bias)
            elif isinstance(m, nn.BatchNorm1d):
                nn.init.ones_(m.weight)
                nn.init.zeros_(m.bias)
            elif isinstance(m, nn.Linear):
                nn.init.kaiming_normal_(m.weight, nonlinearity="relu")
                if m.bias is not None:
                    nn.init.zeros_(m.bias)

    # ------------------------------------------------------------------
    # Forward
    # ------------------------------------------------------------------

    def forward(self, x: Tensor) -> Tensor:
        """Forward pass.

        Args:
            x: Input tensor of shape ``(batch, in_channels, time)``.

        Returns:
            Class logits of shape ``(batch, num_classes)``.
        """
        x = self.stem(x)
        x = self.block1(x)
        x = self.block2(x)
        x = self.block3(x)
        x = self.gap(x)
        return self.head(x)

    # ------------------------------------------------------------------
    # Quantization helpers
    # ------------------------------------------------------------------

    def fuse_modules(self) -> None:
        """Fuse Conv-BN-ReLU triplets for quantization-aware training."""
        torch.quantization.fuse_modules(
            self.stem,
            [["0", "1", "2"]],
            inplace=True,
        )

    # ------------------------------------------------------------------
    # Convenience helpers
    # ------------------------------------------------------------------

    @property
    def num_parameters(self) -> int:
        """Total number of trainable parameters."""
        return sum(p.numel() for p in self.parameters() if p.requires_grad)

    def predict(self, x: Tensor) -> Tensor:
        """Return predicted class indices.

        Args:
            x: Input tensor ``(batch, in_channels, time)``.

        Returns:
            Integer class indices of shape ``(batch,)``.
        """
        with torch.no_grad():
            logits = self.forward(x)
        return logits.argmax(dim=-1)
