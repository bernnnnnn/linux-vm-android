package com.berns.linuxports.term

import java.io.File

object Pty {
    init { System.loadLibrary("bernspty") }

    @JvmStatic external fun nativeForkExec(
        cmd: String, argv: Array<String>, envp: Array<String>,
        cwd: String?, rows: Int, cols: Int, pid: IntArray
    ): Int

    @JvmStatic external fun nativeResize(fd: Int, rows: Int, cols: Int)
    @JvmStatic external fun nativeRead(fd: Int, buf: ByteArray, len: Int): Int
    @JvmStatic external fun nativeWrite(fd: Int, buf: ByteArray, len: Int): Int
    @JvmStatic external fun nativeClose(fd: Int)
    @JvmStatic external fun nativeWaitFor(pid: Int): Int
    @JvmStatic external fun nativeKill(pid: Int, sig: Int)
}

/** A child process attached to a pseudo-terminal. */
class PtyProcess private constructor(private var fd: Int, val pid: Int) {

    @Volatile var running: Boolean = true
        private set

    fun read(buf: ByteArray): Int {
        if (!running) return -1
        return try { Pty.nativeRead(fd, buf, buf.size) } catch (t: Throwable) { -1 }
    }

    fun write(bytes: ByteArray) {
        if (!running) return
        runCatching { Pty.nativeWrite(fd, bytes, bytes.size) }
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun resize(rows: Int, cols: Int) {
        if (running && rows > 0 && cols > 0) runCatching { Pty.nativeResize(fd, rows, cols) }
    }

    fun waitFor(): Int = Pty.nativeWaitFor(pid).also { running = false }

    fun destroy() {
        if (!running) return
        running = false
        runCatching { Pty.nativeKill(pid, 15) }
        runCatching { Pty.nativeClose(fd) }
        fd = -1
    }

    companion object {
        fun start(
            argv: List<String>,
            env: Map<String, String>,
            cwd: File? = null,
            rows: Int = 24,
            cols: Int = 80
        ): PtyProcess {
            require(argv.isNotEmpty()) { "empty command" }
            val pidOut = IntArray(1)
            val envp = env.map { "${it.key}=${it.value}" }.toTypedArray()
            val fd = Pty.nativeForkExec(
                argv[0], argv.toTypedArray(), envp, cwd?.absolutePath, rows, cols, pidOut
            )
            if (fd < 0) throw java.io.IOException("could not start ${argv[0]}")
            return PtyProcess(fd, pidOut[0])
        }
    }
}
