#![no_std]
#![forbid(unsafe_code)]

#[cfg(feature = "trace")]
macro_rules! trace {
    ($($body:tt)*) => { $($body)* };
}
#[cfg(not(feature = "trace"))]
macro_rules! trace {
    ($($body:tt)*) => {};
}

pub mod be;
pub mod bitstream;
pub mod compressor;
pub mod count;
pub mod decompressor;
pub mod mask;
pub mod rle;
#[cfg(feature = "trace")]
pub mod trace;

pub use compressor::{CompressError, CompressScratch, CompressorContext};
pub use decompressor::{
    DecompressScratch, DecompressStatus, DecompressorContext, FrameError,
    MAX_COMPRESSED_PACKET_BITS, check_frame,
};
#[cfg(feature = "trace")]
pub use trace::{CompressTrace, DecompressTrace, Seg, Segments, Span, SEGS};

pub const MAX_PACKET_BITS: usize = (1 << 16) - 1; // 65535
const _: () = assert!(
    1 <= MAX_PACKET_BITS && MAX_PACKET_BITS <= (1 << 16) - 1,
    "MAX_PACKET_BITS must be within the CCSDS 124.0-B-1 range 1..=(2^16 - 1)",
);

#[cfg(all(feature = "block32", feature = "block64"))]
compile_error!("features `block32` and `block64` are mutually exclusive");

/// Storage block of every bit buffer: `usize` unless the `block32` or `block64` feature fixes it.
/// MSB-first, so the serialized bitstream is identical for any width.
#[cfg(not(any(feature = "block32", feature = "block64")))]
pub type Block = usize;
#[cfg(feature = "block32")]
pub type Block = u32;
#[cfg(all(feature = "block64", not(feature = "block32")))]
pub type Block = u64;

pub const BLOCK_BITS: usize = Block::BITS as usize;
pub const BLOCK_SHIFT: u32 = Block::BITS.trailing_zeros();
pub const BLOCK_MASK: usize = BLOCK_BITS - 1;
const _: () = assert!(
    BLOCK_BITS >= 32,
    "a COUNT field is written and read as a single block"
);

pub(crate) const BLOCK_MSB: Block = 1 << (BLOCK_BITS - 1);

/// Blocks needed to hold `bits` bits.
pub const fn blocks_for(bits: usize) -> usize {
    (bits + BLOCK_MASK) >> BLOCK_SHIFT
}

pub const BUF_LEN: usize = blocks_for(MAX_PACKET_BITS);

pub const MAX_ROBUSTNESS: isize = 7;
