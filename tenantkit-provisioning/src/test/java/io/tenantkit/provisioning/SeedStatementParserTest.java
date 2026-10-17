package io.tenantkit.provisioning;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Statement splitting: semicolons inside quoted literals and comments never
 * split; declared boundaries live in {@link SeedStatementParser}'s javadoc.
 */
class SeedStatementParserTest {

    @Test
    void splitsSimpleStatementsAndDropsEmpties() {
        List<String> statements = SeedStatementParser.split("INSERT INTO t VALUES (1); ;\n  ;INSERT INTO t VALUES (2);");
        assertThat(statements).containsExactly("INSERT INTO t VALUES (1)", "INSERT INTO t VALUES (2)");
    }

    @Test
    void semicolonInsideSingleQuotedLiteralDoesNotSplit() {
        List<String> statements = SeedStatementParser
                .split("INSERT INTO t VALUES ('a;b', 'c') ; INSERT INTO t VALUES ('d')");
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).isEqualTo("INSERT INTO t VALUES ('a;b', 'c')");
    }

    @Test
    void escapedQuotePairKeepsLiteralOpen() {
        List<String> statements = SeedStatementParser.split("INSERT INTO t VALUES ('it''s; fine'); SELECT 1");
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).isEqualTo("INSERT INTO t VALUES ('it''s; fine')");
    }

    @Test
    void semicolonInsideDoubleQuotedLiteralDoesNotSplit() {
        List<String> statements = SeedStatementParser.split("SELECT \"a;b\" FROM t; SELECT 1");
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).isEqualTo("SELECT \"a;b\" FROM t");
    }

    @Test
    void semicolonInsideLineCommentDoesNotSplit() {
        List<String> statements = SeedStatementParser.split("-- note; ignored\nSELECT 1; -- tail; comment");
        assertThat(statements).containsExactly("-- note; ignored\nSELECT 1", "-- tail; comment");
    }

    @Test
    void semicolonInsideBlockCommentDoesNotSplit() {
        List<String> statements = SeedStatementParser.split("/* header; with; semicolons */ SELECT 1; SELECT 2");
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).isEqualTo("/* header; with; semicolons */ SELECT 1");
    }

    @Test
    void quotesInsideCommentsDoNotOpenLiterals() {
        List<String> statements = SeedStatementParser.split("/* don't */ SELECT 1; -- it's; fine\nSELECT 2");
        assertThat(statements).hasSize(2);
    }

    @Test
    void unterminatedBlockCommentConsumesRest() {
        List<String> statements = SeedStatementParser.split("SELECT 1; /* unfinished");
        assertThat(statements).containsExactly("SELECT 1", "/* unfinished");
    }

    @Test
    void blankInputYieldsNoStatements() {
        assertThat(SeedStatementParser.split("")).isEmpty();
        assertThat(SeedStatementParser.split("  \n ; ; \n")).isEmpty();
    }
}
