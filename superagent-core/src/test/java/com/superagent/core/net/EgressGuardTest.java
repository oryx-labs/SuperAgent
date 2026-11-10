package com.superagent.core.net;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EgressGuardTest {

    @Test
    void onlyPublicHttpAddressesUnlessAnAdministratorAllowedTheInternalOne() {
        for (String internal : new String[]{"http://127.0.0.1/", "http://10.1.2.3/", "http://172.20.0.1/", "http://192.168.1.1/",
                "http://169.254.169.254/latest/meta-data", "http://[::1]/", "http://[fd00::1]/", "http://100.64.0.1/",
                "http://localhost:8080/", "http://printer.local/", "http://0.0.0.0/"}) {
            assertThatThrownBy(() -> EgressGuard.PUBLIC_ONLY.check(internal)).as(internal)
                    .isInstanceOf(EgressDeniedException.class).hasMessageContaining("内网");
        }
        assertThatThrownBy(() -> EgressGuard.PUBLIC_ONLY.check("file:///etc/passwd")).isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> EgressGuard.PUBLIC_ONLY.check("http://93.184.216.34:22/")).isInstanceOf(EgressDeniedException.class);
        assertThat(EgressGuard.PUBLIC_ONLY.check("https://93.184.216.34/page").getHost()).isEqualTo("93.184.216.34");

        EgressGuard corp = new EgressGuard(List.of("10.8.0.0/16", ".corp.local"));
        assertThat(corp.check("http://10.8.3.4/wiki").getHost()).isEqualTo("10.8.3.4");
        assertThat(corp.check("http://wiki.corp.local/").getHost()).isEqualTo("wiki.corp.local");
        assertThatThrownBy(() -> corp.check("http://10.9.0.1/")).isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> corp.check("http://169.254.169.254/")).isInstanceOf(EgressDeniedException.class);
    }

    @Test
    void addressesWithChineseOrSpacesStillBecomeUris() {
        assertThat(EgressGuard.toUri("https://93.184.216.34/文档 一").toString())
                .isEqualTo("https://93.184.216.34/%E6%96%87%E6%A1%A3%20%E4%B8%80");
    }
}
