#![no_std]
#![forbid(unsafe_code)]

pub mod be;
pub mod bitstream;
pub mod compressor;
pub mod count;
pub mod decompressor;
pub mod mask;
pub mod rle;

pub use compressor::{CompressError, CompressScratch, CompressorContext};
pub use decompressor::{
    DecompressScratch, DecompressStatus, DecompressorContext, FrameError,
    MAX_COMPRESSED_PACKET_BITS, check_frame,
};

pub const MAX_PACKET_BITS: usize = (1 << 16) - 1; // 65535
const _: () = assert!(
    1 <= MAX_PACKET_BITS && MAX_PACKET_BITS <= (1 << 16) - 1,
    "MAX_PACKET_BITS must be within the CCSDS 124.0-B-1 range 1..=(2^16 - 1)",
);

/// Storage block of every bit buffer; MSB-first, so the serialized bitstream is identical for any width.
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
