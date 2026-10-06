//! Green book (CCSDS 120.4-G-0) 3.3.4: an uncompressed packet without the full mask restores the input
//! vector but not the mask when the mask gap exceeds V_t (partial resynchronization, case d).

use pocketrust::{
    blocks_for, Block, CompressScratch, CompressorContext, DecompressScratch, DecompressStatus, DecompressorContext,
    BLOCK_BITS, BUF_LEN,
};

const F: u16 = 200;

fn next(seed: &mut u64) -> u64 {
    *seed ^= *seed << 13;
    *seed ^= *seed >> 7;
    *seed ^= *seed << 17;
    *seed
}

/// Each vector differs from the previous one in a few bits, so the mask changes every step and V_t stays at R.
fn inputs(n: usize) -> Vec<[Block; BUF_LEN]> {
    let mut seed = 0x5eed_0f_9e_17_u64;
    let nb = blocks_for(F as usize);
    let mut cur = [0 as Block; BUF_LEN];
    for w in cur.iter_mut().take(nb) {
        *w = next(&mut seed) as Block;
    }
    let rem = F as usize % BLOCK_BITS;
    let mut out = Vec::new();
    for _ in 0..n {
        if rem != 0 {
            cur[nb - 1] &= Block::MAX << (BLOCK_BITS - rem);
        }
        out.push(cur);
        for _ in 0..4 {
            let b = (next(&mut seed) % F as u64) as usize;
            cur[b / BLOCK_BITS] ^= (1 as Block) << (BLOCK_BITS - 1 - b % BLOCK_BITS);
        }
    }
    out
}

#[derive(Clone, Copy)]
enum Rx {
    Ok,
    Lost,
}

/// Compresses `inputs` with R = 1 and the given (new_mask, send_mask, uncompressed) overrides, delivers them as `rx`
/// says, and returns the decoder status per packet (None = lost) and whether each decoded output is correct.
fn run(flags: &dyn Fn(usize) -> (bool, bool, bool), rx: &dyn Fn(usize) -> Rx, n: usize) -> Vec<Option<(DecompressStatus, bool)>> {
    let ins = inputs(n);
    let nb = blocks_for(F as usize);
    let mut enc = CompressorContext::init(F);
    let mut es = CompressScratch::new();
    let mut dec = DecompressorContext::init_f_known(F);
    let mut ds = DecompressScratch::new();
    let mut res = Vec::new();
    for (t, i_t) in ins.iter().enumerate() {
        let forced = t <= 1;
        let (p, f, r) = if forced { (false, true, true) } else { flags(t) };
        let mut out = vec![0 as Block; nb * 12 + 8];
        let (pos, idx) = enc.compress(i_t, 1, p, f, r, &mut out, 0, 0, &mut es).expect("valid flags");
        let len = pos * BLOCK_BITS + idx as usize;
        match rx(t) {
            Rx::Lost => {
                dec.notify_packet_loss(1);
                res.push(None);
            }
            Rx::Ok => {
                let mut o = [0 as Block; BUF_LEN];
                let (st, _, _) = dec.decompress(&out, 0, 0, len, &mut o, &mut ds);
                res.push(Some((st, o[..nb] == i_t[..nb])));
            }
        }
    }
    res
}

fn guaranteed_means_correct(res: &[Option<(DecompressStatus, bool)>]) {
    for (t, r) in res.iter().enumerate() {
        if let Some((DecompressStatus::Guaranteed, correct)) = r {
            assert!(correct, "t={t} reported as guaranteed but the output is wrong");
        }
    }
}

#[test]
fn an_uncompressed_packet_without_mask_after_a_long_gap_is_not_a_restart_point() {
    let flags = |t: usize| (false, t == 12, t == 8 || t == 12);
    let rx = |t: usize| if (5..8).contains(&t) { Rx::Lost } else { Rx::Ok };
    let res = run(&flags, &rx, 15);
    guaranteed_means_correct(&res);
    assert_eq!(res[8], Some((DecompressStatus::Guaranteed, true)), "the uncompressed packet itself is correct");
    for t in 9..12 {
        assert_eq!(res[t].map(|r| r.0), Some(DecompressStatus::Unguaranteed), "t={t} relies on an unsynchronized mask");
    }
    for t in 12..15 {
        assert_eq!(res[t], Some((DecompressStatus::Guaranteed, true)), "t={t} after the r+f packet");
    }
}

#[test]
fn new_mask_and_uncompressed_without_mask_is_no_restart_point_either() {
    let flags = |t: usize| (t == 8, t == 12, t == 8 || t == 12);
    let rx = |t: usize| if (5..8).contains(&t) { Rx::Lost } else { Rx::Ok };
    let res = run(&flags, &rx, 15);
    guaranteed_means_correct(&res);
    for t in 9..12 {
        assert_eq!(res[t].map(|r| r.0), Some(DecompressStatus::Unguaranteed), "t={t}");
    }
}

#[test]
fn an_uncompressed_packet_within_v_t_stays_a_restart_point() {
    let flags = |t: usize| (false, false, t == 8);
    let rx = |t: usize| if t == 7 { Rx::Lost } else { Rx::Ok };
    let res = run(&flags, &rx, 12);
    guaranteed_means_correct(&res);
    for t in 8..12 {
        assert_eq!(res[t], Some((DecompressStatus::Guaranteed, true)), "t={t}");
    }
}
