#![cfg(feature = "trace")]

use pocketrust::{
    blocks_for, Block, CompressScratch, CompressorContext, DecompressScratch, DecompressStatus,
    DecompressorContext, Seg, BLOCK_BITS, BUF_LEN, SEGS,
};

fn next(seed: &mut u64) -> u64 {
    *seed ^= *seed << 13;
    *seed ^= *seed >> 7;
    *seed ^= *seed << 17;
    *seed
}

fn clean(v: &mut [Block; BUF_LEN], f: u16) {
    let n = blocks_for(f as usize);
    for w in v.iter_mut().skip(n) {
        *w = 0;
    }
    let rem = f as usize % BLOCK_BITS;
    if rem != 0 {
        v[n - 1] &= Block::MAX << (BLOCK_BITS - rem);
    }
}

fn random_field(seed: &mut u64, f: u16) -> [Block; BUF_LEN] {
    let mut v = [0; BUF_LEN];
    for w in v.iter_mut().take(blocks_for(f as usize)) {
        *w = next(seed) as Block;
    }
    clean(&mut v, f);
    v
}

/// Flips roughly one bit in eight.
fn mutate(seed: &mut u64, prev: &[Block; BUF_LEN], f: u16) -> [Block; BUF_LEN] {
    let mut v = *prev;
    for w in v.iter_mut().take(blocks_for(f as usize)) {
        *w ^= (next(seed) & next(seed) & next(seed)) as Block;
    }
    clean(&mut v, f);
    v
}

struct Flags {
    r: isize,
    new_mask: bool,
    send_mask: bool,
    uncompressed: bool,
}

/// Flags of packet t under the pt = 3, ft = 5, rt = 7 schedule, with the init phase forced.
fn schedule(t: usize, r: isize) -> Flags {
    let forced = t as isize <= r;
    Flags {
        r,
        new_mask: !forced && t > 0 && t % 3 == 0,
        send_mask: forced || (t > 0 && t % 5 == 0),
        uncompressed: forced || (t > 0 && t % 7 == 0),
    }
}

/// Compresses one packet into a fresh buffer at bit `at`; returns the buffer and the packet length.
fn compress_at(enc: &mut CompressorContext, i_t: &[Block; BUF_LEN], fl: &Flags, f: u16, at: usize) -> (Vec<Block>, usize) {
    let mut out = vec![0 as Block; blocks_for(f as usize) * 12 + 8 + at / BLOCK_BITS];
    let mut scratch = CompressScratch::new();
    let (pos, idx) = enc
        .compress(i_t, fl.r, fl.new_mask, fl.send_mask, fl.uncompressed, &mut out, at / BLOCK_BITS, (at % BLOCK_BITS) as u8, &mut scratch)
        .expect("valid input");
    (out, pos * BLOCK_BITS + idx as usize - at)
}

fn compress(enc: &mut CompressorContext, i_t: &[Block; BUF_LEN], fl: &Flags, f: u16) -> (Vec<Block>, usize) {
    compress_at(enc, i_t, fl, f, 0)
}

#[test]
fn d_t_is_the_change_outside_the_previous_mask() {
    let f = 200u16;
    let n = blocks_for(f as usize);
    let mut seed = 0x9e37_79b9_7f4a_7c15;
    let mut enc = CompressorContext::init(f);
    let mut prev = random_field(&mut seed, f);
    compress(&mut enc, &prev, &Flags { r: 1, new_mask: false, send_mask: true, uncompressed: true }, f);
    assert!(enc.last_trace().d_t[..n].iter().all(|&w| w == 0), "no D_t at t = 0");
    for t in 1..30 {
        let cur = mutate(&mut seed, &prev, f);
        let m_before = enc.state_m().to_vec();
        let forced = t <= 1;
        compress(&mut enc, &cur, &Flags { r: 1, new_mask: false, send_mask: forced, uncompressed: forced }, f);
        let tr = enc.last_trace();
        for i in 0..n {
            assert_eq!(tr.d_t[i], (cur[i] ^ prev[i]) & !m_before[i], "t={t} word {i}");
        }
        assert_eq!(&tr.m_t[..n], enc.state_m(), "t={t}");
        assert_eq!(tr.t, t as isize);
        prev = cur;
    }
}

#[test]
fn segments_tile_the_packet() {
    for f in [1u16, 200, 720] {
        for r in [0isize, 2, 7] {
            let mut seed = 0x1234_5678_9abc_def0;
            let mut enc = CompressorContext::init(f);
            let mut cur = random_field(&mut seed, f);
            for t in 0..40 {
                let (_, len) = compress(&mut enc, &cur, &schedule(t, r), f);
                let tr = enc.last_trace();
                let mut at = 0;
                for s in SEGS {
                    let sp = tr.segments.get(s);
                    assert_eq!(sp.start, at, "f={f} r={r} t={t} {s:?}");
                    at += sp.len;
                }
                assert_eq!(at, len, "f={f} r={r} t={t}");
                assert_eq!(tr.len_bits, len, "f={f} r={r} t={t}");
                assert_eq!(tr.segments.get(Seg::Vt).len, 4, "f={f} r={r} t={t}");
                assert_eq!(tr.segments.get(Seg::Dt).len, 1, "f={f} r={r} t={t}");
                cur = mutate(&mut seed, &cur, f);
            }
        }
    }
}

