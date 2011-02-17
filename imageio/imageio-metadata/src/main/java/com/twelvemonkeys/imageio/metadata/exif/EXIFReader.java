/*
 * Copyright (c) 2009, Harald Kuhr
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name "TwelveMonkeys" nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.twelvemonkeys.imageio.metadata.exif;

import com.twelvemonkeys.imageio.metadata.Directory;
import com.twelvemonkeys.imageio.metadata.Entry;
import com.twelvemonkeys.imageio.metadata.MetadataReader;
import com.twelvemonkeys.lang.StringUtil;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * EXIFReader
 *
 * @author <a href="mailto:harald.kuhr@gmail.com">Harald Kuhr</a>
 * @author last modified by $Author: haraldk$
 * @version $Id: EXIFReader.java,v 1.0 Nov 13, 2009 5:42:51 PM haraldk Exp$
 */
public final class EXIFReader extends MetadataReader {
    static final Collection<Integer> KNOWN_IFDS = Arrays.asList(TIFF.TAG_EXIF_IFD, TIFF.TAG_GPS_IFD, TIFF.TAG_INTEROP_IFD);

    @Override
    public Directory read(final ImageInputStream pInput) throws IOException {
        byte[] bom = new byte[2];
        pInput.readFully(bom);
        if (bom[0] == 'I' && bom[1] == 'I') {
            pInput.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        }
        else if (bom[0] == 'M' && bom[1] == 'M') {
            pInput.setByteOrder(ByteOrder.BIG_ENDIAN);
        }
        else  {
            throw new IIOException(String.format("Invalid TIFF byte order mark '%s', expected: 'II' or 'MM'", StringUtil.decode(bom, 0, bom.length, "ASCII")));
        }

        // TODO: BigTiff uses version 43 instead of TIFF's 42, and header is slightly different, see
        // http://www.awaresystems.be/imaging/tiff/bigtiff.html
        int magic = pInput.readUnsignedShort();
        if (magic != TIFF.TIFF_MAGIC) {
            throw new IIOException(String.format("Wrong TIFF magic in EXIF data: %04x, expected: %04x", magic,  TIFF.TIFF_MAGIC));
        }

        long directoryOffset = pInput.readUnsignedInt();

        return readDirectory(pInput, directoryOffset);
    }

    private EXIFDirectory readDirectory(final ImageInputStream pInput, final long pOffset) throws IOException {
        List<Entry> entries = new ArrayList<Entry>();
        pInput.seek(pOffset);
        int entryCount = pInput.readUnsignedShort();

        for (int i = 0; i < entryCount; i++) {
            entries.add(readEntry(pInput));
        }

        long nextOffset = pInput.readUnsignedInt();

        // Read linked IFDs
        if (nextOffset != 0) {
            EXIFDirectory next = readDirectory(pInput, nextOffset);

            for (Entry entry : next) {
                entries.add(entry);
            }
        }

        // TODO: Make what sub-IFDs to parse optional? Or leave this to client code? At least skip the non-TIFF data?
        readSubdirectories(pInput, entries,
                Arrays.asList(TIFF.TAG_EXIF_IFD, TIFF.TAG_GPS_IFD, TIFF.TAG_INTEROP_IFD
//                        , TIFF.TAG_IPTC, TIFF.TAG_XMP
//                        , TIFF.TAG_ICC_PROFILE
//                        , TIFF.TAG_PHOTOSHOP
//                        ,TIFF.TAG_MODI_OLE_PROPERTY_SET
                )
        );

        return new EXIFDirectory(entries);
    }

//    private Directory readForeignMetadata(final MetadataReader reader, final byte[] bytes) throws IOException {
//        return reader.read(ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)));
//    }

