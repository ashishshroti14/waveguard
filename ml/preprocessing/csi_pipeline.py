"""CSI preprocessing pipeline for WaveGuard.

Mirrors the Android ``CsiPreprocessor.kt`` implementation so that
training and on-device inference use identical signal processing steps.

Pipeline order
--------------
1. :class:`HampelFilter`    – remove impulsive amplitude outliers
2. :class:`ButterworthBPF`  – bandpass filter (0.1 – 20 Hz by default)
3. :class:`PhaseUnwrapper`  – unwrap and sanitise phase across subcarriers
4. :class:`PCADenoiser`     – keep top-k principal components per window
5. :class:`CSIPipeline`     – chains all of the above

All classes operate on NumPy arrays with shape ``(channels, time)``.
"""

from __future__ import annotations

import numpy as np
from numpy.typing import NDArray
from scipy.signal import butter, sosfilt, sosfiltfilt
from sklearn.decomposition import PCA


# ---------------------------------------------------------------------------
# HampelFilter
# ---------------------------------------------------------------------------

class HampelFilter:
    """Remove impulsive outliers using the Hampel identifier.

    For each sample in a sliding window the estimator computes the
    Median Absolute Deviation (MAD).  Samples more than ``n_sigma``
    MADs away from the local median are replaced with the median.

    This matches the ``hampelFilter`` method in ``CsiPreprocessor.kt``.

    Args:
        window_size: Half-width of the sliding window (samples on each side).
        n_sigma: Threshold expressed as a multiple of the scaled MAD.
    """

    _CONSISTENCY_CONSTANT = 1.4826  # makes MAD consistent with std for Gaussian

    def __init__(self, window_size: int = 5, n_sigma: float = 3.0) -> None:
        self.window_size = window_size
        self.n_sigma = n_sigma

    def _filter_1d(self, x: NDArray[np.float64]) -> NDArray[np.float64]:
        n = len(x)
        out = x.copy()
        k = self.window_size
        for i in range(n):
            lo = max(0, i - k)
            hi = min(n, i + k + 1)
            window = x[lo:hi]
            median = np.median(window)
            mad = self._CONSISTENCY_CONSTANT * np.median(np.abs(window - median))
            if mad > 0 and abs(x[i] - median) > self.n_sigma * mad:
                out[i] = median
        return out

    def __call__(self, csi: NDArray[np.float64]) -> NDArray[np.float64]:
        """Apply Hampel filter independently to each channel.

        Args:
            csi: Array of shape ``(channels, time)``.

        Returns:
            Filtered array of the same shape.
        """
        return np.stack([self._filter_1d(row) for row in csi])


# ---------------------------------------------------------------------------
# ButterworthBPF
# ---------------------------------------------------------------------------

class ButterworthBPF:
    """Zero-phase Butterworth bandpass filter using second-order sections.

    Applies ``sosfiltfilt`` (forward-backward) for zero phase distortion,
    matching the ``applyBandpassFilter`` method in ``CsiPreprocessor.kt``.

    Args:
        low_hz: Lower cut-off frequency in Hz (default 0.1 Hz).
        high_hz: Upper cut-off frequency in Hz (default 20.0 Hz).
        fs: Sampling frequency in Hz (default 100 Hz).
        order: Filter order (default 4).
    """

    def __init__(
        self,
        low_hz: float = 0.1,
        high_hz: float = 20.0,
        fs: float = 100.0,
        order: int = 4,
    ) -> None:
        nyq = fs / 2.0
        self.sos = butter(order, [low_hz / nyq, high_hz / nyq], btype="band", output="sos")

    def __call__(self, csi: NDArray[np.float64]) -> NDArray[np.float64]:
        """Apply bandpass filter to each channel.

        Falls back to causal ``sosfilt`` when the signal is too short for
        the zero-phase version.

        Args:
            csi: Array of shape ``(channels, time)``.

        Returns:
            Filtered array of the same shape.
        """
        min_len = 3 * (len(self.sos) + 1)  # minimum for sosfiltfilt
        if csi.shape[1] >= min_len:
            return sosfiltfilt(self.sos, csi, axis=1).astype(np.float64)
        return sosfilt(self.sos, csi, axis=1).astype(np.float64)


# ---------------------------------------------------------------------------
# PhaseUnwrapper
# ---------------------------------------------------------------------------

class PhaseUnwrapper:
    """Unwrap CSI phase across subcarriers and remove linear drift.

    Steps:
    1. ``np.unwrap`` across the subcarrier axis to remove ``2π`` jumps.
    2. Linear de-trending across time to remove constant frequency offsets
       (Carrier Frequency Offset, Sampling Frequency Offset).

    This mirrors ``unwrapAndSanitisePhase`` in ``CsiPreprocessor.kt``.

    Args:
        detrend: Whether to remove the linear trend after unwrapping.
    """

    def __init__(self, detrend: bool = True) -> None:
        self.detrend = detrend

    def __call__(self, phase: NDArray[np.float64]) -> NDArray[np.float64]:
        """Unwrap and optionally de-trend CSI phase.

        Args:
            phase: Phase array of shape ``(channels, time)``.

        Returns:
            Processed phase array of the same shape.
        """
        unwrapped = np.unwrap(phase, axis=0)  # across subcarriers
        if self.detrend:
            # Remove per-channel linear trend across time
            t = np.arange(unwrapped.shape[1], dtype=np.float64)
            for i in range(unwrapped.shape[0]):
                coeffs = np.polyfit(t, unwrapped[i], 1)
                unwrapped[i] -= np.polyval(coeffs, t)
        return unwrapped


