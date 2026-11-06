package com.superagent.platform.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InstallFailuresTest {

    @Test
    void commonNpmFailuresReadAsOneLine() {
        String unreachable = """
                npm http fetch GET https://registry.npmjs.org/@google%2fgemini-cli attempt 1 failed with ECONNREFUSED
                npm error code ECONNREFUSED
                npm error syscall connect
                npm error errno ECONNREFUSED
                npm error FetchError: request to https://registry.npmjs.org/@google%2fgemini-cli failed, reason: connect ECONNREFUSED 127.0.0.1:443
                npm error     at ClientRequest.<anonymous> (/x/node_modules/minipass-fetch/lib/index.js:130:14)
                npm error A complete log of this run can be found in: /x/_logs/debug-0.log
                """;
        assertThat(InstallFailures.summarize(unreachable, 1))
                .isEqualTo("连不上 npm 源（https://registry.npmjs.org/@google%2fgemini-cli）：请检查服务器网络、代理或 npm 镜像设置（npm error code ECONNREFUSED）");

        String denied = """
                npm error code EACCES
                npm error syscall mkdir
                npm error path /usr/local/lib/node_modules/@google
                npm error errno -13
                """;
        assertThat(InstallFailures.summarize(denied, 243))
                .startsWith("没有写入 npm 全局目录的权限（/usr/local/lib/node_modules/@google）").contains("EACCES");

        String other = """
                npm error something odd happened
                npm error     at foo (bar.js:1:1)
                npm error A complete log of this run can be found in: /x.log
                """;
        assertThat(InstallFailures.summarize(other, 1)).isEqualTo("something odd happened");
        assertThat(InstallFailures.summarize("", 7)).contains("退出码 7");
    }
}