    // TODO: Might be better to leave this for client code, as it's tempting go really overboard and support any possible embedded format..
    private void readSubdirectories(ImageInputStream input, List<Entry> entries, List<Integer> subIFDs) throws IOException {
        if (subIFDs == null || subIFDs.isEmpty()) {
            return;
        }

        for (int i = 0, entriesSize = entries.size(); i < entriesSize; i++) {
            EXIFEntry entry = (EXIFEntry) entries.get(i);
            int tagId = (Integer) entry.getIdentifier();

            if (subIFDs.contains(tagId)) {
                try {
                    Object directory;

                    /*
                    if (tagId == TIFF.TAG_IPTC) {
                        directory = readForeignMetadata(new IPTCReader(), (byte[]) entry.getValue());
                    }
                    else if (tagId == TIFF.TAG_XMP) {
                        directory = readForeignMetadata(new XMPReader(), (byte[]) entry.getValue());
                    }
                    else if (tagId == TIFF.TAG_PHOTOSHOP) {
                        // TODO: This is waaay too fragile.. Might need registry-based meta data parsers?
                        try {
                            Class cl = Class.forName("com.twelvemonkeys.imageio.plugins.psd.PSDImageResource");
                            Method method = cl.getMethod("read", ImageInputStream.class);
                            method.setAccessible(true);
                            directory = method.invoke(null, ImageIO.createImageInputStream(new ByteArrayInputStream((byte[]) entry.getValue())));
                        }
                        catch (Exception ignore) {
                            continue;
                        }
                    }
                    else if (tagId == TIFF.TAG_ICC_PROFILE) {
                        directory = ICC_Profile.getInstance((byte[]) entry.getValue());
                    }
                    else if (tagId == TIFF.TAG_MODI_OLE_PROPERTY_SET) {
                        // TODO: Encapsulate in something more useful?
                        directory = new CompoundDocument(new ByteArrayInputStream((byte[]) entry.getValue())).getRootEntry();
                    }
                    else*/ if (KNOWN_IFDS.contains(tagId)) {
                        directory = readDirectory(input, getPointerOffset(entry));
                    }
                    else {
                        continue;
                    }

                    // Replace the entry with parsed data
                    entries.set(i, new EXIFEntry(tagId, directory, entry.getType()));
                }
                catch (IIOException e) {
                    // TODO: Issue warning without crashing...?
                    e.printStackTrace();
                }
            }
        }
    }

    private long getPointerOffset(final Entry entry) throws IIOException {
        long offset;
        Object value = entry.getValue();

        if (value instanceof Byte) {
            offset = ((Byte) value & 0xff);
        }
        else if (value instanceof Short) {
            offset = ((Short) value & 0xffff);
        }
        else if (value instanceof Integer) {
            offset = ((Integer) value & 0xffffffffL);
        }
        else if (value instanceof Long) {
            offset = (Long) value;
        }
        else {
            throw new IIOException(String.format("Unknown pointer type: %s", (value != null ? value.getClass() : null)));
        }

        return offset;
    }

    private EXIFEntry readEntry(final ImageInputStream pInput) throws IOException {
        // TODO: BigTiff entries are different
        int tagId = pInput.readUnsignedShort();

        short type = pInput.readShort();
        int count = pInput.readInt(); // Number of values

        if (count < 0) {
            throw new IIOException(String.format("Illegal count %d for tag %s type %s @%08x", count, tagId, type, pInput.getStreamPosition()));
        }

        Object value;
        int valueLength = getValueLength(type, count);

        if (type < 0 || type > 13) {
            // Invalid tag, this is just for debugging
            System.err.printf("offset: %08x%n", pInput.getStreamPosition() - 8l);
            System.err.println("tagId: " + tagId);
            System.err.println("type: " + type + " (INVALID)");
            System.err.println("count: " + count);

            pInput.mark();
            pInput.seek(pInput.getStreamPosition() - 8);

            try {
                byte[] bytes = new byte[8 + Math.max(20, valueLength)];
                pInput.readFully(bytes);

                System.err.print("data: " + HexDump.dump(bytes));
                System.err.println(bytes.length < valueLength ? "..." : "");
            }
            finally {
                pInput.reset();
            }
        }

        // TODO: For BigTiff allow size <= 8
        if (valueLength > 0 && valueLength <= 4) {
            value = readValueInLine(pInput, type, count);
            pInput.skipBytes(4 - valueLength);
        }
        else {
            long valueOffset = pInput.readUnsignedInt(); // This is the *value* iff the value size is <= 4 bytes
            value = readValueAt(pInput, valueOffset, type, count);
        }

        return new EXIFEntry(tagId, value, type);
    }

