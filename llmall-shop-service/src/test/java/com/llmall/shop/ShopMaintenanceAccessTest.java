package com.llmall.shop;

import com.llmall.common.advice.CommonExceptionAdvice;
import com.llmall.common.utils.UserContext;
import com.llmall.shop.controller.ShopController;
import com.llmall.shop.service.IShopService;
import com.llmall.shop.task.ShopSearchReconcileTask;
import com.llmall.shop.task.ShopStatRollupTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDate;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShopMaintenanceAccessTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private IShopService service;
    private ShopSearchReconcileTask reconcile;
    private ShopStatRollupTask rollup;
    private ShopController controller;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        service = mock(IShopService.class);
        reconcile = mock(ShopSearchReconcileTask.class);
        rollup = mock(ShopStatRollupTask.class);
        controller = new ShopController(service, null, null, reconcile, rollup);
        ReflectionTestUtils.setField(controller, "maintenanceEnabled", true);
        ReflectionTestUtils.setField(controller, "serviceToken", TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new CommonExceptionAdvice()).build();
    }
    @AfterEach void clearContext() { UserContext.removeUser(); }

    private void assertMaintenance(int status, String token) throws Exception {
        MockHttpServletRequestBuilder[] requests = { post("/shops/1/hot/recompute"), post("/shops/hot/reconcile"),
            post("/shops/cache/geo/rebuild"), post("/shops/stat/rollup"), delete("/shops/cache/1"), delete("/shops/cache/1/offline") };
        for (var request : requests) {
            if (token != null) request.header("x-service-token", token);
            mvc.perform(request).andExpect(status().is(status));
        }
    }
    @Test void loginAndEnabledSwitchDoNotAuthorizeMaintenance() throws Exception {
        UserContext.setUser(1L);
        assertMaintenance(403, null);
        verifyNoInteractions(service, reconcile, rollup);
    }
    @Test void missingWeakWrongOrDisabledCredentialsFailClosed() throws Exception {
        for (String configured : new String[]{null, "", "demo", TOKEN}) {
            ReflectionTestUtils.setField(controller, "serviceToken", configured);
            assertMaintenance(403, "wrong");
        }
        ReflectionTestUtils.setField(controller, "serviceToken", " ".repeat(32));
        assertMaintenance(403, " ".repeat(32));
        ReflectionTestUtils.setField(controller, "serviceToken", "demo");
        assertMaintenance(403, "demo");
        ReflectionTestUtils.setField(controller, "serviceToken", TOKEN);
        ReflectionTestUtils.setField(controller, "maintenanceEnabled", false);
        assertMaintenance(403, TOKEN);
        verifyNoInteractions(service, reconcile, rollup);
    }
    @Test void enabledStrongServiceCredentialAuthorizesMaintenance() throws Exception {
        assertMaintenance(200, TOKEN);
        verify(service).recomputeHotScore(1L);
        verify(service).rebuildGeoIndex();
        verify(service).evictCache(1L);
        verify(service).markShopOffline(1L);
        verify(reconcile).reconcile();
        verify(rollup).rollupYesterday();
        mvc.perform(post("/shops/stat/rollup").param("date", "2026-10-28").header("x-service-token", TOKEN))
            .andExpect(status().isOk());
        verify(rollup).rollup(LocalDate.of(2026, 10, 28));
    }
    @Test void normalShopReadRemainsAvailableWithoutMaintenanceCredential() throws Exception {
        ReflectionTestUtils.setField(controller, "maintenanceEnabled", false);
        mvc.perform(get("/shops/1")).andExpect(status().isOk());
        verify(service).queryById(1L);
    }
}