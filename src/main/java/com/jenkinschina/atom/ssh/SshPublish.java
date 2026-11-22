package com.jenkinschina.atom.ssh;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Glob;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Copy files to another machine over SSH, keeping the directory structure.
 *
 * <p>The publish-over-ssh plugin drives scp/rsync and a pile of transfer options; the useful core is "these files
 * end up over there, with their paths intact". This builds one tar stream in memory and feeds it to
 * {@code ssh host 'tar -xf -'}, which needs nothing on the far side but sshd and tar, and is one connection
 * instead of one per file.
 */
final class SshPublish {

    private SshPublish() {
    }

    static AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String remoteDir = Ssh.param(context, "remoteDir", "", "remotePath", "targetDir").trim();
        String sourceFiles = Ssh.param(context, "sourceFiles", "**/*", "patterns");
        String exclude = context.getParam("exclude", "");
        String baseDir = context.getParam("baseDir", "").trim();
        boolean deleteRemote = context.getBooleanParam("deleteRemote", false);
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 120), 3600));

        try {
            Ssh.requireHostAndUser(context);
            if (remoteDir.isEmpty()) {
                return AtomResult.error(ErrorType.USER, 40003, "remoteDir is required");
            }
            if (remoteDir.equals("/") || remoteDir.equals("*")) {
                return AtomResult.error(ErrorType.USER, 40004,
                        "remoteDir must be a real directory, not '" + remoteDir + "'");
            }
            Ssh.requirePort(context, 40005);

            Path base = baseDir.isEmpty() ? workspace : workspace.resolve(baseDir).normalize();
            if (!base.startsWith(workspace) || !Files.isDirectory(base)) {
                return AtomResult.error(ErrorType.USER, 40006, "baseDir must be a directory inside the workspace");
            }

            List<Path> files = collect(base, sourceFiles, exclude);
            if (files.isEmpty()) {
                return AtomResult.error(ErrorType.USER, 40007, "no file matched '" + sourceFiles + "'"
                        + (exclude.isBlank() ? "" : " (excluding " + exclude + ")"));
            }

            byte[] archive = tar(base, files);
            try (Ssh.Target target = Ssh.target(context, 40008)) {
                String quoted = Ssh.shellQuote(remoteDir);
                StringBuilder remote = new StringBuilder("mkdir -p " + quoted + " && ");
                if (deleteRemote) {
                    // only inside a real path, and never the directory itself
                    remote.append("find ").append(quoted).append(" -mindepth 1 -delete && ");
                }
                remote.append("tar -xf - -C ").append(quoted);
                List<String> command = target.with(remote.toString());

                context.log("[ssh] " + files.size() + " file(s), " + archive.length + " bytes -> "
                        + target.address() + ":" + remoteDir);
                Ssh.Result result = Ssh.run(command, archive, timeoutSeconds);
                if (result.exit() != 0) {
                    String message = result.stderr().isBlank() ? result.stdout() : result.stderr();
                    ErrorType type = message.contains("Permission denied") || message.contains("Host key verification")
                            || message.contains("IDENTIFICATION HAS CHANGED")
                            || message.contains("No such file or directory")
                                    ? ErrorType.USER : ErrorType.ENVIRONMENT;
                    return AtomResult.error(type, 40009, "ssh failed (exit " + result.exit() + "): "
                            + Ssh.oneLine(message, 300));
                }
                return AtomResult.success()
                        .data("filesUploaded", String.valueOf(files.size()))
                        .data("bytes", String.valueOf(archive.length))
                        .data("remoteDir", remoteDir)
                        .data("host", context.getParam("host", "").trim());
            }
        } catch (Ssh.Refused refused) {
            return refused.result();
        }
    }

    /**
     * The runner keeps {@code input.json}, {@code .secrets.json} and {@code output.json} under
     * {@code .atoms/<runId>/}. An upload pattern of {@code **}{@code /*} would otherwise ship a credential file to a
     * remote host, so the runner's scratch directory is never part of the payload.
     */
    static boolean isRunnerScratch(String relative) {
        return relative.startsWith(".atoms/") || relative.equals(".atoms")
                || relative.endsWith("/.secrets.json") || relative.equals(".secrets.json");
    }

    /** Files under {@code base} matching any pattern in {@code sourceFiles}, minus anything in {@code exclude}. */
    private static List<Path> collect(Path base, String sourceFiles, String exclude) throws IOException {
        List<String> patterns = split(sourceFiles);
        List<String> excluded = split(exclude);
        Set<Path> matches = new LinkedHashSet<>();
        for (String pattern : patterns) {
            for (Path found : Glob.find(base, pattern)) {
                if (Files.isRegularFile(found)) {
                    matches.add(found.normalize());
                }
            }
            if (patterns.size() == 1 && matches.isEmpty()) {
                // a plain path is also an answer: "dist/app.jar" should not need a glob
                Path literal = base.resolve(pattern).normalize();
                if (literal.startsWith(base) && Files.isRegularFile(literal)) {
                    matches.add(literal);
                }
            }
        }
        List<Path> kept = new ArrayList<>();
        for (Path candidate : matches) {
            String relative = base.relativize(candidate).toString().replace('\\', '/');
            if (isRunnerScratch(relative)) {
                continue;
            }
            boolean skip = false;
            for (String pattern : excluded) {
                if (matchesPattern(relative, pattern)) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    private static boolean matchesPattern(String relative, String pattern) {
        String regex = pattern.replace(".", "\\.").replace("**", "\u0000").replace("*", "[^/]*")
                .replace("\u0000", ".*").replace("?", ".");
        return relative.matches(regex) || relative.matches(".*/" + regex);
    }

    private static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split("[,\\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /* ---------------------------------------------------------------- tar */

    /** ustar, no compression: ssh already encrypts, and tar is on every POSIX box. */
    static byte[] tar(Path base, List<Path> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] block = new byte[512];
        for (Path file : files) {
            String name = base.relativize(file).toString().replace('\\', '/');
            long size = Files.size(file);
            byte[] header = new byte[512];
            put(header, 0, 100, name);
            put(header, 100, 8, String.format(Locale.ROOT, "%07o", 0644));
            put(header, 108, 8, String.format(Locale.ROOT, "%07o", 0));
            put(header, 116, 8, String.format(Locale.ROOT, "%07o", 0));
            put(header, 124, 12, String.format(Locale.ROOT, "%011o", size));
            put(header, 136, 12,
                    String.format(Locale.ROOT, "%011o", Files.getLastModifiedTime(file).toMillis() / 1000));
            put(header, 148, 8, "        "); // checksum is computed with this field blank
            header[156] = '0';              // regular file
            put(header, 257, 6, "ustar");
            put(header, 263, 2, "00");
            long sum = 0;
            for (byte value : header) {
                sum += value & 0xff;
            }
            put(header, 148, 8, String.format(Locale.ROOT, "%06o", sum) + "\u0000 ");

            out.write(header);
            try (var in = Files.newInputStream(file)) {
                in.transferTo(out);
            }
            int pad = (int) ((512 - (size % 512)) % 512);
            if (pad > 0) {
                out.write(new byte[pad]);
            }
        }
        out.write(block);
        out.write(block);
        return out.toByteArray();
    }

    private static void put(byte[] target, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, target, offset, Math.min(bytes.length, length));
    }
}
