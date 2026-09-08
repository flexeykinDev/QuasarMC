package dev.quasar.world.light;

import java.util.Arrays;

/**
 * Sky and block light for one chunk, as the nibble arrays the protocol expects.
 *
 * <h2>Why sections are lazy</h2>
 *
 * <p>Light is 4 bits per block. A 16x16x16 section is 2048 bytes, and a chunk covering -64..319 has
 * 24 sections plus one above and one below, for both sky and block: about 106 KB per chunk if every
 * array is allocated. A few thousand loaded chunks would be hundreds of megabytes of mostly
 * identical bytes -- pitch black underground, full daylight above.
 *
 * <p>So a section holds either a real array or a single uniform value, and only materialises when
 * something actually varies inside it. In a generated world that is the handful of sections around
 * the surface and around any light source; everything else stays a single byte.
 */
public final class LightStorage {

    /** Bytes in one section's nibble array: 16*16*16 blocks at two blocks per byte. */
    public static final int SECTION_BYTES = 2048;

    public static final int MAX_LEVEL = 15;

    /** A section that is entirely one value; {@code data} is materialised only when one differs. */
    private static final class Layer {
        byte[] data;
        byte uniform;

        Layer(int uniform) {
            this.uniform = (byte) uniform;
        }

        int get(int index) {
            if (data == null) {
                return uniform;
            }
            int b = data[index >> 1] & 0xFF;
            return (index & 1) == 0 ? (b & 0x0F) : (b >> 4);
        }

        void set(int index, int value) {
            if (data == null) {
                if (value == uniform) {
                    return;
                }
                materialise();
            }
            int byteIndex = index >> 1;
            int b = data[byteIndex] & 0xFF;
            if ((index & 1) == 0) {
                data[byteIndex] = (byte) ((b & 0xF0) | (value & 0x0F));
            } else {
                data[byteIndex] = (byte) ((b & 0x0F) | ((value & 0x0F) << 4));
            }
        }

        private void materialise() {
            data = new byte[SECTION_BYTES];
            if (uniform != 0) {
                byte packed = (byte) ((uniform & 0x0F) | ((uniform & 0x0F) << 4));
                Arrays.fill(data, packed);
            }
        }

        boolean isEmpty() {
            return data == null && uniform == 0;
        }
    }

    /**
     * Light sections, indexed from one below the world to one above it.
     *
     * <p>The client expects those two extra sections: light spills out of the top and bottom of the
     * world, and omitting them leaves a visible seam at the build limits.
     */
    private final Layer[] sky;
    private final Layer[] block;
    private final int minY;

    public LightStorage(int worldSectionCount, int minY) {
        int count = worldSectionCount + 2;
        this.minY = minY;
        this.sky = new Layer[count];
        this.block = new Layer[count];
        for (int i = 0; i < count; i++) {
            // Sky starts dark rather than lit. Filling it with daylight and then carving out the
            // ground would mean touching every section of every chunk; starting dark means the
            // engine only writes where light actually reaches.
            sky[i] = new Layer(0);
            block[i] = new Layer(0);
        }
    }

    public int sectionCount() {
        return sky.length;
    }

    /** Number of light sections; index 0 sits below the world. */
    private int layerIndex(int y) {
        return ((y - minY) >> 4) + 1;
    }

    private static int blockIndex(int localX, int y, int localZ) {
        return ((y & 15) << 8) | ((localZ & 15) << 4) | (localX & 15);
    }

    public int sky(int localX, int y, int localZ) {
        int layer = layerIndex(y);
        if (layer < 0 || layer >= sky.length) {
            return 0;
        }
        return sky[layer].get(blockIndex(localX, y, localZ));
    }

    public void setSky(int localX, int y, int localZ, int value) {
        int layer = layerIndex(y);
        if (layer < 0 || layer >= sky.length) {
            return;
        }
        sky[layer].set(blockIndex(localX, y, localZ), value);
    }

    public int block(int localX, int y, int localZ) {
        int layer = layerIndex(y);
        if (layer < 0 || layer >= block.length) {
            return 0;
        }
        return block[layer].get(blockIndex(localX, y, localZ));
    }

    public void setBlock(int localX, int y, int localZ, int value) {
        int layer = layerIndex(y);
        if (layer < 0 || layer >= block.length) {
            return;
        }
        block[layer].set(blockIndex(localX, y, localZ), value);
    }

    /** Fills a whole light section with one value, without allocating anything. */
    public void fillSkySection(int layer, int value) {
        if (layer >= 0 && layer < sky.length) {
            sky[layer].data = null;
            sky[layer].uniform = (byte) value;
        }
    }

    public boolean isSkySectionEmpty(int layer) {
        return sky[layer].isEmpty();
    }

    public boolean isBlockSectionEmpty(int layer) {
        return block[layer].isEmpty();
    }

    /**
     * The 2048-byte array for one sky section, materialising it if it was uniform.
     *
     * <p>Only for writing packets: the protocol has a mask for "this section is all zero" but none
     * for "all fifteen", so a uniformly lit section still has to go out as real bytes.
     */
    public byte[] skyBytes(int layer) {
        return bytesOf(sky[layer]);
    }

    public byte[] blockBytes(int layer) {
        return bytesOf(block[layer]);
    }

    private static byte[] bytesOf(Layer layer) {
        if (layer.data == null) {
            byte packed = (byte) ((layer.uniform & 0x0F) | ((layer.uniform & 0x0F) << 4));
            byte[] out = new byte[SECTION_BYTES];
            if (packed != 0) {
                Arrays.fill(out, packed);
            }
            return out;
        }
        return layer.data;
    }

    /** Approximate retained size, for the memory reporting that justified the lazy layers. */
    public int allocatedSections() {
        int count = 0;
        for (Layer layer : sky) {
            if (layer.data != null) {
                count++;
            }
        }
        for (Layer layer : block) {
            if (layer.data != null) {
                count++;
            }
        }
        return count;
    }
}