    private Object readValueAt(final ImageInputStream pInput, final long pOffset, final short pType, final int pCount) throws IOException {
        long pos = pInput.getStreamPosition();
        try {
            pInput.seek(pOffset);
            return readValue(pInput, pType, pCount);
        }
        finally {
            pInput.seek(pos);
        }
    }

    private Object readValueInLine(final ImageInputStream pInput, final short pType, final int pCount) throws IOException {
        return readValue(pInput, pType, pCount);
    }

    private static Object readValue(final ImageInputStream pInput, final short pType, final int pCount) throws IOException {
        // TODO: Review value "widening" for the unsigned types. Right now it's inconsistent. Should we leave it to client code?

        long pos = pInput.getStreamPosition();

        switch (pType) {
            case 2: // ASCII
                // TODO: This might be UTF-8 or ISO-8859-x, even though spec says ASCII
                byte[] ascii = new byte[pCount];
                pInput.readFully(ascii);
                return StringUtil.decode(ascii, 0, ascii.length, "UTF-8"); // UTF-8 is ASCII compatible
            case 1: // BYTE
                if (pCount == 1) {
                    return pInput.readUnsignedByte();
                }
                // else fall through
            case 6: // SBYTE
                if (pCount == 1) {
                    return pInput.readByte();
                }
                // else fall through
            case 7: // UNDEFINED
                byte[] bytes = new byte[pCount];
                pInput.readFully(bytes);

                // NOTE: We don't change (unsigned) BYTE array wider Java type, as most often BYTE array means
                // binary data and we want to keep that as a byte array for clients to parse futher

                return bytes;
            case 3: // SHORT
                if (pCount == 1) {
                    return pInput.readUnsignedShort();
                }
            case 8: // SSHORT
                if (pCount == 1) {
                    return pInput.readShort();
                }

                short[] shorts = new short[pCount];
                pInput.readFully(shorts, 0, shorts.length);

                if (pType == 3) {
                    int[] ints = new int[pCount];
                    for (int i = 0; i < pCount; i++) {
                        ints[i] = shorts[i] & 0xffff;
                    }
                    return ints;
                }

                return shorts;
            case 13: // IFD
            case 4: // LONG
                if (pCount == 1) {
                    return pInput.readUnsignedInt();
                }
            case 9: // SLONG
                if (pCount == 1) {
                    return pInput.readInt();
                }

                int[] ints = new int[pCount];
                pInput.readFully(ints, 0, ints.length);

                if (pType == 4 || pType == 13) {
                    long[] longs = new long[pCount];
                    for (int i = 0; i < pCount; i++) {
                        longs[i] = ints[i] & 0xffffffffL;
                    }
                    return longs;
                }

                return ints;
            case 11: // FLOAT
                if (pCount == 1) {
                    return pInput.readFloat();
                }

                float[] floats = new float[pCount];
                pInput.readFully(floats, 0, floats.length);
                return floats;
            case 12: // DOUBLE
                if (pCount == 1) {
                    return pInput.readDouble();
                }

                double[] doubles = new double[pCount];
                pInput.readFully(doubles, 0, doubles.length);
                return doubles;

            case 5: // RATIONAL
                if (pCount == 1) {
                    return new Rational(pInput.readUnsignedInt(), pInput.readUnsignedInt());
                }

                Rational[] rationals = new Rational[pCount];
                for (int i = 0; i < rationals.length; i++) {
                    rationals[i] = new Rational(pInput.readUnsignedInt(), pInput.readUnsignedInt());
                }

                return rationals;
            case 10: // SRATIONAL
                if (pCount == 1) {
                    return new Rational(pInput.readInt(), pInput.readInt());
                }

                Rational[] srationals = new Rational[pCount];
                for (int i = 0; i < srationals.length; i++) {
                    srationals[i] = new Rational(pInput.readInt(), pInput.readInt());
                }

                return srationals;

            // BigTiff:
            case 16: // LONG8
            case 17: // SLONG8
            case 18: // IFD8
                // TODO: Assert BigTiff (version == 43)

                if (pCount == 1) {
                    long val = pInput.readLong();
                    if (pType != 17 && val < 0) {
                        throw new IIOException(String.format("Value > %s", Long.MAX_VALUE));
                    }
                    return val;
                }

                long[] longs = new long[pCount];
                for (int i = 0; i < pCount; i++) {
                    longs[i] = pInput.readLong();
                }

                return longs;

            default:
                // Spec says skip unknown values:
                // TODO: Rather just return null, UNKNOWN_TYPE or new Unknown(type, count, offset) for value?
                return new Unknown(pType, pCount, pos);
//                throw new IIOException(String.format("Unknown EXIF type '%s' at pos %d", pType, pInput.getStreamPosition()));
        }
    }

