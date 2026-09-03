//! Shared pocketbench adapter plumbing for the Rust adapters: subcommand dispatch, keyed-flag
//! parsing, the capabilities JSON, file IO, packets_per_iter, the warmup+timed loop and the
//! raw-nanos payload. An adapter implements [`Adapter`] and calls [`run`].
use std::collections::HashMap;
use std::fs;
use std::process::exit;
use std::str::FromStr;
use std::time::Instant;

/// CCSDS 124.0-B-1 allows F in 1..=65535, which is exactly the non-zero range of `u16`.
pub const MAX_PACKET_BITS: u32 = u16::MAX as u32;

/// A codec ignores any field it does not use.
#[derive(Clone, Copy, Debug)]
pub struct Params {
    /// F (large_f), the packet field width in bits. Validated to 1..=65535 before an adapter
    /// sees it.
    pub packet_bits: u16,
    pub pt: isize,
    pub ft: isize,
    pub rt: isize,
    pub robustness: isize,
}

impl Params {
    pub fn stride(&self) -> usize {
        (self.packet_bits as usize).div_ceil(8)
    }
}

/// `ops` and the two conformance flags are not here: [`run`] derives them from what the adapter
/// implements, so a claim of support cannot drift from the code.
#[derive(Clone, Copy, Debug)]
pub struct Caps {
    /// `"in_process"` (this wrapper runs the timed loop) or `"subprocess"`.
    pub timing_tier: &'static str,
    /// Compressed output is byte-identical to the ESA reference.
    pub reference_conformant: bool,
    /// Free label for how the impl uses the params, e.g. `"pt_ft_rt"`.
    pub param_schedule: &'static str,
    pub build_profile: &'static str,
    /// Constraints of this build: packet-size range, missing APIs.
    pub limitations: &'static str,
}

/// One UAB/CNES vector in, its byte-exact result out. The suite expects an empty output file
/// rather than an error for a malformed vector, hence no `Result`.
pub type ConformanceFn = fn(&[u8]) -> Vec<u8>;

/// Both ops are required, so [`run`] reports `ops = ["compress", "decompress"]` for any
/// implementor. Conformance is optional; its capability flags follow the hooks.
pub trait Adapter {
    fn caps(&self) -> Caps;

    /// Compress a whole buffer of byte-padded packets. Include any per-call codec setup: a real
    /// caller pays it, so it is timed.
    fn compress(&self, data: &[u8], p: &Params) -> Result<Vec<u8>, String>;

    fn decompress(&self, data: &[u8], p: &Params) -> Result<Vec<u8>, String>;

    fn conformance_compress(&self) -> Option<ConformanceFn> {
        None
    }

    fn conformance_decompress(&self) -> Option<ConformanceFn> {
        None
    }
}

/// A failed subcommand: exit code (2 = usage error, 1 = runtime error) plus a stderr message.
pub struct CmdError(pub i32, pub String);

pub fn usage(msg: impl Into<String>) -> CmdError {
    CmdError(2, msg.into())
}

pub fn failure(msg: impl Into<String>) -> CmdError {
    CmdError(1, msg.into())
}

// --- keyed-flag parsing -------------------------------------------------------------------------

/// Every declared key is required, unknown keys are rejected, and a value must parse completely.
struct Flags<'a> {
    sub: &'a str,
    values: HashMap<&'a str, &'a str>,
}

