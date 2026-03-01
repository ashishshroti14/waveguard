"""WiFlexFormer: Transformer-based CSI activity recognition model.

Architecture follows the WiFlexFormer paper with:
- Conv1D stem for local feature extraction
- Gaussian positional encoding (sigma=10)
- CLS token prepended to sequence
- 4-layer Transformer encoder with 16 heads
- Linear classifier head

Classes: empty (0), presence (1), movement (2), fall (3)
"""

import math
import torch
import torch.nn as nn
from torch import Tensor


# ---------------------------------------------------------------------------
# Positional encoding
# ---------------------------------------------------------------------------

class GaussianPositionalEncoding(nn.Module):
    """Gaussian positional encoding as described in the WiFlexFormer paper.

    Unlike sinusoidal encoding, each position is encoded using a set of
    Gaussian basis functions centred at evenly-spaced positions along the
    sequence, giving a smooth, localised representation.

    Args:
        embed_dim: Embedding dimension (must be even).
        max_len: Maximum sequence length supported.
        sigma: Standard deviation of the Gaussian kernels.
    """

    def __init__(self, embed_dim: int, max_len: int = 512, sigma: float = 10.0) -> None:
        super().__init__()
        if embed_dim % 2 != 0:
            raise ValueError(f"embed_dim must be even, got {embed_dim}")

        self.embed_dim = embed_dim
        self.sigma = sigma

        # Centres evenly spaced across [0, max_len)
        num_centres = embed_dim // 2
        centres = torch.linspace(0, max_len - 1, num_centres)  # (D/2,)

        # Positions [0, max_len)
        positions = torch.arange(max_len).float()  # (L,)

        # Gaussian basis: shape (L, D/2)
        diff = positions.unsqueeze(1) - centres.unsqueeze(0)  # (L, D/2)
        gauss = torch.exp(-0.5 * (diff / sigma) ** 2)

        # Concatenate [gauss, gauss] to fill embed_dim
        pe = torch.cat([gauss, gauss], dim=-1)  # (L, D)

        # Register as buffer so it moves to device automatically
        self.register_buffer("pe", pe.unsqueeze(0))  # (1, L, D)

    def forward(self, x: Tensor) -> Tensor:
        """Add positional encoding to token sequence.

        Args:
            x: Input tensor of shape (batch, seq_len, embed_dim).

        Returns:
            Tensor of the same shape with positional encoding added.
        """
        seq_len = x.size(1)
        return x + self.pe[:, :seq_len, :]  # type: ignore[index]


# ---------------------------------------------------------------------------
# Feed-forward block
# ---------------------------------------------------------------------------

class FFN(nn.Module):
    """Position-wise feed-forward network used inside each Transformer layer.

    Args:
        embed_dim: Input / output dimension.
        ffn_dim: Hidden dimension (typically 4 * embed_dim).
        dropout: Dropout probability.
    """

    def __init__(self, embed_dim: int, ffn_dim: int, dropout: float = 0.1) -> None:
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(embed_dim, ffn_dim),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(ffn_dim, embed_dim),
            nn.Dropout(dropout),
        )

    def forward(self, x: Tensor) -> Tensor:
        return self.net(x)


# ---------------------------------------------------------------------------
# Single Transformer encoder layer
# ---------------------------------------------------------------------------

class TransformerEncoderLayer(nn.Module):
    """Pre-norm Transformer encoder layer (attention + FFN with residuals).

    Args:
        embed_dim: Model dimensionality.
        num_heads: Number of attention heads.
        ffn_dim: Hidden dimension of the FFN.
        dropout: Dropout probability applied throughout.
    """

    def __init__(
        self,
        embed_dim: int,
        num_heads: int,
        ffn_dim: int,
        dropout: float = 0.1,
    ) -> None:
        super().__init__()
        self.norm1 = nn.LayerNorm(embed_dim)
        self.attn = nn.MultiheadAttention(
            embed_dim=embed_dim,
            num_heads=num_heads,
            dropout=dropout,
            batch_first=True,
        )
        self.drop1 = nn.Dropout(dropout)

        self.norm2 = nn.LayerNorm(embed_dim)
        self.ffn = FFN(embed_dim, ffn_dim, dropout)

    def forward(self, x: Tensor, key_padding_mask: Tensor | None = None) -> Tensor:
        """Forward pass.

        Args:
            x: Input of shape (batch, seq_len, embed_dim).
            key_padding_mask: Optional boolean mask (batch, seq_len).

        Returns:
            Output of shape (batch, seq_len, embed_dim).
        """
        # Self-attention with pre-norm
        residual = x
        x = self.norm1(x)
        attn_out, _ = self.attn(x, x, x, key_padding_mask=key_padding_mask)
        x = residual + self.drop1(attn_out)

        # FFN with pre-norm
        residual = x
        x = self.norm2(x)
        x = residual + self.ffn(x)
        return x


# ---------------------------------------------------------------------------
# WiFlexFormer
# ---------------------------------------------------------------------------

