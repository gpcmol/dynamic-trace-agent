package org.otel.agent.extension.reload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import org.otel.agent.extension.diag.AgentLog;

/**
 * Checks the configuration file once per {@link #poll()} (spec {@code runtime-reload}).
 *
 * <pre>
 *   file missing                 → listener.fileMissing()  (once per absence)
 *   exists but cannot be read    → keep current config, one warning per episode
 *   modification time and size   → not read at all
 *     unchanged
 *   readable, content changed    → listener.contentChanged(text)
 * </pre>
 *
 * Kubernetes ConfigMap mounts replace the file by an atomic symlink swap; following the symlink
 * shows the new file's time and size, so updates are seen on the next poll.
 *
 * <p>Not thread-safe: called by the single poller thread only.
 */
public final class ConfigFileWatcher {

  /** Receives what the watcher found. */
  public interface Listener {
    void contentChanged(String content);

    void fileMissing();
  }

  private final Path file;
  private final Listener listener;
  private final AgentLog log;

  private FileTime lastModified;
  private long lastSize = -1;
  private String lastContent;
  private boolean missing;
  private boolean unreadableReported;

  public ConfigFileWatcher(final Path file, final Listener listener, final AgentLog log) {
    this.file = Objects.requireNonNull(file, "file");
    this.listener = Objects.requireNonNull(listener, "listener");
    this.log = Objects.requireNonNull(log, "log");
  }

  public Path file() {
    return file;
  }

  public void poll() {
    final BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(file, BasicFileAttributes.class);
    } catch (NoSuchFileException absent) {
      onMissing();
      return;
    } catch (IOException | RuntimeException unreadable) {
      onUnreadable(unreadable);
      return;
    }
    if (!missing
        && attributes.lastModifiedTime().equals(lastModified)
        && attributes.size() == lastSize) {
      return;
    }
    final String content;
    try {
      content = Files.readString(file, StandardCharsets.UTF_8);
    } catch (NoSuchFileException removedMeanwhile) {
      onMissing();
      return;
    } catch (IOException | RuntimeException unreadable) {
      onUnreadable(unreadable);
      return;
    }
    missing = false;
    unreadableReported = false;
    lastModified = attributes.lastModifiedTime();
    lastSize = attributes.size();
    if (!content.equals(lastContent)) {
      lastContent = content;
      listener.contentChanged(content);
    }
  }

  private void onMissing() {
    if (!missing) {
      missing = true;
      lastContent = null;
      lastModified = null;
      lastSize = -1;
      log.warn("configuration file " + file + " does not exist: enrichment is disabled");
      listener.fileMissing();
    }
  }

  /** Keeps the current configuration; warns once until the file is readable again. */
  private void onUnreadable(final Exception cause) {
    if (!unreadableReported) {
      unreadableReported = true;
      log.warn(
          "configuration file "
              + file
              + " cannot be read ("
              + cause
              + "); keeping the current configuration and retrying");
    }
  }
}