impl<'a> Flags<'a> {
    fn parse(sub: &'a str, args: &'a [String], keys: &[&'a str]) -> Result<Self, CmdError> {
        let mut values: HashMap<&str, &str> = HashMap::new();
        for arg in args {
            let Some(body) = arg.strip_prefix("--") else {
                return Err(usage(format!(
                    "{}: unexpected positional argument \"{}\"; the contract passes keyed flags \
                     (--key=value)",
                    sub, arg
                )));
            };
            let Some((key, value)) = body.split_once('=') else {
                return Err(usage(format!(
                    "{}: flag \"{}\" needs a value, written --key=value",
                    sub, arg
                )));
            };
            if !keys.contains(&key) {
                return Err(usage(format!(
                    "{}: unknown flag --{}; this adapter does not implement it, so the harness and \
                     the adapter disagree about the contract",
                    sub, key
                )));
            }
            if values.insert(key, value).is_some() {
                return Err(usage(format!("{}: flag --{} given more than once", sub, key)));
            }
        }
        for key in keys {
            if !values.contains_key(key) {
                return Err(usage(format!("{}: missing required flag --{}", sub, key)));
            }
        }
        Ok(Flags { sub, values })
    }

    /// Present by construction: `parse` rejects a missing key.
    fn str(&self, key: &str) -> &'a str {
        self.values[key]
    }

    fn parse_as<T: FromStr>(&self, key: &str, what: &str) -> Result<T, CmdError> {
        self.str(key).parse().map_err(|_| {
            usage(format!(
                "{}: flag --{} must be {}, got \"{}\"",
                self.sub,
                key,
                what,
                self.str(key)
            ))
        })
    }

    fn packet_bits(&self) -> Result<u16, CmdError> {
        match self.parse_as::<u16>("packet-bits", "an integer in 1..=65535")? {
            0 => Err(usage(format!(
                "{}: flag --packet-bits must be 1..={} (F, the packet field width in bits)",
                self.sub, MAX_PACKET_BITS
            ))),
            v => Ok(v),
        }
    }

    fn params(&self) -> Result<Params, CmdError> {
        Ok(Params {
            packet_bits: self.packet_bits()?,
            pt: self.parse_as("pt", "an integer")?,
            ft: self.parse_as("ft", "an integer")?,
            rt: self.parse_as("rt", "an integer")?,
            robustness: self.parse_as("robustness", "an integer")?,
        })
    }
}

const PARAM_KEYS: [&str; 5] = ["packet-bits", "pt", "ft", "rt", "robustness"];

