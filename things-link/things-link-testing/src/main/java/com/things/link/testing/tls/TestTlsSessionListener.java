package com.things.link.testing.tls;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import java.nio.file.Path;

/** Runs before discovery/static fixture initialization for Maven, CI and IDE JUnit launchers. */
public final class TestTlsSessionListener implements LauncherSessionListener {
    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            TestTlsMaterial.ensure(TestTlsMaterial.checkoutRoot(Path.of("")));
        } catch (Exception failure) {
            throw new IllegalStateException("Local test TLS preparation failed; tests must not start", failure);
        }
    }
}
