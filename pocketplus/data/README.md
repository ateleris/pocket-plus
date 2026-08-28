# Private test vectors

Drop additional POCKET+ test vectors here as `*.vectors` files. **Contents of this directory are
git-ignored** (private) — only this `README.md` and `.gitkeep` are tracked.

`python/tests/test_data_vectors.py` automatically discovers `data/*.vectors` and round-trips each
through the C codec at several robustness levels. If no files are present, those tests are skipped.

## File format (`*.vectors`)

Plain UTF-8 text:

- The first non-blank, non-comment line is `F` — the bit width of every vector.
- Each subsequent non-blank, non-comment line is exactly `F` characters of `0`/`1`, MSB first
  (index 0 = first transmitted bit), one input vector per line.
- Blank lines and lines starting with `#` are ignored.

### Example (`example.vectors`, F = 8)

```
# 8-bit housekeeping word, slowly changing
8
00000000
00000001
00000001
00000011
```
