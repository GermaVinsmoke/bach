package org.astro;

public class WorldTester {

  public static void main(String... args) {
    if (World.answer != 42) {
      throw new AssertionError("expected 42, but was " + World.answer);
    }
  }
}
