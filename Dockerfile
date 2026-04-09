FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_NDK_VERSION=27.2.12479018
ENV ANDROID_API_LEVEL=24
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH=/root/.cargo/bin:/root/.local/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    build-essential \
    ca-certificates \
    clang \
    cmake \
    curl \
    file \
    git \
    jq \
    make \
    ninja-build \
    openjdk-17-jdk \
    patch \
    pkg-config \
    python3 \
    python3-pip \
    rsync \
    unzip \
    wget \
    xz-utils \
    zip \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m pip install --break-system-packages uv
RUN curl https://sh.rustup.rs -sSf | bash -s -- -y --profile minimal --default-toolchain stable
RUN rustup target add aarch64-linux-android
RUN cargo install cargo-ndk

RUN mkdir -p /opt/android-sdk/cmdline-tools \
    && curl --fail --location --retry 5 --retry-delay 5 \
      -o /tmp/cmdline-tools.zip \
      https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip \
    && mkdir -p /tmp/cmdline-tools \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
    && mkdir -p /opt/android-sdk/cmdline-tools/latest \
    && mv /tmp/cmdline-tools/cmdline-tools/* /opt/android-sdk/cmdline-tools/latest/ \
    && rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip

RUN yes | sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" --licenses
RUN sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" \
    "platform-tools" \
    "platforms;android-${ANDROID_API_LEVEL}" \
    "build-tools;36.0.0" \
    "cmake;3.22.1" \
    "ndk;${ANDROID_NDK_VERSION}"

COPY docker/android-build/entrypoint.sh /usr/local/bin/cory-build
RUN chmod +x /usr/local/bin/cory-build

WORKDIR /workspace/Cory

ENTRYPOINT ["/usr/local/bin/cory-build"]
CMD []