#[test]
fn state_getters_show_the_last_packet() {
    let f = 200u16;
    let n = blocks_for(f as usize);
    let mut seed = 0xdead_beef_0bad_f00d;
    let mut enc = CompressorContext::init(f);
    assert_eq!(enc.t(), -1);
    let mut cur = random_field(&mut seed, f);
    for t in 0..20 {
        compress(&mut enc, &cur, &schedule(t, 1), f);
        let tr = enc.last_trace();
        assert_eq!(enc.t(), t as isize);
        assert_eq!(enc.state_i(), &cur[..n]);
        assert_eq!(enc.state_m(), &tr.m_t[..n]);
        assert_eq!(enc.state_b(), &tr.b_t[..n]);
        cur = mutate(&mut seed, &cur, f);
    }
}

fn decode(dec: &mut DecompressorContext, buf: &[Block], at: usize, len: usize) -> DecompressStatus {
    let mut out = [0 as Block; BUF_LEN];
    let mut scratch = DecompressScratch::new();
    dec.decompress(buf, at / BLOCK_BITS, (at % BLOCK_BITS) as u8, at + len, &mut out, &mut scratch).0
}

#[test]
fn decoder_parses_the_segments_the_encoder_wrote() {
    for f in [1u16, 200, 720] {
        for r in [0isize, 2, 7] {
            let n = blocks_for(f as usize);
            let mut seed = 0x0fee_1dea_dbee_f001;
            let mut enc = CompressorContext::init(f);
            let mut dec = DecompressorContext::init_f_known(f);
            let mut cur = random_field(&mut seed, f);
            for t in 0..40 {
                let (buf, len) = compress(&mut enc, &cur, &schedule(t, r), f);
                let status = decode(&mut dec, &buf, 0, len);
                assert_eq!(status, DecompressStatus::Guaranteed, "f={f} r={r} t={t}");
                let (ct, dt) = (enc.last_trace(), dec.last_trace());
                assert_eq!(dt.segments, ct.segments, "f={f} r={r} t={t}");
                assert_eq!(&dt.x_t[..n], &ct.x_t[..n], "f={f} r={r} t={t}");
                assert_eq!(dt.v_t, Some(ct.v_t as u8), "f={f} r={r} t={t}");
                assert_eq!(dt.e_t, ct.e_t, "f={f} r={r} t={t}");
                assert_eq!(dt.c_t, ct.c_t, "f={f} r={r} t={t}");
                assert_eq!(dt.dot_d_t, Some(ct.dot_d_t), "f={f} r={r} t={t}");
                assert_eq!(&dt.m_committed[..n], enc.state_m(), "f={f} r={r} t={t}");
                assert_eq!(dec.state_m(), enc.state_m(), "f={f} r={r} t={t}");
                assert_eq!(dec.state_i(), &cur[..n], "f={f} r={r} t={t}");
                assert_eq!(dt.failed_at, None);
                assert_eq!(dt.status, 0);
                assert_eq!(dt.ring[dt.ring_len - 1], 0);
                cur = mutate(&mut seed, &cur, f);
            }
        }
    }
}

#[test]
fn offsets_are_relative_to_the_packet_start() {
    let f = 200u16;
    let mut seed = 0x5555_aaaa_1234_4321;
    let mut enc = CompressorContext::init(f);
    let mut dec = DecompressorContext::init_f_known(f);
    let cur = random_field(&mut seed, f);
    let (buf, len) = compress_at(&mut enc, &cur, &schedule(0, 0), f, 77);
    assert_eq!(decode(&mut dec, &buf, 77, len), DecompressStatus::Guaranteed);
    assert_eq!(dec.last_trace().segments, enc.last_trace().segments);
    assert_eq!(enc.last_trace().segments.get(Seg::RleX).start, 0);
}

#[test]
fn a_truncated_packet_reports_where_parsing_stopped() {
    let f = 200u16;
    let mut seed = 0x0123_4567_89ab_cdef;
    let mut enc = CompressorContext::init(f);
    let mut dec = DecompressorContext::init_f_known(f);
    let first = random_field(&mut seed, f);
    let (buf, len) = compress(&mut enc, &first, &Flags { r: 0, new_mask: false, send_mask: true, uncompressed: true }, f);
    assert_eq!(decode(&mut dec, &buf, 0, len), DecompressStatus::Guaranteed);
    let second = mutate(&mut seed, &first, f);
    let (buf, _) = compress(&mut enc, &second, &Flags { r: 0, new_mask: false, send_mask: false, uncompressed: false }, f);
    let ut = enc.last_trace().segments.get(Seg::Ut);
    assert!(ut.len > 1, "the packet must carry unpredictable bits");
    assert_eq!(decode(&mut dec, &buf, 0, ut.start + 1), DecompressStatus::Unguaranteed);
    let dt = dec.last_trace();
    assert_eq!(dt.failed_at, Some((Seg::Ut, ut.start)));
    assert_eq!(dt.status, 1);
    assert_eq!(dt.ring[dt.ring_len - 1], 1);
}

