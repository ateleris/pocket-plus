//! pocketrust pocketbench adapter: links the RHEA-internal pocketrust codec and speaks the fixed
//! contract. The codec is mechanism-only (per-packet encode/decode with reusable scratch buffers),
//! so this adapter owns the framing policy around it: the self-delimiting .pkt whole-buffer loops
//! with the reference pt/ft/rt flag schedule and byte-aligned padding, and the UAB/CNES
//! conformance drivers.
use pocketbench_adapter::{Adapter, Caps, ConformanceFn, Params};
use pocketrust::{
    CompressorState, DecodeScratch, DecodeStatus, DecompressorState, EncodeScratch, BUF_LEN,
    MAX_PACKET_BITS,
};

// The shared crate's u16 parse is what enforces this cap.
const _: () = assert!(MAX_PACKET_BITS == u16::MAX as usize);

// --- word/byte helpers ------------------------------------------------------------------------

/// Words past ceil(len/8) are left untouched, so callers pass one zeroed buffer and reuse it.
fn pack_be_into(bytes: &[u8], out: &mut [u64; BUF_LEN]) {
    for (i, chunk) in bytes.chunks(8).enumerate() {
        let mut buf = [0u8; 8];
        buf[..chunk.len()].copy_from_slice(chunk);
        out[i] = u64::from_be_bytes(buf);
    }
}

fn bytes_to_words(bytes: &[u8]) -> Vec<u64> {
    bytes
        .chunks(8)
        .map(|chunk| {
            let mut buf = [0u8; 8];
            buf[..chunk.len()].copy_from_slice(chunk);
            u64::from_be_bytes(buf)
        })
        .collect()
}

fn words_to_bytes(words: &[u64], num_bytes: usize) -> Vec<u8> {
    let num_words = num_bytes.div_ceil(8).min(words.len());
    let mut out = Vec::with_capacity(num_words * 8);
    for w in &words[..num_words] {
        out.extend_from_slice(&w.to_be_bytes());
    }
    out.truncate(num_bytes);
    out
}

/// Serialize an encoded stream up to its bit cursor (pos words + idx bits), byte-padded.
fn stream_to_bytes(words: &[u64], pos: usize, idx: u8) -> Vec<u8> {
    words_to_bytes(words, (pos * 64 + idx as usize).div_ceil(8))
}

/// Words occupied by an F-bit field.
fn num_blocks(packet_bits: u16) -> usize {
    (packet_bits as usize).div_ceil(64)
}

fn byte_align(pos: usize, idx: u8) -> (usize, u8) {
    let aligned = (idx as usize).div_ceil(8) * 8;
    if aligned >= 64 {
        (pos + 1, 0)
    } else {
        (pos, aligned as u8)
    }
}

// --- .pkt stream codec ------------------------------------------------------------------------

