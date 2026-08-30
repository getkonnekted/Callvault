#include <jni.h>
#include <android/log.h>

#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/ioctl.h>

#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <elf.h>

#include <cstring>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <csetjmp>
#include <csignal>
#include <cerrno>

#include <vector>

#define TAG "CV:AudioHandoff"

#define LOGI(...) \
    __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define LOGE(...) \
    __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)


// ============================================================================
// Manual symbol resolution
// ============================================================================

static uintptr_t manualLibBase(const char* nameSuffix) {

    FILE* file = fopen("/proc/self/maps", "r");

    if (!file) {
        return 0;
    }

    char line[512];
    uintptr_t base = 0;

    const size_t suffixLength = strlen(nameSuffix);

    while (fgets(line, sizeof(line), file)) {

        char* path = strchr(line, '/');

        if (!path) {
            continue;
        }

        size_t pathLength = strlen(path);

        if (pathLength > 0 && path[pathLength - 1] == '\n') {
            path[--pathLength] = '\0';
        }

        if (
                pathLength >= suffixLength &&
                strcmp(
                        path + pathLength - suffixLength,
                        nameSuffix
                ) == 0
                ) {

            uintptr_t start =
                    strtoull(line, nullptr, 16);

            if (base == 0 || start < base) {
                base = start;
            }
        }
    }

    fclose(file);

    return base;
}


static uintptr_t manualSymOffset(
        const char* fullPath,
        const char* symbol,
        uintptr_t* minVaddrOut
) {

    int fd = open(fullPath, O_RDONLY);

    if (fd < 0) {
        return 0;
    }

    struct stat st{};

    if (fstat(fd, &st) != 0) {
        close(fd);
        return 0;
    }

    if (st.st_size <= 0) {
        close(fd);
        return 0;
    }

    void* mapping =
            mmap(
                    nullptr,
                    static_cast<size_t>(st.st_size),
                    PROT_READ,
                    MAP_PRIVATE,
                    fd,
                    0
            );

    close(fd);

    if (mapping == MAP_FAILED) {
        return 0;
    }

    auto* base =
            static_cast<uint8_t*>(mapping);

    auto* eh =
            reinterpret_cast<Elf64_Ehdr*>(base);

    if (
            eh->e_ident[EI_MAG0] != ELFMAG0 ||
            eh->e_ident[EI_MAG1] != ELFMAG1 ||
            eh->e_ident[EI_MAG2] != ELFMAG2 ||
            eh->e_ident[EI_MAG3] != ELFMAG3
            ) {

        munmap(
                mapping,
                static_cast<size_t>(st.st_size)
        );

        return 0;
    }

    uintptr_t minVaddr = UINTPTR_MAX;

    auto* ph =
            reinterpret_cast<Elf64_Phdr*>(
                    base + eh->e_phoff
            );

    for (int i = 0; i < eh->e_phnum; ++i) {

        if (ph[i].p_type == PT_LOAD) {

            if (ph[i].p_vaddr < minVaddr) {
                minVaddr = ph[i].p_vaddr;
            }
        }
    }

    uintptr_t symbolOffset = 0;

    auto* sh =
            reinterpret_cast<Elf64_Shdr*>(
                    base + eh->e_shoff
            );

    for (int i = 0; i < eh->e_shnum; ++i) {

        if (sh[i].sh_type != SHT_DYNSYM) {
            continue;
        }

        auto* symbols =
                reinterpret_cast<Elf64_Sym*>(
                        base + sh[i].sh_offset
                );

        const size_t count =
                sh[i].sh_size /
                sizeof(Elf64_Sym);

        const char* strtab =
                reinterpret_cast<const char*>(
                        base +
                        sh[sh[i].sh_link].sh_offset
                );

        for (size_t k = 0; k < count; ++k) {

            if (
                    strcmp(
                            strtab + symbols[k].st_name,
                            symbol
                    ) == 0
                    ) {

                symbolOffset =
                        symbols[k].st_value;

                break;
            }
        }

        if (symbolOffset != 0) {
            break;
        }
    }

    munmap(
            mapping,
            static_cast<size_t>(st.st_size)
    );

    if (minVaddrOut != nullptr) {

        *minVaddrOut =
                minVaddr == UINTPTR_MAX
                ? 0
                : minVaddr;
    }

    return symbolOffset;
}