#[test]
fn discovery_keeps_the_trace() {
    let f = 200u16;
    let mut seed = 0x0bad_cafe_0bad_cafe;
    let mut enc = CompressorContext::init(f);
    let mut dec = DecompressorContext::init();
    let cur = random_field(&mut seed, f);
    let (buf, len) = compress(&mut enc, &cur, &schedule(0, 1), f);
    assert_eq!(decode(&mut dec, &buf, 0, len), DecompressStatus::Guaranteed);
    assert_eq!(dec.discovered_f(), Some(f));
    assert_eq!(dec.last_trace().segments, enc.last_trace().segments);
    assert!(!dec.last_trace().f_unknown);
}

#[test]
fn an_undiscoverable_packet_is_flagged() {
    let f = 200u16;
    let mut seed = 0x7777_1111_2222_3333;
    let mut enc = CompressorContext::init(f);
    let first = random_field(&mut seed, f);
    compress(&mut enc, &first, &schedule(0, 0), f);
    let second = mutate(&mut seed, &first, f);
    let (buf, len) = compress(&mut enc, &second, &Flags { r: 0, new_mask: false, send_mask: false, uncompressed: false }, f);
    let mut dec = DecompressorContext::init();
    assert_eq!(decode(&mut dec, &buf, 0, len), DecompressStatus::Unguaranteed);
    assert!(dec.last_trace().f_unknown);
    assert_eq!(dec.last_trace().status, 1);
}

/// Packets 0..=5 under R = 1; new masks at t = 3 (clears B) and t = 4 make bits predictable, so packet 5 (uncompressed, with mask) carries k_t.
fn rt_packet_after_history(seed: u64) -> (Vec<Block>, usize, CompressorContext) {
    let f = 200u16;
    let mut seed = seed;
    let mut enc = CompressorContext::init(f);
    let mut cur = random_field(&mut seed, f);
    let mut last = (Vec::new(), 0);
    for t in 0..6 {
        let forced = t <= 1;
        let rt = forced || t == 5;
        last = compress(&mut enc, &cur, &Flags { r: 1, new_mask: t == 3 || t == 4, send_mask: rt, uncompressed: rt }, f);
        cur = mutate(&mut seed, &cur, f);
    }
    (last.0, last.1, enc)
}

#[test]
fn a_weak_discovery_reports_where_parsing_stopped() {
    let f = 200u16;
    let mut seed = 0x1357_9bdf_2468_ace0;
    let mut enc = CompressorContext::init(f);
    let cur = random_field(&mut seed, f);
    let (buf, len) = compress(&mut enc, &cur, &schedule(0, 1), f);
    let ut = enc.last_trace().segments.get(Seg::Ut);
    let mut dec = DecompressorContext::init();
    assert_eq!(decode(&mut dec, &buf, 0, len - 10), DecompressStatus::Unguaranteed);
    assert_eq!(dec.discovered_f(), Some(f), "a weak discovery still locks F");
    let dt = dec.last_trace();
    assert!(dt.weak_discovery);
    assert!(!dt.f_rejected);
    assert_eq!(dt.failed_at, Some((Seg::Ut, ut.start)));
    assert_eq!(&dt.ring[..dt.ring_len], &[1]);
    assert_eq!(dt.status, 1);
}

#[test]
fn a_rejected_weak_discovery_does_not_claim_f() {
    let (mut buf, len, enc) = rt_packet_after_history(0x2468_1357_aaaa_5555);
    let kt = enc.last_trace().segments.get(Seg::Kt);
    assert!(kt.len > 0, "the packet must carry k_t");
    buf[kt.start / BLOCK_BITS] ^= (1 as Block) << (BLOCK_BITS - 1 - kt.start % BLOCK_BITS);
    let mut dec = DecompressorContext::init();
    assert_eq!(decode(&mut dec, &buf, 0, len - 10), DecompressStatus::Unguaranteed);
    assert_eq!(dec.discovered_f(), None);
    let dt = dec.last_trace();
    assert!(dt.f_rejected);
    assert!(!dt.weak_discovery);
    assert!(dt.mask_inc_changed);
    assert_eq!(dt.ring_len, 0);
}

#[test]
fn a_failed_strict_discovery_shows_the_rolled_back_ring() {
    let (mut buf, len, enc) = rt_packet_after_history(0x2468_1357_aaaa_5555);
    let kt = enc.last_trace().segments.get(Seg::Kt);
    assert!(kt.len > 0, "the packet must carry k_t");
    buf[kt.start / BLOCK_BITS] ^= (1 as Block) << (BLOCK_BITS - 1 - kt.start % BLOCK_BITS);
    let mut dec = DecompressorContext::init();
    assert_eq!(decode(&mut dec, &buf, 0, len), DecompressStatus::Unguaranteed);
    assert_eq!(dec.discovered_f(), None);
    let dt = dec.last_trace();
    assert!(dt.mask_inc_changed);
    assert_eq!(dt.ring_len, 0, "the decoder rolled its status push back");
    assert_eq!(dt.status, 1);
}
