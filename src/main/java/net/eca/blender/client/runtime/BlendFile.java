package net.eca.blender.client.runtime;

import io.airlift.compress.zstd.ZstdInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Reads serialized data only; embedded scripts and drivers are never executed. */
final class BlendFile {
    private static final Pattern ARRAY = Pattern.compile("\\[(\\d+)]");
    private final ByteBuffer bytes;
    private final int pointerSize;
    private final List<Block> blocks = new ArrayList<>();
    private final NavigableMap<Long, Block> addresses = new TreeMap<>(Long::compareUnsigned);
    private final NavigableMap<Long, Block> ids = new TreeMap<>(Long::compareUnsigned);
    private final Map<Integer, NavigableMap<Long, Block>> scopes = new HashMap<>();
    private final List<Struct> structs = new ArrayList<>();
    private final Map<String, Struct> types = new HashMap<>();

    static BlendFile read(InputStream source) throws IOException {
        BufferedInputStream input = new BufferedInputStream(source);
        input.mark(4);
        byte[] magic = input.readNBytes(4);
        input.reset();
        InputStream decoded = input;
        if (magic.length >= 2 && (magic[0] & 255) == 31 && (magic[1] & 255) == 139) {
            decoded = new GZIPInputStream(input);
        }
        try (InputStream stream = decoded) {
            int signature = magic.length == 4 ? ByteBuffer.wrap(magic).order(ByteOrder.LITTLE_ENDIAN).getInt() : 0;
            byte[] data = signature == 0xFD2FB528 || (signature & 0xFFFFFFF0) == 0x184D2A50
                ? readZstd(stream) : stream.readAllBytes();
            try {
                return new BlendFile(data);
            } catch (IllegalArgumentException | IndexOutOfBoundsException | ArithmeticException exception) {
                throw new IOException("Malformed blend resource: " + exception.getMessage(), exception);
            }
        }
    }

    private static byte[] readZstd(InputStream stream) throws IOException {
        byte[] compressed = stream.readAllBytes();
        ByteBuffer frames = ByteBuffer.wrap(compressed).order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (frames.hasRemaining()) {
            int start = frames.position();
            require(frames.remaining() >= 4, "Truncated Zstandard magic");
            int magic = frames.getInt();
            if ((magic & 0xFFFFFFF0) == 0x184D2A50) {
                require(frames.remaining() >= 4, "Truncated Zstandard skip frame");
                skipCompressed(frames, Integer.toUnsignedLong(frames.getInt()));
                continue;
            }
            require(magic == 0xFD2FB528 && frames.hasRemaining(), "Invalid Zstandard frame");
            int descriptor = Byte.toUnsignedInt(frames.get());
            require((descriptor & 8) == 0, "Reserved Zstandard header flag");
            boolean single = (descriptor & 32) != 0;
            int sizeFlag = descriptor >>> 6;
            int dictionarySize = switch (descriptor & 3) { case 0 -> 0; case 1 -> 1; case 2 -> 2; default -> 4; };
            int contentSize = sizeFlag == 0 ? (single ? 1 : 0) : 1 << sizeFlag;
            skipCompressed(frames, (single ? 0 : 1) + dictionarySize + contentSize);
            boolean last;
            do {
                require(frames.remaining() >= 3, "Truncated Zstandard block");
                int block = Byte.toUnsignedInt(frames.get()) | Byte.toUnsignedInt(frames.get()) << 8 | Byte.toUnsignedInt(frames.get()) << 16;
                last = (block & 1) != 0;
                int type = block >>> 1 & 3;
                require(type != 3, "Reserved Zstandard block type");
                skipCompressed(frames, type == 1 ? 1 : block >>> 3);
            } while (!last);
            if ((descriptor & 4) != 0) skipCompressed(frames, 4);
            // Seek tables are metadata; only ordinary frames reach the decompressor.
            try (ZstdInputStream frame = new ZstdInputStream(new ByteArrayInputStream(compressed, start, frames.position() - start))) {
                frame.transferTo(output);
            }
        }
        return output.toByteArray();
    }

    private static void skipCompressed(ByteBuffer buffer, long count) throws IOException {
        require(count >= 0 && count <= buffer.remaining(), "Truncated Zstandard frame payload");
        buffer.position(buffer.position() + (int) count);
    }

