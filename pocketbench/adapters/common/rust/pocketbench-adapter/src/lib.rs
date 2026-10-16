//! Shared pocketbench adapter plumbing for the Rust adapters: subcommand dispatch, strict
//! keyed-flag parsing, the record container, the warmup + timed loop and the summary JSON. An
//! adapter implements [`Codec`] and calls [`run`].
use std::fs;
use std::process::exit;
use std::time::Instant;

/// Decompress output kinds.
pub const DECODED: u8 = 0x00;
pub const FAILED: u8 = 0x01;
pub const LOST: u8 = 0x02;

const HEADER: usize = 4;
const RECORD_HEADER: usize = 5;
/// Room for one decoded field: F <= 65535 bits is at most 8192 bytes.
const DECODE_RESERVE: usize = 8192 + 64;
/// Room per compressed packet, per field byte; POCKET+ stays far below it.
const ENCODE_GROWTH: usize = 64;
const OUTPUT_SLACK: usize = 4096;

/// Why a hook could not take its input.
#[derive(Debug)]
pub enum HookError {
    /// The codec's API cannot take the input at all: a value its types cannot hold, or a field
    /// of the wrong length. Returned without calling the codec.
    NotRepresentable,
    /// The codec rejected the input; reported as `codec error <text>`.
    Codec(String),
    /// The harness output buffer is too small: a harness limit, not a codec result.
    OutputFull,
}

impl HookError {
    fn message(&self) -> String {
        match self {
            HookError::NotRepresentable => "input not representable by this codec's API".into(),
            HookError::Codec(e) => format!("codec error {}", e),
            HookError::OutputFull => "output buffer exhausted".into(),
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Record<'a> {
    pub kind: u8,
    /// The declared length.
    pub bits: u32,
    /// The bytes physically present.
    pub data: &'a [u8],
}

#[derive(Clone, Copy, Debug)]
pub struct Caps {
    pub build_profile: &'static str,
    pub limitations: &'static str,
}

/// Per-packet codec hooks. State lives in `Enc` / `Dec` for one pass and is dropped after it.
pub trait Codec {
    type Enc;
    type Dec;

    fn caps(&self) -> Caps;

    /// `f` is `None` when the input is shorter than its 4-byte header; `mask` is `None` when it
    /// holds no mask record.
    fn enc_create(&self, f: Option<u32>, mask: Option<&Record>) -> Result<Self::Enc, HookError>;

    /// Writes the packet to the front of `out` and returns its length in bits.
    fn enc_packet(&self, s: &mut Self::Enc, flag: u8, field: &[u8], out: &mut [u8])
        -> Result<u32, HookError>;

    /// `f` is 0 when unknown; `records` is the whole input, for codecs that pre-scan it.
    fn dec_create(&self, f: u32, records: &[Record]) -> Result<Self::Dec, HookError>;

    /// Returns `(DECODED, field bits)` with the field at the front of `out`, or `(FAILED, 0)`; an
    /// error stops the pass at this record. `avail_bits` is `min(declared_bits, 8 * data.len())`,
    /// the bits actually present; `out` holds at least `max(8256, data.len())` bytes.
    fn dec_packet(&self, s: &mut Self::Dec, data: &[u8], declared_bits: u32, avail_bits: u32,
                  out: &mut [u8]) -> Result<(u8, u32), HookError>;

    fn dec_lost(&self, s: &mut Self::Dec);

    fn dec_trailer_f(&self, s: &Self::Dec) -> u32;
}

/// A failed subcommand: exit code (2 = contract violation, 1 = runtime error) plus a message.
struct CmdError(i32, String);

fn usage(msg: impl Into<String>) -> CmdError {
    CmdError(2, msg.into())
}

fn failure(msg: impl Into<String>) -> CmdError {
    CmdError(1, msg.into())
}

// --- keyed flags --------------------------------------------------------------------------------

struct Args {
    input: String,
    output: String,
    warmup: Option<usize>,
    iterations: Option<usize>,
}

fn set_path(sub: &str, key: &str, value: &str, dst: &mut Option<String>) -> Result<(), CmdError> {
    if dst.is_some() {
        return Err(usage(format!("{}: flag --{} given more than once", sub, key)));
    }
    if value.is_empty() {
        return Err(usage(format!("{}: flag --{} needs a non-empty value", sub, key)));
    }
    *dst = Some(value.to_string());
    Ok(())
}

fn set_count(sub: &str, key: &str, value: &str, dst: &mut Option<usize>) -> Result<(), CmdError> {
    if dst.is_some() {
        return Err(usage(format!("{}: flag --{} given more than once", sub, key)));
    }
    let v = value.parse::<usize>().map_err(|_| {
        usage(format!("{}: flag --{} must be a non-negative integer, got \"{}\"", sub, key, value))
    })?;
    *dst = Some(v);
    Ok(())
}

fn parse_args(sub: &str, args: &[String]) -> Result<Args, CmdError> {
    let (mut input, mut output, mut warmup, mut iterations) = (None, None, None, None);
    for arg in args {
        let Some(body) = arg.strip_prefix("--") else {
            return Err(usage(format!(
                "{}: unexpected positional argument \"{}\"; the contract passes keyed flags \
                 (--key=value)",
                sub, arg
            )));
        };
        let Some((key, value)) = body.split_once('=') else {
            return Err(usage(format!("{}: flag \"{}\" needs a value, written --key=value", sub, arg)));
        };
        match key {
            "in" => set_path(sub, key, value, &mut input)?,
            "out" => set_path(sub, key, value, &mut output)?,
            "warmup" => set_count(sub, key, value, &mut warmup)?,
            "iterations" => set_count(sub, key, value, &mut iterations)?,
            _ => {
                return Err(usage(format!(
                    "{}: unknown flag --{}; this adapter does not implement it, so the harness and \
                     the adapter disagree about the contract",
                    sub, key
                )))
            }
        }
    }
    Ok(Args {
        input: input.ok_or_else(|| usage(format!("{}: missing required flag --in", sub)))?,
        output: output.ok_or_else(|| usage(format!("{}: missing required flag --out", sub)))?,
        warmup,
        iterations,
    })
}

// --- the record container -----------------------------------------------------------------------

struct Container<'a> {
    f: Option<u32>,
    records: Vec<Record<'a>>,
}

fn be32(b: &[u8]) -> u32 {
    u32::from_be_bytes([b[0], b[1], b[2], b[3]])
}

fn parse_container<'a>(sub: &str, buf: &'a [u8]) -> Result<Container<'a>, CmdError> {
    if buf.len() < HEADER {
        return Ok(Container { f: None, records: Vec::new() });
    }
    let f = be32(buf);
    let mut records = Vec::new();
    let mut pos = HEADER;
    while pos < buf.len() {
        if buf.len() - pos < RECORD_HEADER {
            return Err(usage(format!(
                "{}: malformed record container: record header cut off at byte {}",
                sub, pos
            )));
        }
        let kind = buf[pos];
        let bits = be32(&buf[pos + 1..]);
        pos += RECORD_HEADER;
        let len = (bits as usize).div_ceil(8).min(buf.len() - pos);
        records.push(Record { kind, bits, data: &buf[pos..pos + len] });
        pos += len;
    }
    Ok(Container { f: Some(f), records })
}

