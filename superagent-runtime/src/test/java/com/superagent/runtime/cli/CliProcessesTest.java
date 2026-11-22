package com.superagent.runtime.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Ends every tracked CLI process in this JVM: nothing else may run alongside it.
@Isolated
class CliProcessesTest {

    @Test
    void destroyAllEndsTrackedProcessesAndTheirChildren() throws Exception {
        Process parent = CliProcesses.track(new ProcessBuilder("sh", "-c", "sleep 60 & wait").start());
        long deadline = System.currentTimeMillis() + 5000;
        while (parent.descendants().findAny().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        List<ProcessHandle> children = parent.descendants().toList();
        assertThat(children).isNotEmpty();

        assertThat(CliProcesses.destroyAll()).isGreaterThanOrEqualTo(1);

        assertThat(parent.waitFor(5, TimeUnit.SECONDS)).isTrue();
        for (ProcessHandle child : children) {
            child.onExit().get(5, TimeUnit.SECONDS);
            assertThat(child.isAlive()).isFalse();
        }
        assertThat(CliProcesses.liveCount()).isZero();
    }

    @Test
    void killEndsAProcessWithTheChildrenItStarted() throws Exception {
        Process parent = new ProcessBuilder("sh", "-c", "sleep 60 & wait").start();
        long deadline = System.currentTimeMillis() + 5000;
        while (parent.descendants().findAny().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        List<ProcessHandle> children = parent.descendants().toList();

        CliProcesses.kill(parent);

        assertThat(parent.waitFor(5, TimeUnit.SECONDS)).isTrue();
        for (ProcessHandle child : children) {
            child.onExit().get(5, TimeUnit.SECONDS);
        }
        assertThat(children).isNotEmpty().noneMatch(ProcessHandle::isAlive);
    }
}