static void* manualResolve(
        const char* suffix,
        const char* fullPath,
        const char* symbol
) {

    const uintptr_t base =
            manualLibBase(suffix);

    uintptr_t minVaddr = 0;

    const uintptr_t offset =
            manualSymOffset(
                    fullPath,
                    symbol,
                    &minVaddr
            );

    if (
            base == 0 ||
            offset == 0
            ) {

        LOGE(
                "manualResolve failed: %s",
                symbol
        );

        return nullptr;
    }

    void* address =
            reinterpret_cast<void*>(
                    base +
                    offset -
                    minVaddr
            );

    LOGI(
            "manualResolve: %s -> %p",
            symbol,
            address
    );

    return address;
}


// ============================================================================
// Guarded native pointer reads
// ============================================================================

static sigjmp_buf g_hf;

static void hfHandler(int /*signal*/) {

    siglongjmp(g_hf, 1);
}


static bool hfPlaus(void* ptr) {

    const uintptr_t value =
            reinterpret_cast<uintptr_t>(ptr);

    return
            (value & 7) == 0 &&
            (value & 0x00FFFFFFFFFFFFFFULL) >= 0x10000;
}


static bool hfRead(
        void* address,
        void** output
) {

    if (!hfPlaus(address)) {
        return false;
    }

    if (sigsetjmp(g_hf, 1)) {
        return false;
    }

    *output =
            *reinterpret_cast<void**>(address);

    return true;
}


// ============================================================================
// javaObjectForIBinder
// ============================================================================

typedef jobject (*JavaObjForIBinderFn)(
        JNIEnv*,
        const void*
);

static JavaObjForIBinderFn
        g_javaObjForIBinder = nullptr;


// ============================================================================
// nativeValidateArPtr
// ============================================================================

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_baba_callvault_services_recording_handoff_AudioHandoffNative_nativeValidateArPtr(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong ptr
) {

    struct sigaction sa{};
    struct sigaction oldSegv{};
    struct sigaction oldBus{};

    sa.sa_handler = hfHandler;

    sigemptyset(&sa.sa_mask);

    sigaction(
            SIGSEGV,
            &sa,
            &oldSegv
    );

    sigaction(
            SIGBUS,
            &sa,
            &oldBus
    );

    void* ar =
            reinterpret_cast<void*>(
                    static_cast<uintptr_t>(ptr)
            );

    void* bpAudioRecord = nullptr;
    void* bpBinder = nullptr;
    void* vptr = nullptr;

    bool ok =
            hfRead(
                    reinterpret_cast<uint8_t*>(ar) + 0x190,
                    &bpAudioRecord
            ) &&
            hfPlaus(bpAudioRecord) &&
            hfRead(
                    reinterpret_cast<uint8_t*>(bpAudioRecord) + 0x10,
                    &bpBinder
            ) &&
            hfPlaus(bpBinder) &&
            hfRead(
                    bpBinder,
                    &vptr
            ) &&
            hfPlaus(vptr);

    sigaction(
            SIGSEGV,
            &oldSegv,
            nullptr
    );

    sigaction(
            SIGBUS,
            &oldBus,
            nullptr
    );

    return ok
           ? JNI_TRUE
           : JNI_FALSE;
}


// ============================================================================
// nativeExtractBinder
// ============================================================================

extern "C"
JNIEXPORT jobject JNICALL
Java_com_baba_callvault_services_recording_handoff_AudioHandoffNative_nativeExtractBinder(
        JNIEnv* env,
        jobject /*thiz*/,
        jlong ptr
) {

    if (!g_javaObjForIBinder) {

        g_javaObjForIBinder =
                reinterpret_cast<JavaObjForIBinderFn>(
                        manualResolve(
                                "libandroid_runtime.so",
                                "/system/lib64/libandroid_runtime.so",
                                "_ZN7android20javaObjectForIBinderEP7_JNIEnvRKNS_2spINS_7IBinderEEE"
                        )
                );
    }

    if (!g_javaObjForIBinder) {

        LOGE(
                "nativeExtractBinder: "
                "javaObjectForIBinder unresolved"
        );

        return nullptr;
    }

    struct sigaction sa{};
    struct sigaction oldSegv{};
    struct sigaction oldBus{};

    sa.sa_handler = hfHandler;

    sigemptyset(&sa.sa_mask);

    sigaction(
            SIGSEGV,
            &sa,
            &oldSegv
    );

    sigaction(
            SIGBUS,
            &sa,
            &oldBus
    );

    void* ar =
            reinterpret_cast<void*>(
                    static_cast<uintptr_t>(ptr)
            );

    void* bpAudioRecord = nullptr;
    void* bpBinder = nullptr;

    bool ok =
            hfRead(
                    reinterpret_cast<uint8_t*>(ar) + 0x190,
                    &bpAudioRecord
            ) &&
            hfPlaus(bpAudioRecord) &&
            hfRead(
                    reinterpret_cast<uint8_t*>(bpAudioRecord) + 0x10,
                    &bpBinder
            ) &&
            hfPlaus(bpBinder);

    sigaction(
            SIGSEGV,
            &oldSegv,
            nullptr
    );

    sigaction(
            SIGBUS,
            &oldBus,
            nullptr
    );

    if (!ok) {

        LOGE(
                "nativeExtractBinder: "
                "invalid AudioRecord pointer"
        );

        return nullptr;
    }

    const void* spRef = bpBinder;

    jobject binder =
            g_javaObjForIBinder(
                    env,
                    &spRef
            );

    LOGI(
            "nativeExtractBinder: "
            "BpBinder=%p JavaBinder=%p",
            bpBinder,
            binder
    );

    return binder;
}


