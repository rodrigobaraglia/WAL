
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

public class WalManager implements AutoCloseable {

    private final MemorySegment segment;
    private final Arena arena;
    /**
     * tail is a global cursor pointing to the last datum in the log file
     */
    private volatile long position = 0;

    /**
     * thread safe tail handle, it allows each thread to compute the required
     * offset for the next write (Header + Payload + Checksum + Padding) and add
     * it atomically to tail
     */
    private static final VarHandle POSITION_HANDLE;
    /**
     * each thread gets an instanc of a crc holder to reduce allocation
     */
    private static final ThreadLocal<CRC32C> THREAD_LOCAL_CRC = ThreadLocal.withInitial(CRC32C::new);
    private static final ThreadLocal<ByteBuffer> THREAD_LOCAL_BYTE_BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN));

    static {
        try {
            POSITION_HANDLE = MethodHandles.lookup().findVarHandle(WalManager.class, "position", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public WalManager(Path path, long maxLogSize) throws IOException, IllegalStateException {
        this.arena = Arena.ofShared();

        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE)) {

            this.segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, maxLogSize, this.arena);
            this.recoverPosition();
        }
    }

    public long position() {
        return (long) POSITION_HANDLE.getAcquire(this);
    }

    long alignEntrySize(long entrySize) {
        return (entrySize + 7) & ~7;
    }

    /**
     * thread safe, zero allocation, cache locality friendly computation of
     * CRC32C checksum using payload and sequence number
     */
    private int computeCrc(MemorySegment source, long payloadOffset, int payloadSize, long sequenceNumber) {
        CRC32C crc = THREAD_LOCAL_CRC.get();
        ByteBuffer buffer = THREAD_LOCAL_BYTE_BUFFER.get();
        buffer.clear();
        buffer.putLong(sequenceNumber);
        buffer.flip();
        crc.reset();
        crc.update(buffer);
        crc.update(source.asSlice(payloadOffset, payloadSize).asByteBuffer());
        crc.update(((int) payloadOffset));
        return (int) crc.getValue();
    }

    private void writeToSegment(byte[] data, long headerOffset) {
        var payloadSize = data.length;
        var payloadOffset = headerOffset + WalProtocol.HEADER_SIZE;
        var checksumOffset = payloadOffset + payloadSize;
        var sequence = headerOffset;
        // write payload
        MemorySegment.copy(data, 0, this.segment, ValueLayout.JAVA_BYTE, payloadOffset, payloadSize);
        // write checksum
        var checksum = this.computeCrc(this.segment, payloadOffset, payloadSize, sequence);
        this.segment.set(ValueLayout.JAVA_INT_UNALIGNED, checksumOffset, checksum);
        // write header
        WalProtocol.MAGIC_NUMBER_HANDLE.set(this.segment, headerOffset, WalProtocol.MAGIC_NUMBER_VALUE);
        WalProtocol.PAYLOAD_SIZE_HANDLE.set(this.segment, headerOffset, payloadSize);
        // commit by writing sequence number
        WalProtocol.SEQUENCE_NUMBER_HANDLE.setRelease(this.segment, headerOffset, sequence);
    }

    /**
     *
     *
     * @return long
     */
    public long append(byte[] payload) {
        // compute sizes
        int payloadSize = payload.length;
        long entrySize = WalProtocol.HEADER_SIZE + payloadSize + WalProtocol.CHECKSUM_SIZE;
        long alignedSize = alignEntrySize(entrySize);

        // compute offsets
        long headerOffset = (long) POSITION_HANDLE.getAndAdd(this, alignedSize);
        if (headerOffset + alignedSize > this.segment.byteSize()) {
            throw new IllegalStateException("WAL Capacity Exceeded");
        }

        writeToSegment(payload, headerOffset);

        return headerOffset;
    }

    /**
     * recoverPosition scans the mapped source file for the last written byte
     * and directly sets the volatile tail to its offset, this is safe as long
     * as recoverTail is only called within the constructor, while the program
     * is single threaded
     */
    private void recoverPosition() throws IllegalStateException {
        long currentOffset = 0;
        // scan while there's space for at least one header
        while (currentOffset + WalProtocol.HEADER_SIZE <= this.segment.byteSize()) {
            int magicNumber = (int) WalProtocol.MAGIC_NUMBER_HANDLE.get(this.segment, currentOffset);
            if (magicNumber == 0) {
                break;
            }
            if (magicNumber != WalProtocol.MAGIC_NUMBER_VALUE) {
                var message = String.format("magic nymbers are not equal, expected %d got %d", WalProtocol.MAGIC_NUMBER_VALUE, magicNumber);
                throw new IllegalStateException(message);
            }

            long sequence = (long) WalProtocol.SEQUENCE_NUMBER_HANDLE.get(this.segment, currentOffset);
            int payloadSize = (int) WalProtocol.PAYLOAD_SIZE_HANDLE.get(this.segment, currentOffset);
            long payloadOffset = currentOffset + WalProtocol.HEADER_SIZE;
            long checksumOffset = payloadOffset + payloadSize;
            long entrySize = WalProtocol.HEADER_SIZE + payloadSize + WalProtocol.CHECKSUM_SIZE;
            long alignedEntrySize = alignEntrySize(entrySize);
            if (currentOffset + alignedEntrySize > this.segment.byteSize()) {
                System.out.println("entry out of bounds by " + (currentOffset + alignedEntrySize - this.segment.byteSize()));
                break;
            }
            // validate checksum
            var computedCrc = this.computeCrc(this.segment, payloadOffset, payloadSize, sequence);
            var storedCrc = this.segment.get(ValueLayout.JAVA_INT_UNALIGNED, checksumOffset);

            if (computedCrc != storedCrc) {
                System.out.println("checksum invalid: " + computedCrc + " != " + storedCrc);
                break;
            }

            currentOffset += alignedEntrySize;

        }

        // it's safe to write to tail directly here as long as this method is only called inside the constructor
        this.position = currentOffset;
    }

    public void sync() {
        segment.force();
    }

    @Override
    public void close() {
        this.sync();
        if (this.arena.scope().isAlive()) {
            this.arena.close();
        }
    }

}
