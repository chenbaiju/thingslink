package com.things.link.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/** 在 Linux 文件系统执行真实生成器，保留绝对路径和 POSIX 私有权限校验。 */
final class AccessCutoverFixture {

    private AccessCutoverFixture() { }

    static void prepare(Path checkout, Path destination) throws Exception {
        try (var generator = new GenericContainer<>("python:3.13-alpine")
                .withCommand("sh", "-c", "echo fixture-ready; exec sleep infinity")
                .waitingFor(Wait.forLogMessage(".*fixture-ready.*", 1))) {
            for (String source : List.of(
                    "deploy/acceptance/scripts/prepare.py",
                    "deploy/acceptance/scripts/prepare-backend.py",
                    "deploy/acceptance/scripts/prepare-cutover.py",
                    "deploy/acceptance/.env.example",
                    "deploy/acceptance/nginx.conf.template",
                    "deploy/emqx/base.hocon")) {
                generator.withCopyToContainer(Transferable.of(Files.readAllBytes(checkout.resolve(source)), 0444),
                        "/checkout/" + source);
            }
            generator.start();
            run(generator, "/checkout/deploy/acceptance/scripts/prepare.py",
                    "--directory", "/fixture/private");
            var smtp = generator.execInContainer("python3", "-c", """
                    from pathlib import Path
                    with Path('/fixture/private/.env').open('a', encoding='utf-8') as output:
                        output.write('MAIL_SMTP_HOST=smtp.example.test\\nMAIL_SMTP_PORT=465\\n'
                            'MAIL_SMTP_USERNAME=test@example.test\\nMAIL_SMTP_PASSWORD=test-smtp-secret\\n'
                            'MAIL_SMTP_TLS_MODE=ssl\\n')
                    """);
            assertThat(smtp.getExitCode()).as("配置测试 SMTP 夹具").isZero();
            run(generator, "/checkout/deploy/acceptance/scripts/prepare-backend.py",
                    "--directory", "/fixture/private", "--origin", "https://acceptance.example",
                    "--storage-origin", "https://acceptance.example:8066",
                    "--registry-directory", "/fixture/registry", "--log-directory", "/fixture/logs");
            run(generator, "/checkout/deploy/acceptance/scripts/prepare-cutover.py",
                    "--directory", "/fixture/private");
            // 只取回原路由断言需要的文件，生成器的密钥和私有角色配置留在临时容器。
            for (String output : List.of("runtime/emqx-base.hocon",
                    "runtime/emqx-base-device-access.hocon", "nginx-device-access.conf.template")) {
                Path target = destination.resolve(output);
                Files.createDirectories(target.getParent());
                generator.copyFileFromContainer("/fixture/private/" + output, target.toString());
            }
        }
    }

    private static void run(GenericContainer<?> generator, String script, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 2];
        command[0] = "python3";
        command[1] = script;
        System.arraycopy(arguments, 0, command, 2, arguments.length);
        var result = generator.execInContainer(command);
        assertThat(result.getExitCode()).as("真实私有配置生成器: %s; %s", script, result.getStderr()).isZero();
    }
}
