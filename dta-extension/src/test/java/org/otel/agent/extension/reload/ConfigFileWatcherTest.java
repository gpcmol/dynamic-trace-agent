package org.otel.agent.extension.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.otel.agent.extension.diag.AgentLog;

class ConfigFileWatcherTest {

  @TempDir Path directory;

  private final List<String> events = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();
  private final Handler capture =
      new Handler() {
        @Override
        public void publish(final LogRecord record) {
          warnings.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  private Path file;
  private ConfigFileWatcher watcher;

  @BeforeEach
  void setUp() {
    Logger.getLogger("org.otel.agent").addHandler(capture);
    file = directory.resolve("agent-config.xml");
    watcher =
        new ConfigFileWatcher(
            file,
            new ConfigFileWatcher.Listener() {
              @Override
              public void contentChanged(final String content) {
                events.add("content:" + content);
              }

              @Override
              public void fileMissing() {
                events.add("missing");
              }
            },
            new AgentLog(false));
  }

  @AfterEach
  void tearDown() {
    Logger.getLogger("org.otel.agent").removeHandler(capture);
  }

  private void write(final String content, final long epochSecond) throws IOException {
    Files.writeString(file, content);
    Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(epochSecond)));
  }

  @Test
  void deliversNewContent() throws Exception {
    write("<configuration/>", 1);

    watcher.poll();

    assertEquals(List.of("content:<configuration/>"), events);
  }

  @Test
  void unchangedTimeAndSizeIsNotReadAgain() throws Exception {
    write("<a/>", 1);
    watcher.poll();
    write("<b/>", 1); // same size and modification time: treated as unchanged

    watcher.poll();

    assertEquals(List.of("content:<a/>"), events);
  }

  @Test
  void touchedFileWithSameContentIsNotDeliveredAgain() throws Exception {
    write("<a/>", 1);
    watcher.poll();
    write("<a/>", 2);

    watcher.poll();

    assertEquals(1, events.size());
  }

  @Test
  void changedFileIsDelivered() throws Exception {
    write("<a/>", 1);
    watcher.poll();
    write("<bb/>", 2);

    watcher.poll();

    assertEquals(List.of("content:<a/>", "content:<bb/>"), events);
  }

  @Test
  void missingFileIsReportedOnceAndRecovered() throws Exception {
    watcher.poll();
    watcher.poll();
    write("<a/>", 1);
    watcher.poll();

    assertEquals(List.of("missing", "content:<a/>"), events);
  }

  @Test
  void deletedFileDisables() throws Exception {
    write("<a/>", 1);
    watcher.poll();
    Files.delete(file);

    watcher.poll();

    assertEquals(List.of("content:<a/>", "missing"), events);
  }

  @Test
  void unreadableFileKeepsConfigurationWarnsOnceAndRecovers() throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    write("<a/>", 1);
    watcher.poll();
    write("<bb/>", 2);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
    assumeTrue(!Files.isReadable(file), "running as a user that can read anything");

    watcher.poll();
    watcher.poll();
    watcher.poll();

    assertEquals(List.of("content:<a/>"), events, "current configuration stays");
    assertEquals(1, warnings.stream().filter(w -> w.contains("cannot be read")).count());

    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    watcher.poll();

    assertEquals(List.of("content:<a/>", "content:<bb/>"), events);
  }

  @Test
  void oversizedFileIsNotLoadedAndKeepsTheConfiguration() throws Exception {
    write("<a/>", 1);
    watcher.poll();
    write("<!--" + "x".repeat(ConfigFileWatcher.MAX_FILE_BYTES) + "--><b/>", 2);

    watcher.poll();
    watcher.poll();

    assertEquals(List.of("content:<a/>"), events, "current configuration stays");
    assertEquals(1, warnings.stream().filter(w -> w.contains("larger than")).count());

    write("<bb/>", 3);
    watcher.poll();

    assertEquals(List.of("content:<a/>", "content:<bb/>"), events);
  }

  @Test
  void directoryInsteadOfFileIsNotRead() throws Exception {
    Files.createDirectory(file);

    watcher.poll();

    assertEquals(List.of(), events);
    assertEquals(1, warnings.stream().filter(w -> w.contains("not a regular file")).count());
  }

  @Test
  void malformedUtf8IsNotLoaded() throws Exception {
    Files.write(file, new byte[] {'<', 'a', (byte) 0xC3, '/', '>'});

    watcher.poll();

    assertEquals(List.of(), events);
    assertEquals(1, warnings.stream().filter(w -> w.contains("cannot be read")).count());
  }
}
