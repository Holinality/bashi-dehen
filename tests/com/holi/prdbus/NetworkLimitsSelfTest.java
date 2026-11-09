package com.holi.prdbus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;

public final class NetworkLimitsSelfTest {
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        long deadline = BoundedStreams.deadlineAfter(2_000);
        long size = BoundedStreams.copy(new ByteArrayInputStream(new byte[4096]), result, 4096, deadline);
        require(size == 4096 && result.size() == 4096, "Response at the exact limit must work");
        result.reset();
        try {
            BoundedStreams.copy(new ByteArrayInputStream(new byte[4097]), result, 4096, deadline);
            throw new AssertionError("Oversized response accepted");
        } catch (IOException expected) {
            require(result.size() <= 4096, "The oversized chunk reached the output");
        }
        try {
            BoundedStreams.copy(new ByteArrayInputStream(new byte[1]), result, 1, BoundedStreams.deadlineAfter(-1));
            throw new AssertionError("Expired deadline accepted");
        } catch (SocketTimeoutException expected) { }
        Thread.currentThread().interrupt();
        try {
            BoundedStreams.copy(new ByteArrayInputStream(new byte[1]), result, 1, deadline);
            throw new AssertionError("Interrupted download accepted");
        } catch (InterruptedIOException expected) {
            require(Thread.currentThread().isInterrupted(), "Cancellation flag was lost");
        } finally {
            Thread.interrupted();
        }
        byte[] actual = MessageDigest.getInstance("SHA-256").digest(new byte[]{'a', 'b', 'c'});
        String expected = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD";
        require(UpdatePolicy.matchesSha256(expected, actual), "Valid digest rejected");
        require(!UpdatePolicy.matchesSha256("0" + expected.substring(1), actual), "Mismatched digest accepted");
        require(!UpdatePolicy.matchesSha256(expected.substring(1), actual), "Truncated digest accepted");
        System.out.println("NetworkLimitsSelfTest passed");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