/// Compress a whole buffer to the standard CCSDS 124.0-B-1 stream. Each packet is
/// encoded with the periodic pt/ft/rt flag schedule (matching the ccsds124 C
/// reference's countdown counters) and padded to a byte boundary, then concatenated
/// with no container header: byte-identical to the ESA reference `.pkt`. When any of
/// pt/ft/rt is <= 0, falls back to all-zero flags (manual control), like the reference.
fn do_compress(
    data: &[u8],
    packet_bits: u16,
    pt: isize,
    ft: isize,
    rt: isize,
    robustness: isize,
) -> Result<Vec<u8>, String> {
    // F (large_f) is the field width in bits; each packet occupies a byte-padded stride of
    // ceil(F/8) bytes (meaningful bits high, padding bits low), matching the ESA reference framing.
    let stride = (packet_bits as usize).div_ceil(8);
    if data.len() % stride != 0 {
        return Err(format!(
            "input size {} not a multiple of the byte stride {} (F={} bits)",
            data.len(),
            stride,
            packet_bits
        ));
    }
    let num_packets = data.len() / stride;
    let auto = pt > 0 && ft > 0 && rt > 0;

    let mut state = CompressorState::init(packet_bits);
    let mut scratch = EncodeScratch::new();
    // Worst case per packet (uncompressed content + full mask + RLE overhead), matching the C
    // reference's 12x per-packet output sizing, plus slack.
    let mut o = vec![0u64; num_packets * (num_blocks(packet_bits) * 12 + 4) + 4096];
    let mut packet = [0u64; BUF_LEN];
    let (mut pos, mut idx) = (0usize, 0u8);
    // Countdown counters seeded at the limits, matching ccsds124_compressor_reset.
    let (mut ft_c, mut pt_c, mut rt_c) = (ft, pt, rt);
    for t in 0..num_packets {
        let (new_mask, send_mask, uncompressed) = if !auto {
            (false, false, false)
        } else if t == 0 {
            // First packet: fixed init values; counters not checked.
            (false, true, true)
        } else {
            // Advance the counters every packet (as the reference does), then
            // override for the first Rt+1 init packets.
            let send = if ft_c == 1 { ft_c = ft; true } else { ft_c -= 1; false };
            let new = if pt_c == 1 { pt_c = pt; true } else { pt_c -= 1; false };
            let unc = if rt_c == 1 { rt_c = rt; true } else { rt_c -= 1; false };
            if (t as isize) <= robustness {
                (false, true, true)
            } else {
                (new, send, unc)
            }
        };
        pack_be_into(&data[t * stride..(t + 1) * stride], &mut packet);
        (pos, idx) = state.encode(
            &packet, robustness, new_mask, send_mask, uncompressed, &mut o, pos, idx, &mut scratch,
        );
        (pos, idx) = byte_align(pos, idx);
    }
    Ok(stream_to_bytes(&o, pos, idx))
}

/// Decompress the standard CCSDS 124.0-B-1 stream: decode byte-aligned packets until
/// the input is exhausted (self-delimiting, no header), matching the ESA reference
/// decompressor (`ccsds124_decompress`: decode packet, align to the next byte, repeat
/// while bits remain). Robustness is not needed: v_t is read from the stream.
fn do_decompress(data: &[u8], packet_bits: u16, _robustness: isize) -> Result<Vec<u8>, String> {
    let words = bytes_to_words(data);
    let stride = (packet_bits as usize).div_ceil(8); // byte-padded output stride, ceil(F/8)
    let n = num_blocks(packet_bits);
    let total_bits = data.len() * 8;

    let mut state = DecompressorState::init_f_known(packet_bits);
    let mut scratch = DecodeScratch::new();
    let (mut pos, mut idx) = (0usize, 0u8);
    let mut out = Vec::new();
    let mut i_out = [0u64; BUF_LEN];
    while pos * 64 + (idx as usize) < total_bits {
        let (_status, np, ni) = state.decode(&words, pos, idx, total_bits, &mut i_out, &mut scratch);
        (pos, idx) = byte_align(np, ni);
        out.extend_from_slice(&words_to_bytes(&i_out[..n], stride));
    }
    Ok(out)
}

// --- conformance encoder (UAB/CNES .raw+config -> .124) ----------------------------------------

struct CvFlags {
    send_mask: bool,
    new_mask: bool,
    uncompressed: bool,
    robustness: isize,
}

fn cv_decode_flags(b: u8) -> CvFlags {
    CvFlags {
        send_mask: (b >> 6) & 1 == 1,
        new_mask: (b >> 5) & 1 == 1,
        uncompressed: (b >> 4) & 1 == 1,
        robustness: (b & 0x0F) as isize,
    }
}

/// A parsed `.raw+config` encoder vector: F, the initial mask, per-packet (flag byte, content).
struct CvVector {
    large_f: u16,
    mask: Vec<u8>,
    packets: Vec<(u8, Vec<u8>)>,
}

