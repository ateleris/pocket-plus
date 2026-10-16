FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

# clang for native/build.sh (the GenC output uses C99 VLAs — same reason the
# Windows build uses ClangCL); Python 3.12 (Ubuntu 24.04's python3) matches CI;
# curl/unzip for install.sh.
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get install -y --no-install-recommends \
        clang clangd build-essential \
        python3 python3-venv python3-pip \
        curl ca-certificates unzip zip \
    && rm -rf /var/lib/apt/lists/* \
    # native/build.sh invokes `cc`; point it at clang instead of gcc.
    && update-alternatives --install /usr/bin/cc cc /usr/bin/clang 100

# Scala toolchain via Coursier: sbt, scala and scalac launchers on PATH.
# (Stainless bundles its own compiler; these are for ad-hoc compiles, the REPL
# and Metals.) The launchers for archive-distributed apps (scala, sbt) exec
# straight out of the coursier caches that were active at install time — jars
# under COURSIER_CACHE, extracted archives under COURSIER_ARCHIVE_CACHE — so
# install into world-readable shared caches; with the defaults they land under
# /root/.cache, unreadable at runtime. The overrides are scoped to this RUN:
# further fetches at runtime (e.g. scalac pulling compiler jars on first use)
# go to the user's own ~/.cache/coursier.
RUN arch="$(dpkg --print-architecture)" \
    && case "$arch" in \
         amd64) cs=cs-x86_64-pc-linux ;; \
         arm64) cs=cs-aarch64-pc-linux ;; \
         *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
       esac \
    && curl -fL "https://github.com/coursier/launchers/raw/master/${cs}.gz" | gzip -d > /usr/local/bin/cs \
    && chmod +x /usr/local/bin/cs \
    && COURSIER_CACHE=/opt/coursier/cache COURSIER_ARCHIVE_CACHE=/opt/coursier/arc \
       cs install --install-dir /usr/local/bin sbt scala scalac \
    && chmod -R a+rX /opt/coursier
