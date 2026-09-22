/**
 * A modular consumer of the SDK. Compiling this and running with {@code -m} proves the SDK's {@code
 * module-info} exports what a user needs, and that its bundled certificates load from inside the
 * named module.
 */
module consumercheck {
  requires io.github.baljinnyamday.xyp;
}
