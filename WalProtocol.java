
import java.lang.foreign.*;
import java.lang.foreign.MemoryLayout.PathElement;
import static java.lang.foreign.ValueLayout.*;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

public class WalProtocol {

    private static final String MAGIC_NUMBER_KEY = "magicNumber";
    private static final String SEQUENCE_NUMBER_KEY = "sequenceNumber";
    private static final String PAYLOAD_SIZE_KEY = "payloadSize";
    public static final StructLayout HEADER_LAYOUT = MemoryLayout.structLayout(
            JAVA_LONG.withName(SEQUENCE_NUMBER_KEY).withOrder(ByteOrder.LITTLE_ENDIAN),
            JAVA_INT.withName(MAGIC_NUMBER_KEY),
            JAVA_INT.withName(PAYLOAD_SIZE_KEY)
    ).withByteAlignment(8);

    public static final long HEADER_SIZE = HEADER_LAYOUT.byteSize();
    public static final long CHECKSUM_SIZE = JAVA_INT.byteSize();
    public static final int MAGIC_NUMBER_VALUE = 0xCAFEBABE;

    // handles
    public static final VarHandle MAGIC_NUMBER_HANDLE = HEADER_LAYOUT.varHandle(PathElement.groupElement(MAGIC_NUMBER_KEY));
    public static final VarHandle SEQUENCE_NUMBER_HANDLE = HEADER_LAYOUT.varHandle(PathElement.groupElement(SEQUENCE_NUMBER_KEY));
    public static final VarHandle PAYLOAD_SIZE_HANDLE = HEADER_LAYOUT.varHandle(PathElement.groupElement(PAYLOAD_SIZE_KEY));

}
