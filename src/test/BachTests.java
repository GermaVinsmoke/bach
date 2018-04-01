/*
 * Bach - Java Shell Builder
 * Copyright (C) 2018 Christian Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class BachTests {

  private final List<String> actualLogLines = new CopyOnWriteArrayList<>();
  private final Bach bach = createBach(actualLogLines);

  private Bach createBach(List<String> lines) {
    var bach = new Bach();
    bach.debug = true;
    bach.quiet = false;
    bach.offline = false;
    bach.logger = lines::add;
    return bach;
  }

  @Test
  void log() {
    bach.log("log %s", "1");
    bach.quiet = true;
    bach.log("log %s", "2");
    assertEquals(1, actualLogLines.size());
    assertEquals("log 1", actualLogLines.get(0));
  }

  @Test
  void debug() {
    bach.debug("debug %s", "1");
    bach.quiet = true;
    bach.debug("debug %s", "2");
    bach.quiet = false;
    bach.debug = false;
    bach.debug("debug %s", "3");
    assertEquals(1, actualLogLines.size());
    assertEquals("debug 1", actualLogLines.get(0));
  }

  @Test
  void runExecutable() {
    assertThrows(Error.class, () -> bach.run("command", "a", "b", "3"));
    assertEquals("[run] command [a, b, 3]", actualLogLines.get(0));
  }

  @Test
  void runExecutableInQuietMode() {
    bach.quiet = true;
    assertThrows(Error.class, () -> bach.run("command", "a", "b", "3"));
    assertTrue(actualLogLines.isEmpty());
  }

  @Test
  void runStreamSequentially() {
    Stream<Supplier<Integer>> tasks = Stream.of(() -> task("1"), () -> task("2"), () -> task("3"));
    var result = bach.run("run stream sequentially", tasks);
    assertEquals(0, result);
    var expected =
        List.of(
            "[run] run stream sequentially...",
            "1 begin",
            "1 done. .+",
            "2 begin",
            "2 done. .+",
            "3 begin",
            "3 done. .+",
            "[run] run stream sequentially done.");
    assertLinesMatch(expected, actualLogLines);
  }

  @Test
  void runStreamParallel() {
    Stream<Supplier<Integer>> tasks = Stream.of(() -> task("1"), () -> task("2"), () -> task("3"));
    var result = bach.run("run stream in parallel", tasks.parallel());
    assertEquals(0, result);
    var expected =
        List.of(
            "[run] run stream in parallel...",
            ". begin",
            ". begin",
            ". begin",
            ". done. .+",
            ". done. .+",
            ". done. .+",
            "[run] run stream in parallel done.");
    assertLinesMatch(expected, actualLogLines);
  }

  @Test
  void runVarArgs() {
    var result = bach.run("run varargs", () -> task("4"), () -> task("5"), () -> task("6"));
    assertEquals(0, result);
    var expected =
        List.of(
            "[run] run varargs...",
            ". begin",
            ". begin",
            ". begin",
            ". done. .+",
            ". done. .+",
            ". done. .+",
            "[run] run varargs done.");
    assertLinesMatch(expected, actualLogLines);
  }

  @Test
  void runThrowsIllegalStateExceptionOnNoneZeroResult() {
    Executable executable = () -> bach.run("23", Stream.of(() -> task("42", () -> 9)));
    Exception exception = assertThrows(IllegalStateException.class, executable);
    assertEquals("0 expected, but got: 9", exception.getMessage());
  }

  @Test
  void runCommand() {
    var bytes = new ByteArrayOutputStream(2000);
    var out = new PrintStream(bytes);
    var command = new JdkTool.Java().toCommand().add("--version");
    command.setStandardStreams(out, out);
    command.setLogger(actualLogLines::add);
    var result = bach.run("java --version", command);
    assertEquals(0, result);
    assertTrue(bytes.toString().contains(Runtime.version().toString()));
    var expected =
        List.of(
            "[run] java --version...",
            "running java with 1 argument(s)",
            "java\n--version",
            "replaced executable `java` with program `" + Util.getJdkCommand("java") + "`",
            "[run] java --version done.");
    assertLinesMatch(expected, actualLogLines);
  }

  private int task(String name) {
    return task(name, () -> 0);
  }

  private int task(String name, IntSupplier result) {
    bach.log("%s begin", name);
    var millis = (long) (Math.random() * 200 + 50);
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.interrupted();
    }
    bach.log("%s done. %d", name, millis);
    return result.getAsInt();
  }

  @Test
  void download() throws Exception {
    var tempRoot = Files.createTempDirectory("BachTests.download-");
    var content = List.of("Lorem", "ipsum", "dolor", "sit", "amet");
    var tempFile = Files.createFile(tempRoot.resolve("source.txt"));
    Files.write(tempFile, content);
    var tempPath = Files.createDirectory(tempRoot.resolve("target"));
    var first = bach.download(tempFile.toUri(), tempPath);
    var name = tempFile.getFileName().toString();
    var actual = tempPath.resolve(name);
    assertEquals(actual, first);
    assertTrue(Files.exists(actual));
    assertLinesMatch(content, Files.readAllLines(actual));
    assertLinesMatch(
        List.of(
            "download.*",
            "transferring `" + tempFile.toUri().toString() + "`...",
            "`" + name + "` downloaded .*"),
        actualLogLines);
    // reload
    actualLogLines.clear();
    var second = bach.download(tempFile.toUri(), tempPath);
    assertEquals(first, second);
    assertLinesMatch(
        List.of(
            "download.*",
            "local file already exists -- comparing properties to remote file...",
            "local and remote file properties seem to match, using .*"),
        actualLogLines);
    // offline mode
    actualLogLines.clear();
    bach.offline = true;
    var third = bach.download(tempFile.toUri(), tempPath);
    assertEquals(second, third);
    assertLinesMatch(List.of("download.*"), actualLogLines);
    // offline mode with error
    Files.delete(actual);
    assertThrows(Error.class, () -> bach.download(tempFile.toUri(), tempPath));
    // online but different file
    actualLogLines.clear();
    bach.offline = false;
    Files.write(actual, List.of("Hello world!"));
    var forth = bach.download(tempFile.toUri(), tempPath);
    assertEquals(actual, forth);
    assertLinesMatch(content, Files.readAllLines(actual));
    assertLinesMatch(
        List.of(
            "download.*",
            "local file already exists -- comparing properties to remote file...",
            "local file `.*` differs from remote one -- replacing it",
            "transferring `" + tempFile.toUri().toString() + "`...",
            "`" + name + "` downloaded .*"),
        actualLogLines);
    Util.removeTree(tempRoot);
  }
}
