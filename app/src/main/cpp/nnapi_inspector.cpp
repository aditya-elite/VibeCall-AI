#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <cctype>

#define LOG_TAG "NnapiInspector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Function pointer typedefs matching Android NDK NeuralNetworks.h
typedef int32_t (*fn_ANeuralNetworks_getDeviceCount)(uint32_t* numDevices);
typedef int32_t (*fn_ANeuralNetworks_getDevice)(uint32_t devIndex, void** device);
typedef int32_t (*fn_ANeuralNetworksDevice_getName)(const void* device, const char** name);
typedef int32_t (*fn_ANeuralNetworksDevice_getType)(const void* device, int32_t* type);
typedef int32_t (*fn_ANeuralNetworksDevice_getVersion)(const void* device, const char** version);
typedef int32_t (*fn_ANeuralNetworksDevice_getFeatureLevel)(const void* device, int64_t* featureLevel);

static std::string toLower(const std::string& s) {
    std::string out = s;
    for (char& c : out) {
        c = (char)std::tolower((unsigned char)c);
    }
    return out;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_vibecall_sensortest_NnapiDeviceInspector_nativeGetDevices(JNIEnv *env, jclass clazz) {
    jclass infoClass = env->FindClass("com/vibecall/sensortest/NnapiDeviceInfo");
    if (!infoClass) {
        LOGE("Failed to find class com/vibecall/sensortest/NnapiDeviceInfo");
        return nullptr;
    }

    jmethodID ctor = env->GetMethodID(infoClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JZ)V");
    if (!ctor) {
        LOGE("Failed to find constructor for NnapiDeviceInfo");
        return nullptr;
    }

    void* libnn = dlopen("libneuralnetworks.so", RTLD_NOW | RTLD_LOCAL);
    if (!libnn) {
        LOGW("dlopen(libneuralnetworks.so) failed: %s", dlerror());
        return env->NewObjectArray(0, infoClass, nullptr);
    }

    auto pGetDeviceCount = (fn_ANeuralNetworks_getDeviceCount)dlsym(libnn, "ANeuralNetworks_getDeviceCount");
    auto pGetDevice = (fn_ANeuralNetworks_getDevice)dlsym(libnn, "ANeuralNetworks_getDevice");
    auto pGetName = (fn_ANeuralNetworksDevice_getName)dlsym(libnn, "ANeuralNetworksDevice_getName");
    auto pGetType = (fn_ANeuralNetworksDevice_getType)dlsym(libnn, "ANeuralNetworksDevice_getType");
    auto pGetVersion = (fn_ANeuralNetworksDevice_getVersion)dlsym(libnn, "ANeuralNetworksDevice_getVersion");
    auto pGetFeatureLevel = (fn_ANeuralNetworksDevice_getFeatureLevel)dlsym(libnn, "ANeuralNetworksDevice_getFeatureLevel");

    if (!pGetDeviceCount || !pGetDevice || !pGetName || !pGetType) {
        LOGW("One or more ANeuralNetworksDevice_* symbols could not be resolved in libneuralnetworks.so");
        dlclose(libnn);
        return env->NewObjectArray(0, infoClass, nullptr);
    }

    uint32_t numDevices = 0;
    int32_t res = pGetDeviceCount(&numDevices);
    if (res != 0) {
        LOGW("ANeuralNetworks_getDeviceCount returned error code %d", res);
        dlclose(libnn);
        return env->NewObjectArray(0, infoClass, nullptr);
    }

    LOGI("Discovered %u NNAPI devices", numDevices);
    jobjectArray resultArray = env->NewObjectArray((jsize)numDevices, infoClass, nullptr);

    for (uint32_t i = 0; i < numDevices; i++) {
        void* device = nullptr;
        if (pGetDevice(i, &device) != 0 || !device) {
            continue;
        }

        const char* nameStr = "unknown";
        pGetName(device, &nameStr);
        std::string name = nameStr ? nameStr : "unknown";

        int32_t typeCode = 0;
        pGetType(device, &typeCode);

        const char* verStr = "unknown";
        if (pGetVersion) {
            pGetVersion(device, &verStr);
        }
        std::string version = verStr ? verStr : "unknown";

        int64_t featureLevel = 0;
        if (pGetFeatureLevel) {
            pGetFeatureLevel(device, &featureLevel);
        }

        std::string lowerName = toLower(name);
        std::string typeLabel = "OTHER";
        bool isCpu = false;

        // Strict mapping:
        // ANEURALNETWORKS_DEVICE_CPU = 2
        // ANEURALNETWORKS_DEVICE_GPU = 3
        // ANEURALNETWORKS_DEVICE_ACCELERATOR = 4
        // Treat nnapi-reference as CPU
        if (lowerName == "nnapi-reference" || lowerName.find("reference") != std::string::npos) {
            typeLabel = "CPU";
            isCpu = true;
        } else if (typeCode == 2) { // CPU
            typeLabel = "CPU";
            isCpu = true;
        } else if (typeCode == 3) { // GPU
            typeLabel = "GPU";
            isCpu = false;
        } else if (typeCode == 4) { // ACCELERATOR
            if (lowerName.find("dsp") != std::string::npos && lowerName.find("npu") == std::string::npos) {
                typeLabel = "DSP";
            } else if (lowerName.find("npu") != std::string::npos) {
                typeLabel = "NPU";
            } else {
                typeLabel = "ACCELERATOR";
            }
            isCpu = false;
        } else {
            typeLabel = "OTHER";
            isCpu = false;
        }

        LOGI("Device %u: name='%s', type=%d ('%s'), version='%s', featureLevel=%lld, isCpu=%d",
             i, name.c_str(), typeCode, typeLabel.c_str(), version.c_str(), (long long)featureLevel, isCpu ? 1 : 0);

        jstring jName = env->NewStringUTF(name.c_str());
        jstring jType = env->NewStringUTF(typeLabel.c_str());
        jstring jVer = env->NewStringUTF(version.c_str());

        jobject deviceObj = env->NewObject(infoClass, ctor, jName, jType, jVer, (jlong)featureLevel, (jboolean)isCpu);

        env->SetObjectArrayElement(resultArray, (jsize)i, deviceObj);

        env->DeleteLocalRef(jName);
        env->DeleteLocalRef(jType);
        env->DeleteLocalRef(jVer);
        env->DeleteLocalRef(deviceObj);
    }

    dlclose(libnn);
    return resultArray;
}