    private int getValueLength(final int pType, final int pCount) {
        if (pType > 0 && pType <= TIFF.TYPE_LENGTHS.length) {
            return TIFF.TYPE_LENGTHS[pType - 1] * pCount;
        }

        return -1;
    }

    public static void main(String[] args) throws IOException {
        EXIFReader reader = new EXIFReader();
        ImageInputStream stream = ImageIO.createImageInputStream(new File(args[0]));

        long pos = 0;
        if (args.length > 1) {
            if (args[1].startsWith("0x")) {
                pos = Integer.parseInt(args[1].substring(2), 16);
            }
            else {
                pos = Long.parseLong(args[1]);
            }

            stream.setByteOrder(pos < 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

            pos = Math.abs(pos);

            stream.seek(pos);
        }

        try {
            Directory directory;

            if (args.length > 1) {
                directory = reader.readDirectory(stream, pos);
            }
            else {
                directory = reader.read(stream);
            }

            for (Entry entry : directory) {
                System.err.println(entry);

                Object value = entry.getValue();
                if (value instanceof byte[]) {
                    byte[] bytes = (byte[]) value;
                    System.err.println(HexDump.dump(bytes, 0, Math.min(bytes.length, 128)));
                }
            }
        }
        finally {
            stream.close();
        }
    }

    //////////////////////
    // TODO: Stream based hex dump util?
    public static class HexDump {
        private HexDump() {}

        private static final int WIDTH = 32;

        public static String dump(byte[] bytes) {
            return dump(bytes, 0, bytes.length);
        }

        public static String dump(byte[] bytes, int off, int len) {
            StringBuilder builder = new StringBuilder();

            int i;
            for (i = 0; i < len; i++) {
                if (i % WIDTH == 0) {
                    if (i > 0 ) {
                        builder.append("\n");
                    }
                    builder.append(String.format("%08x: ", i + off));
                }
                else if (i > 0 && i % 2 == 0) {
                    builder.append(" ");
                }

                builder.append(String.format("%02x", bytes[i + off]));

                int next = i + 1;
                if (next % WIDTH == 0 || next == len) {
                    int leftOver = (WIDTH - (next % WIDTH)) % WIDTH;

                    if (leftOver != 0) {
                        // Pad: 5 spaces for every 2 bytes... Special care if padding is non-even.
                        int pad = leftOver / 2;

                        if (len % 2 != 0) {
                            builder.append("  ");
                        }

                        for (int j = 0; j < pad; j++) {
                            builder.append("     ");
                        }
                    }

                    builder.append("  ");
                    builder.append(toAsciiString(bytes, next - (WIDTH - leftOver) + off, next + off));
                }
            }

            return builder.toString();
        }

        private static String toAsciiString(final byte[] bytes, final int from, final int to) {
            byte[] range = Arrays.copyOfRange(bytes, from, to);

            for (int i = 0; i < range.length; i++) {
                if (range[i] < 32 || range[i] > 126) {
                    range[i] = '.'; // Unreadable char
                }
            }

            return new String(range, Charset.forName("ascii"));
        }
    }
}