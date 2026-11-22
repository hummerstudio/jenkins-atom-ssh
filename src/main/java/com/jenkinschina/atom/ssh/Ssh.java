package com.jenkinschina.atom.ssh;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * What both actions need: one ssh command line built the same way, the private key written where only this user can
 * read it, and a process whose output is collected instead of inherited.
 *
 * <p>None of this knows whether the far side is about to receive a tar stream or a shell script — that is the only
 * thing the two actions disagree on, so it lives here and not twice.
 */
final class Ssh {

    private Ssh() {
    }

    /**
     * A refusal the caller has to fix (a missing field, a bad port, a key that is not there). The action returns
     * {@link #result()} as the atom's answer; the numeric code is the action's own, because the two old atoms number
     * their errors differently and callers match on those codes.
     */
    static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;
        private final transient AtomResult result;

        Refused(ErrorType type, int code, String message) {
            super(message);
            this.result = AtomResult.error(type, code, message);
        }

        AtomResult result() {
            return result;
        }
    }

    /**
     * A first value that is set, then any accepted spelling of the same thing, then the default.
     *
     * <p>The two old atoms were separate products and a job that used one may spell a field the way the other did;
     * accepting both costs a lookup and saves every existing job from being rewritten.
     */
    static String param(AtomContext context, String name, String fallback, String... aliases) {
        String value = context.getParam(name, "");
        if (!value.isBlank()) {
            return value;
        }
        for (String alias : aliases) {
            String alternate = context.getParam(alias, "");
            if (!alternate.isBlank()) {
                return alternate;
            }
        }
        return fallback;
    }

    /** host, then user: the same two codes in both old atoms, checked before anything else. */
    static void requireHostAndUser(AtomContext context) throws Refused {
        if (context.getParam("host", "").trim().isEmpty()) {
            throw new Refused(ErrorType.USER, 40001, "host is required");
        }
        if (context.getParam("user", "").trim().isEmpty()) {
            throw new Refused(ErrorType.USER, 40002, "user is required");
        }
    }

    static void requirePort(AtomContext context, int code) throws Refused {
        String port = context.getParam("port", "22").trim();
        if (!port.matches("\\d+")) {
            throw new Refused(ErrorType.USER, code, "port must be a number");
        }
    }

    /**
     * An ssh command line up to and including {@code user@host}; the caller appends what to run over there.
     *
     * <p>{@code missingKeyCode} is the action's own "there is no private key at …" code.
     */
    static Target target(AtomContext context, int missingKeyCode) throws Refused, IOException {
        Path workspace = context.getWorkspace();
        String port = context.getParam("port", "22").trim();
        String strict = context.getParam("strictHostKeyChecking", "accept-new").trim();
        String knownHosts = param(context, "knownHostsFile", "", "knownHosts").trim();
        String keyFile = param(context, "keyFile", "", "privateKeyFile").trim();
        String key = context.getSecret("key");

        List<String> argv = new ArrayList<>();
        argv.add("ssh");
        argv.add("-p");
        argv.add(port);
        argv.add("-o");
        argv.add("BatchMode=yes");
        argv.add("-o");
        argv.add("StrictHostKeyChecking=" + (strict.isEmpty() ? "accept-new" : strict));
        // Debian/Ubuntu default to hashed known_hosts, which reads as "the key was never recorded" to a human and to
        // every script that greps the file. Keep the entries readable for both actions.
        argv.add("-o");
        argv.add("HashKnownHosts=no");
        if (!knownHosts.isEmpty()) {
            Path known = workspace.resolve(knownHosts).normalize();
            Files.createDirectories(known.getParent() == null ? workspace : known.getParent());
            if (!Files.exists(known)) {
                Files.writeString(known, "", StandardCharsets.UTF_8);
            }
            argv.add("-o");
            argv.add("UserKnownHostsFile=" + known);
        }
        argv.add("-o");
        argv.add("ConnectTimeout=10");

        Path temporaryKey = null;
        String keyOption = null;
        if (!keyFile.isEmpty()) {
            Path keyPath = workspace.resolve(keyFile).normalize();
            if (!Files.isRegularFile(keyPath)) {
                throw new Refused(ErrorType.USER, missingKeyCode, "no private key at " + keyFile);
            }
            keyOption = keyPath.toString();
        } else if (key != null && !key.isBlank()) {
            temporaryKey = createOwnerOnlyTempFile("atom-ssh-", ".key");
            Files.writeString(temporaryKey, key.endsWith("\n") ? key : key + "\n", StandardCharsets.UTF_8);
            setOwnerOnly(temporaryKey);
            keyOption = temporaryKey.toString();
        }
        if (keyOption != null) {
            argv.add("-i");
            argv.add(keyOption);
        }
        argv.add(context.getParam("user", "").trim() + "@" + context.getParam("host", "").trim());
        return new Target(argv, temporaryKey);
    }

    /** The command line built by {@link #target}, and the temp key (if any) to delete once the connection is done. */
    static final class Target implements AutoCloseable {

        private final List<String> argv;
        private final Path temporaryKey;

        private Target(List<String> argv, Path temporaryKey) {
            this.argv = argv;
            this.temporaryKey = temporaryKey;
        }

        List<String> argv() {
            return argv;
        }

        /** The whole command line with one more argument: what to run on the far side. */
        List<String> with(String argument) {
            List<String> command = new ArrayList<>(argv);
            command.add(argument);
            return command;
        }

        String address() {
            return argv.get(argv.size() - 1);
        }

        @Override
        public void close() throws IOException {
            if (temporaryKey != null) {
                Files.deleteIfExists(temporaryKey);
            }
        }
    }

    /* ---------------------------------------------------------------- process */

    record Result(int exit, String stdout, String stderr) {}

    static Result run(List<String> command, byte[] stdin, int timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder(command).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outReader = pump(process.getInputStream(), out);
        Thread errReader = pump(process.getErrorStream(), err);
        try (OutputStream input = process.getOutputStream()) {
            input.write(stdin);
        } catch (IOException e) {
            // the far side may close early (a bad key, a full disk); the exit code tells the story
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return new Result(124, out.toString(StandardCharsets.UTF_8), "timed out after " + timeoutSeconds + "s");
        }
        outReader.join(5000);
        errReader.join(5000);
        return new Result(process.exitValue(), out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static Thread pump(java.io.InputStream stream, ByteArrayOutputStream sink) {
        Thread thread = new Thread(() -> {
            try (stream) {
                stream.transferTo(sink);
            } catch (IOException ignored) {
                // the process went away; the exit code is the signal
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * The key lands in a file nobody else can read: 0600 from creation, so there is no window where the private key
     * is readable by another account. A filesystem without POSIX modes has no group/world bit to drop, so the
     * attribute is left off there.
     */
    private static Path createOwnerOnlyTempFile(String prefix, String suffix) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile(prefix, suffix,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createTempFile(prefix, suffix, new FileAttribute<?>[0]);
    }

    private static void setOwnerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // windows or a filesystem without POSIX modes: ssh will complain about the key, not us
        }
    }

    /* ---------------------------------------------------------------- text */

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static String firstLine(String value) {
        return value.lines().findFirst().orElse("").trim();
    }

    /** The last few lines: ssh's own complaint is at the end, the command's noise is not worth keeping. */
    static String tail(String value, int lines) {
        if (value == null) {
            return "";
        }
        String[] split = value.stripTrailing().split("\n");
        int from = Math.max(0, split.length - lines);
        return String.join("\n", java.util.Arrays.copyOfRange(split, from, split.length));
    }

    static String oneLine(String value, int limit) {
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() > limit ? flat.substring(0, limit) + "…" : flat;
    }
}
