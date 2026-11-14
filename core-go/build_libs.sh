#!/usr/bin/env bash
set -e

NDK_DIR="/Users/maksim/Library/Android/sdk/ndk/28.2.13676358"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin"
TARGET_DIR="../app/src/main/jniLibs"

mkdir -p "$TARGET_DIR/x86_64"
mkdir -p "$TARGET_DIR/arm64-v8a"
mkdir -p "$TARGET_DIR/armeabi-v7a"

echo "=== Building x86_64 ==="
CGO_ENABLED=1 \
CC="$TOOLCHAIN/x86_64-linux-android26-clang" \
GOOS=android \
GOARCH=amd64 \
go build -ldflags="-checklinkname=0 -s -w" -buildmode=c-shared -o "$TARGET_DIR/x86_64/libwgturn.so" ./bridge

echo "=== Building arm64-v8a ==="
CGO_ENABLED=1 \
CC="$TOOLCHAIN/aarch64-linux-android26-clang" \
GOOS=android \
GOARCH=arm64 \
go build -ldflags="-checklinkname=0 -s -w" -buildmode=c-shared -o "$TARGET_DIR/arm64-v8a/libwgturn.so" ./bridge

echo "=== Building armeabi-v7a ==="
CGO_ENABLED=1 \
CC="$TOOLCHAIN/armv7a-linux-androideabi26-clang" \
GOOS=android \
GOARCH=arm \
GOARM=7 \
go build -ldflags="-checklinkname=0 -s -w" -buildmode=c-shared -o "$TARGET_DIR/armeabi-v7a/libwgturn.so" ./bridge

echo "=== Build finished! ==="
ls -lh "$TARGET_DIR"/*/*.so
