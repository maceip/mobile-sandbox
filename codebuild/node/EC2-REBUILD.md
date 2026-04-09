# EC2 Node24 Rebuild

This is the coalesced state for the verified Node.js 24 CMake/Android rebuild
path on:

- `cory@ec2-3-120-153-36.eu-central-1.compute.amazonaws.com`

## Verified EC2 directories

- Generator repo:
  - `/home/cory/node-cmake-build-clean`
  - commit `94e33bbdffa97dba43556d553bff943fc20fe38e`
- Full Node source tree:
  - `/home/cory/node-clean`
  - commit `d89bb1b482fa09245c4f2cbb3b5b6a70bea6deaf`
- Host-tools / Linux CMake build:
  - `/home/cory/build-clean`
- Android build output:
  - `/home/cory/your_lazy_ass/node24-android-build`
- Reproducible Android rebuild output:
  - `/home/cory/node24-android-rebuild`
- Per-run logs and manifests:
  - `/home/cory/node24-android-rebuild-runs`

## Verified artifacts on EC2

- Host tools:
  - `/home/cory/build-clean/node`
  - `/home/cory/build-clean/node_mksnapshot`
  - `/home/cory/build-clean/tools/icu/genccode`
  - `/home/cory/build-clean/tools/icu/icupkg`
  - `/home/cory/build-clean/liblibnode.so`
- Android outputs:
  - `/home/cory/your_lazy_ass/node24-android-build/node`
  - `/home/cory/your_lazy_ass/node24-android-build/node_mksnapshot`
  - `/home/cory/your_lazy_ass/node24-android-build/liblibnode.a`

## Important caveat

The existing Android build cache under:

- `/home/cory/your_lazy_ass/node24-android-build`

still records the original build-host paths in `CMakeCache.txt`:

- `CMAKE_HOME_DIRECTORY=/home/devuser/node24`
- `NCG_HOST_TOOLS_DIR=/home/devuser/node24-build`

That means the output tree itself is real, but it is not the canonical rebuild
root. The canonical rebuild inputs on EC2 are:

- `/home/cory/node-clean`
- `/home/cory/build-clean`
- `/home/cory/node-cmake-build-clean`

## Known-good Linux CMake repro

EC2 already has a repro script:

- `/home/cory/run-node-cmake-repro.sh`

and log:

- `/home/cory/node-cmake-repro.log`

That repro proves:

1. `node-clean` + `node-cmake-build-clean` can generate CMake
2. `build-clean`-style host toolchain output is reproducible on EC2

## Android rebuild script

Use the repo script copied onto EC2:

```bash
/home/cory/Cory/codebuild/node/ec2-rebuild-node24-android.sh
```

The script intentionally:

- builds from `/home/cory/node-clean`
- uses `/home/cory/node-cmake-build-clean`
- uses `/home/cory/build-clean` as `NCG_HOST_TOOLS_DIR`
- creates a fresh detached worktree at `/tmp/node24-android-src`
- rebuilds into `/home/cory/node24-android-rebuild`
- writes a per-run log and manifest under `/home/cory/node24-android-rebuild-runs/<RUN_ID>/`

## Why this script differs from the naive recipe

The working EC2 rebuild adds a few safeguards that were necessary to avoid the
previous failures:

1. It uses a fresh disposable worktree each run, so generated `CMakeLists.txt`
   files are never regenerated on top of an already-mutated source tree.
2. It installs the custom generator as `tools/gyp/pylib/gyp/generator/ncg_analyse.py`
   and invokes `gyp_node.py -f ncg_analyse`, which avoids the absolute-path
   generator import failure.
3. It patches the copied generator to define the `python` variable expected by
   some gyp files.
4. It captures a persistent build log plus a manifest with commit SHAs and
   output checksums.

## Repo-side script

The repo script:

- `/home/pooppoop/ndk-samples/Cory/codebuild/node/build-node-android.sh`

now carries default values for:

- Node repo / ref
- `node-cmake-build` repo / ref
- Android command-line tools URL
- NDK version
- Android API level
- Android target arch / ABI

That makes it runnable outside AWS CodeBuild without failing on unbound env
variables.
