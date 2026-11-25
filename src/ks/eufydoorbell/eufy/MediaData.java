// SPDX-License-Identifier: Apache-2.0
package ks.eufydoorbell.eufy;

/**
 * Reads the bytes out of a bridge livestream message without a JSON parser. The bridge sends
 * {"buffer":{"type":"Buffer","data":[1,2,3,...]},"metadata":{...}}, and one keyframe is a list of tens of thousands of
 * numbers, which a general parser would turn into as many objects.
 */
final class MediaData {
    private MediaData() {}

    static byte[] bytes(String message) {
        int start = message.indexOf("\"data\":[");
        if (start < 0) return null;
        int i = start + 8;
        int length = message.length();
        int count = 1;
        for (int j = i; j < length && message.charAt(j) != ']'; j++) if (message.charAt(j) == ',') count++;
        byte[] out = new byte[count];
        int n = 0, value = 0;
        boolean any = false;
        for (; i < length; i++) {
            char c = message.charAt(i);
            if (c >= '0' && c <= '9') {
                value = value * 10 + (c - '0');
                any = true;
            } else if (c == ',' || c == ']') {
                if (any && n < out.length) out[n++] = (byte) value;
                value = 0;
                any = false;
                if (c == ']') break;
            }
        }
        if (n == out.length) return out;
        byte[] trimmed = new byte[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** A string field's value, such as the serial number, or null. */
    static String string(String message, String key) {
        String needle = "\"" + key + "\":\"";
        int at = message.indexOf(needle);
        if (at < 0) return null;
        int from = at + needle.length();
        int end = message.indexOf('"', from);
        return end < 0 ? null : message.substring(from, end);
    }

    /** A numeric field's value, or 0 when it is missing. */
    static int number(String message, String key) {
        String needle = "\"" + key + "\":";
        int at = message.indexOf(needle);
        if (at < 0) return 0;
        int i = at + needle.length();
        int value = 0;
        while (i < message.length() && Character.isDigit(message.charAt(i))) value = value * 10 + (message.charAt(i++) - '0');
        return value;
    }
}