class WiFlexFormer(nn.Module):
    """WiFlexFormer: CSI-based human activity recognition via Transformers.

    Pipeline:
        1. Conv1D stem  – extracts local temporal features from raw CSI
        2. Linear projection – maps stem channels to ``embed_dim``
        3. CLS token prepended to the projected sequence
        4. Gaussian positional encoding added
        5. 4-layer Transformer encoder
        6. CLS output fed to a linear classifier

    Args:
        in_channels: Number of CSI subcarrier channels (default 52).
        embed_dim: Transformer embedding dimension (default 32).
        num_heads: Number of attention heads (default 16).
        num_layers: Number of Transformer encoder layers (default 4).
        num_classes: Number of output classes (default 4).
        stem_channels: Intermediate channels in the Conv1D stem (default 64).
        stem_kernel: Kernel size for the Conv1D stem (default 7).
        ffn_multiplier: FFN hidden-dim = embed_dim × ffn_multiplier (default 4).
        dropout: Dropout probability (default 0.1).
        max_len: Maximum sequence length for positional encoding (default 512).
        sigma: Sigma for Gaussian positional encoding (default 10.0).

    Input shape:
        ``(batch, in_channels, time)``  – channels-first CSI window.

    Output shape:
        ``(batch, num_classes)``  – raw logits.
    """

    CLASSES = ["empty", "presence", "movement", "fall"]

    def __init__(
        self,
        in_channels: int = 52,
        embed_dim: int = 32,
        num_heads: int = 16,
        num_layers: int = 4,
        num_classes: int = 4,
        stem_channels: int = 64,
        stem_kernel: int = 7,
        ffn_multiplier: int = 4,
        dropout: float = 0.1,
        max_len: int = 512,
        sigma: float = 10.0,
    ) -> None:
        super().__init__()

        # --- Conv1D stem ---------------------------------------------------
        padding = stem_kernel // 2
        self.stem = nn.Sequential(
            nn.Conv1d(in_channels, stem_channels, kernel_size=stem_kernel, padding=padding),
            nn.BatchNorm1d(stem_channels),
            nn.GELU(),
            nn.Conv1d(stem_channels, stem_channels, kernel_size=3, padding=1),
            nn.BatchNorm1d(stem_channels),
            nn.GELU(),
        )

        # --- Projection to embed_dim ---------------------------------------
        self.proj = nn.Linear(stem_channels, embed_dim)

        # --- CLS token -----------------------------------------------------
        self.cls_token = nn.Parameter(torch.zeros(1, 1, embed_dim))
        nn.init.trunc_normal_(self.cls_token, std=0.02)

        # --- Positional encoding -------------------------------------------
        self.pos_enc = GaussianPositionalEncoding(embed_dim, max_len=max_len + 1, sigma=sigma)

        # --- Transformer encoder -------------------------------------------
        ffn_dim = embed_dim * ffn_multiplier
        self.encoder = nn.ModuleList(
            [
                TransformerEncoderLayer(embed_dim, num_heads, ffn_dim, dropout)
                for _ in range(num_layers)
            ]
        )
        self.norm = nn.LayerNorm(embed_dim)

        # --- Classifier head -----------------------------------------------
        self.head = nn.Linear(embed_dim, num_classes)

        self._init_weights()

    # ------------------------------------------------------------------
    # Weight initialisation
    # ------------------------------------------------------------------

    def _init_weights(self) -> None:
        for m in self.modules():
            if isinstance(m, nn.Linear):
                nn.init.trunc_normal_(m.weight, std=0.02)
                if m.bias is not None:
                    nn.init.zeros_(m.bias)
            elif isinstance(m, (nn.Conv1d, nn.BatchNorm1d)):
                if hasattr(m, "weight") and m.weight is not None:
                    nn.init.trunc_normal_(m.weight, std=0.02)
                if hasattr(m, "bias") and m.bias is not None:
                    nn.init.zeros_(m.bias)

    # ------------------------------------------------------------------
    # Forward
    # ------------------------------------------------------------------

    def forward(self, x: Tensor) -> Tensor:
        """Forward pass.

        Args:
            x: CSI input tensor of shape ``(batch, in_channels, time)``.

        Returns:
            Class logits of shape ``(batch, num_classes)``.
        """
        batch_size = x.size(0)

        # 1. Conv1D stem – output: (batch, stem_channels, time)
        x = self.stem(x)

        # 2. Reshape to (batch, time, stem_channels) then project
        x = x.permute(0, 2, 1)          # (B, T, C)
        x = self.proj(x)                 # (B, T, embed_dim)

        # 3. Prepend CLS token
        cls = self.cls_token.expand(batch_size, -1, -1)  # (B, 1, embed_dim)
        x = torch.cat([cls, x], dim=1)   # (B, T+1, embed_dim)

        # 4. Add Gaussian positional encoding
        x = self.pos_enc(x)

        # 5. Transformer encoder
        for layer in self.encoder:
            x = layer(x)
        x = self.norm(x)

        # 6. Extract CLS representation and classify
        cls_out = x[:, 0, :]             # (B, embed_dim)
        return self.head(cls_out)        # (B, num_classes)

    # ------------------------------------------------------------------
    # Convenience helpers
    # ------------------------------------------------------------------

    @property
    def num_parameters(self) -> int:
        """Total number of trainable parameters."""
        return sum(p.numel() for p in self.parameters() if p.requires_grad)

    def predict(self, x: Tensor) -> Tensor:
        """Return predicted class indices (argmax of logits).

        Args:
            x: Input tensor ``(batch, in_channels, time)``.

        Returns:
            Integer class indices of shape ``(batch,)``.
        """
        with torch.no_grad():
            logits = self.forward(x)
        return logits.argmax(dim=-1)