    private BlendFile(byte[] data) throws IOException {
        bytes = ByteBuffer.wrap(data);
        require(data.length >= 12 && ascii(0, 7).equals("BLENDER"), "Missing BLENDER header");
        boolean large = data[7] == '1';
        int version;
        if (large) {
            require(data.length >= 17 && ascii(7, 6).equals("17-01v"), "Unsupported blend container version");
            pointerSize = 8;
            bytes.order(ByteOrder.LITTLE_ENDIAN);
            version = Integer.parseInt(ascii(13, 4));
            bytes.position(17);
        } else {
            require(data[7] == '-' || data[7] == '_', "Invalid pointer size");
            require(data[8] == 'v' || data[8] == 'V', "Invalid byte order");
            pointerSize = data[7] == '-' ? 8 : 4;
            bytes.order(data[8] == 'v' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            version = Integer.parseInt(ascii(9, 3));
            bytes.position(12);
        }
        require(version == 502, "Expected Blender 5.2 file data, found " + version);
        Block dna = null;
        boolean ended = false;
        int scope = 0;
        long virtualAddress = 4096;
        while (bytes.remaining() >= (large ? 32 : 16 + pointerSize)) {
            String code = ascii(bytes.position(), 4);
            bytes.position(bytes.position() + 4);
            int schema;
            long address;
            long length;
            long count;
            if (large) {
                schema = bytes.getInt();
                address = bytes.getLong();
                length = bytes.getLong();
                count = bytes.getLong();
            } else {
                length = bytes.getInt();
                address = pointerSize == 8 ? bytes.getLong() : Integer.toUnsignedLong(bytes.getInt());
                schema = bytes.getInt();
                count = bytes.getInt();
            }
            require(length >= 0 && length <= bytes.remaining(), "Invalid block length: " + code);
            require(count >= 0 && count <= Integer.MAX_VALUE, "Invalid block element count: " + code);
            if (!code.equals("DATA")) scope++;
            Block block = new Block(code, virtualAddress, address, scope, bytes.position(), (int) length, schema, (int) count);
            virtualAddress += length + 16;
            blocks.add(block);
            if (address != 0 && length != 0) {
                addresses.put(block.address, block);
                NavigableMap<Long, Block> index = code.equals("DATA")
                    ? scopes.computeIfAbsent(scope, ignored -> new TreeMap<>(Long::compareUnsigned)) : ids;
                // The file reader resolves repeated addresses to the last block in that owner.
                index.put(address, block);
            }
            if (code.equals("DNA1")) dna = block;
            bytes.position(bytes.position() + (int) length);
            if (code.equals("ENDB")) { ended = true; break; }
        }
        require(ended && dna != null, "Incomplete blend resource");
        readDna(dna);
        for (Block block : blocks) {
            if (block.schema > 0 && !block.code.equals("DNA1")) {
                require(block.schema < structs.size(), "Invalid SDNA index");
                require((long) structs.get(block.schema).size * block.count <= block.length, "Truncated structured block");
            }
        }
    }

    private void readDna(Block dna) throws IOException {
        ByteBuffer cursor = bytes.duplicate().order(bytes.order());
        cursor.position(dna.offset).limit(dna.offset + dna.length);
        tag(cursor, "SDNA");
        tag(cursor, "NAME");
        List<String> names = strings(cursor, count(cursor.getInt()));
        align(cursor, dna.offset);
        tag(cursor, "TYPE");
        List<String> typeNames = strings(cursor, count(cursor.getInt()));
        align(cursor, dna.offset);
        tag(cursor, "TLEN");
        require(typeNames.size() <= cursor.remaining() / 2, "Truncated SDNA type sizes");
        int[] sizes = new int[typeNames.size()];
        for (int i = 0; i < sizes.length; i++) sizes[i] = Short.toUnsignedInt(cursor.getShort());
        align(cursor, dna.offset);
        tag(cursor, "STRC");
        int total = count(cursor.getInt());
        require(total <= cursor.remaining() / 4, "Truncated SDNA structures");
        for (int i = 0; i < total; i++) {
            int type = Short.toUnsignedInt(cursor.getShort());
            int members = Short.toUnsignedInt(cursor.getShort());
            require(type < sizes.length, "Invalid SDNA type");
            Map<String, Field> fields = new LinkedHashMap<>();
            int offset = 0;
            for (int j = 0; j < members; j++) {
                int fieldType = Short.toUnsignedInt(cursor.getShort());
                int fieldName = Short.toUnsignedInt(cursor.getShort());
                require(fieldType < sizes.length && fieldName < names.size(), "Invalid SDNA member");
                String declaration = names.get(fieldName);
                boolean pointer = declaration.contains("*");
                int elements = 1;
                Matcher matcher = ARRAY.matcher(declaration);
                if (!declaration.startsWith("(*")) {
                    while (matcher.find()) elements = Math.multiplyExact(elements, Integer.parseInt(matcher.group(1)));
                }
                int length = Math.multiplyExact(elements, pointer ? pointerSize : sizes[fieldType]);
                String name = declaration.replaceAll("\\[[^]]*]", "").replace("*", "");
                if (name.startsWith("(")) name = name.substring(1, name.indexOf(')'));
                fields.put(name, new Field(typeNames.get(fieldType), offset, length, elements, pointer));
                offset = Math.addExact(offset, length);
            }
            require(offset == sizes[type], "SDNA layout mismatch for " + typeNames.get(type));
            Struct struct = new Struct(typeNames.get(type), sizes[type], Map.copyOf(fields));
            structs.add(struct);
            types.put(struct.name, struct);
        }
    }

    List<View> all(String type) {
        List<View> result = new ArrayList<>();
        for (Block block : blocks) {
            if (block.schema <= 0 || block.schema >= structs.size()) continue;
            Struct struct = structs.get(block.schema);
            if (!struct.name.equals(type)) continue;
            for (int i = 0; i < block.count; i++) result.add(new View(block, block.offset + i * struct.size, struct));
        }
        return result;
    }

    View view(long address) throws IOException {
        if (address == 0) return null;
        Block block = block(address, 1);
        require(block.schema > 0 && block.schema < structs.size(), "Pointer does not reference a structure");
        Struct struct = structs.get(block.schema);
        int offset = offset(block, address);
        require(struct.size > 0 && (offset - block.offset) % struct.size == 0
            && struct.size <= block.offset + block.length - offset, "Unaligned structure reference");
        return new View(block, offset, struct);
    }

    List<View> array(long address, int length) throws IOException {
        count(length);
        if (length == 0) return List.of();
        View first = view(address);
        require(first != null, "Missing structure array");
        block(address, Math.multiplyExact(length, first.struct.size));
        List<View> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) result.add(new View(first.block, first.offset + i * first.struct.size, first.struct));
        return result;
    }

