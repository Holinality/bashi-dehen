package com.holi.prdbus;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

final class BoundedStreams {
    private BoundedStreams() {}

    static long deadlineAfter(long milliseconds) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
    }

    static long copy(InputStream input, OutputStream output, long limit, long deadline) throws IOException {
        byte[] buffer = new byte[8192];
        long written = 0;
        while (true) {
            checkDeadline(deadline);
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, limit - written + 1));
            checkDeadline(deadline);
            if (count < 0) return written;
            if (count > limit - written) throw new IOException("响应数据超过大小限制");
            output.write(buffer, 0, count);
            written += count;
        }
    }

    static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("请求已取消");
        if (System.nanoTime() - deadline >= 0) throw new SocketTimeoutException("请求超时");
    }
}