/// True if the field's padding bits (from `large_f` to the byte boundary) are 0.
fn cv_padding_ok(bytes: &[u8], large_f: u16) -> bool {
    (large_f as usize..bytes.len() * 8).all(|bit| (bytes[bit / 8] >> (7 - (bit % 8))) & 1 == 0)
}

/// Parse a `.raw+config` encoder vector; `None` on structural errors (truncated header or
/// mask, F outside 1..=65535), for which the suite expects empty output. A trailing partial
/// packet record is ignored, like the reference harness.
fn cv_parse_input(data: &[u8]) -> Option<CvVector> {
    let header: [u8; 4] = data.get(..4)?.try_into().unwrap();
    let large_f = u32::from_be_bytes(header);
    if large_f == 0 || large_f > MAX_PACKET_BITS as u32 {
        return None;
    }
    let large_f = large_f as u16;
    let field_bytes = (large_f as usize).div_ceil(8);
    let mask = data.get(4..4 + field_bytes)?.to_vec();
    let mut off = 4 + field_bytes;
    let mut packets = Vec::new();
    while off + 1 + field_bytes <= data.len() {
        packets.push((data[off], data[off + 1..off + 1 + field_bytes].to_vec()));
        off += 1 + field_bytes;
    }
    Some(CvVector { large_f, mask, packets })
}

/// Encode a parsed vector with the per-packet flags it carries. Empty output when the mask
/// padding is invalid; encoding stops at the first packet with invalid flags or padding.
fn cv_encode_vector(v: &CvVector) -> Vec<u8> {
    if !cv_padding_ok(&v.mask, v.large_f) {
        return Vec::new();
    }
    let mut o = vec![0u64; v.packets.len() * (num_blocks(v.large_f) * 12 + 4) + 4096];

    let mut state = CompressorState::init(v.large_f);
    let mut scratch = EncodeScratch::new();
    let mut packet = [0u64; BUF_LEN];
    pack_be_into(&v.mask, &mut packet);
    state.set_initial_mask(&packet);

    let (mut pos, mut idx) = (0usize, 0u8);
    for (t, (flag, content)) in v.packets.iter().enumerate() {
        let f = cv_decode_flags(*flag);
        let forced = (t as isize) <= f.robustness;
        if f.robustness > 7
            || !cv_padding_ok(content, v.large_f)
            || (forced && (!f.send_mask || !f.uncompressed))
        {
            break;
        }
        pack_be_into(content, &mut packet);
        (pos, idx) = state.encode(
            &packet, f.robustness, f.new_mask, f.send_mask, f.uncompressed, &mut o, pos, idx,
            &mut scratch,
        );
        (pos, idx) = byte_align(pos, idx);
    }
    stream_to_bytes(&o, pos, idx)
}

fn conformance_compress(data: &[u8]) -> Vec<u8> {
    cv_parse_input(data).map_or_else(Vec::new, |v| cv_encode_vector(&v))
}

// --- conformance decoder (yellow-book stream decode, .124+config -> .raw+large_f) --------------
// pocketrust exposes encode/decode per packet; the framing, F-discovery orchestration and trailer
// are policy and live here. The per-element guarantee decision is the codec's `decode`.

const CONFORMANCE_MAX_PACKET_BITS: u32 = 622627;

/// One received element of a UAB/CNES decoder conformance stream.
enum RxElement {
    Lost,
    /// Present packet: bitstream words + its exact length in bits.
    Present(Vec<u64>, usize),
}

/// Parse a `.124+config` decoder-input buffer into its received elements. Stops
/// (per the suite spec) on truncation or an invalid length field.
fn cv_parse_elements(data: &[u8]) -> Vec<RxElement> {
    let mut elements = Vec::new();
    let mut pos = 0;
    while pos < data.len() {
        let reception = data[pos];
        pos += 1;
        if reception & 1 == 1 {
            elements.push(RxElement::Lost);
            continue;
        }
        let Some(len_field) = data.get(pos..pos + 4) else {
            break; // incomplete length field: no output for this packet
        };
        let length_bits = u32::from_be_bytes(len_field.try_into().unwrap());
        pos += 4;
        if length_bits == 0 || length_bits > CONFORMANCE_MAX_PACKET_BITS {
            break; // ignore and stop decompression
        }
        let byte_len = (length_bits as usize).div_ceil(8);
        let Some(bitstream) = data.get(pos..pos + byte_len) else {
            break; // incomplete bitstream
        };
        elements.push(RxElement::Present(bytes_to_words(bitstream), length_bits as usize));
        pos += byte_len;
    }
    elements
}