// --- passes ---------------------------------------------------------------------------------------

struct Pass {
    records_out: usize,
    stopped_at: Option<usize>,
    error: Option<HookError>,
}

/// The buffer outlives the passes, so a timed pass reuses what the warmup or first pass sized.
fn room(out: &mut Vec<u8>, size: usize, n: usize) {
    if out.len() < size + n {
        let len = (out.len() * 2).max(size + n);
        out.resize(len, 0);
    }
}

fn compress_pass<C: Codec>(c: &C, input: &Container<'_>, out: &mut Vec<u8>) -> (usize, Pass) {
    let mut pass = Pass { records_out: 0, stopped_at: None, error: None };
    room(out, 0, HEADER);
    out[..HEADER].copy_from_slice(&input.f.unwrap_or(0).to_be_bytes());
    let mut size = HEADER;
    let mut state = match c.enc_create(input.f, input.records.first()) {
        Ok(s) => s,
        Err(e) => {
            pass.stopped_at = Some(0);
            pass.error = Some(e);
            return (size, pass);
        }
    };
    for (i, rec) in input.records.iter().enumerate().skip(1) {
        room(out, size, RECORD_HEADER + ENCODE_GROWTH * rec.data.len().max(128));
        let body = size + RECORD_HEADER;
        match c.enc_packet(&mut state, rec.kind, rec.data, &mut out[body..]) {
            Ok(bits) => {
                out[size] = 0;
                out[size + 1..body].copy_from_slice(&bits.to_be_bytes());
                size = body + (bits as usize).div_ceil(8);
                pass.records_out += 1;
            }
            Err(e) => {
                pass.stopped_at = Some(i);
                pass.error = Some(e);
                break;
            }
        }
    }
    (size, pass)
}