fn keys(front: &[&'static str]) -> Vec<&'static str> {
    let mut all = front.to_vec();
    all.extend_from_slice(&PARAM_KEYS);
    all
}

// --- file IO ------------------------------------------------------------------------------------

fn read_input(path: &str) -> Result<Vec<u8>, CmdError> {
    fs::read(path).map_err(|e| failure(format!("cannot read {}: {}", path, e)))
}

fn write_output(path: &str, data: &[u8]) -> Result<(), CmdError> {
    fs::write(path, data).map_err(|e| failure(format!("cannot write {}: {}", path, e)))
}

// --- capabilities -------------------------------------------------------------------------------

fn json_str(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn capabilities_json(a: &dyn Adapter) -> String {
    let caps = a.caps();
    format!(
        "{{\"ops\":[\"compress\",\"decompress\"],\
         \"timing_tier\":{},\
         \"reference_conformant\":{},\
         \"conformance_compress\":{},\
         \"conformance_decompress\":{},\
         \"param_schedule\":{},\
         \"build_profile\":{},\
         \"limitations\":{}}}",
        json_str(caps.timing_tier),
        caps.reference_conformant,
        a.conformance_compress().is_some(),
        a.conformance_decompress().is_some(),
        json_str(caps.param_schedule),
        json_str(caps.build_profile),
        json_str(caps.limitations),
    )
}

// --- subcommands --------------------------------------------------------------------------------

fn cmd_oneshot(a: &dyn Adapter, args: &[String], is_compress: bool) -> Result<(), CmdError> {
    let sub = if is_compress { "compress" } else { "decompress" };
    let f = Flags::parse(sub, args, &keys(&["in", "out"]))?;
    let p = f.params()?;
    let input = read_input(f.str("in"))?;
    let output = if is_compress {
        a.compress(&input, &p)
    } else {
        a.decompress(&input, &p)
    }
    .map_err(|e| failure(format!("codec error: {}", e)))?;
    write_output(f.str("out"), &output)
}

fn cmd_bench(a: &dyn Adapter, args: &[String]) -> Result<(), CmdError> {
    let f = Flags::parse("bench", args, &keys(&["op", "in", "warmup", "iterations"]))?;
    let op = f.str("op");
    let is_compress = match op {
        "compress" => true,
        "decompress" => false,
        other => {
            return Err(usage(format!(
                "bench: --op must be compress or decompress, got \"{}\"",
                other
            )))
        }
    };
    let warmup: usize = f.parse_as("warmup", "a non-negative integer")?;
    let iterations: usize = f.parse_as("iterations", "a non-negative integer")?;
    let p = f.params()?;

    let input = read_input(f.str("in"))?;
    let packets_per_iter = input.len() / p.stride();

    let bench_in: Vec<u8> = if is_compress {
        input
    } else {
        a.compress(&input, &p)
            .map_err(|e| failure(format!("bench: pre-compress failed: {}", e)))?
    };

    let run_once = |data: &[u8]| -> Result<Vec<u8>, CmdError> {
        if is_compress {
            a.compress(data, &p)
        } else {
            a.decompress(data, &p)
        }
        .map_err(|e| failure(format!("bench codec error: {}", e)))
    };

    for _ in 0..warmup {
        run_once(&bench_in)?;
    }

    let mut nanos: Vec<u128> = Vec::with_capacity(iterations);
    for _ in 0..iterations {
        let t0 = Instant::now();
        let r = run_once(&bench_in);
        let dt = t0.elapsed().as_nanos();
        r?;
        nanos.push(dt);
    }

    let nanos_str: Vec<String> = nanos.iter().map(|n| n.to_string()).collect();
    println!(
        "{{\"op\":\"{}\",\"iterations\":{},\"packets_per_iter\":{},\"nanos\":[{}]}}",
        op,
        iterations,
        packets_per_iter,
        nanos_str.join(",")
    );
    Ok(())
}

fn cmd_conformance(
    args: &[String],
    sub: &'static str,
    transform: Option<ConformanceFn>,
) -> Result<(), CmdError> {
    let f = Flags::parse(sub, args, &["in", "out"])?;
    let Some(transform) = transform else {
        return Err(usage(format!("{}: not supported by this implementation", sub)));
    };
    let data = read_input(f.str("in"))?;
    write_output(f.str("out"), &transform(&data))
}

fn dispatch(a: &dyn Adapter, argv: &[String]) -> Result<(), CmdError> {
    let Some(cmd) = argv.get(1) else {
        return Err(usage(
            "usage: adapter <capabilities|compress|decompress|bench|conformance-compress|\
             conformance-decompress> [--key=value ...]",
        ));
    };
    let rest = &argv[2..];
    match cmd.as_str() {
        "capabilities" => {
            println!("{}", capabilities_json(a));
            Ok(())
        }
        "compress" => cmd_oneshot(a, rest, true),
        "decompress" => cmd_oneshot(a, rest, false),
        "bench" => cmd_bench(a, rest),
        "conformance-compress" => {
            cmd_conformance(rest, "conformance-compress", a.conformance_compress())
        }
        "conformance-decompress" => {
            cmd_conformance(rest, "conformance-decompress", a.conformance_decompress())
        }
        other => Err(usage(format!("unknown subcommand: {}", other))),
    }
}

/// Call from the adapter's `main`.
pub fn run(a: &dyn Adapter) -> ! {
    let argv: Vec<String> = std::env::args().collect();
    match dispatch(a, &argv) {
        Ok(()) => exit(0),
        Err(CmdError(code, msg)) => {
            eprintln!("{}", msg);
            exit(code);
        }
    }
}

/// For a codec API that takes unsigned params: errors on a negative value rather than wrapping.
pub fn unsigned(name: &str, v: isize) -> Result<usize, String> {
    usize::try_from(v).map_err(|_| format!("{} must be non-negative, got {}", name, v))
}
