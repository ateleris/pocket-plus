//! The shared Rust plumbing's test double: an identity "codec" with the same behaviour as
//! adapters/mock/adapter.py, so tests/test_plumbing.py holds every plumbing copy to one contract.
use pocketbench_adapter::{Caps, Codec, HookError, Record, DECODED, FAILED};

struct Identity;

impl Codec for Identity {
    type Enc = ();
    type Dec = u32;

    fn caps(&self) -> Caps {
        Caps { build_profile: "", limitations: "identity test double: every packet is its own field" }
    }

    fn enc_create(&self, f: Option<u32>, _mask: Option<&Record>) -> Result<(), HookError> {
        f.map(|_| ()).ok_or(HookError::NotRepresentable)
    }

    fn enc_packet(&self, _s: &mut (), flag: u8, field: &[u8], out: &mut [u8]) -> Result<u32, HookError> {
        if flag & 0x80 != 0 {
            return Err(HookError::Codec("7".into()));
        }
        if field.len() > out.len() {
            return Err(HookError::OutputFull);
        }
        out[..field.len()].copy_from_slice(field);
        Ok(8 * field.len() as u32)
    }

    fn dec_create(&self, f: u32, _records: &[Record]) -> Result<u32, HookError> {
        Ok(f)
    }

    fn dec_packet(&self, f: &mut u32, data: &[u8], declared: u32, _avail: u32, out: &mut [u8])
        -> Result<(u8, u32), HookError> {
        if declared == 13 {
            return Err(HookError::Codec("-9".into()));
        }
        if *f == 0 {
            return Ok((FAILED, 0));
        }
        out[..data.len()].copy_from_slice(data);
        Ok((DECODED, 8 * data.len() as u32))
    }

    fn dec_lost(&self, _f: &mut u32) {}

    fn dec_trailer_f(&self, f: &u32) -> u32 {
        *f
    }
}

fn main() {
    pocketbench_adapter::run(&Identity)
}
