/*
 * PTY JNI layer for Android.
 *
 * Provides:
 *   createSubprocess  – fork+exec a command inside a new PTY
 *   waitFor           – blocking waitpid
 *   setPtyWindowSize  – TIOCSWINSZ ioctl
 *   setPtyUTF8Mode    – toggle IUTF8 flag
 */
#include <jni.h>
#include <string.h>
#include <unistd.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <stdio.h>
#include <android/log.h>
#include <termios.h>

#ifdef __linux__
#include <pty.h>
#elif __APPLE__
#include <util.h>
#endif

#define TAG "PtyJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/*  Helper: build a NULL-terminated C string array from a Java array   */
/* ------------------------------------------------------------------ */
static char **jstring_array_to_cstrings(JNIEnv *env, jobjectArray arr, int *out_len) {
    int len = (*env)->GetArrayLength(env, arr);
    char **result = (char **) malloc(sizeof(char *) * (len + 1));
    if (!result) return NULL;
    for (int i = 0; i < len; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *s = (*env)->GetStringUTFChars(env, js, 0);
        result[i] = strdup(s);
        (*env)->ReleaseStringUTFChars(env, js, s);
        (*env)->DeleteLocalRef(env, js);
    }
    result[len] = NULL;
    *out_len = len;
    return result;
}

static void free_cstrings(char **arr, int len) {
    if (!arr) return;
    for (int i = 0; i < len; i++) free(arr[i]);
    free(arr);
}

/* ------------------------------------------------------------------ */
/*  Default termios initialisation (sane terminal defaults)            */
/* ------------------------------------------------------------------ */
static void init_default_termios(struct termios *tt) {
    memset(tt, 0, sizeof(*tt));
    tt->c_iflag  = ICRNL | IXON | IXANY | IMAXBEL | IUTF8;
    tt->c_oflag  = OPOST | ONLCR;
    tt->c_lflag  = ISIG | ICANON | ECHO | ECHOE | ECHOK | IEXTEN | ECHOCTL | ECHOKE;
    tt->c_cflag  = CS8 | CREAD;
    tt->c_cc[VINTR]    = 'C'  - '@';   /* Ctrl-C  */
    tt->c_cc[VQUIT]    = '\\' - '@';   /* Ctrl-\  */
    tt->c_cc[VERASE]   = 0x7f;         /* DEL     */
    tt->c_cc[VKILL]    = 'U'  - '@';   /* Ctrl-U  */
    tt->c_cc[VEOF]     = 'D'  - '@';   /* Ctrl-D  */
    tt->c_cc[VSTOP]    = 'S'  - '@';   /* Ctrl-S  */
    tt->c_cc[VSUSP]    = 'Z'  - '@';   /* Ctrl-Z  */
    tt->c_cc[VSTART]   = 'Q'  - '@';   /* Ctrl-Q  */
    tt->c_cc[VMIN]     = 1;
    tt->c_cc[VTIME]    = 0;
    cfsetispeed(tt, B38400);
    cfsetospeed(tt, B38400);
}

