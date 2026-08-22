/*
 * Minimal PTY bridge for Berns Linux Ports.
 *
 * Android has no public API for pseudo-terminals, but interactive shells need a
 * real controlling tty: job control, line editing, `apt` progress bars and curses
 * apps all break when a process is wired up to plain pipes. This creates a pty
 * pair, forks, and hands the master fd back to Kotlin as a plain int.
 */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void throw_ioe(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, msg);
}

JNIEXPORT jint JNICALL
Java_com_berns_linuxports_term_Pty_nativeForkExec(
        JNIEnv *env, jclass clazz,
        jstring j_cmd, jobjectArray j_argv, jobjectArray j_envp,
        jstring j_cwd, jint rows, jint cols, jintArray j_pid) {
    (void) clazz;
    int master = -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;

    const char *cmd = (*env)->GetStringUTFChars(env, j_cmd, NULL);
    const char *cwd = j_cwd == NULL ? NULL : (*env)->GetStringUTFChars(env, j_cwd, NULL);

    jsize argc = (*env)->GetArrayLength(env, j_argv);
    jsize envc = (*env)->GetArrayLength(env, j_envp);
    char **argv = calloc((size_t) argc + 1, sizeof(char *));
    char **envp = calloc((size_t) envc + 1, sizeof(char *));
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, j_argv, i);
        argv[i] = strdup((*env)->GetStringUTFChars(env, s, NULL));
    }
    for (jsize i = 0; i < envc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, j_envp, i);
        envp[i] = strdup((*env)->GetStringUTFChars(env, s, NULL));
    }

    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        throw_ioe(env, "forkpty() failed");
        return -1;
    }
    if (pid == 0) {
        /* Child: become a session leader with the slave pty as controlling tty
         * (forkpty already did that), then hand over to the real program. */
        if (cwd != NULL && cwd[0] != '\0') {
            if (chdir(cwd) != 0) { /* non fatal, the shell will start in / */ }
        }
        signal(SIGCHLD, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);
        execve(cmd, argv, envp);
        /* Only reached when exec fails; report through the pty so the user sees it. */
        fprintf(stderr, "\r\nberns: cannot execute %s: %s\r\n", cmd, strerror(errno));
        _exit(127);
    }

    /* Parent */
    int flags = fcntl(master, F_GETFD);
    if (flags != -1) fcntl(master, F_SETFD, flags | FD_CLOEXEC);

    jint pid_out = (jint) pid;
    (*env)->SetIntArrayRegion(env, j_pid, 0, 1, &pid_out);

    (*env)->ReleaseStringUTFChars(env, j_cmd, cmd);
    if (cwd != NULL) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
    return master;
}

JNIEXPORT void JNICALL
Java_com_berns_linuxports_term_Pty_nativeResize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    (void) env; (void) clazz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_com_berns_linuxports_term_Pty_nativeRead(JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint len) {
    (void) clazz;
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    ssize_t n = read(fd, b, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, buf, b, 0);
    if (n < 0 && (errno == EIO || errno == EBADF)) return -1; /* peer closed */
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_com_berns_linuxports_term_Pty_nativeWrite(JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint len) {
    (void) clazz;
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    ssize_t written = 0;
    while (written < len) {
        ssize_t n = write(fd, b + written, (size_t) (len - written));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        written += n;
    }
    (*env)->ReleaseByteArrayElements(env, buf, b, JNI_ABORT);
    return (jint) written;
}

JNIEXPORT void JNICALL
Java_com_berns_linuxports_term_Pty_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
    (void) env; (void) clazz;
    if (fd >= 0) close(fd);
}

JNIEXPORT jint JNICALL
Java_com_berns_linuxports_term_Pty_nativeWaitFor(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    int status = 0;
    while (waitpid((pid_t) pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_berns_linuxports_term_Pty_nativeKill(JNIEnv *env, jclass clazz, jint pid, jint sig) {
    (void) env; (void) clazz;
    /* Negative pid: the whole process group, so proot and everything it started dies. */
    kill((pid_t) -pid, sig);
    kill((pid_t) pid, sig);
}
