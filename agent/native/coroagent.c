// Minimal JNI bridge for OBI's ioctl control channel
// (bpf/generictracer/java_tls.c in OBI v0.10.0):
//   ioctl(fd=0, cmd=0x0b10b1, packet)
//   op=3 (java_threads):    packet[1..8] = u64 parent kernel tid
//   op=4 (java_vt_mount):   packet[1..8] = u64 logical id, mounted on the current tid
//   op=5 (java_vt_unmount): unmount from the current tid
#include <jni.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <unistd.h>

// The return value is always an error and carries no information about OBI: the kprobe on
// sys_ioctl consumes the request at syscall entry, then the kernel rejects cmd 0x0b10b1 on fd 0
// (ENOTTY, or EBADF if stdin is closed). Do not use it to detect whether OBI is attached.
static jint send_op(unsigned char op, jlong payload) {
    unsigned char buf[9];
    buf[0] = op;
    memcpy(buf + 1, &payload, 8);
    return ioctl(0, 0x0b10b1, buf);
}

JNIEXPORT jint JNICALL Java_obicoro_Native_gettid0(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return (jint)syscall(SYS_gettid);
}

JNIEXPORT jint JNICALL Java_obicoro_Native_sendParent0(JNIEnv *env, jclass cls, jlong parent) {
    (void)env;
    (void)cls;
    return send_op(3, parent);
}

JNIEXPORT jint JNICALL Java_obicoro_Native_mount0(JNIEnv *env, jclass cls, jlong id) {
    (void)env;
    (void)cls;
    return send_op(4, id);
}

JNIEXPORT jint JNICALL Java_obicoro_Native_unmount0(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return send_op(5, 0);
}
