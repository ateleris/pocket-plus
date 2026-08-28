#![no_std]
#![forbid(unsafe_code)]

pub mod be;
pub mod bitstream;
pub mod compressor;
pub mod count;
pub mod decompressor;
pub mod mask;
pub mod rle;

pub use compressor::{CompressorState, EncodeScratch};
pub use decompressor::{DecodeScratch, DecodeStatus, DecompressorState};

pub const MAX_PACKET_BITS: usize = (1 << 16) - 1; // 65535
const _: () = assert!(
    1 <= MAX_PACKET_BITS && MAX_PACKET_BITS <= (1 << 16) - 1,
    "MAX_PACKET_BITS must be within the CCSDS 124.0-B-1 range 1..=(2^16 - 1)",
);

pub const BUF_LEN: usize = (MAX_PACKET_BITS + 63) >> 6;
