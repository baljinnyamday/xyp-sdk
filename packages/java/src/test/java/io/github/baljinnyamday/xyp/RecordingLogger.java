package io.github.baljinnyamday.xyp;

import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/** A {@code System.Logger} that keeps what it is given, for assertions. */
final class RecordingLogger implements System.Logger {

  record Entry(Level level, String message, Throwable thrown) {}

  private final List<Entry> entries = new ArrayList<>();

  synchronized List<Entry> entries() {
    return List.copyOf(entries);
  }

  @Override
  public String getName() {
    return "recording";
  }

  @Override
  public boolean isLoggable(Level level) {
    return true;
  }

  @Override
  public synchronized void log(
      Level level, ResourceBundle bundle, String message, Throwable thrown) {
    entries.add(new Entry(level, message, thrown));
  }

  @Override
  public synchronized void log(
      Level level, ResourceBundle bundle, String format, Object... params) {
    entries.add(new Entry(level, params == null ? format : format + " " + List.of(params), null));
  }
}
