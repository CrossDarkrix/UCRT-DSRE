import os
import subprocess
import shutil
import time
import threading

home_gradle = os.path.join(os.getenv("HOME"), ".gradle")
src = os.path.join(os.getcwd(), "apkSrc")
ver = "0.6.1"
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
        f.write("sdk.dir={}/android-sdk\nndk.dir={}/android-ndk".format(os.getenv("HOME"), os.getenv("HOME"))

def runGradle():
    return subprocess.run("gradle assembleRelease --no-daemon", shell=True, stdout=subprocess.DEVNULL)

def startComplete():
    while True:
        if os.path.exists(firstOutput):
            shutil.copy(firstOutput, outAPK)
            print("Work Complete!\noutput={}".format(outAPK))
            subprocess.run("termux-open {}".format(outAPK), shell=True)
            break
        else:
            time.sleep(0.89)

def main():
    setupGradle()
    setupLocalProperty()
    threading.Thread(target=startComplete, daemon=True).start()
    runGradle()

if __name__ == '__main__':
    main()