fn decompress_pass<C: Codec>(c: &C, input: &Container<'_>, out: &mut Vec<u8>) -> (usize, Pass) {
    let mut pass = Pass { records_out: 0, stopped_at: None, error: None };
    room(out, 0, HEADER);
    out[..HEADER].fill(0);
    let mut size = HEADER;
    let mut state = match c.dec_create(input.f.unwrap_or(0), &input.records) {
        Ok(s) => s,
        Err(e) => {
            pass.stopped_at = Some(0);
            pass.error = Some(e);
            return (size, pass);
        }
    };
    for (i, rec) in input.records.iter().enumerate() {
        room(out, size, RECORD_HEADER + rec.data.len().max(DECODE_RESERVE));
        let body = size + RECORD_HEADER;
        let (status, bits) = if rec.kind & 1 == 1 {
            c.dec_lost(&mut state);
            (LOST, 0)
        } else {
            let avail = (rec.data.len() as u64 * 8).min(rec.bits as u64) as u32;
            match c.dec_packet(&mut state, rec.data, rec.bits, avail, &mut out[body..]) {
                Ok((DECODED, bits)) => (DECODED, bits),
                Ok(_) => (FAILED, 0),
                Err(e) => {
                    pass.stopped_at = Some(i);
                    pass.error = Some(e);
                    break;
                }
            }
        };
        out[size] = status;
        out[size + 1..body].copy_from_slice(&bits.to_be_bytes());
        size = body + (bits as usize).div_ceil(8);
        pass.records_out += 1;
    }
    out[..HEADER].copy_from_slice(&c.dec_trailer_f(&state).to_be_bytes());
    (size, pass)
}

// --- the verbs ------------------------------------------------------------------------------------

fn json_str(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            ch if (ch as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", ch as u32)),
            ch => out.push(ch),
        }
    }
    out.push('"');
    out
}

fn summary(records_in: usize, pass: &Pass, nanos: Option<&[u128]>) -> String {
    let stopped = pass.stopped_at.map_or("null".to_string(), |i| i.to_string());
    let error = pass.error.as_ref().map_or("null".to_string(), |e| json_str(&e.message()));
    let mut s = format!(
        "{{\"records_in\":{},\"records_out\":{},\"stopped_at\":{},\"error\":{}",
        records_in, pass.records_out, stopped, error
    );
    if let Some(n) = nanos {
        let list: Vec<String> = n.iter().map(|x| x.to_string()).collect();
        s.push_str(&format!(",\"nanos\":[{}]", list.join(",")));
    }
    s.push('}');
    s
}

type PassFn<C> = fn(&C, &Container<'_>, &mut Vec<u8>) -> (usize, Pass);

fn cmd_verb<C: Codec>(c: &C, args: &[String], is_compress: bool) -> Result<(), CmdError> {
    let sub = if is_compress { "compress" } else { "decompress" };
    let a = parse_args(sub, args)?;
    let buf = fs::read(&a.input)
        .map_err(|e| failure(format!("{}: cannot read {}: {}", sub, a.input, e)))?;
    let input = parse_container(sub, &buf)?;
    let payload: usize = input.records.iter().map(|r| r.data.len()).sum();
    let f = input.f.unwrap_or(0) as usize;
    let estimate = if is_compress {
        12 * payload + ENCODE_GROWTH * 8192
    } else {
        input.records.len() * f.div_ceil(8) + DECODE_RESERVE
    };
    let mut out = vec![0u8; HEADER + input.records.len() * RECORD_HEADER + OUTPUT_SLACK + estimate];
    let one_pass: PassFn<C> = if is_compress { compress_pass::<C> } else { decompress_pass::<C> };

    for _ in 0..a.warmup.unwrap_or(0) {
        one_pass(c, &input, &mut out);
    }
    let mut nanos: Vec<u128> = Vec::new();
    let (size, pass) = match a.iterations {
        Some(n) if n > 0 => {
            let mut last = None;
            for _ in 0..n {
                let t0 = Instant::now();
                let result = one_pass(c, &input, &mut out);
                nanos.push(t0.elapsed().as_nanos());
                let stopped = result.1.stopped_at.is_some();
                last = Some(result);
                if stopped {
                    break;
                }
            }
            last.expect("at least one timed pass")
        }
        _ => one_pass(c, &input, &mut out),
    };
    fs::write(&a.output, &out[..size])
        .map_err(|e| failure(format!("{}: cannot write {}: {}", sub, a.output, e)))?;
    println!("{}", summary(input.records.len(), &pass, a.iterations.map(|_| &nanos[..])));
    match pass.error {
        Some(HookError::OutputFull) => Err(failure(format!("{}: output buffer exhausted", sub))),
        _ => Ok(()),
    }
}

/// Call from the adapter's `main`.
pub fn run<C: Codec>(c: &C) -> ! {
    let argv: Vec<String> = std::env::args().collect();
    let result = match argv.get(1).map(String::as_str) {
        Some("capabilities") => {
            let caps = c.caps();
            println!(
                "{{\"build_profile\":{},\"limitations\":{}}}",
                json_str(caps.build_profile),
                json_str(caps.limitations)
            );
            Ok(())
        }
        Some("compress") => cmd_verb(c, &argv[2..], true),
        Some("decompress") => cmd_verb(c, &argv[2..], false),
        Some(other) => Err(usage(format!("unknown subcommand: {}", other))),
        None => Err(usage("usage: adapter <capabilities|compress|decompress> [--key=value ...]")),
    };
    match result {
        Ok(()) => exit(0),
        Err(CmdError(code, msg)) => {
            eprintln!("{}", msg);
            exit(code)
        }
    }
}
