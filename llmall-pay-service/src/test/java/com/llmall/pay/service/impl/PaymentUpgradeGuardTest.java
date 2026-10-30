package com.llmall.pay.service.impl;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the migration's actual preflight SQL; H2 checks predicates, not MySQL-specific DDL. */
class PaymentUpgradeGuardTest {
    private JdbcDataSource ds;private JdbcTemplate db;private String guard;
    @BeforeEach void setup() throws Exception {
        ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");db=new JdbcTemplate(ds);
        db.execute("CREATE SCHEMA `llmall-pay`");db.execute("CREATE SCHEMA `llmall-user`");
        db.execute("CREATE TABLE `llmall-pay`.pay_order(id BIGINT,status INT,result_code VARCHAR(32))");
        db.execute("CREATE TABLE `llmall-user`.balance_flow(id BIGINT,user_id BIGINT,biz_type VARCHAR(32),biz_no VARCHAR(64),change_amount INT)");
        Path root=Paths.get("").toAbsolutePath();while(root!=null&&!Files.exists(root.resolve("sql/merged/64-payment-recovery.sql")))root=root.getParent();
        assertNotNull(root,"repository SQL must be available to test the migration guard");
        guard=Files.readString(root.resolve("sql/merged/64-payment-recovery.sql")).split("-- Additive only:")[0];
        // H2 requires DROP TABLE for temporary tables; the guard predicates execute unchanged.
        guard=guard.replace("DROP TEMPORARY TABLE","DROP TABLE");
    }
    @AfterEach void cleanup(){db.execute("DROP ALL OBJECTS");}
    private void runGuard() throws Exception {try(Connection c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ByteArrayResource(guard.getBytes(StandardCharsets.UTF_8)));}}
    private void unpaidDebit(){db.update("INSERT INTO `llmall-pay`.pay_order VALUES(10,1,NULL)");db.update("INSERT INTO `llmall-user`.balance_flow VALUES(1,1,'DEDUCT','PAY:10:300',-300)");}
    @Test void cleanInstallationPasses(){assertDoesNotThrow(this::runGuard);}
    @Test void unmarkedLegacyDebitBlocksUpgrade(){unpaidDebit();assertThrows(Exception.class,this::runGuard);}
    @Test void fullyRefundedLegacyAttemptPasses(){unpaidDebit();db.update("INSERT INTO `llmall-user`.balance_flow VALUES(2,1,'REFUND','PAYFAIL:PAY:10:300',300)");assertDoesNotThrow(this::runGuard);}
    @Test void wrongRefundAmountCannotHideLegacyDebit(){unpaidDebit();db.update("INSERT INTO `llmall-user`.balance_flow VALUES(2,1,'REFUND','PAYFAIL:PAY:10:300',100)");assertThrows(Exception.class,this::runGuard);}
    @Test void processingLegacyAttemptBlocksUpgrade(){db.update("INSERT INTO `llmall-pay`.pay_order VALUES(10,4,NULL)");assertThrows(Exception.class,this::runGuard);}
}
