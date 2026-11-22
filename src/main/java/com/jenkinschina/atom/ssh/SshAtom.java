package com.jenkinschina.atom.ssh;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.util.Locale;

/**
 * ssh as one atom, two verbs: {@code publish} sends files over, {@code exec} runs a command over there.
 *
 * <p>They were two atoms and two products (publish-over-ssh and ssh-steps), which is an accident of which plugin a
 * job happened to need: the connection — host, port, user, key, known_hosts — is the same question either way.
 * QuickBuild treats it as one integration, and so does this atom. Each action keeps the fields, error codes and
 * outputs its old atom had, so an existing job moves over by adding {@code action}.
 */
public class SshAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String action = context.getParam("action", "publish").trim().toLowerCase(Locale.ROOT);
        if (action.isEmpty() || action.equals("publish")) {
            return SshPublish.run(context);
        }
        if (action.equals("exec")) {
            return SshExec.run(context);
        }
        // 40010: neither old atom used it, so a mistyped action cannot be mistaken for one of their refusals
        return AtomResult.error(ErrorType.USER, 40010,
                "action must be publish or exec, not '" + context.getParam("action", "") + "'");
    }
}
