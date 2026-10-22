package org.forgesilicon.caesar;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Reassembles fragmented USB packets; drops oversized lines until the next newline. */
public final class LineFramer {
    private final byte[] line = new byte[1024];
    private int length;
    private boolean dropping;
    public void accept(byte[] bytes, int count, Consumer<String> consumer) {
        for (int i = 0; i < count; i++) {
            byte b = bytes[i];
            if (b == '\n') {
                if (!dropping && length > 0) consumer.accept(new String(line, 0, length, StandardCharsets.UTF_8).trim());
                length = 0; dropping = false;
            } else if (!dropping) {
                if (length == line.length) { dropping = true; length = 0; }
                else line[length++] = b;
            }
        }
    }
}