// ============================================================================
// nativeAshmemSize
// ============================================================================

extern "C"
JNIEXPORT jint JNICALL
Java_com_baba_callvault_services_recording_handoff_AudioHandoffNative_nativeAshmemSize(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint fd
) {

    if (fd < 0) {
        return -1;
    }

    return ioctl(
            fd,
            0x00007704,
            0
    );
}


// ============================================================================
// nativeFindCblkFd
// ============================================================================

extern "C"
JNIEXPORT jint JNICALL
Java_com_baba_callvault_services_recording_handoff_AudioHandoffNative_nativeFindCblkFd(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint expectedFrameCount
) {

    DIR* dir =
            opendir("/proc/self/fd");

    if (!dir) {
        return -1;
    }

    struct dirent* entry;

    char path[64];
    char target[256];

    int result = -1;

    const uint32_t expected =
            static_cast<uint32_t>(
                    expectedFrameCount
            );

    while (
            (entry = readdir(dir)) != nullptr
            ) {

        if (entry->d_name[0] == '.') {
            continue;
        }

        snprintf(
                path,
                sizeof(path),
                "/proc/self/fd/%s",
                entry->d_name
        );

        const ssize_t length =
                readlink(
                        path,
                        target,
                        sizeof(target) - 1
                );

        if (length <= 0) {
            continue;
        }

        target[length] = '\0';

        if (
                strstr(target, "ashmem") == nullptr
                ) {
            continue;
        }

        const int fd =
                atoi(entry->d_name);

        const int size =
                ioctl(
                        fd,
                        0x00007704,
                        0
                );

        if (size < 200) {
            continue;
        }

        void* mapping =
                mmap(
                        nullptr,
                        static_cast<size_t>(size),
                        PROT_READ,
                        MAP_SHARED,
                        fd,
                        0
                );

        if (mapping == MAP_FAILED) {
            continue;
        }

        const uint32_t frameCount =
                static_cast<volatile uint32_t*>(
                        mapping
                )[42];

        munmap(
                mapping,
                static_cast<size_t>(size)
        );

        if (frameCount == expected) {

            result = dup(fd);

            LOGI(
                    "nativeFindCblkFd: "
                    "fd=%d size=%d frameCount=%u dup=%d",
                    fd,
                    size,
                    frameCount,
                    result
            );

            break;
        }
    }

    closedir(dir);

    if (result < 0) {

        LOGI(
                "nativeFindCblkFd: "
                "no matching ashmem found"
        );
    }

    return result;
}


