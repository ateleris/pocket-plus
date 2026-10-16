//! pocketrust pocketbench adapter: per-packet hooks over the RHEA-internal pocketrust codec. The
//! codec works on big-endian `Block`s, so the hooks convert each field and packet at the boundary.
use pocketbench_adapter::{Caps, Codec, HookError, Record, DECODED, FAILED};
use pocketrust::{
    check_frame, CompressScratch, CompressorContext, DecompressScratch, DecompressStatus,
    blocks_for, Block, DecompressorContext, BLOCK_BITS, BUF_LEN,
};

const BLOCK_BYTES: usize = size_of::<Block>();

/// Blocks past `ceil(bytes.len() / BLOCK_BYTES)` are left untouched.
fn pack_be_into(bytes: &[u8], out: &mut [Block]) {
    for (i, chunk) in bytes.chunks(BLOCK_BYTES).enumerate() {
        let mut buf = [0u8; BLOCK_BYTES];
        buf[..chunk.len()].copy_from_slice(chunk);
        out[i] = Block::from_be_bytes(buf);
    }
}

fn unpack_be(words: &[Block], out: &mut [u8]) {
    for (i, chunk) in out.chunks_mut(BLOCK_BYTES).enumerate() {
        chunk.copy_from_slice(&words[i].to_be_bytes()[..chunk.len()]);
    }
}

/// Worst-case words for one compressed packet, matching the C reference's 12x sizing.
fn packet_words(f: u16) -> usize {
    blocks_for(f as usize) * 12 + 4
}

struct Enc {
    ctx: CompressorContext,
    scratch: CompressScratch,
    field: [Block; BUF_LEN],
    packet: Vec<Block>,
    stride: usize,
}

struct Dec {
    ctx: DecompressorContext,
    scratch: DecompressScratch,
    words: Vec<Block>,
    field: [Block; BUF_LEN],
}

struct Pocketrust;

impl Codec for Pocketrust {
    type Enc = Enc;
    type Dec = Dec;

    fn caps(&self) -> Caps {
        Caps {
            build_profile: match BLOCK_BITS {
                32 => "cargo release: opt-level=3, lto=true, codegen-units=1; 32-bit blocks",
                64 => "cargo release: opt-level=3, lto=true, codegen-units=1; 64-bit blocks",
                _ => "cargo release: opt-level=3, lto=true, codegen-units=1",
            },
            limitations: "large_f is capped at 65535 bits (the codec's u16 F / fixed BUF_LEN-block \
                          buffer), so a larger F is not representable; portable: std bit \
                          operations only, no CPU-feature or target requirements",
        }
    }

    fn enc_create(&self, f: Option<u32>, mask: Option<&Record>) -> Result<Enc, HookError> {
        let f = f.and_then(|f| u16::try_from(f).ok()).ok_or(HookError::NotRepresentable)?;
        let stride = (f as usize).div_ceil(8);
        let mask = mask.filter(|m| m.data.len() == stride).ok_or(HookError::NotRepresentable)?;
        let mut ctx = CompressorContext::init(f);
        let mut words: [Block; BUF_LEN] = [0; BUF_LEN];
        pack_be_into(mask.data, &mut words);
        ctx.set_initial_mask(&words).map_err(|e| HookError::Codec(e.to_string()))?;
        Ok(Enc {
            ctx,
            scratch: CompressScratch::new(),
            field: [0; BUF_LEN],
            packet: vec![0; packet_words(f)],
            stride,
        })
    }

    fn enc_packet(&self, s: &mut Enc, flag: u8, field: &[u8], out: &mut [u8]) -> Result<u32, HookError> {
        if field.len() != s.stride {
            return Err(HookError::NotRepresentable);
        }
        pack_be_into(field, &mut s.field);
        let (pos, idx) = s.ctx.compress(
            &s.field,
            (flag & 0x0F) as isize,
            flag & 0x20 != 0,
            flag & 0x40 != 0,
            flag & 0x10 != 0,
            &mut s.packet,
            0,
            0,
            &mut s.scratch,
        )
        .map_err(|e| HookError::Codec(e.to_string()))?;
        let bits = pos * BLOCK_BITS + idx as usize;
        let bytes = bits.div_ceil(8);
        if bytes > out.len() {
            return Err(HookError::OutputFull);
        }
        unpack_be(&s.packet, &mut out[..bytes]);
        // The bit writer ORs into the word after the cursor, so clear what this packet used.
        let used = (pos + 2).min(s.packet.len());
        s.packet[..used].fill(0);
        Ok(bits as u32)
    }

    fn dec_create(&self, f: u32, _records: &[Record]) -> Result<Dec, HookError> {
        let ctx = match f {
            0 => DecompressorContext::init(),
            f => DecompressorContext::init_f_known(
                u16::try_from(f).map_err(|_| HookError::NotRepresentable)?,
            ),
        };
        Ok(Dec { ctx, scratch: DecompressScratch::new(), words: Vec::new(), field: [0; BUF_LEN] })
    }

    fn dec_packet(&self, s: &mut Dec, data: &[u8], declared: u32, avail: u32, out: &mut [u8])
        -> Result<(u8, u32), HookError> {
        check_frame(declared as usize, data.len() * 8).map_err(|e| HookError::Codec(e.to_string()))?;
        s.words.clear();
        s.words.resize(blocks_for(data.len() * 8), 0);
        pack_be_into(data, &mut s.words);
        match s.ctx.decompress(&s.words, 0, 0, avail as usize, &mut s.field, &mut s.scratch).0 {
            DecompressStatus::Guaranteed => {
                let f = s.ctx.discovered_f().expect("a Guaranteed decode locks F") as usize;
                unpack_be(&s.field, &mut out[..f.div_ceil(8)]);
                Ok((DECODED, f as u32))
            }
            DecompressStatus::Unguaranteed => Ok((FAILED, 0)),
        }
    }

    fn dec_lost(&self, s: &mut Dec) {
        // The robustness window only advances once F is locked.
        if s.ctx.discovered_f().is_some() {
            s.ctx.notify_packet_loss(1);
        }
    }

    fn dec_trailer_f(&self, s: &Dec) -> u32 {
        s.ctx.discovered_f().unwrap_or(0) as u32
    }
}

fn main() {
    pocketbench_adapter::run(&Pocketrust)
}
