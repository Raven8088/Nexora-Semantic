package com.nexora.semantic.api.web;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;

/** Reads a request body without ever buffering more than {@code maxBytes} (+1 to detect the overflow). */
public final class BoundedBody {

    private BoundedBody() {
    }

    /** @throws PayloadTooLargeException if the declared or actual size exceeds {@code maxBytes} */
    public static byte[] read(HttpServletRequest request, int maxBytes) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            throw new PayloadTooLargeException();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(maxBytes + 1);
            if (body.length > maxBytes) {
                throw new PayloadTooLargeException();
            }
            return body;
        }
    }
}
