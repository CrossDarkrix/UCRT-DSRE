import os
import subprocess
import shutil
import time
import threading

MOVE_CMAKE = """SDK_CMAKE="$HOME/android-sdk/cmake/3.22.1/bin"
TERMUX_CMAKE="$(command -v cmake)"
TERMUX_NINJA="$(command -v ninja)"

test -x "$TERMUX_CMAKE" || {
    echo "ERROR: Termux cmake not found"
    exit 1
}

test -x "$TERMUX_NINJA" || {
    echo "ERROR: Termux ninja not found"
    exit 1
}

mv "$SDK_CMAKE/cmake" "$SDK_CMAKE/cmake.x86_64.disabled"
mv "$SDK_CMAKE/ninja" "$SDK_CMAKE/ninja.x86_64.disabled"

ln -s  "$TERMUX_CMAKE" "$SDK_CMAKE/cmake"
ln -s "$TERMUX_NINJA" "$SDK_CMAKE/ninja"

chmod 755 "$SDK_CMAKE/cmake" "$SDK_CMAKE/ninja"""

MOVE_CMAKE_command = os.path.join(os.getcwd(), "move_sdk.sh")

with open(MOVE_CMAKE_command, "w", encoding='utf-8') as f1:
    f1.write(MOVE_CMAKE)
try:
    os.chmod(MOVE_CMAKE_command, 0o755)
except:
    pass
try:
    subprocess.run("{}".format(MOVE_CMAKE_command), shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
except:
    pass
try:
    os.remove(MOVE_CMAKE_command)
except:
    pass

home_gradle = os.path.join(os.getenv("HOME"), ".gradle")
src = os.path.join(os.getcwd(), "apkSrc")
ver = "1.9.1"
firstOutput = os.path.join(os.getcwd(), "app", "build", "outputs", "apk", "release", "app-release.apk")
outAPK = os.path.join(src, "UCRT_{}.apk".format(ver))

os.makedirs(src, exist_ok=True)

def setupGradle():
    if not os.path.exists(home_gradle):
        os.makedirs(home_gradle, exist_ok=True)
    with open(os.path.join(home_gradle, "gradle.properties"), "w", encoding='utf-8') as f:
        f.write("android.aapt2FromMavenOverride={}/bin/aapt2".format(os.getenv("PREFIX")))

def setupLocalProperty():
    with open(os.path.join(os.getcwd(), "local.properties"), "w", encoding='utf-8') as f:
        f.write("sdk.dir={}/android-sdk\nndk.dir={}/android-ndk".format(os.getenv("HOME"), os.getenv("HOME")))

def runGradle():
    return subprocess.run("gradle assembleRelease --no-daemon", shell=True, stdout=subprocess.DEVNULL)

def main():
    try:
        os.remove(firstOutput)
    except:
        pass
    setupGradle()
    setupLocalProperty()
    print('Please Wait!\nBuild APK.................')
    runGradle()
    shutil.copy(firstOutput, outAPK)
    print("Work Complete!\noutput={}".format(outAPK))
    subprocess.run("termux-open >/dev/null && termux-open {}".format(outAPK), shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

if __name__ == '__main__':
    main()

