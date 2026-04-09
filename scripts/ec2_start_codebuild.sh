#!/usr/bin/env bash
# Run ON the EC2 host after stack deploy: pack trimmed tree, upload, start CodeBuild.
set -euo pipefail
export AWS_REGION="${AWS_REGION:-eu-central-1}"
cd /home/cory/Cory
sudo rm -rf /tmp/cory-cb-stage.* 2>/dev/null || true
OUT=$(aws cloudformation describe-stacks --stack-name cory-android-ci-manual --region "$AWS_REGION" --output json)
SRC_BUCKET=$(python3 -c "import json,sys; o=json.loads(sys.argv[1])['Stacks'][0]['Outputs']; print([x['OutputValue'] for x in o if x['OutputKey']=='SourceBucketName'][0])" "$OUT")
PROJ=$(python3 -c "import json,sys; o=json.loads(sys.argv[1])['Stacks'][0]['Outputs']; print([x['OutputValue'] for x in o if x['OutputKey']=='CodeBuildProjectName'][0])" "$OUT")
export CORY_CODEBUILD_SOURCE_BUCKET="$SRC_BUCKET"
export CORY_CODEBUILD_PROJECT="$PROJ"

unset CORY_ZIP_WORKING_TREE CORY_ZIP_WITH_SUDO CORY_USE_RSYNC_PACK
if git rev-parse --git-dir >/dev/null 2>&1; then
  git add infrastructure/cloudformation/cory-android-ci-manual-s3.yaml \
    codebuild/android-full scripts/pack_codebuild_source.sh \
    scripts/trigger_cory_codebuild.sh scripts/ec2_start_codebuild.sh 2>/dev/null || true
  if git config user.email >/dev/null 2>&1 && ! git diff --cached --quiet 2>/dev/null; then
    git commit -m "chore(ci): sync CodeBuild manual wiring" || true
  fi
else
  # Huge non-git trees on EC2: rsync pack + sudo for root-owned files.
  export CORY_USE_RSYNC_PACK=1
  export CORY_ZIP_WITH_SUDO=1
fi

exec bash scripts/trigger_cory_codebuild.sh
