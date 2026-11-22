package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One atom, two verbs: {@code action} is what picks the verb, and everything before the connection is the same either
 * way.
 *
 * <p>These cases need no sshd: they are about the dispatch itself — the default, the two names, and a typo being
 * refused rather than quietly treated as a publish.
 */
class SshActionIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("no action means publish, so an old ssh-publish job carries over untouched")
    void defaultsToPublish() throws Exception {
        AtomResult run = atom.input("host", "127.0.0.1").input("user", "someone").run();

        run.assertRefused();
        // publish's own "remoteDir is required", reached without any action field at all
        assertEquals("40003", run.field("errorCode"), run.field("message"));
        assertTrue(run.field("message").contains("remoteDir is required"), run.field("message"));
    }

    @Test
    @DisplayName("action is case-insensitive, and the blank default is publish")
    void acceptsBothNamesInAnyCase() throws Exception {
        AtomResult publish = Atom.of().input("action", "PUBLISH").input("host", "127.0.0.1").input("user", "someone").run();
        assertEquals("40003", publish.field("errorCode"), publish.field("message"));

        AtomResult exec = Atom.of().input("action", "EXEC").input("host", "127.0.0.1").input("user", "someone").run();
        assertEquals("40003", exec.field("errorCode"), exec.field("message"));
        assertTrue(exec.field("message").contains("command is required"), exec.field("message"));

        AtomResult blank = Atom.of().input("action", "").input("host", "127.0.0.1").input("user", "someone").run();
        assertTrue(blank.field("message").contains("remoteDir is required"), blank.field("message"));
    }

    @Test
    @DisplayName("a mistyped action is refused instead of silently publishing something")
    void refusesAnUnknownAction() throws Exception {
        AtomResult run = atom.input("action", "upload").input("host", "127.0.0.1").input("user", "someone").run();

        run.assertRefused();
        assertEquals("40010", run.field("errorCode"), run.field("message"));
        assertTrue(run.field("message").contains("action must be publish or exec, not 'upload'"), run.field("message"));
    }

    @Test
    @DisplayName("host and user are checked by both actions, with the same codes")
    void sharesTheConnectionRefusals() throws Exception {
        AtomResult publish = Atom.of().input("action", "publish").input("user", "someone").run();
        assertEquals("40001", publish.field("errorCode"), publish.field("message"));
        assertTrue(publish.field("message").contains("host is required"), publish.field("message"));

        AtomResult exec = Atom.of().input("action", "exec").input("host", "127.0.0.1").input("command", "true").run();
        assertEquals("40002", exec.field("errorCode"), exec.field("message"));
        assertTrue(exec.field("message").contains("user is required"), exec.field("message"));
    }
}
