"""Explain why one UAB/CNES conformance vector diverged from the reference output.

`conformance` reports *which* vectors failed; this module says *what* differed, by diffing an
implementation's output against the suite's expected output. The suite ships the expected bytes
(`encoder_output/`, `decoder_output/`), which the graded sweep never reads because it compares only
size + SHA-256, so ground truth is already on disk.

**Decoder output is diffed structurally.** Its format is self-describing, so it can be parsed with
no POCKET+ knowledge here: a sequence of **frames**, each a status byte plus (for status 0x00)
`large_f` bits zero-padded to the next byte boundary, followed by a 32-bit big-endian `large_f`
trailer. (The yellow book calls a frame an "output element"; we say frame throughout. It is
unrelated to a CCSDS transfer frame, which this codebase never handles.) Status bytes are 0x00
decoded, 0x01 present but not decodable with an accuracy guarantee, 0x02 lost/dropped.

**Encoder output is diffed bytewise only.** It is a concatenation of byte-aligned compressed packets
with no length prefix, and packet lengths follow from the RLE-coded mask content, so locating packet
`t` needs what amounts to a decoder. The encoder report therefore gives a byte offset and says that
packet attribution is unavailable.

Keep the diff and render core pure (bytes in, structured diff out): `resolve_vector` is the only
function here allowed to touch the filesystem.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

# Decoder frame status bytes, per the suite README.
STATUS_DECODED = 0x00
STATUS_UNDECODABLE = 0x01
STATUS_LOST = 0x02

_STATUS_NAME = {
    STATUS_DECODED: "decoded",
    STATUS_UNDECODABLE: "not decodable with guarantee",
    STATUS_LOST: "lost packet",
}

# The 32-bit big-endian large_f trailer at the end of a decoder output stream.
_TRAILER_BYTES = 4


def status_name(status: int | None) -> str:
    if status is None:
        return "absent"
    return _STATUS_NAME.get(status, f"unknown (0x{status:02x})")


def status_gloss(expected: int | None, actual: int | None) -> str:
    """Plain-language reading of a status divergence, to point at the likely area."""
    if expected == actual:
        return ""
    if actual is None:
        return "your output ends early (fewer frames than the reference)"
    if expected is None:
        return "your output has extra frames beyond the reference"
    if expected == STATUS_DECODED and actual == STATUS_UNDECODABLE:
        return "you rejected a packet the reference decoded"
    if expected == STATUS_UNDECODABLE and actual == STATUS_DECODED:
        return "you accepted a packet the reference could not guarantee"
    if expected == STATUS_LOST or actual == STATUS_LOST:
        return "disagreement about a lost packet"
    return "status differs"


# --------------------------------------------------------------------------- data model


@dataclass(frozen=True)
class FrameView:
    """One decoder output frame."""

    index: int
    status: int
    payload: bytes      # empty unless status is 0x00
    payload_bits: int   # large_f for a decoded frame, else 0


@dataclass(frozen=True)
class FrameDiff:
    """How one frame differs. Bit indices are within the frame payload."""

    index: int
    expected_status: int | None
    actual_status: int | None
    first_diff_bit: int | None = None
    last_diff_bit: int | None = None

    @property
    def status_differs(self) -> bool:
        return self.expected_status != self.actual_status


@dataclass
class DecoderDiff:
    """Structural diff of two decoder output streams.

    Every diverging frame is reported. Frame divergences are independent, not a cascade: the
    format is self-delimiting (a frame's own status byte says whether a payload follows), so the
    parser holds no cross-frame state and the actual stream re-synchronizes at the very next
    frame. A differing status shifts byte offsets but not frame indices, and we compare by index.

    What *does* break the frame-to-frame correspondence is a differing frame count, since the
    yellow book requires exactly one output frame per input element. `frame_count_differs` flags it.
    """

    expected_f: int
    actual_f: int
    frames_total: int          # frames in the expected stream
    actual_frames: int
    frames_matching: int
    first_divergence: int | None = None
    frame_diffs: list[FrameDiff] = field(default_factory=list)
    # False when large_f diverged: frame boundaries in the actual stream are then unknown, so
    # the frame-level findings are not trustworthy and callers should use the byte diff.
    parse_reliable: bool = True

    @property
    def f_differs(self) -> bool:
        return self.expected_f != self.actual_f

    @property
    def frame_count_differs(self) -> bool:
        return self.frames_total != self.actual_frames


@dataclass
class ByteDiff:
    """Bytewise diff, used for the encoder phase and as the decoder fallback."""

    expected_size: int
    actual_size: int
    first_diff_offset: int | None = None
    expected_byte: int | None = None
    actual_byte: int | None = None

    @property
    def size_delta(self) -> int:
        return self.actual_size - self.expected_size


@dataclass
class ExplainResult:
    """One (impl, vector) explanation."""

    impl: str
    vector: str            # output name as recorded in ConformanceResult.failures
    phase: str             # "encoder" | "decoder"
    ran: bool              # the adapter exited 0 and wrote output
    returncode: int
    stderr: str
    expected: bytes        # ground truth from the suite
    actual: bytes          # what the adapter produced (empty when it did not run)
    byte_diff: ByteDiff
    decoder: DecoderDiff | None = None

    @property
    def identical(self) -> bool:
        return self.ran and self.expected == self.actual

    def show_frame(
        self, index: int, *, mode: str = "hex", full: bool = False, context: int = 1
    ) -> str:
        """Render decoder frame `index` against its ground truth.

        `mode` is "hex" (byte rows) or "bin" (bit rows). Only lines containing a difference are
        shown, plus `context` lines either side; `full=True` dumps the whole payload.
        """
        if self.phase != "decoder":
            raise ValueError(
                "show_frame is decoder-only: encoder output is an unframed concatenation of "
                "compressed packets, so it has no frame boundaries to show"
            )
        if self.decoder is None:
            raise ValueError(f"no frame data: the adapter did not run ({self.stderr.strip()})")
        if not self.decoder.parse_reliable:
            raise ValueError(
                f"cannot locate frames: large_f differs (expected {self.decoder.expected_f}, "
                f"got {self.decoder.actual_f}), so frame boundaries in your output are unknown"
            )
        exp_frames = parse_frames(self.expected).frames
        act_frames = parse_frames(self.actual).frames
        count = max(len(exp_frames), len(act_frames))
        if not 0 <= index < count:
            valid = f"valid 0..{count - 1}" if count else "the streams contain no frames"
            raise ValueError(
                f"frame {index} out of range: expected stream has {len(exp_frames)} frames, "
                f"yours has {len(act_frames)} ({valid})"
            )
        warning = ""
        d = self.decoder
        if d.frame_count_differs and index >= min(d.frames_total, d.actual_frames):
            warning = (
                f"WARNING: frame counts differ (reference {d.frames_total}, yours "
                f"{d.actual_frames}), so frames past {min(d.frames_total, d.actual_frames) - 1} "
                "may not correspond to the same input element\n"
            )
        return warning + render_frame(
            _at(exp_frames, index), _at(act_frames, index),
            label=f"frame {index}  {self.vector}", mode=mode, full=full, context=context,
        )


@dataclass(frozen=True)
class ParsedStream:
    """A parsed decoder output stream."""

    large_f: int
    frames: list[FrameView]
    exact: bool   # the frames consumed the stream exactly (no trailing junk / truncation)


def _at(frames: list[FrameView], index: int) -> FrameView | None:
    return frames[index] if index < len(frames) else None


# --------------------------------------------------------------------------- parsing


def parse_frames(data: bytes) -> ParsedStream:
    """Parse a decoder output stream into frames plus its trailing large_f.

    A short stream (no room for the trailer) yields large_f 0, no frames, and exact=False.
    """
    if len(data) < _TRAILER_BYTES:
        return ParsedStream(large_f=0, frames=[], exact=False)
    body_end = len(data) - _TRAILER_BYTES
    large_f = int.from_bytes(data[body_end:], "big")
    stride = (large_f + 7) // 8
    frames: list[FrameView] = []
    i = 0
    while i < body_end:
        status = data[i]
        i += 1
        payload = b""
        bits = 0
        if status == STATUS_DECODED:
            if i + stride > body_end:      # truncated payload
                return ParsedStream(large_f=large_f, frames=frames, exact=False)
            payload = data[i : i + stride]
            bits = large_f
            i += stride
        frames.append(FrameView(len(frames), status, payload, bits))
    return ParsedStream(large_f=large_f, frames=frames, exact=i == body_end)


# --------------------------------------------------------------------------- diffing


def diff_bytes(expected: bytes, actual: bytes) -> ByteDiff:
    """First differing byte offset plus sizes. Offset is None when one is a prefix of the other."""
    diff = ByteDiff(expected_size=len(expected), actual_size=len(actual))
    for off in range(min(len(expected), len(actual))):
        if expected[off] != actual[off]:
            return ByteDiff(
                expected_size=len(expected), actual_size=len(actual),
                first_diff_offset=off, expected_byte=expected[off], actual_byte=actual[off],
            )
    return diff


def _payload_diff_bits(a: FrameView, b: FrameView) -> tuple[int | None, int | None]:
    """First and last differing bit index within two frames' payloads."""
    bits = min(a.payload_bits, b.payload_bits)
    first = last = None
    for bit in range(bits):
        byte, off = divmod(bit, 8)
        mask = 0x80 >> off
        if (a.payload[byte] & mask) != (b.payload[byte] & mask):
            if first is None:
                first = bit
            last = bit
    return first, last


def diff_decoder(expected: bytes, actual: bytes) -> DecoderDiff:
    """Structurally diff two decoder output streams.

    Checked in order of what invalidates what: large_f first (a wrong stride makes frame parsing
    meaningless), then per-frame status, then payload bits. Every diverging frame is reported;
    see :class:`DecoderDiff` for why divergences are independent rather than a cascade.
    """
    exp = parse_frames(expected)
    act = parse_frames(actual)
    if exp.large_f != act.large_f:
        return DecoderDiff(
            expected_f=exp.large_f, actual_f=act.large_f,
            frames_total=len(exp.frames), actual_frames=len(act.frames),
            frames_matching=0, parse_reliable=False,
        )

    diffs: list[FrameDiff] = []
    matching = 0
    first_divergence: int | None = None
    for i in range(max(len(exp.frames), len(act.frames))):
        e, a = _at(exp.frames, i), _at(act.frames, i)
        if e is not None and a is not None and e == a:
            matching += 1
            continue
        if first_divergence is None:
            first_divergence = i
        e_status = e.status if e else None
        a_status = a.status if a else None
        if e_status != a_status:
            diffs.append(FrameDiff(i, e_status, a_status))
            continue
        first_bit, last_bit = _payload_diff_bits(e, a)   # type: ignore[arg-type]
        diffs.append(FrameDiff(i, e_status, a_status, first_bit, last_bit))

    return DecoderDiff(
        expected_f=exp.large_f, actual_f=act.large_f,
        frames_total=len(exp.frames), actual_frames=len(act.frames),
        frames_matching=matching, first_divergence=first_divergence, frame_diffs=diffs,
    )


# --------------------------------------------------------------------------- rendering


def _hex_lines(exp: bytes, act: bytes, width: int) -> list[tuple[int, str, str, str]]:
    out = []
    for start in range(0, max(len(exp), len(act)), width):
        e, a = exp[start : start + width], act[start : start + width]
        e_txt = " ".join(f"{b:02x}" for b in e)
        a_txt = " ".join(f"{b:02x}" for b in a)
        marks = "".join(
            ("^^ " if i < min(len(e), len(a)) and e[i] != a[i] else "   ")
            for i in range(max(len(e), len(a)))
        )
        out.append((start, e_txt, a_txt, marks.rstrip()))
    return out


def _bits(data: bytes, count: int) -> str:
    return "".join(f"{b:08b}" for b in data)[:count]


def _bin_lines(exp: bytes, act: bytes, bits: int, width: int) -> list[tuple[int, str, str, str]]:
    e_all, a_all = _bits(exp, bits), _bits(act, bits)
    out = []
    for start in range(0, bits, width):
        e, a = e_all[start : start + width], a_all[start : start + width]
        marks = "".join(
            ("^" if i < min(len(e), len(a)) and e[i] != a[i] else " ")
            for i in range(max(len(e), len(a)))
        )
        out.append((start, e, a, marks.rstrip()))
    return out


def render_frame(
    expected: FrameView | None, actual: FrameView | None, *,
    label: str = "frame", mode: str = "hex", full: bool = False, context: int = 1,
) -> str:
    """Render one frame against its ground truth as hex or binary rows."""
    if mode not in ("hex", "bin"):
        raise ValueError(f"mode must be 'hex' or 'bin', got {mode!r}")
    e_status = expected.status if expected else None
    a_status = actual.status if actual else None
    head = (
        f"{label}\n"
        f"  status   expected {_fmt_status(e_status)}   actual {_fmt_status(a_status)}"
    )
    if e_status != a_status:
        gloss = status_gloss(e_status, a_status)
        return (
            f"{head}   MISMATCH\n"
            f"  {gloss}\n"
            "  no byte comparison: the statuses differ, so payload presence differs too"
        )
    if expected is None or actual is None or not expected.payload:
        note = "no payload for this status" if expected else "frame absent from both streams"
        return f"{head}\n  {note}"

    exp, act = expected.payload, actual.payload
    head += f"\n  payload  {len(exp)} B, large_f={expected.payload_bits}"
    if exp == act:
        return f"{head}\n  payload identical"

    # Row widths are chosen so a rendered line (prefix + data) stays inside 80 columns; wider rows
    # get re-wrapped by the terminal, which silently destroys the marker alignment.
    lines = (
        _hex_lines(exp, act, 16) if mode == "hex"
        else _bin_lines(exp, act, expected.payload_bits, 32)
    )
    keep = _select_lines(lines, full=full, context=context)
    body = []
    prev = None
    for idx in keep:
        start, e_txt, a_txt, marks = lines[idx]
        if prev is not None and idx != prev + 1:
            body.append("  ...")
        pos = f"0x{start:04x}" if mode == "hex" else f"bit {start:>6}"
        body.append(f"  {pos}  exp  {e_txt}")
        body.append(f"  {' ' * len(pos)}  act  {a_txt}")
        if marks:
            body.append(f"  {' ' * len(pos)}       {marks}")
        prev = idx
    return f"{head}\n" + "\n".join(body)


def _fmt_status(status: int | None) -> str:
    if status is None:
        return "absent"
    return f"0x{status:02x} ({status_name(status)})"


def _select_lines(lines: list[tuple[int, str, str, str]], *, full: bool, context: int) -> list[int]:
    """Indices of lines to print: all of them, or only differing ones plus context."""
    if full:
        return list(range(len(lines)))
    differing = [i for i, (_, _, _, marks) in enumerate(lines) if marks]
    keep: set[int] = set()
    for i in differing:
        keep.update(range(max(0, i - context), min(len(lines), i + context + 1)))
    return sorted(keep)


# --------------------------------------------------------------------------- text summary


MAX_LISTED_FRAMES = 20


def summary_lines(r: ExplainResult, *, max_frames: int = MAX_LISTED_FRAMES) -> list[str]:
    """Human-readable summary of one explanation, shared by the CLI and notebooks.

    At most `max_frames` diverging frames are listed, with a count of the remainder; the full set
    is always on ``result.decoder.frame_diffs``.
    """
    out = [f"{r.phase.upper()}  {r.impl}  {r.vector}"]
    if not r.ran:
        out.append(f"  adapter did not produce output (exit {r.returncode})")
        if r.stderr.strip():
            out.append(f"  stderr: {r.stderr.strip().splitlines()[0]}")
        return out
    bd = r.byte_diff
    if not bd.expected_size:
        out.append(f"  expected empty output, got {bd.actual_size} B")
        return out
    delta = f"  ({bd.size_delta:+d})" if bd.size_delta else ""
    out.append(f"  expected {bd.expected_size} B, got {bd.actual_size} B{delta}")
    if r.identical:
        out.append("  identical to the reference output")
        return out

    d = r.decoder
    if d is None:
        out.append(_byte_line(bd))
        out.append("  no packet attribution: encoder output is unframed")
        return out
    if d.f_differs:
        out.append(f"  large_f trailer  {d.expected_f} -> {d.actual_f}  MISMATCH")
        out.append("  frame boundaries unknown, falling back to bytes")
        out.append(_byte_line(bd))
        return out
    out.append(f"  large_f trailer  {d.expected_f} -> {d.actual_f}  ok")
    if d.frame_count_differs:
        out.append(
            f"  frame count  {d.frames_total} -> {d.actual_frames}  MISMATCH"
            "   (one output frame per input element is required)"
        )
    for fd in d.frame_diffs[:max_frames]:
        if fd.status_differs:
            out.append(
                f"  frame {fd.index}  status  {_fmt_status(fd.expected_status)} -> "
                f"{_fmt_status(fd.actual_status)}   {status_gloss(fd.expected_status, fd.actual_status)}"
            )
        elif fd.first_diff_bit is None:
            out.append(f"  frame {fd.index}  payload differs (length only)")
        else:
            out.append(
                f"  frame {fd.index}  payload bits {fd.first_diff_bit}..{fd.last_diff_bit} differ"
            )
    if len(d.frame_diffs) > max_frames:
        out.append(f"  ... {len(d.frame_diffs) - max_frames} more diverging frames")
    out.append(f"  {d.frames_matching} of {d.frames_total} frames match")
    return out


def _byte_line(bd: ByteDiff) -> str:
    if bd.first_diff_offset is None:
        return "  no differing byte in the common prefix (one output is truncated)"
    return (
        f"  first differing byte  {bd.first_diff_offset} of {bd.expected_size}"
        f"   expected 0x{bd.expected_byte:02x}, got 0x{bd.actual_byte:02x}"
    )


def build_result(
    *, impl: str, vector: str, phase: str, ran: bool, returncode: int, stderr: str,
    expected: bytes, actual: bytes,
) -> ExplainResult:
    """Assemble an :class:`ExplainResult` from ground truth and produced bytes.

    The decoder diff runs only when the adapter actually produced output; otherwise there is
    nothing to compare and the not-a-diff outcome is the finding.
    """
    return ExplainResult(
        impl=impl, vector=vector, phase=phase, ran=ran, returncode=returncode, stderr=stderr,
        expected=expected, actual=actual,
        byte_diff=diff_bytes(expected, actual),
        decoder=diff_decoder(expected, actual) if phase == "decoder" and ran else None,
    )


# --------------------------------------------------------------------------- vector resolution


@dataclass(frozen=True)
class ResolvedVector:
    """A vector name mapped onto its suite paths."""

    name: str            # output name as ConformanceResult.failures records it
    stem: str            # e.g. decoder_sequence_08724
    phase: str           # "encoder" | "decoder"
    verb: str            # the adapter contract verb that runs it
    input_path: Path
    expected_path: Path


# phase -> (input subdir, input suffix, output subdir, output suffix, contract verb)
_PHASE_PATHS = {
    "encoder": ("encoder_input", ".raw+config", "encoder_output", ".124",
                "conformance-compress"),
    "decoder": ("decoder_input", ".124+config", "decoder_output", ".raw+large_f",
                "conformance-decompress"),
}


def resolve_vector(data_dir: Path, name: str) -> ResolvedVector:
    """Map a vector name onto its suite input and expected-output paths.

    Accepts the output name as recorded in ``ConformanceResult.failures``
    (``decoder_sequence_08724.raw+large_f``), the input name, or the bare stem. Raises
    ``LookupError`` when the phase cannot be told from the name or the input file is absent.
    """
    raw = name.strip()
    phase = next((ph for ph in _PHASE_PATHS if raw.startswith(f"{ph}_")), None)
    if phase is None:
        raise LookupError(
            f"cannot tell the phase of {name!r}: a vector name must start with 'encoder_' or "
            "'decoder_' (for example decoder_sequence_08724)"
        )
    in_subdir, in_suffix, out_subdir, out_suffix, verb = _PHASE_PATHS[phase]
    stem = raw
    for suffix in (out_suffix, in_suffix):     # tolerate either extension, or none
        if stem.endswith(suffix):
            stem = stem[: -len(suffix)]
            break
    input_path = data_dir / in_subdir / f"{stem}{in_suffix}"
    if not input_path.exists():
        # Deliberately no directory scan for near-misses: these dirs hold up to 16,965 files and
        # listing them on a network/WSL mount takes minutes. Name the exact path instead.
        raise LookupError(f"no such vector: {input_path} does not exist")
    return ResolvedVector(
        name=f"{stem}{out_suffix}", stem=stem, phase=phase, verb=verb,
        input_path=input_path, expected_path=data_dir / out_subdir / f"{stem}{out_suffix}",
    )
