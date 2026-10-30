package com.llmall.search.controller;

import com.llmall.common.advice.CommonExceptionAdvice;
import com.llmall.common.utils.UserContext;
import com.llmall.search.service.ISearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchMaintenanceAccessTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private ISearchService service;
    private SearchController controller;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        service = mock(ISearchService.class);
        controller = new SearchController(service);
        ReflectionTestUtils.setField(controller, "maintenanceEnabled", true);
        ReflectionTestUtils.setField(controller, "serviceToken", TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new CommonExceptionAdvice()).build();
    }
    @AfterEach void clearContext() { UserContext.removeUser(); }

    private void assertRebuilds(int status, String token) throws Exception {
        for (String path : new String[]{"/search/view/rebuild", "/search/index/rebuild"}) {
            var request = post(path);
            if (token != null) request.header("x-service-token", token);
            mvc.perform(request).andExpect(status().is(status));
        }
    }
    @Test void loginAndEnabledSwitchDoNotAuthorizeRebuilds() throws Exception {
        UserContext.setUser(1L);
        assertRebuilds(403, null);
        verifyNoInteractions(service);
    }
    @Test void missingWeakWrongOrDisabledCredentialsFailClosed() throws Exception {
        for (String configured : new String[]{null, "", "demo", TOKEN}) {
            ReflectionTestUtils.setField(controller, "serviceToken", configured);
            assertRebuilds(403, "wrong");
        }
        ReflectionTestUtils.setField(controller, "serviceToken", " ".repeat(32));
        assertRebuilds(403, " ".repeat(32));
        ReflectionTestUtils.setField(controller, "serviceToken", "demo");
        assertRebuilds(403, "demo");
        ReflectionTestUtils.setField(controller, "serviceToken", TOKEN);
        ReflectionTestUtils.setField(controller, "maintenanceEnabled", false);
        assertRebuilds(403, TOKEN);
        verifyNoInteractions(service);
    }
    @Test void enabledStrongServiceCredentialAuthorizesBothRebuilds() throws Exception {
        assertRebuilds(200, TOKEN);
        verify(service).rebuildSearchView();
        verify(service).rebuildEsIndex();
    }
    @Test void normalSearchRemainsAvailableWithoutMaintenanceCredential() throws Exception {
        ReflectionTestUtils.setField(controller, "maintenanceEnabled", false);
        mvc.perform(get("/search")).andExpect(status().isOk());
        verify(service).search(null, null, 1, 10, null);
    }
}