/* ------------------------------------------------------------------ */
/*  createSubprocess – fork+exec inside a PTY                          */
/*                                                                     */
/*  Returns int[2] = { pid, masterFd } on success, NULL on failure.    */
/*  Window size is now parameterised (rows, cols).                     */
/* ------------------------------------------------------------------ */
JNIEXPORT jintArray JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_createSubprocess(
        JNIEnv *env, jobject thiz,
        jobjectArray cmdarray,
        jobjectArray envarray,
        jstring workingDir,
        jint rows,
        jint cols) {

    int master_fd;
    pid_t pid;

    /* --- unpack Java strings --- */
    const char *cwd_jni = (*env)->GetStringUTFChars(env, workingDir, 0);
    char *cwd = strdup(cwd_jni);
    (*env)->ReleaseStringUTFChars(env, workingDir, cwd_jni);

    int env_len = 0, cmd_len = 0;
    char **envp = jstring_array_to_cstrings(env, envarray, &env_len);
    char **argv = jstring_array_to_cstrings(env, cmdarray, &cmd_len);

    if (!envp || !argv) {
        LOGE("malloc failed while preparing argv/envp");
        free(cwd);
        free_cstrings(argv, cmd_len);
        free_cstrings(envp, env_len);
        return NULL;
    }

    /* --- terminal attributes --- */
    struct termios tt;
    init_default_termios(&tt);

    struct winsize ws = {
        .ws_row    = (unsigned short)(rows > 0 ? rows : 24),
        .ws_col    = (unsigned short)(cols > 0 ? cols : 80),
        .ws_xpixel = 0,
        .ws_ypixel = 0,
    };

    /* --- fork --- */
    pid = forkpty(&master_fd, NULL, &tt, &ws);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        free(cwd);
        free_cstrings(argv, cmd_len);
        free_cstrings(envp, env_len);
        return NULL;
    }

    if (pid == 0) {
        /* ---- CHILD ---- */
        /* Close leaked fds (stdin/stdout/stderr are 0-2, kept open) */
        long max_fd = sysconf(_SC_OPEN_MAX);
        if (max_fd < 0) max_fd = 256;
        for (int fd = 3; fd < max_fd; fd++) close(fd);

        if (chdir(cwd) != 0) {
            fprintf(stderr, "chdir(%s): %s\n", cwd, strerror(errno));
            _exit(1);
        }

        execve(argv[0], argv, envp);
        fprintf(stderr, "execve(%s): %s\n", argv[0], strerror(errno));
        _exit(127);
    }

    /* ---- PARENT ---- */
    /* Prevent the master fd from leaking into future child processes */
    fcntl(master_fd, F_SETFD, FD_CLOEXEC);

    jintArray result = (*env)->NewIntArray(env, 2);
    if (result) {
        jint fill[2] = { pid, master_fd };
        (*env)->SetIntArrayRegion(env, result, 0, 2, fill);
    }

    free(cwd);
    free_cstrings(argv, cmd_len);
    free_cstrings(envp, env_len);
    return result;
}

/* ------------------------------------------------------------------ */
/*  waitFor – blocking wait for process exit                           */
/* ------------------------------------------------------------------ */
JNIEXPORT jint JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_waitFor(JNIEnv *env, jobject thiz, jint pid) {
    int status;
    pid_t rc;
    do {
        rc = waitpid(pid, &status, 0);
    } while (rc == -1 && errno == EINTR);

    if (rc == -1) {
        LOGE("waitpid(%d) failed: %s", pid, strerror(errno));
        return -1;
    }
    if (WIFEXITED(status))   return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/* ------------------------------------------------------------------ */
/*  setPtyWindowSize – TIOCSWINSZ ioctl                                */
/* ------------------------------------------------------------------ */
JNIEXPORT jint JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_setPtyWindowSize(
        JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    struct winsize ws = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) cols,
        .ws_xpixel = 0,
        .ws_ypixel = 0,
    };
    if (ioctl(fd, TIOCSWINSZ, &ws) != 0) {
        LOGE("TIOCSWINSZ fd=%d %dx%d: %s", fd, rows, cols, strerror(errno));
        return -1;
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/*  setPtyUTF8Mode – toggle IUTF8 input flag                           */
/* ------------------------------------------------------------------ */
JNIEXPORT jint JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_setPtyUTF8Mode(
        JNIEnv *env, jobject thiz, jint fd, jboolean enabled) {
    struct termios tt;
    if (tcgetattr(fd, &tt) != 0) {
        LOGE("tcgetattr fd=%d: %s", fd, strerror(errno));
        return -1;
    }
    if (enabled) {
        tt.c_iflag |= IUTF8;
    } else {
        tt.c_iflag &= ~((tcflag_t) IUTF8);
    }
    if (tcsetattr(fd, TCSANOW, &tt) != 0) {
        LOGE("tcsetattr fd=%d: %s", fd, strerror(errno));
        return -1;
    }
    return 0;
}
