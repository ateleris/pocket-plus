//! Round-trip: decode(encode(x)) == x for the new_mask=false schedule
//! (send_mask + uncompressed for the first R+1 packets, then incremental).

use pocketrust::{
    CompressorState, DecodeScratch, DecodeStatus, DecompressorState, EncodeScratch,
};

fn num_blocks(f: u16) -> usize {
    let mut n = (f as usize) / 64;
    if f % 64 > 0 {
        n += 1;
    }
    n
}

/// Deterministic sequence of F-bit vectors (MSB-first words), each differing
/// from the last in a handful of bit positions, using a small LCG.
fn make_packets(f: u16, count: usize) -> Vec<[u64; 1024]> {
    let n = num_blocks(f);
    let mut state: u64 = 0x9E37_79B9_7F4A_7C15;
    let mut next = || {
        state = state
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        state
    };
    let mut cur = [0u64; 1024];
    // Seed a few set bits.
    for _ in 0..(f as usize / 8) {
        let bit = (next() % f as u64) as usize;
        cur[bit / 64] |= 1u64 << (63 - (bit % 64));
    }
    let mut out = Vec::with_capacity(count);
    for _ in 0..count {
        out.push(cur);
        // Flip a few bits within the valid range for the next vector.
        for _ in 0..3 {
            let bit = (next() % f as u64) as usize;
            cur[bit / 64] ^= 1u64 << (63 - (bit % 64));
        }
        let _ = n;
    }
    out
}

fn roundtrip(
    f: u16,
    robustness: isize,
    count: usize,
    new_mask_period: usize,
    send_on_new_mask: bool,
) {
    let n = num_blocks(f);
    let packets = make_packets(f, count);
    let new_mask_at = |t: usize| new_mask_period != 0 && t > 0 && t % new_mask_period == 0;

    let mut enc = CompressorState::init(f);
    let mut scratch = EncodeScratch::new();
    let mut o = vec![0u64; count * 32 + 4096];
    let (mut pos, mut idx) = (0usize, 0u8);
    for (t, p) in packets.iter().enumerate() {
        let forced = (t as isize) <= robustness;
        let new_mask = new_mask_at(t);
        let send_mask = forced || (new_mask && send_on_new_mask);
        (pos, idx) = enc.encode(
            p,
            robustness,
            new_mask,
            send_mask,
            forced,
            &mut o,
            pos,
            idx,
            &mut scratch,
        );
    }

    let mut dec = DecompressorState::init_f_known(f);
    let mut dscratch = DecodeScratch::new();
    let (mut dpos, mut didx) = (0usize, 0u8);
    for (t, p) in packets.iter().enumerate() {
        let mut out = [0u64; 1024];
        let (_status, np, ni) = dec.decode(&o, dpos, didx, o.len() * 64, &mut out, &mut dscratch);
        (dpos, didx) = (np, ni);
        assert_eq!(
            &out[..n],
            &p[..n],
            "mismatch f={f} r={robustness} new_mask_period={new_mask_period} \
             send_on_new_mask={send_on_new_mask} packet={t}"
        );
    }
}

#[test]
fn decode_discovers_f_when_unknown() {
    for &f in &[128u16, 200, 720] {
        for &r in &[0isize, 1, 2, 7] {
            let count = 40;
            let n = num_blocks(f);
            let packets = make_packets(f, count);

            let mut enc = CompressorState::init(f);
            let mut escratch = EncodeScratch::new();
            let mut o = vec![0u64; count * 32 + 4096];
            let (mut pos, mut idx) = (0usize, 0u8);
            for (t, p) in packets.iter().enumerate() {
                let forced = (t as isize) <= r;
                (pos, idx) =
                    enc.encode(p, r, false, forced, forced, &mut o, pos, idx, &mut escratch);
            }

            // Decode without telling the decoder F.
            let mut dec = DecompressorState::init();
            assert_eq!(
                dec.discovered_f(),
                None,
                "f={f} r={r}: F known before decode"
            );
            let mut dscratch = DecodeScratch::new();
            let (mut dpos, mut didx) = (0usize, 0u8);
            for (t, p) in packets.iter().enumerate() {
                let mut out = [0u64; 1024];
                let (status, np, ni) =
                    dec.decode(&o, dpos, didx, o.len() * 64, &mut out, &mut dscratch);
                (dpos, didx) = (np, ni);
                assert_eq!(
                    status,
                    DecodeStatus::Guaranteed,
                    "f={f} r={r} packet={t}: not guaranteed"
                );
                assert_eq!(&out[..n], &p[..n], "mismatch f={f} r={r} packet={t}");
            }
            assert_eq!(dec.discovered_f(), Some(f), "f={f} r={r}: F not locked");
        }
    }
}

#[test]
fn roundtrip_various() {
    for &f in &[128u16, 200, 720] {
        for &r in &[0isize, 1, 2, 7] {
            roundtrip(f, r, 40, 0, false);
        }
    }
}

#[test]
fn roundtrip_new_mask_with_send() {
    for &f in &[128u16, 200, 720] {
        for &r in &[0isize, 1, 2, 7] {
            for &period in &[3usize, 5, 8] {
                roundtrip(f, r, 40, period, true);
            }
        }
    }
}

#[test]
fn roundtrip_new_mask_incremental() {
    for &f in &[128u16, 200, 720] {
        for &r in &[1isize, 2, 7] {
            for &period in &[3usize, 5, 8] {
                roundtrip(f, r, 40, period, false);
            }
        }
    }
}
