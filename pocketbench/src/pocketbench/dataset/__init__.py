"""Dataset discovery: a committed `datasets/<name>/` folder, parallel to `adapters/`.

A per-`kind` loader maps the on-disk structure into a uniform descriptor: `config.Dataset` for
`reference`, `config.ConformanceSuite` for `conformance`.
"""
