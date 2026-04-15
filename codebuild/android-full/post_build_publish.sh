#!/usr/bin/env bash
# Collect APK + native artifacts, sync to a public-read S3 prefix, optionally run Device Farm.
set -euo pipefail

ROOT="${CORY_ROOT:-${CODEBUILD_SRC_DIR:-.}}"
ROOT="$(cd "$ROOT" && pwd)"
cd "$ROOT"

DF_REGION="${AWS_DEFAULT_REGION_DEVICE_FARM:-us-west-2}"
STAGE="${ROOT}/cb-out"
PREFIX="${CORY_ARTIFACT_PREFIX:-builds/${CODEBUILD_BUILD_ID:-manual}/}"

require_bucket() {
  if [ -z "${CORY_PUBLIC_ARTIFACTS_BUCKET:-}" ]; then
    echo "post_build_publish: CORY_PUBLIC_ARTIFACTS_BUCKET is not set; skipping S3 and Device Farm" >&2
    exit 0
  fi
}

sync_public() {
  mkdir -p "$STAGE"/{apk,native,reports,meta}
  local apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  if [ -f "$apk" ]; then
    cp -a "$apk" "$STAGE/apk/"
  fi
  if [ -f "$ROOT/build-all-assets-manifest.txt" ]; then
    cp -a "$ROOT/build-all-assets-manifest.txt" "$STAGE/reports/"
  fi
  if [ -f "$ROOT/build-all-assets-report.txt" ]; then
    cp -a "$ROOT/build-all-assets-report.txt" "$STAGE/reports/"
  fi
  if [ -f "$ROOT/build-all-assets-last-run.txt" ]; then
    cp -a "$ROOT/build-all-assets-last-run.txt" "$STAGE/meta/"
  fi

  # Native artifacts (best-effort globs)
  shopt -s nullglob
  for f in "$ROOT"/third_party/node24-android/lib/arm64-v8a/*.a; do
    cp -a "$f" "$STAGE/native/"
  done
  for f in "$ROOT"/third_party/node24-android/bin/arm64-v8a/node; do
    [ -f "$f" ] && cp -a "$f" "$STAGE/native/"
  done
  if [ -f "$ROOT/third_party/ripgrep/target/aarch64-linux-android/release/rg" ]; then
    cp -a "$ROOT/third_party/ripgrep/target/aarch64-linux-android/release/rg" "$STAGE/native/"
  fi
  if [ -f "$ROOT/rust/cory_rust/target/aarch64-linux-android/release/libcory_rust.a" ]; then
    cp -a "$ROOT/rust/cory_rust/target/aarch64-linux-android/release/libcory_rust.a" "$STAGE/native/"
  fi
  for f in "$ROOT"/rust/cory_rust/target/aarch64-linux-android/release/deps/*.so; do
    cp -a "$f" "$STAGE/native/" || true
  done
  shopt -u nullglob

  # Bucket policy should allow public GetObject; omit ACL for ObjectOwnership / enforced buckets.
  aws s3 sync "$STAGE" "s3://${CORY_PUBLIC_ARTIFACTS_BUCKET%/}/${PREFIX}" \
    --region "${AWS_REGION:-eu-central-1}" \
    --cache-control "public, max-age=300"

  echo "post_build_publish: artifacts at s3://${CORY_PUBLIC_ARTIFACTS_BUCKET%/}/${PREFIX}"
}

sync_prebuilt_latest() {
  local prebuilt_stage="$STAGE/prebuilt"
  local prebuilt_prefix="${CORY_PREBUILT_PREFIX:-builds/prebuilt/latest/}"
  mkdir -p "$prebuilt_stage"

  if [ -d "$ROOT/third_party/node24-android" ]; then
    tar -C "$ROOT" -czf "$prebuilt_stage/node24-android.tgz" third_party/node24-android
  fi
  if [ -d "$ROOT/third_party/python-android/prefix" ]; then
    tar -C "$ROOT" -czf "$prebuilt_stage/python-android-prefix.tgz" third_party/python-android/prefix
  fi
  if [ -f "$ROOT/third_party/ndk-busybox-ref/libs/arm64-v8a/busybox" ]; then
    tar -C "$ROOT" -czf "$prebuilt_stage/busybox-arm64-v8a.tgz" third_party/ndk-busybox-ref/libs/arm64-v8a
  fi
  if [ -f "$ROOT/third_party/ripgrep/target/aarch64-linux-android/release/rg" ]; then
    tar -C "$ROOT" -czf "$prebuilt_stage/ripgrep-arm64-v8a.tgz" third_party/ripgrep/target/aarch64-linux-android/release
  fi
  if [ -d "$ROOT/rust/cory_rust/target/aarch64-linux-android/release" ]; then
    tar -C "$ROOT" -czf "$prebuilt_stage/rust-cory_rust-arm64-release.tgz" rust/cory_rust/target/aarch64-linux-android/release
  fi

  if compgen -G "$prebuilt_stage/*.tgz" > /dev/null; then
    aws s3 sync "$prebuilt_stage" "s3://${CORY_PUBLIC_ARTIFACTS_BUCKET%/}/${prebuilt_prefix}" \
      --region "${AWS_REGION:-eu-central-1}" \
      --cache-control "public, max-age=300"
    echo "post_build_publish: prebuilt bundles at s3://${CORY_PUBLIC_ARTIFACTS_BUCKET%/}/${prebuilt_prefix}"
  else
    echo "post_build_publish: no prebuilt bundles were generated"
  fi
}

device_farm_run() {
  if [ -n "${CORY_SKIP_DEVICE_FARM:-}" ] && [ "$CORY_SKIP_DEVICE_FARM" != "0" ]; then
    echo "post_build_publish: CORY_SKIP_DEVICE_FARM set; skipping Device Farm"
    return 0
  fi
  if [ -z "${DEVICE_FARM_PROJECT_ARN:-}" ]; then
    echo "post_build_publish: DEVICE_FARM_PROJECT_ARN unset; skipping Device Farm"
    return 0
  fi
  local apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  if [ ! -f "$apk" ]; then
    echo "post_build_publish: no APK; skipping Device Farm"
    return 0
  fi

  local pool_arn=""
  if [ -n "${DEVICE_FARM_DEVICE_POOL_ARN:-}" ]; then
    pool_arn="$DEVICE_FARM_DEVICE_POOL_ARN"
    echo "post_build_publish: using DEVICE_FARM_DEVICE_POOL_ARN"
  else
    local device_arn
    if ! device_arn="$(AWS_DEFAULT_REGION_DEVICE_FARM="$DF_REGION" python3 "$ROOT/codebuild/android-full/pick_newest_android_device.py")"; then
      echo "post_build_publish: could not pick a Device Farm device (set DEVICE_FARM_DEVICE_POOL_ARN to use a fixed pool)" >&2
      return 1
    fi
    local rules_file
    rules_file="$(mktemp)"
    python3 -c "import json,sys; a=sys.argv[1]; print(json.dumps([{'attribute':'ARN','operator':'IN','value':json.dumps([a])}]))" \
      "$device_arn" >"$rules_file"

    local pool_name="cory-newest-${CODEBUILD_BUILD_NUMBER:-0}-$(date +%s)"
    pool_arn="$(aws devicefarm create-device-pool \
      --region "$DF_REGION" \
      --project-arn "$DEVICE_FARM_PROJECT_ARN" \
      --name "$pool_name" \
      --rules file://"$rules_file" \
      --query 'devicePool.arn' --output text)"
    rm -f "$rules_file"
  fi

  local upload_name="app-debug-${CODEBUILD_BUILD_ID:-local}.apk"
  local upload_meta
  upload_meta="$(aws devicefarm create-upload \
    --region "$DF_REGION" \
    --project-arn "$DEVICE_FARM_PROJECT_ARN" \
    --name "$upload_name" \
    --type ANDROID_APP \
    --content-type application/vnd.android.package-archive \
    --output json)"

  local upload_arn url
  upload_arn="$(echo "$upload_meta" | python3 -c "import sys,json; print(json.load(sys.stdin)['upload']['arn'])")"
  url="$(echo "$upload_meta" | python3 -c "import sys,json; print(json.load(sys.stdin)['upload']['url'])")"

  curl -fS --retry 3 --retry-delay 2 -T "$apk" "$url"

  while true; do
    status="$(aws devicefarm get-upload --region "$DF_REGION" --arn "$upload_arn" --query 'upload.status' --output text)"
    if [ "$status" = "SUCCEEDED" ]; then
      break
    fi
    if [ "$status" = "FAILED" ]; then
      echo "post_build_publish: Device Farm upload failed" >&2
      if [ -z "${DEVICE_FARM_DEVICE_POOL_ARN:-}" ]; then
        aws devicefarm delete-device-pool --region "$DF_REGION" --arn "$pool_arn" || true
      fi
      return 1
    fi
    sleep 5
  done

  local test_json='{"type":"BUILTIN_FUZZ","parameters":{"eventCount":30,"throttle":15}}'
  aws devicefarm schedule-run \
    --region "$DF_REGION" \
    --project-arn "$DEVICE_FARM_PROJECT_ARN" \
    --app-arn "$upload_arn" \
    --device-pool-arn "$pool_arn" \
    --name "cory-${CODEBUILD_BUILD_ID:-manual}" \
    --test "$test_json" \
    --configuration '{"billingMethod":"METERED"}'

  if [ -z "${DEVICE_FARM_DEVICE_POOL_ARN:-}" ]; then
    aws devicefarm delete-device-pool --region "$DF_REGION" --arn "$pool_arn" || true
    echo "post_build_publish: temporary Device Farm pool removed; run continues asynchronously"
  else
    echo "post_build_publish: Device Farm run scheduled (fixed pool)"
  fi
}

require_bucket
sync_public
sync_prebuilt_latest
device_farm_run