# ---------------------------------------------------------------------------
# PCADenoiser
# ---------------------------------------------------------------------------

class PCADenoiser:
    """Denoise CSI by projecting onto the top-k principal components.

    Reduces the effect of multipath noise and environmental clutter by
    retaining only the dominant signal subspace.  This matches the
    ``applyPCA`` method in ``CsiPreprocessor.kt``.

    Args:
        n_components: Number of principal components to keep (default 3).
    """

    def __init__(self, n_components: int = 3) -> None:
        self.n_components = n_components

    def __call__(self, csi: NDArray[np.float64]) -> NDArray[np.float64]:
        """Project CSI onto its top-k principal components.

        Args:
            csi: Array of shape ``(channels, time)``.

        Returns:
            Denoised array of the same shape ``(channels, time)``.
        """
        # PCA is fitted over the time axis; channels are features
        n_comp = min(self.n_components, csi.shape[0], csi.shape[1])
        pca = PCA(n_components=n_comp)
        # shape convention for sklearn: (samples=time, features=channels)
        transformed = pca.fit_transform(csi.T)  # (time, n_comp)
        reconstructed = pca.inverse_transform(transformed)  # (time, channels)
        return reconstructed.T  # (channels, time)


# ---------------------------------------------------------------------------
# CSIPipeline
# ---------------------------------------------------------------------------

class CSIPipeline:
    """End-to-end CSI preprocessing pipeline.

    Chains the four processing stages in the order expected by both the
    Android runtime (``CsiPreprocessor.kt``) and the model training code:

    1. Hampel outlier removal
    2. Butterworth bandpass filtering
    3. Phase unwrapping & de-trending
    4. PCA denoising

    Amplitude and phase are expected to be stacked along the channel
    dimension: ``csi[:n_subcarriers]`` = amplitude,
    ``csi[n_subcarriers:]`` = phase.

    Args:
        n_subcarriers: Number of subcarriers (default 52 for 80 MHz WiFi).
        hampel_window: Half-window for Hampel filter (default 5).
        hampel_sigma: Sigma threshold for Hampel filter (default 3.0).
        bp_low_hz: Bandpass lower cut-off in Hz (default 0.1).
        bp_high_hz: Bandpass upper cut-off in Hz (default 20.0).
        fs: Sampling frequency in Hz (default 100.0).
        bp_order: Butterworth filter order (default 4).
        phase_detrend: Whether to de-trend phase after unwrapping (default True).
        pca_components: Number of PCA components (default 3).
    """

    def __init__(
        self,
        n_subcarriers: int = 52,
        hampel_window: int = 5,
        hampel_sigma: float = 3.0,
        bp_low_hz: float = 0.1,
        bp_high_hz: float = 20.0,
        fs: float = 100.0,
        bp_order: int = 4,
        phase_detrend: bool = True,
        pca_components: int = 3,
    ) -> None:
        self.n_subcarriers = n_subcarriers
        self.hampel = HampelFilter(hampel_window, hampel_sigma)
        self.bpf = ButterworthBPF(bp_low_hz, bp_high_hz, fs, bp_order)
        self.phase_unwrap = PhaseUnwrapper(phase_detrend)
        self.pca = PCADenoiser(pca_components)

    def __call__(self, csi: NDArray[np.float64]) -> NDArray[np.float64]:
        """Run the full preprocessing pipeline.

        Args:
            csi: Raw CSI array of shape ``(channels, time)`` where
                 ``channels = 2 * n_subcarriers`` (amplitude + phase stacked).
                 Alternatively, pass amplitude-only with ``channels = n_subcarriers``.

        Returns:
            Preprocessed CSI array of the same shape.
        """
        n = self.n_subcarriers
        has_phase = csi.shape[0] >= 2 * n

        amp = csi[:n]
        phase = csi[n : 2 * n] if has_phase else None

        # Amplitude pipeline
        amp = self.hampel(amp)
        amp = self.bpf(amp)
        amp = self.pca(amp)

        if phase is not None:
            # Phase pipeline
            phase = self.hampel(phase)
            phase = self.phase_unwrap(phase)
            phase = self.bpf(phase)
            phase = self.pca(phase)
            # Re-stack remaining channels (e.g. extra features)
            extra = csi[2 * n :]
            parts = [amp, phase] + ([extra] if extra.size else [])
            return np.concatenate(parts, axis=0)

        extra = csi[n:]
        if extra.size:
            return np.concatenate([amp, extra], axis=0)
        return amp
