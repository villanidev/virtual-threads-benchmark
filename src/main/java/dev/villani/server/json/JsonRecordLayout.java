package dev.villani.server.json;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

/**
 * Byte layout of one {@link JsonRecord} for a given schema, declared as an FFM StructLayout (only the offsets are
 * used, so it works for both Region backends): {@code values[n]:long | lengths[n]:int | present[n]:byte |
 * strings[stringBytes]:byte | padding}, with n = number of fields. Built once at start-up.
 */
final class JsonRecordLayout {

    private final int lengths;
    private final int present;
    private final int strings;
    private final int size;

    JsonRecordLayout(int fields, int stringBytes) {
        final int used = fields * (Long.BYTES + Integer.BYTES + 1) + stringBytes;
        final int pad = (Long.BYTES - used % Long.BYTES) % Long.BYTES;
        final MemoryLayout[] members = new MemoryLayout[pad == 0 ? 4 : 5];
        members[0] = MemoryLayout.sequenceLayout(fields, ValueLayout.JAVA_LONG).withName("values");
        members[1] = MemoryLayout.sequenceLayout(fields, ValueLayout.JAVA_INT).withName("lengths");
        members[2] = MemoryLayout.sequenceLayout(fields, ValueLayout.JAVA_BYTE).withName("present");
        members[3] = MemoryLayout.sequenceLayout(stringBytes, ValueLayout.JAVA_BYTE).withName("strings");
        if (pad != 0) {
            members[4] = MemoryLayout.paddingLayout(pad);
        }
        final StructLayout layout = MemoryLayout.structLayout(members);
        this.lengths = (int) layout.byteOffset(PathElement.groupElement("lengths"));
        this.present = (int) layout.byteOffset(PathElement.groupElement("present"));
        this.strings = (int) layout.byteOffset(PathElement.groupElement("strings"));
        this.size = (int) layout.byteSize();
    }

    int size() {
        return size;
    }

    int valueOffset(int field) {
        return field * Long.BYTES;
    }

    int lengthOffset(int field) {
        return lengths + field * Integer.BYTES;
    }

    int presentOffset(int field) {
        return present + field;
    }

    int stringsOffset() {
        return strings;
    }
}
