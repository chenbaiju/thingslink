package com.things.link.project.infrastructure.operations;

import org.junit.jupiter.api.Test;
import com.things.link.shared.id.Uuid7;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 先于连接的封闭命令与凭据入口校验。 */
class AutomationQuotaCliTests {
    @Test void invalidInputCannotOpenDatabase() {
        for(String[] args:List.of(new String[]{},new String[]{"set","bad"},new String[]{"unknown",Uuid7.generate().toString()},
                new String[]{"set",UUID.randomUUID().toString(),Uuid7.generate().toString(),"1","1"})) {
            var out=new ByteArrayOutputStream();
            assertThat(AutomationQuotaCli.run(args,Map.of(),new PrintStream(out),env->{throw new AssertionError();},
                    (a,b,c)->{throw new AssertionError();})).isEqualTo(2);
            assertThat(out.toString()).contains("INPUT_INVALID");
        }
    }
    @Test void credentialsInUrlAreRejectedWithoutEcho() {
        var out=new ByteArrayOutputStream();
        var env=Map.of("TC_QUOTA_OPERATOR_JDBC_URL","jdbc:postgresql://localhost/db?password=secret",
                "TC_QUOTA_OPERATOR_USER","operator","TC_QUOTA_OPERATOR_PASSWORD","secret");
        assertThat(AutomationQuotaCli.run(new String[]{"inspect",Uuid7.generate().toString()},env,new PrintStream(out),
                ignored->{throw new AssertionError();},(a,b,c)->false)).isEqualTo(2);
        assertThat(out.toString()).doesNotContain("secret","localhost");
    }
}
