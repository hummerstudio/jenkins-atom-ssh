package com.jenkinschina.atom.ssh;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.ErrorType;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Run a shell command on another machine over SSH.
 *
 * <p>ssh-steps does this with a Groovy DSL around the JSch library; the transferable part is "run these lines over
 * there and tell me what happened". The command goes to {@code bash -s} on stdin rather than through a quoted
 * argument, so quotes, newlines and {@code $} survive untouched.
 */
final class SshExec {

    private SshExec() {
    }

    static AtomResult run(AtomContext context) throws Exception {
        String command = Ssh.param(context, "command", "", "script", "commands");
        boolean failOnError = context.getBooleanParam("failOnError", true);
        int expected = context.getIntParam("expectedExitCode", 0);
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 120), 3600));

        try {
            Ssh.requireHostAndUser(context);
            if (command.isBlank()) {
                return AtomResult.error(ErrorType.USER, 40003, "command is required");
            }
            Ssh.requirePort(context, 40004);

            try (Ssh.Target target = Ssh.target(context, 40005)) {
                // the script arrives on stdin, so nothing needs quoting
                List<String> argv = target.with("bash -s");

                context.log("[ssh-exec] " + target.address() + ":" + context.getParam("port", "22").trim()
                        + " -> " + Ssh.firstLine(command));
                long started = System.currentTimeMillis();
                Ssh.Result result = Ssh.run(argv, command.getBytes(StandardCharsets.UTF_8), timeoutSeconds);
                long duration = System.currentTimeMillis() - started;
                if (!result.stdout().isBlank()) {
                    context.log(result.stdout().stripTrailing());
                }
                if (!result.stderr().isBlank()) {
                    context.log("[stderr] " + result.stderr().stripTrailing());
                }

                boolean timedOut = result.exit() == 124 && result.stderr().startsWith("timed out");
                if (result.exit() != expected && !failOnError) {
                    context.log("[ssh-exec] exit " + result.exit() + " (expected " + expected
                            + "), not failing the build because failOnError is off");
                    return AtomResult.success()
                            .data("exitCode", String.valueOf(result.exit()))
                            .data("durationMs", String.valueOf(duration))
                            .data("stdoutBytes",
                                    String.valueOf(result.stdout().getBytes(StandardCharsets.UTF_8).length));
                }
                if (result.exit() != expected) {
                    String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
                    // ssh's own failures (255: key, host, port) and the remote command's are both the caller's to fix
                    ErrorType type = timedOut ? ErrorType.ENVIRONMENT : ErrorType.USER;
                    return AtomResult.error(type, 40006, (timedOut ? "" : "the command exited " + result.exit()
                            + " (expected " + expected + "): ") + Ssh.oneLine(Ssh.tail(detail, 5), 400))
                            .data("exitCode", String.valueOf(result.exit()))
                            .data("durationMs", String.valueOf(duration));
                }
                return AtomResult.success()
                        .data("exitCode", String.valueOf(result.exit()))
                        .data("durationMs", String.valueOf(duration))
                        .data("stdoutBytes",
                                String.valueOf(result.stdout().getBytes(StandardCharsets.UTF_8).length));
            }
        } catch (Ssh.Refused refused) {
            return refused.result();
        }
    }
}
