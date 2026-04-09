# Cory Runbook

## Source of truth

- Local project: `/home/pooppoop/ndk-samples/Cory`
- Remote x86 host: `cory@3.120.153.36`
- Remote project: `/home/cory/ndk-samples/Cory`
- Remote Node source: `/home/cory/node`
- Remote emulator: `emulator-5554`
- Asset map: `/home/pooppoop/ndk-samples/Cory/ASSET-MAP.md`

## Node build

Start or restart:

```bash
ssh cory@3.120.153.36 /home/cory/start-node-cmake-android.sh
```

Watch:

```bash
ssh cory@3.120.153.36 'tail -f /home/cory/node-cmake-android-run.log'
```

Healthy:

- log keeps moving
- host-tools compile advances
- later CMake/Ninja starts

Artifacts to expect:

- `/home/cory/node-native-tools/out/Release/node`
- `/home/cory/node-native-tools/out/Release/node_mksnapshot`
- `/home/cory/node-android-cmake-build/node`

## App build

```bash
cd /home/pooppoop/ndk-samples/Cory
./gradlew :app:assembleDebug --console=plain
```

Remote:

```bash
ssh cory@3.120.153.36 'cd /home/cory/ndk-samples/Cory && ./gradlew :app:assembleDebug --console=plain'
```

## Emulator

Check:

```bash
ssh cory@3.120.153.36 '/home/cory/Android/Sdk/platform-tools/adb devices -l'
```

Boot if needed:

```bash
ssh cory@3.120.153.36 '/home/cory/emulator_cli.sh boot'
```

## AWS Device Farm

Known path:

- Windows AWS CLI:
  - `/mnt/c/Program Files/Amazon/AWSCLIV2/aws.exe`

Known auth command when session is expired:

```bash
TERM=dumb '/mnt/c/Program Files/Amazon/AWSCLIV2/aws.exe' login --remote --no-cli-pager
```

Known usage shape:

```bash
'/mnt/c/Program Files/Amazon/AWSCLIV2/aws.exe' devicefarm list-projects
'/mnt/c/Program Files/Amazon/AWSCLIV2/aws.exe' devicefarm list-device-pools --arn <project-arn>
'/mnt/c/Program Files/Amazon/AWSCLIV2/aws.exe' devicefarm create-upload --project-arn <project-arn> --name app-debug.apk --type ANDROID_APP
'/mnt/c/Program Files/Amazon/AWSCLIV2/aws.exe' devicefarm schedule-run --project-arn <project-arn> --app-arn <upload-arn> --device-pool-arn <pool-arn> --name cory-debug
```

Remote access / manual session state was used earlier for real-device proof of:

- Python
- git
- git worktrees

Current missing proof on that path:

- JS via `node`

## JS proof loop

1. Build Node
2. Build APK
3. Install APK on a real arm64 target
4. Create `hello.js`
5. Run `node hello.js`
6. Verify terminal output
7. Expand test to file I/O, JSON, import

## If it fails

- host-tools fail: inspect `/home/cory/node-native-tools-build.log`
- analysis fails: inspect `/home/cory/node-android-cmake-analyse.log`
- CMake gen fails: inspect `/home/cory/node-android-cmake-generate.log`
- app runs but JS fails: inspect packaging, sandbox `bin`, and `cory_process.py`

## Avoid repeating

- do not rely on memory of remote state
- do not mix Android and host outputs in one `out/Release`
- do not use blind large-file copy when source build is available
