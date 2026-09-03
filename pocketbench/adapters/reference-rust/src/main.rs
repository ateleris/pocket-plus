//! reference-rust pocketbench adapter: links the ccsds124 Rust codec and speaks the fixed
//! contract. Reuses the codec's only public entry points, the whole-buffer ccsds124::compress and
//! ccsds124::decompress. conformance is not supported (the per-packet flag API is not public), so
//! the conformance hooks are left at their `None` default.
use ccsds124::{compress, decompress};
use pocketbench_adapter::{unsigned, Adapter, Caps, Params};

struct ReferenceRust;

impl Adapter for ReferenceRust {
    fn caps(&self) -> Caps {
        Caps {
            timing_tier: "in_process",
            reference_conformant: true,
            param_schedule: "pt_ft_rt",
            build_profile: "cargo release: opt-level=3, lto=true, codegen-units=1",
            limitations: "whole-buffer public API only (per-packet flag API is private in the \
                          crate), so no conformance support and no sub-byte F: the public \
                          compress/decompress reject a packet_bits that is not a multiple of 8 \
                          (byte-aligned framing); packet size is a runtime argument (no \
                          compile-time size cap)",
        }
    }

    /// Byte-aligned only: the codec rejects a bit count that is not a multiple of 8
    /// (Ccsds124Error::InvalidPacketSize), so sub-byte F is unsupported.
    fn compress(&self, data: &[u8], p: &Params) -> Result<Vec<u8>, String> {
        compress(
            data,
            p.packet_bits as usize,
            unsigned("robustness", p.robustness)?,
            unsigned("pt", p.pt)?,
            unsigned("ft", p.ft)?,
            unsigned("rt", p.rt)?,
        )
        .map_err(|e| e.to_string())
    }

    fn decompress(&self, data: &[u8], p: &Params) -> Result<Vec<u8>, String> {
        decompress(
            data,
            p.packet_bits as usize,
            unsigned("robustness", p.robustness)?,
        )
        .map_err(|e| e.to_string())
    }
}

fn main() {
    pocketbench_adapter::run(&ReferenceRust)
}
