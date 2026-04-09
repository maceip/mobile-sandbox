#!/usr/bin/env bash
# Run on EC2 (or anywhere with AWS creds + Cory checkout).
# Packs the repo, uploads to the manual CodeBuild source bucket, starts the build.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REGION="${AWS_REGION:-eu-central-1}"
ZIP="${TMPDIR:-/tmp}/cory-source-$$.zip"
cleanup() { rm -f "$ZIP"; }
trap cleanup EXIT

: "${CORY_CODEBUILD_SOURCE_BUCKET:?Export CORY_CODEBUILD_SOURCE_BUCKET (from stack output SourceBucketName)}"
: "${CORY_CODEBUILD_PROJECT:?Export CORY_CODEBUILD_PROJECT (from stack output CodeBuildProjectName)}"
KEY="${CORY_SOURCE_OBJECT_KEY:-cory-source.zip}"

cd "$ROOT"
if git rev-parse --git-dir >/dev/null 2>&1 && [[ -z "${CORY_ZIP_WORKING_TREE:-}" ]]; then
  git archive --format=zip -o "$ZIP" HEAD
elif [[ -n "${CORY_USE_RSYNC_PACK:-}" ]]; then
  export CORY_PACK_WITH_SUDO="${CORY_ZIP_WITH_SUDO:-}"
  ZIP="$(bash "$ROOT/scripts/pack_codebuild_source.sh")"
else
  if [[ -n "${CORY_ZIP_WITH_SUDO:-}" ]]; then
    sudo rm -f "$ZIP"
    sudo zip -rq "$ZIP" . \
      -x '*/build/*' \
      -x '*/.gradle/*' \
      -x '*/third_party/Python-*/.git/*' \
      -x 'third_party/node24-android/*' \
      -x 'third_party/ripgrep/target/*' \
      -x 'report_assets/*' \
      -x 'reconstruction/*' \
      -x '*.png' \
      -x '*.md' \
      -x '*.iml' \
      -x '.idea/*'
    sudo chown "$(id -u):$(id -g)" "$ZIP"
  else
    zip -rq "$ZIP" . \
      -x '*/build/*' \
      -x '*/.gradle/*' \
      -x '*/third_party/Python-*/.git/*' \
      -x 'third_party/node24-android/*' \
      -x 'third_party/ripgrep/target/*' \
      -x 'report_assets/*' \
      -x 'reconstruction/*' \
      -x '*.png' \
      -x '*.md' \
      -x '*.iml' \
      -x '.idea/*'
  fi
fi

aws s3 cp "$ZIP" "s3://${CORY_CODEBUILD_SOURCE_BUCKET}/${KEY}" --region "$REGION"
VID="$(aws s3api list-object-versions \
  --bucket "$CORY_CODEBUILD_SOURCE_BUCKET" \
  --prefix "$KEY" \
  --region "$REGION" \
  --query 'Versions[0].VersionId' \
  --output text)"

if [[ -n "$VID" && "$VID" != "None" ]]; then
  aws codebuild start-build --region "$REGION" \
    --project-name "$CORY_CODEBUILD_PROJECT" \
    --source-version "$VID"
else
  aws codebuild start-build --region "$REGION" \
    --project-name "$CORY_CODEBUILD_PROJECT"
fi

echo "Started CodeBuild project $CORY_CODEBUILD_PROJECT (source s3://${CORY_CODEBUILD_SOURCE_BUCKET}/${KEY})"
