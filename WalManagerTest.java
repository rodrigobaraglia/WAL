
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public class WalManagerTest {

    public static void testAppend() throws IOException, InterruptedException, IllegalStateException {
        var filepath = "testlog";
        var walManager = new WalManager(Path.of(filepath), 1024);
        for (int i = 0; i < 10; i++) {
            var payload = String.format("appending entry number %d", i + 1);
            var position = walManager.append(payload.getBytes());
            System.out.format("returned position: %d\n", position);
            System.out.format("current position: %d\n", walManager.position());
        }

        System.out.println("--- HEXDUMP ---");
        new ProcessBuilder("hexdump", "-C", filepath).inheritIO().start().waitFor();
        new ProcessBuilder("rm", filepath).inheritIO().start().waitFor();
    }

    public static void testAppendToPreexistingValidFile() throws IOException, InterruptedException, IllegalStateException {
        var filepath = "testlog2";
        for (int i = 0; i < 10; i++) {
            var walManager = new WalManager(Path.of(filepath), 1024);
            var payload = String.format("appending entry number %d", i + 1);
            var position = walManager.append(payload.getBytes());
            System.out.format("returned position: %d\n", position);
            System.out.format("current position: %d\n", walManager.position());

        }
        System.out.println("--- HEXDUMP ---");
        new ProcessBuilder("hexdump", "-C", filepath).inheritIO().start().waitFor();
        new ProcessBuilder("rm", filepath).inheritIO().start().waitFor();
    }

    public static void testAppendConcurrently() throws IOException, InterruptedException {
        var filepath = "testlog3";
        var walManager = new WalManager(Path.of(filepath), 1024 * 1024);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1000; i++) {
                final var threadId = (i + 1);
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < 10; j++) {
                            var payload = String.format("Writer %d appending entry number %d", threadId, (threadId * 10) + j + 1);
                            var position = walManager.append(payload.getBytes());
                            var positionMessage = String.format("Writer %d returned position: %d\nWriter %d current position: %d\n",
                                    threadId, position, threadId, walManager.position());
                            System.out.println(positionMessage);
                        }
                    } catch (Exception e) {
                        System.err.println("Error in thread " + threadId + ": " + e.getMessage());
                    }
                });
            }
        }

        System.out.println("--- HEXDUMP ---");
        new ProcessBuilder("hexdump", "-C", filepath).inheritIO().start().waitFor();
        new ProcessBuilder("rm", filepath).inheritIO().start().waitFor();
    }
}
