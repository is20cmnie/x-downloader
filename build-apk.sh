#!/bin/bash
# 不用 Gradle 的手工打包（这个环境拦 127.0.0.1，Gradle 守护进程起不来）：
# aapt2 编资源 -> javac 编代码 -> d8 转 dex -> zipalign -> apksigner 签名
set -e
cd "$(dirname "$0")"
BT=~/tools/sdk/build-tools/36.0.0
PLAT=~/tools/sdk/platforms/android-36/android.jar
export JAVA_HOME=~/tools/jdk17
PATH=$JAVA_HOME/bin:$PATH
rm -rf build
mkdir -p build/gen build/classes build/dex

echo "[1/6] 编资源"
$BT/aapt2 compile --dir app/src/main/res -o build/res.zip

echo "[2/6] 链接资源、生成 R.java"
$BT/aapt2 link -o build/base.apk -I "$PLAT" \
  --manifest app/src/main/AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version 28 --target-sdk-version 36 \
  --version-code 9 --version-name 1.0.8 \
  build/res.zip

echo "[3/6] 编 Java 代码"
find app/src/main/java build/gen -name '*.java' > build/sources.txt
javac -source 17 -target 17 -encoding UTF-8 -cp "$PLAT" -d build/classes @build/sources.txt

echo "[4/6] 转 dex"
$BT/d8 --release --min-api 28 --lib "$PLAT" --output build/dex $(find build/classes -name '*.class')

echo "[5/6] 合并、对齐"
cp build/base.apk build/app.apk
# 注意：classes.dex 必须在 APK 根目录，放进子目录手机会报 code is missing
cd build/dex && zip -q ../app.apk classes.dex && cd ../..
$BT/zipalign -f 4 build/app.apk build/aligned.apk

echo "[6/6] 签名"
# 签名密钥和密码不随仓库发布：需自备 xdownloader.keystore，密码从环境变量或本地文件读取
KS_FILE="xdownloader.keystore"
KS_PASS="${KEYSTORE_PASSWORD:-${KS_PASS:-}}"
if [ -z "$KS_PASS" ] && [ -f local.properties ]; then
  KS_PASS=$(grep -E '^(keystore\.password|ks\.pass)=' local.properties | head -1 | cut -d= -f2- | tr -d '\r')
fi
if [ ! -f "$KS_FILE" ]; then
  echo "缺少签名文件 $KS_FILE，请先用 keytool 生成你自己的：" >&2
  echo "  keytool -genkeypair -v -keystore $KS_FILE -alias xdownloader -keyalg RSA -keysize 2048 -validity 10000" >&2
  exit 1
fi
if [ -z "$KS_PASS" ]; then
  echo "没有读到 keystore 密码，请二选一：" >&2
  echo "  1) 环境变量：KEYSTORE_PASSWORD=你的密码 bash build-apk.sh" >&2
  echo "  2) 在脚本同目录的 local.properties 里写一行：keystore.password=你的密码" >&2
  exit 1
fi
$BT/apksigner sign --ks "$KS_FILE" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out build/signed.apk build/aligned.apk
$BT/apksigner verify --print-certs build/signed.apk | head -3
$BT/aapt2 dump badging build/signed.apk | head -4
# 这台机器是 C 语言环境，Java 写中文文件名会变问号，所以最后用 python 改名
python3 -c "
import shutil, os
shutil.copy('build/signed.apk', '推特下载器-v1.0.8.apk')
for f in ('推特下载器-v1.0.8.apk.idsig', 'build/signed.apk.idsig'):
    if os.path.exists(f): os.remove(f)
"
unzip -l build/signed.apk | grep -E "classes.dex|resources.arsc|AndroidManifest.xml"
ls -lh "推特下载器-v1.0.8.apk"
