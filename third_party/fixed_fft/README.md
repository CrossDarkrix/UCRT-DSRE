# Production fixed-length FFT implementation

The multiband exciter remains enabled. Every bandpass transform uses exactly 65,536 samples and therefore stays on the tested radix-2 Fourier path. Arbitrary input lengths are supported by processing consecutive blocks. Short blocks and the final remainder are zero padded. No input samples are truncated and the temporary D2-A size-limit error has been removed.

This is based on the successful D2-A device result where the 65,536-point fixed FFT path produced normal audio.