// ============================================================================
// nativeDrainToPipe
// ============================================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_baba_callvault_services_recording_handoff_AudioHandoffNative_nativeDrainToPipe(
        JNIEnv* env,
        jobject /*thiz*/,
        jint fd,
        jint size,
        jint frameCount,
        jint dataOff,
        jint frameSize,
        jint guardFrames,
        jint writeFd,
        jobject stopFlag,
        jint maxSeconds
) {

    if (
            fd < 0 ||
            size <= 0 ||
            frameCount <= 0 ||
            dataOff < 0 ||
            frameSize <= 0 ||
            guardFrames < 0 ||
            writeFd < 0 ||
            stopFlag == nullptr ||
            maxSeconds <= 0
            ) {

        LOGE(
                "nativeDrainToPipe: invalid arguments"
        );

        if (writeFd >= 0) {
            close(writeFd);
        }

        return;
    }

    void* base =
            mmap(
                    nullptr,
                    static_cast<size_t>(size),
                    PROT_READ | PROT_WRITE,
                    MAP_SHARED,
                    fd,
                    0
            );

    if (base == MAP_FAILED) {

        LOGE(
                "nativeDrainToPipe: mmap failed: %s",
                strerror(errno)
        );

        close(writeFd);

        return;
    }

    auto* stop =
            static_cast<volatile int32_t*>(
                    env->GetDirectBufferAddress(
                            stopFlag
                    )
            );

    if (!stop) {

        LOGE(
                "nativeDrainToPipe: "
                "stopFlag is not a direct ByteBuffer"
        );

        munmap(
                base,
                static_cast<size_t>(size)
        );

        close(writeFd);

        return;
    }

    auto* words =
            reinterpret_cast<volatile uint32_t*>(
                    base
            );

    auto* frontPtr =
            reinterpret_cast<volatile uint32_t*>(
                    words + 46
            );

    auto* rearPtr =
            reinterpret_cast<volatile uint32_t*>(
                    words + 47
            );

    auto* flagsPtr =
            reinterpret_cast<volatile int32_t*>(
                    words + 44
            );

    if (
            dataOff >= size ||
            static_cast<int64_t>(dataOff) +
            static_cast<int64_t>(frameCount) *
            static_cast<int64_t>(frameSize) >
            static_cast<int64_t>(size)
            ) {

        LOGE(
                "nativeDrainToPipe: "
                "ring geometry exceeds mapped cblk"
        );

        munmap(
                base,
                static_cast<size_t>(size)
        );

        close(writeFd);

        return;
    }

    auto* ring =
            static_cast<volatile uint8_t*>(base) +
            dataOff;

    const uint32_t fc =
            static_cast<uint32_t>(frameCount);

    const uint32_t guard =
            static_cast<uint32_t>(guardFrames);

    const int fsz = frameSize;

    /*
     * AudioRecord utilise une taille physique correspondant
     * à la puissance de deux supérieure ou égale au frame count.
     */
    uint32_t p2 = 1;

    while (
            p2 < fc &&
            p2 < 0x80000000u
            ) {

        p2 <<= 1;
    }

    if (p2 < fc) {

        LOGE(
                "nativeDrainToPipe: "
                "invalid frameCount"
        );

        munmap(
                base,
                static_cast<size_t>(size)
        );

        close(writeFd);

        return;
    }

    const uint32_t mask =
            p2 - 1;

    /*
     * Le consommateur du pipe ne doit jamais bloquer
     * la lecture du ring.
     */
    int pipeFlags =
            fcntl(
                    writeFd,
                    F_GETFL,
                    0
            );

    if (pipeFlags >= 0) {

        fcntl(
                writeFd,
                F_SETFL,
                pipeFlags | O_NONBLOCK
        );
    }

    std::vector<uint8_t> stage;

    size_t drainOffset = 0;

    bool pipeBroken = false;

    auto pumpPipe =
            [&](bool finalFlush) {

                while (
                        drainOffset < stage.size()
                        ) {

                    ssize_t written =
                            write(
                                    writeFd,
                                    stage.data() + drainOffset,
                                    stage.size() - drainOffset
                            );

                    if (written > 0) {

                        drainOffset +=
                                static_cast<size_t>(
                                        written
                                );

                        continue;
                    }

                    if (
                            written < 0 &&
                            (
                                    errno == EAGAIN ||
                                    errno == EWOULDBLOCK
                            )
                            ) {

                        if (!finalFlush) {
                            return;
                        }

                        usleep(2'000);

                        continue;
                    }

                    pipeBroken = true;

                    return;
                }

                stage.clear();

                drainOffset = 0;
            };

    const int cycleUs = 5'000;

    const int cyclesPerSecond =
            1'000'000 / cycleUs;

    const long maxCycles =
            static_cast<long>(maxSeconds) *
            cyclesPerSecond;

    uint32_t lastFront =
            __atomic_load_n(
                    rearPtr,
                    __ATOMIC_ACQUIRE
            );

    uint32_t previousRear =
            lastFront;

    uint32_t stallRear =
            lastFront;

    long stallSinceCycle = 0;

    long totalBytes = 0;

    int tick = 0;

    const int32_t CBLK_INVALID_FLAG = 0x04;

    const long stallLimitCycles =
            10L * cyclesPerSecond;

    LOGI(
            "nativeDrainToPipe START "
            "frameCount=%u p2=%u dataOff=%d "
            "frameSize=%d guard=%u maxSeconds=%d",
            fc,
            p2,
            dataOff,
            fsz,
            guard,
            maxSeconds
    );

    for (
            long cycle = 0;
            cycle < maxCycles;
            ++cycle
            ) {

        if (
                __atomic_load_n(
                        stop,
                        __ATOMIC_ACQUIRE
                ) != 0
                ) {

            LOGI(
                    "nativeDrainToPipe: "
                    "stop requested at %lds",
                    cycle / cyclesPerSecond
            );

            break;
        }

        if (pipeBroken) {

            LOGI(
                    "nativeDrainToPipe: "
                    "pipe closed/error"
            );

            break;
        }

        const uint32_t rear =
                __atomic_load_n(
                        rearPtr,
                        __ATOMIC_ACQUIRE
                );

        const int32_t flags =
                __atomic_load_n(
                        flagsPtr,
                        __ATOMIC_RELAXED
                );

        if (
                flags &
                CBLK_INVALID_FLAG
                ) {

            LOGI(
                    "nativeDrainToPipe: "
                    "track invalidated by AudioFlinger "
                    "after %ld bytes",
                    totalBytes
            );

            break;
        }

        /*
         * Détection d'un ring qui ne progresse plus.
         */
        if (rear != stallRear) {

            stallRear = rear;

            stallSinceCycle = cycle;

        } else if (
                cycle - stallSinceCycle >
                stallLimitCycles
                ) {

            LOGI(
                    "nativeDrainToPipe: "
                    "ring stalled for %lds "
                    "after %ld bytes",
                    static_cast<int>(
                            (
                                    cycle -
                                    stallSinceCycle
                            ) / cyclesPerSecond
                    ),
                    totalBytes
            );

            break;
        }

        const uint32_t distance =
                rear - lastFront;

        /*
         * On ne consomme pas les frames les plus récentes
         * afin d'éviter de lire une zone encore en écriture.
         */
        const uint32_t safeRear =
                distance > guard
                ? rear - guard
                : lastFront;

        uint32_t available =
                safeRear - lastFront;

        /*
         * Le producteur nous a rattrapés.
         */
        if (available > fc) {

            LOGI(
                    "nativeDrainToPipe: "
                    "ring overrun detected"
            );

            available = fc;

            lastFront =
                    rear - available;
        }

        if (available > 0) {

            const uint32_t startIndex =
                    lastFront & mask;

            uint32_t firstFrames =
                    p2 - startIndex;

            if (
                    firstFrames >
                    available
                    ) {

                firstFrames =
                        available;
            }

            const uint8_t* first =
                    const_cast<const uint8_t*>(
                            ring +
                            startIndex * fsz
                    );

            const size_t firstBytes =
                    static_cast<size_t>(
                            firstFrames
                    ) *
                    static_cast<size_t>(
                            fsz
                    );

            stage.insert(
                    stage.end(),
                    first,
                    first + firstBytes
            );

            const uint32_t remaining =
                    available -
                    firstFrames;

            if (remaining > 0) {

                const uint8_t* second =
                        const_cast<const uint8_t*>(
                                ring
                        );

                const size_t secondBytes =
                        static_cast<size_t>(
                                remaining
                        ) *
                        static_cast<size_t>(
                                fsz
                        );

                stage.insert(
                        stage.end(),
                        second,
                        second + secondBytes
                );
            }

            totalBytes +=
                    static_cast<long>(
                            available
                    ) *
                    fsz;
        }

        /*
         * IMPORTANT :
         *
         * mFront avance avant toute écriture
         * vers le pipe.
         */
        lastFront =
                safeRear;

        __atomic_store_n(
                frontPtr,
                safeRear,
                __ATOMIC_RELEASE
        );

        pumpPipe(false);

        /*
         * Évite une croissance infinie du vector
         * lorsque drainOffset devient important.
         */
        if (
                drainOffset >
                (1u << 20)
                ) {

            stage.erase(
                    stage.begin(),
                    stage.begin() +
                    static_cast<std::ptrdiff_t>(
                            drainOffset
                    )
            );

            drainOffset = 0;
        }

        if (
                (cycle + 1) %
                cyclesPerSecond ==
                0
                ) {

            LOGI(
                    "nativeDrainToPipe: "
                    "t=%ds rear=%u delta=%u "
                    "bytes=%ld staged=%zu",
                    tick,
                    rear,
                    rear - previousRear,
                    totalBytes,
                    stage.size() - drainOffset
            );

            previousRear = rear;

            ++tick;
        }

        usleep(cycleUs);
    }

    /*
     * Dernier flush.
     */
    if (!pipeBroken) {
        pumpPipe(true);
    }

    /*
     * EOF côté lecteur.
     */
    close(writeFd);

    munmap(
            base,
            static_cast<size_t>(size)
    );

    LOGI(
            "nativeDrainToPipe DONE "
            "PCM bytes=%ld",
            totalBytes
    );
}