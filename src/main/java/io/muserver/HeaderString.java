package io.muserver;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

class HeaderString implements CharSequence {

    private final String s;
    final byte[] bytes;

    private HeaderString(CharSequence value) {
        this.s = value.toString();
        CharsetEncoder encoder = StandardCharsets.US_ASCII.newEncoder();
        var buffer = ByteBuffer.allocate(s.length());
        var result = encoder.encode(CharBuffer.wrap(value), buffer, true);
        if (result.isError() || result.isMalformed() || result.isUnmappable() || result.isOverflow() || result.isUnderflow()) {
            throw new IllegalArgumentException("Could not convert the string to an ascii string: " + result);
        }
        this.bytes = buffer.array();
    }

    public static HeaderString valueOf(String value) {
        return new HeaderString(value);
    }

    @Override
    public int length() {
        return s.length();
    }

    @Override
    public char charAt(int index) {
        return s.charAt(index);
    }

    @NotNull
    @Override
    public CharSequence subSequence(int start, int end) {
        return s.subSequence(start, end);
    }

    @NotNull
    @Override
    public IntStream chars() {
        return s.chars();
    }

    @NotNull
    @Override
    public IntStream codePoints() {
        return s.codePoints();
    }

    @Override
    public int hashCode() {
        return s.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof HeaderString) {
            return ((HeaderString) obj).s.equals(s);
        }
        return false;
    }

    @Override
    @NotNull
    public String toString() {
        return s;
    }
}
