package com.llmall.promotion.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the migration's real guard query; H2 does not validate MySQL DDL. */
class VoucherPurchaseMigrationGuardTest {
    private String migration() throws Exception {
        Path path = Path.of("sql/merged/65-voucher-purchase.sql");
        if (!Files.exists(path)) path = Path.of("../sql/merged/65-voucher-purchase.sql");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private void guard(String fixtures, boolean rejected) throws Exception {
        String sql = migration();
        String guard = sql.substring(sql.indexOf("-- BEGIN LEGACY GUARD"), sql.indexOf("-- END LEGACY GUARD"));
        // Only TEMPORARY syntax is adapted; the actual INSERT SELECT is unchanged.
        guard = guard.replace("TEMPORARY TABLE", "TABLE").replaceAll("(?m)--[^\\r\\n]*", "");
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA `llmall-user`");
                statement.execute("CREATE SCHEMA `llmall-promotion`");
                statement.execute("CREATE TABLE `llmall-user`.balance_flow(user_id BIGINT,change_amount INT,biz_type VARCHAR(16),biz_no VARCHAR(96))");
                statement.execute("CREATE TABLE `llmall-promotion`.user_voucher(id BIGINT,user_id BIGINT,voucher_id BIGINT)");
                for (String fixture : fixtures.split(";")) if (!fixture.isBlank()) statement.execute(fixture);
                final String statements = guard;
                if (rejected) {
                    SQLException error = assertThrows(SQLException.class, () -> execute(statement, statements));
                    assertEquals("23505", error.getSQLState(), "Guard must fail on its deliberate duplicate key");
                } else execute(statement, statements);
            }
        }
    }

    private void execute(Statement statement, String sql) throws SQLException {
        for (String part : sql.split(";")) if (!part.isBlank()) statement.execute(part);
    }

    private String flow(long user, int amount, String type, String key) {
        return "INSERT INTO `llmall-user`.balance_flow VALUES(" + user + "," + amount + ",'" + type + "','" + key + "');";
    }

    @Test void rejectsUnresolvedDebitWithoutVoucher() throws Exception {
        guard(flow(10, -100, "DEDUCT", "VOUCHER_BUY_123_10_attempt"), true);
    }

    @Test void aggregatesBuyAndRefundAfterTheirDifferentPrefixes() throws Exception {
        guard(flow(10, -100, "DEDUCT", "VOUCHER_BUY_123_10_old")
                + flow(10, 100, "REFUND", "VOUCHER_REFUND_123_10_old"), false);
    }

    @Test void rejectsOverRefundEvenWithHeldVoucher() throws Exception {
        guard("INSERT INTO `llmall-promotion`.user_voucher VALUES(1,10,123);"
                + flow(10, -100, "DEDUCT", "VOUCHER_BUY_123_10")
                + flow(10, 100, "REFUND", "VOUCHER_REFUND_123_10_a")
                + flow(10, 100, "REFUND", "VOUCHER_REFUND_123_10_b"), true);
    }

    @Test void heldVoucherAllowsItsHistoricalDebit() throws Exception {
        guard("INSERT INTO `llmall-promotion`.user_voucher VALUES(1,10,123);"
                + flow(10, -100, "DEDUCT", "VOUCHER_BUY_123_10"), false);
    }

    @Test void anotherUserOrVoucherCannotHideAnUnresolvedDebit() throws Exception {
        guard("INSERT INTO `llmall-promotion`.user_voucher VALUES(1,11,123),(2,10,456);"
                + flow(10, -100, "DEDUCT", "VOUCHER_BUY_123_11_attempt"), true);
    }

    @Test void differentVouchersDoNotCancelEachOtherOut() throws Exception {
        guard(flow(10, -100, "DEDUCT", "VOUCHER_BUY_123_10_a")
                + flow(10, 100, "REFUND", "VOUCHER_REFUND_456_10_b"), true);
    }

    @Test void seckillAndSimilarWildcardPrefixesAreOutsideThisGuard() throws Exception {
        guard(flow(10, -100, "DEDUCT", "VOUCHER-SECKILL-token")
                + flow(10, 100, "REFUND", "SECKILL_REFUND:token")
                + flow(10, -100, "DEDUCT", "VOUCHERXBUYX123_10"), false);
    }

    @Test void emptyLedgerIsSafe() throws Exception {
        guard("", false);
    }
}