    List<View> pointers(long address, int length) throws IOException {
        count(length);
        if (length == 0) return List.of();
        Block block = block(address, Math.multiplyExact(length, pointerSize));
        int start = offset(block, address);
        List<View> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) result.add(view(resolve(block, pointer(start + i * pointerSize))));
        return result;
    }

    byte[] data(long address, int length) throws IOException {
        if (length == 0) return new byte[0];
        ByteBuffer source = buffer(address, length);
        byte[] result = new byte[length];
        source.get(result);
        return result;
    }

    ByteBuffer buffer(long address, int length) throws IOException {
        Block block = block(address, length);
        int start = offset(block, address);
        return bytes.duplicate().position(start).limit(start + length).slice().order(bytes.order());
    }

    String string(long address) throws IOException {
        if (address == 0) return "";
        Block block = block(address, 1);
        return cstring(offset(block, address), block.offset + block.length);
    }

    private Block block(long address, int length) throws IOException {
        require(address != 0 && length >= 0, "Invalid data pointer");
        Map.Entry<Long, Block> entry = addresses.floorEntry(address);
        require(entry != null, "Unresolved data pointer");
        Block block = entry.getValue();
        long delta = address - block.address;
        require(delta >= 0 && delta <= block.length && length <= block.length - delta, "Data pointer exceeds block");
        return block;
    }

    private int offset(Block block, long address) { return block.offset + (int) (address - block.address); }
    private long resolve(Block owner, long original) throws IOException {
        if (original == 0) return 0;
        Block target = containing(scopes.get(owner.scope), original);
        if (target == null) target = containing(ids, original);
        require(target != null, "Unresolved serialized pointer in owner " + owner.scope);
        return target.address + original - target.original;
    }

    private static Block containing(NavigableMap<Long, Block> index, long original) {
        if (index == null) return null;
        Map.Entry<Long, Block> entry = index.floorEntry(original);
        if (entry == null) return null;
        Block candidate = entry.getValue();
        long delta = original - candidate.original;
        return delta >= 0 && delta < candidate.length ? candidate : null;
    }
    private long pointer(int offset) { return pointerSize == 8 ? bytes.getLong(offset) : Integer.toUnsignedLong(bytes.getInt(offset)); }
    private String ascii(int offset, int length) { return new String(bytes.array(), offset, length, StandardCharsets.US_ASCII); }

    private String cstring(int start, int end) throws IOException {
        int limit = start;
        while (limit < end && bytes.get(limit) != 0) limit++;
        require(limit < end, "Unterminated string");
        return new String(bytes.array(), start, limit - start, StandardCharsets.UTF_8);
    }

    private static void tag(ByteBuffer cursor, String expected) throws IOException {
        byte[] tag = new byte[4];
        cursor.get(tag);
        require(new String(tag, StandardCharsets.US_ASCII).equals(expected), "Missing SDNA " + expected);
    }

    private static List<String> strings(ByteBuffer cursor, int count) throws IOException {
        require(count <= cursor.remaining(), "Truncated SDNA string table");
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int start = cursor.position();
            require(cursor.hasRemaining(), "Truncated SDNA names");
            while (cursor.hasRemaining() && cursor.get() != 0) { }
            require(cursor.get(cursor.position() - 1) == 0, "Unterminated SDNA name");
            result.add(new String(cursor.array(), start, cursor.position() - start - 1, StandardCharsets.UTF_8));
        }
        return result;
    }

    private static void align(ByteBuffer cursor, int start) {
        cursor.position(Math.toIntExact(start + (((long) cursor.position() - start + 3) & ~3L)));
    }
    static int count(int value) throws IOException {
        require(value >= 0, "Negative element count");
        return value;
    }
    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    final class View {
        private final Block block;
        private final int offset;
        private final Struct struct;
        private View(Block block, int offset, Struct struct) { this.block = block; this.offset = offset; this.struct = struct; }
        long address() { return block.address + offset - block.offset; }
        String type() { return struct.name; }
        boolean has(String name) { return struct.fields.containsKey(name); }

        private Field field(String name) throws IOException {
            Field field = struct.fields.get(name);
            require(field != null, "Missing " + struct.name + "." + name + " in blend schema");
            return field;
        }
        String text(String name) throws IOException {
            Field field = field(name);
            return field.pointer ? string(ptr(name)) : cstring(offset + field.offset, offset + field.offset + field.length);
        }
        long ptr(String name) throws IOException {
            Field field = field(name);
            require(field.pointer, "Expected pointer " + name);
            return resolve(block, pointer(offset + field.offset));
        }
        View ref(String name) throws IOException { return view(ptr(name)); }
        View embedded(String name) throws IOException {
            return embedded(name, 0);
        }
        View embedded(String name, int index) throws IOException {
            Field field = field(name);
            Struct nested = types.get(field.type);
            require(!field.pointer && nested != null && index >= 0 && index < field.elements, "Expected nested structure " + name);
            return new View(block, offset + field.offset + index * nested.size, nested);
        }
        List<View> list(String name) throws IOException {
            List<View> result = new ArrayList<>();
            HashSet<Long> seen = new HashSet<>();
            View item = embedded(name).ref("first");
            while (item != null) {
                require(seen.add(item.address()), "Cyclic list " + name);
                result.add(item);
                item = (item.has("next") ? item : item.embedded("modifier")).ref("next");
            }
            return result;
        }
        int integer(String name) throws IOException { return Math.toIntExact(number(name)); }
        long number(String name) throws IOException {
            Field field = field(name);
            int at = offset + field.offset;
            require(!field.pointer && field.elements == 1, "Expected scalar " + name);
            return switch (field.length) {
                case 1 -> bytes.get(at);
                case 2 -> bytes.getShort(at);
                case 4 -> bytes.getInt(at);
                case 8 -> bytes.getLong(at);
                default -> throw new IOException("Invalid integer width for " + name);
            };
        }
        float scalar(String name) throws IOException { return floats(name, 1)[0]; }
        float[] floats(String name, int count) throws IOException {
            Field field = field(name);
            require(!field.pointer && count >= 0 && count <= field.length / 4, "Invalid float array " + name);
            float[] result = new float[count];
            for (int i = 0; i < count; i++) {
                result[i] = bytes.getFloat(offset + field.offset + i * 4);
                require(Float.isFinite(result[i]), "Non-finite value in " + struct.name + "." + name);
            }
            return result;
        }
        String idName() throws IOException {
            String value = embedded("id").text("name");
            return value.length() > 2 ? value.substring(2) : value;
        }
    }

    private record Block(String code, long address, long original, int scope, int offset, int length, int schema, int count) { }
    private record Struct(String name, int size, Map<String, Field> fields) { }
    private record Field(String type, int offset, int length, int elements, boolean pointer) { }
}