/// Decode a full UAB/CNES decoder conformance input buffer (`.124+config`)
/// into its `.raw+large_f` output. Uses the codec's normal decode path: a single
/// F-unknown decoder (`DecompressorState::init`) whose `decode` recovers F
/// from the first decodable reference internally (Strict -> adopt and keep only if
/// Guaranteed; Weak -> adopt and mark this packet undecodable; None -> stay
/// F-unknown), so the adapter does not duplicate that discovery. Per-element the
/// verdict maps to the status byte (0x00 decoded / 0x01 present-undecodable / 0x02
/// lost); the trailer is the discovered F once content decoded, else 0.
fn conformance_decompress(input: &[u8]) -> Vec<u8> {
    let elements = cv_parse_elements(input);
    let mut state = DecompressorState::init();
    let mut out: Vec<u8> = Vec::new();
    let mut i_out = [0u64; BUF_LEN];
    let mut scratch = DecodeScratch::new();
    for el in &elements {
        match el {
            RxElement::Lost => {
                out.push(0x02);
                // The robustness window only advances once F is locked.
                if state.discovered_f().is_some() {
                    state.notify_packet_loss(1);
                }
            }
            RxElement::Present(words, len_bits) => {
                match state.decode(words, 0, 0, *len_bits, &mut i_out, &mut scratch).0 {
                    DecodeStatus::Guaranteed => {
                        let f = state.discovered_f().expect("a Guaranteed decode locks F");
                        out.push(0x00);
                        out.extend_from_slice(&words_to_bytes(
                            &i_out[..num_blocks(f)],
                            (f as usize).div_ceil(8),
                        ));
                    }
                    DecodeStatus::Unguaranteed => out.push(0x01),
                }
            }
        }
    }
    // Trailer: the discovered F (0 only if no reference was ever locked).
    let trailer_f = state.discovered_f().unwrap_or(0);
    out.extend_from_slice(&(trailer_f as u32).to_be_bytes());
    out
}

// --- the contract ------------------------------------------------------------------------------

struct Pocketrust;

impl Adapter for Pocketrust {
    fn caps(&self) -> Caps {
        Caps {
            timing_tier: "in_process",
            reference_conformant: true,
            param_schedule: "pt_ft_rt",
            build_profile: "cargo release: opt-level=3, lto=true, codegen-units=1",
            limitations: "portable: uses only std bit operations (count_ones / trailing_zeros / \
                          reverse_bits) and no CPU-feature or target requirements, so it builds \
                          and runs on any target; large_f is capped at 65535 bits (the codec's \
                          u16 F / fixed [u64; 1024] word buffer); packet size is a runtime \
                          argument (no compile-time size cap)",
        }
    }

    fn compress(&self, data: &[u8], p: &Params) -> Result<Vec<u8>, String> {
        do_compress(data, p.packet_bits, p.pt, p.ft, p.rt, p.robustness)
    }

    fn decompress(&self, data: &[u8], p: &Params) -> Result<Vec<u8>, String> {
        do_decompress(data, p.packet_bits, p.robustness)
    }

    fn conformance_compress(&self) -> Option<ConformanceFn> {
        Some(conformance_compress as ConformanceFn)
    }

    fn conformance_decompress(&self) -> Option<ConformanceFn> {
        Some(conformance_decompress as ConformanceFn)
    }
}

fn main() {
    pocketbench_adapter::run(&Pocketrust)
}
